"""Black-box checks against the Docker stack. Credentials and tokens are never printed."""
import base64
import hashlib
import http.cookiejar
from html.parser import HTMLParser
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
VALUES = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines() if line and not line.startswith('#'))
BASE = VALUES.get('PUBLIC_ISSUER', 'http://localhost:18100')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())

    def request(self, path, body=None, method=None, status=200, form=False, headers=None):
        headers = dict(headers or {})
        if body is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            content = urllib.parse.urlencode(body, doseq=True).encode() if form else json.dumps(body).encode()
        else:
            content = None
        req = urllib.request.Request(path if path.startswith('http') else BASE + path, data=content, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read().decode()
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            data = raw
        if status is not None:
            expected = status if isinstance(status, tuple) else (status,)
            assert response.code in expected, (path, response.code, str(data)[:600])
        return response.code, data, response.headers

    def csrf(self):
        return self.request('/api/csrf')[1]

    def api(self, path, body=None, method=None, status=200):
        headers = {'Accept': 'application/json'}
        if body is not None or method in ('DELETE', 'PUT', 'POST'):
            csrf = self.csrf()
            headers[csrf['headerName']] = csrf['token']
        return self.request(path, body, method, status, headers=headers)[1]

    def login(self, email, password, status=200):
        csrf = self.csrf()
        return self.request('/login', {'username': email, 'password': password}, status=status, form=True,
                            headers={'Accept': 'application/json', csrf['headerName']: csrf['token']})

class Inputs(HTMLParser):
    def __init__(self):
        super().__init__()
        self.inputs = []
    def handle_starttag(self, tag, attrs):
        if tag == 'input':
            self.inputs.append(dict(attrs))


def authorization(browser, client_id='gatehouse-demo', redirect=None, scopes='openid profile email roles api.read api.write'):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    state = secrets.token_urlsafe(20)
    nonce = secrets.token_urlsafe(20)
    redirect = redirect or BASE + '/demo/callback'
    query = {'response_type': 'code', 'client_id': client_id, 'redirect_uri': redirect,
             'scope': scopes, 'state': state, 'nonce': nonce,
             'code_challenge': challenge, 'code_challenge_method': 'S256'}
    status, body, headers = browser.request('/oauth2/authorize?' + urllib.parse.urlencode(query), status=None, headers={'Accept': 'text/html'})
    for _ in range(6):
        if status in (302, 303) and headers.get('Location', '').startswith(redirect):
            params = urllib.parse.parse_qs(urllib.parse.urlparse(headers['Location']).query)
            assert params.get('state') == [state], params
            assert 'code' in params, params
            return {'grant_type': 'authorization_code', 'client_id': client_id,
                    'redirect_uri': redirect, 'code': params['code'][0], 'code_verifier': verifier}
        if status in (302, 303):
            destination = headers['Location']
            assert urllib.parse.urlparse(destination).path != '/login', 'Sign-in session was not accepted by the authorization endpoint'
            status, body, headers = browser.request(destination, status=None, headers={'Accept': 'text/html'})
            continue
        assert status == 200 and isinstance(body, str), (status, str(body)[:400])
        parser = Inputs()
        parser.feed(body)
        data = {i['name']: i.get('value', '') for i in parser.inputs if i.get('type') == 'hidden' and i.get('name')}
        data['client_id'] = client_id
        data['scope'] = [scope for scope in scopes.split() if scope != 'openid']
        status, body, headers = browser.request('/oauth2/authorize', data, status=None, form=True, headers={'Accept': 'text/html'})
    raise AssertionError('Authorization did not return a code')


def basic(client_id, secret):
    return {'Authorization': 'Basic ' + base64.b64encode(f'{client_id}:{secret}'.encode()).decode()}


def main():
    browser = Browser()
    for _ in range(60):
        try:
            browser.api('/api/health')
            break
        except Exception:
            time.sleep(1)
    else:
        raise AssertionError('Service did not become ready')
    discovery = browser.request('/.well-known/openid-configuration')[1]
    assert discovery['issuer'] == BASE
    assert discovery['token_endpoint'] == BASE + '/oauth2/token'
    assert discovery['jwks_uri'] == BASE + '/oauth2/jwks'
    keys = browser.request('/oauth2/jwks')[1]['keys']
    assert keys and all('d' not in key for key in keys)
    browser.login(VALUES['BOOTSTRAP_ADMIN_EMAIL'], VALUES['BOOTSTRAP_ADMIN_PASSWORD'])
    me = browser.api('/api/me')
    assert me['administrator'] is True
    overview = browser.api('/api/admin/overview')
    assert overview['users'] >= 1 and overview['applications'] >= 2
    roles = browser.api('/api/admin/roles')
    viewer = next(role['id'] for role in roles if role['name'] == 'viewer')
    editor = next(role['id'] for role in roles if role['name'] == 'editor')
    suffix = uuid.uuid4().hex[:10]
    email = f'protocol-{suffix}@example.com'
    password = secrets.token_urlsafe(20)
    user = browser.api('/api/admin/users', {'email': email, 'displayName': 'Protocol test user', 'password': password,
                                          'administrator': False, 'roleIds': [viewer, editor]})
    browser.request('/api/admin/users', {'email': f'csrf-{suffix}@example.com'}, method='POST', status=403)
    user_browser = Browser()
    user_browser.login(email, password)
    assert user_browser.api('/api/me')['email'] == email
    user_browser.api('/api/admin/overview', status=403)
    grant = authorization(user_browser)
    _, tokens, _ = user_browser.request('/oauth2/token', grant, form=True)
    assert 'access_token' in tokens and 'id_token' in tokens and 'refresh_token' not in tokens
    access = {'Authorization': 'Bearer ' + tokens['access_token']}
    _, profile, _ = Browser().request('/api/resource/profile', headers=access)
    assert profile['subject'] == user['id'] and 'editor' in profile['roles']
    Browser().request('/api/resource/write', headers=access)
    Browser().request('/api/resource/profile', status=401)
    wrong_pkce = authorization(user_browser)
    wrong_pkce['code_verifier'] = secrets.token_urlsafe(48)
    _, invalid, _ = user_browser.request('/oauth2/token', wrong_pkce, form=True, status=400)
    assert invalid['error'] == 'invalid_grant'
    query = urllib.parse.urlencode({'response_type': 'code', 'client_id': 'gatehouse-demo', 'redirect_uri': 'https://evil.example/callback', 'scope': 'openid'})
    code, _, headers = user_browser.request('/oauth2/authorize?' + query, status=None, headers={'Accept': 'text/html'})
    assert code in (400, 302) and 'evil.example' not in headers.get('Location', '')
    machine_headers = basic('gatehouse-service', VALUES['DEMO_CLIENT_SECRET'])
    _, service_token, _ = Browser().request('/oauth2/token', {'grant_type': 'client_credentials', 'scope': 'api.read'}, form=True, headers=machine_headers)
    Browser().request('/api/resource/profile', headers={'Authorization': 'Bearer ' + service_token['access_token']})
    _, introspected, _ = Browser().request('/oauth2/introspect', {'token': tokens['access_token']}, form=True, headers=machine_headers)
    assert introspected['active'] is True
    browser.api(f"/api/admin/users/{user['id']}", {'displayName': user['displayName'], 'enabled': True,
                'administrator': False, 'roleIds': [viewer], 'version': user['version']}, method='PUT')
    user_browser.api('/api/me', status=401)
    Browser().request('/api/resource/profile', headers=access, status=401)
    _, introspected, _ = Browser().request('/oauth2/introspect', {'token': tokens['access_token']}, form=True, headers=machine_headers)
    assert introspected['active'] is False
    user_browser.login(email, password)
    grant = authorization(user_browser)
    _, readonly_token, _ = user_browser.request('/oauth2/token', grant, form=True)
    Browser().request('/api/resource/write', headers={'Authorization': 'Bearer ' + readonly_token['access_token']}, status=403)
    # A password reset delivered through local SMTP is single-use and revokes browser sessions.
    user_browser.api('/api/password/forgot', {'email': email})
    inbox = Browser()
    mail_base = 'http://localhost:18125'
    messages = inbox.request(mail_base + '/api/v1/messages')[1]['messages']
    message = next(m for m in messages if m['Subject'] == 'Reset your Gatehouse password' and any(t['Address'] == email for t in m['To']))
    text = inbox.request(mail_base + '/api/v1/message/' + message['ID'])[1]['Text']
    reset_token = urllib.parse.parse_qs(urllib.parse.urlparse(text.splitlines()[-1]).query)['token'][0]
    new_password = secrets.token_urlsafe(20)
    Browser().api('/api/password/reset', {'token': reset_token, 'password': new_password})
    Browser().api('/api/password/reset', {'token': reset_token, 'password': new_password}, status=400)
    user_browser.api('/api/me', status=401)
    user_browser.login(email, new_password)
    # The final enabled administrator cannot disable their own admin access.
    current = browser.api('/api/me')
    browser.api(f"/api/admin/users/{current['id']}", {'displayName': current['displayName'], 'enabled': False,
                'administrator': False, 'roleIds': [], 'version': current['version']}, method='PUT', status=400)
    # Optimistic versions protect against one admin silently overwriting another.
    browser.api(f"/api/admin/users/{user['id']}", {'displayName': 'Stale edit', 'enabled': True,
                'administrator': False, 'roleIds': [], 'version': user['version']}, method='PUT', status=409)
    report = {'status': 'passed', 'checks': ['OIDC discovery', 'public JWKS', 'cross-node browser login', 'CSRF enforcement',
              'administrator authorization', 'authorization code + PKCE', 'role claims', 'scope and role enforcement',
              'invalid verifier rejection', 'redirect allowlist', 'client credentials', 'token introspection',
              'session and grant revocation', 'role updates', 'SMTP password reset', 'single-use reset',
              'last-administrator protection', 'optimistic version checks']}
    (ROOT / 'docs' / 'protocol-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: ' + ', '.join(report['checks']))

if __name__ == '__main__':
    main()
