#!/usr/bin/env python3
"""Package the portable Android repository, including Actions and excluding private data."""
from pathlib import Path
import hashlib
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
output = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parent / 'android-release' / 'Atlante-Android-source.zip'
output.parent.mkdir(parents=True, exist_ok=True)
excluded = {'build', 'dist', '.gradle', '.git', '__pycache__'}
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root)
        if not path.is_file() or excluded.intersection(rel.parts): continue
        if path.name in {'local.properties', 'signing.properties', '.DS_Store'}: continue
        if path.suffix in {'.p12', '.pem', '.jks', '.keystore', '.apk', '.pyc'}: continue
        archive.write(path, str(rel))
with zipfile.ZipFile(output) as archive:
    assert '.github/workflows/release.yml' in archive.namelist()
    assert 'app/src/main/AndroidManifest.xml' in archive.namelist()
    assert not any('private' in name.lower() or name.endswith(('.p12', '.pem')) for name in archive.namelist())
output.with_suffix('.zip.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '  ' + output.name + '\n')
print('Sorgente Android pronto: ' + str(output))
