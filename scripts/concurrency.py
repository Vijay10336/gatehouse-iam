"""Exercise concurrent users and one-use grants through both Docker API nodes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import secrets
import statistics
import time
import uuid
from verify import BASE, ROOT, VALUES, Browser, authorization, basic


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--users', type=int, default=50)
    parser.add_argument('--workers', type=int, default=25)
    args = parser.parse_args()
    assert 2 <= args.users <= 100 and 2 <= args.workers <= 50
    admin = Browser()
    admin.login(VALUES['BOOTSTRAP_ADMIN_EMAIL'], VALUES['BOOTSTRAP_ADMIN_PASSWORD'])
    roles = admin.api('/api/admin/roles')
    viewer = next(role['id'] for role in roles if role['name'] == 'viewer')
    suffix = uuid.uuid4().hex[:8]
    users = []
    for index in range(args.users):
        email = f'load-{suffix}-{index}@example.com'
        password = secrets.token_urlsafe(20)
        user = admin.api('/api/admin/users', {'email': email, 'displayName': f'Concurrent user {index + 1}',
                         'password': password, 'administrator': False, 'roleIds': [viewer]})
        users.append((email, password, user['id']))

    def login_and_read(user):
        email, password, user_id = user
        start = time.perf_counter()
        browser = Browser()
        _, _, headers = browser.login(email, password)
        nodes = {headers.get('X-Gatehouse-Node')}
        for _ in range(3):
            _, me, headers = browser.request('/api/me', headers={'Accept': 'application/json'})
            assert me['id'] == user_id and me['email'] == email
            nodes.add(headers.get('X-Gatehouse-Node'))
        return (time.perf_counter() - start) * 1000, nodes

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        logins = list(pool.map(login_and_read, users))
    elapsed = time.perf_counter() - started
    nodes = set().union(*(entry[1] for entry in logins)) - {None}
    assert len(nodes) >= 2, 'Run docker compose up --scale auth=2 -d for this check'
    times = sorted(entry[0] for entry in logins)

    machine_headers = basic('gatehouse-service', VALUES['DEMO_CLIENT_SECRET'])
    def issue_and_validate(_):
        start = time.perf_counter()
        _, token, headers = Browser().request('/oauth2/token', {'grant_type': 'client_credentials', 'scope': 'api.read'}, form=True, headers=machine_headers)
        Browser().request('/api/resource/profile', headers={'Authorization': 'Bearer ' + token['access_token']})
        return token['access_token'], (time.perf_counter() - start) * 1000
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        machine_results = list(pool.map(issue_and_validate, range(args.users)))
    assert len({entry[0] for entry in machine_results}) == args.users

    # Race the same authorization code on two requests. Only one exchange may win.
    user_browser = Browser()
    user_browser.login(users[0][0], users[0][1])
    grant = authorization(user_browser)
    def exchange(_):
        return Browser().request('/oauth2/token', grant, status=None, form=True)
    with ThreadPoolExecutor(max_workers=2) as pool:
        race = list(pool.map(exchange, range(2)))
    assert sorted(result[0] for result in race) == [200, 400], [(r[0], r[1].get('error')) for r in race]

    # Confidential clients rotate refresh tokens. The same refresh token has one winner.
    client = admin.api('/api/admin/clients', {'name': 'Concurrency web client', 'clientId': f'race-{suffix}', 'type': 'web',
                       'redirectUris': [BASE + '/demo/callback'], 'logoutUris': [BASE + '/demo'],
                       'scopes': ['openid', 'profile', 'api.read']})
    auth = basic(client['client']['clientId'], client['secret'])
    web_grant = authorization(user_browser, client['client']['clientId'], scopes='openid profile api.read')
    web_grant.pop('client_id')
    _, web_tokens, _ = Browser().request('/oauth2/token', web_grant, form=True, headers=auth)
    assert 'refresh_token' in web_tokens
    refresh = {'grant_type': 'refresh_token', 'refresh_token': web_tokens['refresh_token']}
    def refresh_exchange(_):
        return Browser().request('/oauth2/token', refresh, status=None, form=True, headers=auth)
    with ThreadPoolExecutor(max_workers=2) as pool:
        refresh_race = list(pool.map(refresh_exchange, range(2)))
    assert sorted(result[0] for result in refresh_race) == [200, 400], [(r[0], r[1].get('error')) for r in refresh_race]

    # Two admins trying to remove their own admin access cannot remove the final admin.
    secondary_email = f'admin-race-{suffix}@example.com'
    secondary_password = secrets.token_urlsafe(20)
    secondary = admin.api('/api/admin/users', {'email': secondary_email, 'displayName': 'Concurrent admin test',
                          'password': secondary_password, 'administrator': True, 'roleIds': []})
    secondary_browser = Browser()
    secondary_browser.login(secondary_email, secondary_password)
    primary = admin.api('/api/me')
    def disable_self(entry):
        browser, user = entry
        return browser.api(f"/api/admin/users/{user['id']}", {'displayName': user['displayName'], 'enabled': False,
                           'administrator': False, 'roleIds': [], 'version': user['version']}, method='PUT', status=(200, 400))
    with ThreadPoolExecutor(max_workers=2) as pool:
        admin_results = list(pool.map(disable_self, [(admin, primary), (secondary_browser, secondary)]))
    assert sum('error' not in result for result in admin_results) == 1
    survivor = secondary_browser if 'error' not in admin_results[0] else admin
    survivor_users = survivor.api('/api/admin/users?search=' + suffix)['items']
    if 'error' not in admin_results[0]:
        primary_row = survivor.api('/api/admin/users?search=' + VALUES['BOOTSTRAP_ADMIN_EMAIL'])['items'][0]
        survivor.api(f"/api/admin/users/{primary_row['id']}", {'displayName': primary_row['displayName'], 'enabled': True,
                     'administrator': True, 'roleIds': [r['id'] for r in roles if r['name'] in primary['roles']], 'version': primary_row['version']}, method='PUT')
    admin = Browser()
    admin.login(VALUES['BOOTSTRAP_ADMIN_EMAIL'], VALUES['BOOTSTRAP_ADMIN_PASSWORD'])
    # Remove temporary accounts after restoring the bootstrap administrator.
    admin.api(f"/api/admin/users/{secondary['id']}", method='DELETE')
    for _, _, user_id in users:
        admin.api(f'/api/admin/users/{user_id}', method='DELETE')
    admin.api(f"/api/admin/clients/{client['client']['id']}/enabled", {'enabled': False}, method='PUT')
    report = {
        'status': 'passed', 'users': args.users, 'parallelWorkers': args.workers, 'apiNodesObserved': len(nodes),
        'browserWorkload': 'Each user signs in and makes three authenticated reads',
        'browserWorkloadSeconds': round(elapsed, 3), 'browserLatencyP50Ms': round(statistics.median(times), 2),
        'browserLatencyP95Ms': round(times[min(len(times) - 1, math.ceil(len(times) * .95) - 1)], 2),
        'machineTokensIssuedAndValidated': len(machine_results), 'uniqueMachineTokens': len({entry[0] for entry in machine_results}),
        'authorizationCodeRace': 'one success, one rejection', 'refreshTokenRace': 'one success, one rejection',
        'lastAdministratorRace': 'one update accepted, final administrator preserved',
        'note': 'Local Docker workload with 50 accounts, 25 parallel workers, and two API nodes.'
    }
    (ROOT / 'docs' / 'concurrency-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
