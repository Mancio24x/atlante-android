#!/usr/bin/env python3
"""Create one persistent release identity outside the public Android repository."""
import base64
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

repo = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit('Uso: python3 scripts/create-signing.py /cartella/privata/esterna/al/repository')
dest = Path(sys.argv[1]).expanduser().resolve()
if dest == repo or repo in dest.parents:
    raise SystemExit('Scegli una cartella esterna al repository Android: la chiave deve restare privata.')
if any(dest.glob('*')):
    raise SystemExit('La cartella contiene già file. Non sovrascrivo una firma esistente.')
dest.mkdir(mode=0o700, parents=True, exist_ok=True)
dest.chmod(0o700)
password = secrets.token_urlsafe(36)
env = dict(os.environ, ATLANTE_SIGNING_PASSWORD=password)
with tempfile.TemporaryDirectory(prefix='atlante-signing-') as temporary:
    key = str(Path(temporary) / 'key.pem')
    cert = str(Path(temporary) / 'cert.pem')
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', key], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    subprocess.run(['openssl', 'req', '-new', '-x509', '-sha256', '-key', key, '-out', cert,
        '-days', '10000', '-subj', '/CN=Atlante Library/O=Atlante'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    subprocess.run(['openssl', 'pkcs12', '-export', '-inkey', key, '-in', cert,
        '-out', str(dest / 'atlante-release.p12'), '-name', 'atlante',
        '-keypbe', 'AES-256-CBC', '-certpbe', 'AES-256-CBC', '-macalg', 'sha256',
        '-passout', 'env:ATLANTE_SIGNING_PASSWORD'], check=True, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    fingerprint = subprocess.check_output(['openssl', 'x509', '-in', cert, '-noout', '-sha256', '-fingerprint']).decode().strip()
(dest / 'APK_KEYSTORE_BASE64.txt').write_text(base64.b64encode((dest / 'atlante-release.p12').read_bytes()).decode() + '\n')
(dest / 'APK_KEYSTORE_PASSWORD.txt').write_text(password + '\n')
(dest / 'README-PRIVATO.txt').write_text('NON CARICARE QUESTA CARTELLA SU GITHUB.\nConserva una copia sicura di chiave e password: servono per tutti gli aggiornamenti.\n\nGitHub > Settings > Secrets and variables > Actions > New repository secret:\nAPK_KEYSTORE_BASE64 = contenuto di APK_KEYSTORE_BASE64.txt\nAPK_KEYSTORE_PASSWORD = contenuto di APK_KEYSTORE_PASSWORD.txt\n\nAlias: atlante; formato: PKCS12.\n' + fingerprint + '\n')
for file in dest.iterdir():
    if file.is_file(): file.chmod(0o600)
print('Firma creata nella cartella privata indicata. Leggi README-PRIVATO.txt; nessun segreto è stato stampato.')
