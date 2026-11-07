#!/usr/bin/env python3
"""Measurements on the emulator (debug app): Scopri's covers with an empty cache and with the disk cache, frames
while scrolling the library, memory. Written to ui-captures/perf.txt and printed; nothing is asserted except that
the measurement happened (numbers depend on the CI machine and its network)."""
from pathlib import Path
import re, subprocess, time, xml.etree.ElementTree as ET
out = Path('ui-captures'); out.mkdir(exist_ok=True)
package = 'app.atlante.library.debug'
def adb(*args, **kw): return subprocess.check_output(['adb', *args], **kw)
def dump():
    for _ in range(6):
        try:
            o = adb('shell', 'uiautomator', 'dump', '/sdcard/p.xml', stderr=subprocess.STDOUT)
            if b'dumped' in o.lower(): return ET.fromstring(adb('exec-out', 'cat', '/sdcard/p.xml'))
        except (subprocess.CalledProcessError, ET.ParseError): pass
        time.sleep(.5)
    raise RuntimeError('No UI tree')
def tap(label, exact=True):
    for _ in range(10):
        for n in dump().iter('node'):
            for v in (n.get('text', ''), n.get('content-desc', '')):
                if (v == label) if exact else v.startswith(label):
                    b = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
                    adb('shell', 'input', 'tap', str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); time.sleep(.8); return
        time.sleep(.6)
    raise RuntimeError('Control missing: ' + label)
def start():
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-n', package + '/app.atlante.library.MainActivity'); time.sleep(3)
def perf_lines(wait=25):
    end = time.time() + wait; found = []
    while time.time() < end:
        found = [l for l in adb('logcat', '-d', '-s', 'AtlantePerf:I').decode(errors='replace').splitlines() if 'discover' in l]
        if any('cover 12' in l for l in found): break
        time.sleep(1)
    return [l.split('AtlantePerf:', 1)[-1].strip() for l in found]

report = []
def note(line): print(line); report.append(line)

# 1. Scopri with an empty cache: the covers come from the network.
adb('shell', 'run-as', package, 'sh', '-c', 'rm -rf cache/suggestion-covers cache/*')
start(); adb('logcat', '-c')
tap('Scopri'); cold = perf_lines()
note('Scopri, cache vuota:'); [note('  ' + l) for l in cold] or note('  (nessuna misura: rete non disponibile?)')
# 2. Scopri after a restart: the covers from the disk cache (the list too, from the saved answer).
start(); adb('logcat', '-c')
tap('Scopri'); disk = perf_lines(15)
note('Scopri, cache su disco (app riavviata):'); [note('  ' + l) for l in disk] or note('  (nessuna misura)')
# 3. Back to Scopri in the same session: shown from memory, no load at all.
tap('Home'); adb('logcat', '-c'); tap('Scopri'); time.sleep(2)
mem = perf_lines(3)
note('Scopri, cache in memoria: ' + ('nessun nuovo caricamento' if not any('cover' in l for l in mem) else '; '.join(mem)))
# 4. Frames while scrolling the library fast, and memory.
tap('Biblioteca'); time.sleep(1)
adb('shell', 'dumpsys', 'gfxinfo', package, 'reset')
for _ in range(8): adb('shell', 'input', 'swipe', '540', '1500', '540', '400', '120')
for _ in range(8): adb('shell', 'input', 'swipe', '540', '400', '540', '1500', '120')
gfx = adb('shell', 'dumpsys', 'gfxinfo', package).decode(errors='replace')
for key in ('Total frames rendered', 'Janky frames', '50th percentile', '90th percentile', '99th percentile'):
    m = re.search(re.escape(key) + r':\s*([^\n]+)', gfx)
    if m: note(f'Biblioteca, scorrimento rapido · {key}: {m.group(1).strip()}')
mem_info = adb('shell', 'dumpsys', 'meminfo', package).decode(errors='replace')
m = re.search(r'TOTAL PSS:\s*(\d+)', mem_info) or re.search(r'TOTAL\s+(\d+)', mem_info)
if m: note(f'Memoria (PSS totale): {int(m.group(1)) // 1024} MB')
(out / 'perf.txt').write_text('\n'.join(report) + '\n')
assert report, 'No measurement'
print('Performance measured.')
