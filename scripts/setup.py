"""Create local secrets without overwriting an existing environment file."""
import base64
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parent.parent
path = root / '.env'
if path.exists():
    print('.env already exists. Existing credentials have been preserved.')
else:
    values = {
        'DATABASE_PASSWORD': secrets.token_urlsafe(32),
        'KEY_ENCRYPTION_SECRET': base64.b64encode(os.urandom(32)).decode(),
        'BOOTSTRAP_ADMIN_EMAIL': 'admin@gatehouse.local',
        'BOOTSTRAP_ADMIN_PASSWORD': secrets.token_urlsafe(24),
        'DEMO_CLIENT_SECRET': secrets.token_urlsafe(32),
        'PUBLIC_ISSUER': 'http://localhost:18100',
        'PUBLIC_PORT': '18100',
        'PUBLIC_SCHEME': 'http',
        'COOKIE_SECURE': 'false',
    }
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as file:
        file.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    print('Created .env with unique local credentials. Read BOOTSTRAP_ADMIN_PASSWORD there to sign in.')
