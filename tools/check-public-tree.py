"""Conservative checks for tracked files; this is not a complete secret audit."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
result = subprocess.run(['git', 'ls-files', '-z'], cwd=root, capture_output=True)
if result.returncode:
    raise SystemExit('Run this check inside an initialized, staged Git checkout.')
paths = [Path(x.decode('utf-8')) for x in result.stdout.split(b'\0') if x]
bad_names = re.compile(r'(^|/)(local\.properties|\.env(?:\..*)?|id_rsa|id_ed25519|server_key|.*cookie.*)$|\.(?:p12|pfx|jks|keystore|pem|key)$', re.I)
rules = [
    ('private-key material', re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')),
    ('GitHub credential', re.compile(rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{35,})')),
    ('embedded website credential', re.compile(rb'GZ_(?:KEY|AUTH)\s*=\s*[\"\x27][^\"\x27\r\n]{12,}')),
]
issues = []
for path in paths:
    relative = path.as_posix()
    if relative.startswith(('build/', '.git/')) or bad_names.search(relative):
        issues.append((relative, 'private/generated filename'))
        continue
    if relative.endswith(('.apk', '.aab', '.dex', '.class', '.log')):
        issues.append((relative, 'build output or log'))
        continue
    data = (root / path).read_bytes()
    for label, pattern in rules:
        if pattern.search(data): issues.append((relative, label))
for path, reason in issues: print(f'BLOCK {path}: {reason}')
if issues: raise SystemExit(1)
print(f'PASS: {len(paths)} tracked files; no forbidden filenames or matched credential patterns.')
print('Public client protocol constants and disposable development-signing defaults are not production credentials.')
