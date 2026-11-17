"""Verify self-service signup, mailbox proof, and administrator isolation."""
import json
import re
import secrets
import uuid
from verify import Browser, ROOT, VALUES


def main():
    admin = Browser()
    admin.login(VALUES['BOOTSTRAP_ADMIN_EMAIL'], VALUES['BOOTSTRAP_ADMIN_PASSWORD'])
    before = admin.api('/api/admin/users?administrators=true')
    email = f'signup-{uuid.uuid4().hex[:10]}@example.com'
    password = secrets.token_urlsafe(20)
    public = Browser()
    public.api('/api/registration/signup', {'displayName': 'Self-service signup test', 'email': email,
               'password': password, 'administrator': True, 'roleIds': []})
    public.login(email, password, status=401)
    public.api('/api/registration/resend', {'email': email})
    inbox = Browser()
    messages = inbox.request('http://localhost:18125/api/v1/messages')[1]['messages']
    message = next(m for m in messages if m['Subject'] == 'Verify your Gatehouse email'
                   and any(t['Address'] == email for t in m['To']))
    text = inbox.request('http://localhost:18125/api/v1/message/' + message['ID'])[1]['Text']
    token = re.search(r'/verify\?token=([A-Za-z0-9_-]+)', text).group(1)
    public.api('/api/registration/verify', {'token': token, 'password': 'incorrect-password'}, status=400)
    public.api('/api/registration/verify', {'token': token, 'password': password})
    public.api('/api/registration/verify', {'token': token, 'password': password}, status=400)
    public.login(email, password)
    user = public.api('/api/me')
    assert user['emailVerified'] is True and user['administrator'] is False and user['roles'] == []
    public.api('/api/admin/overview', status=403)
    public.api(f"/api/admin/users/{user['id']}", {'displayName': user['displayName'], 'enabled': True,
               'administrator': True, 'roleIds': [], 'version': user['version']}, method='PUT', status=403)
    # Repeating signup cannot replace a verified account's password or privileges.
    public.api('/api/registration/signup', {'displayName': 'Replacement attempt', 'email': email,
               'password': 'another-password-12345', 'administrator': True})
    Browser().login(email, password)
    after = admin.api('/api/admin/users?administrators=true')
    assert before['total'] == after['total']
    assert all(item['id'] != user['id'] for item in after['items'])
    report = {'status': 'passed', 'administratorCount': after['total'],
              'checks': ['signup creates a standard account', 'email verification required',
                         'chosen password required for verification', 'single-use verification link',
                         'verification resend endpoint', 'public admin fields cannot grant privileges',
                         'standard user cannot access admin API', 'standard user cannot promote itself',
                         'duplicate signup preserves the existing account', 'administrator-only directory filter']}
    (ROOT / 'docs/registration-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
