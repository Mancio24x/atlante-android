#!/usr/bin/env python3
"""Smoke-check real Android navigation and capture themes on an emulator (debug app only)."""
from pathlib import Path
import json, re, subprocess, time, xml.etree.ElementTree as ET
out=Path('ui-captures');out.mkdir(exist_ok=True)
package='app.atlante.library.debug'
def adb(*args,**kw): return subprocess.check_output(['adb',*args],**kw)
def dump():
    # uiautomator can return success while reporting a null root and leaving an old file.
    # Never reuse that stale tree to tap coordinates in a different dialog.
    last=None
    for _ in range(6):
        try:
            output=adb('shell','uiautomator','dump','/sdcard/atlante-ui.xml',stderr=subprocess.STDOUT)
            if b'dumped' not in output.lower(): raise RuntimeError(output.decode(errors='replace'))
            return ET.fromstring(adb('exec-out','cat','/sdcard/atlante-ui.xml'))
        except (subprocess.CalledProcessError,ET.ParseError,RuntimeError) as e:
            last=e;time.sleep(.4)
    raise RuntimeError('No fresh Android UI tree: '+str(last))
def dismiss_system_dialog(tree):
    # Slow CI emulators sometimes show "<app> isn't responding" for the launcher over our app.
    texts=[n.get('text','') for n in tree.iter('node')]
    if any("isn't responding" in t for t in texts):
        for node in tree.iter('node'):
            if node.get('text','')=='Wait':
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.6);return True
    return False
def wait_for_app_files():
    # The private files directory exists only once the app has really started.
    for _ in range(60):
        try: adb('shell','run-as',package,'ls','files',stderr=subprocess.STDOUT);return
        except subprocess.CalledProcessError: time.sleep(.5)
    raise RuntimeError('App data directory not ready')
def tap(label, scroll=False, editable=False):
    for attempt in range(7 if scroll else 3):
        tree=dump()
        if dismiss_system_dialog(tree): tree=dump()
        for node in tree.iter('node'):
            if editable and not node.get('class','').endswith('EditText'): continue
            values=[node.get('text',''),node.get('content-desc','')]
            if any(v.casefold().startswith(label.casefold()) for v in values):
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                if len(bounds)==4 and bounds[3]>bounds[1] and bounds[2]>bounds[0]:
                    adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.4);return
        if scroll: adb('shell','input','swipe','500','1550','500','650','350')
        time.sleep(.3)
    raise RuntimeError('UI control missing: '+label)
def shot(name):
    time.sleep(.4); dismiss_system_dialog(dump()); (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    (out/(name+'.xml')).write_bytes(ET.tostring(dump()))
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(2);wait_for_app_files()
# Seed only the disposable debug app; never touches the release library.
books=[]
for i,title in enumerate(['Il giardino delle storie','Oltre il mare','La città delle mappe','Viaggio nella notte','Le pagine ritrovate','Il sentiero di casa']):
    books.append(dict(id=f'{i+1:064x}',title=title,author=['Elena Rossi','Luca Moretti','Anna Costa'][i%3],format='CATALOGO',status='In lettura' if i==0 else 'Da leggere',currentPage=83 if i==0 else 0,totalPages=240 if i==0 else 0))
seed=out/'sample-library.json';seed.write_text(json.dumps(books))
adb('shell','am','force-stop',package)
adb('push',str(seed),'/data/local/tmp/atlante-sample.json')
adb('shell','run-as',package,'cp','/data/local/tmp/atlante-sample.json','files/library.json')
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(1)
shot('01-library')
tap('Apri menu laterale');shot('02-drawer');tap('Chiudi menu laterale')
tap('Impostazioni');shot('03-settings');tap('◐ Temi');shot('04-theme-picker');tap('Rosa petalo',True);shot('05-rose')
for theme,name in [('Lavanda','06-lavender'),('Blu polvere','07-blue'),('AMOLED nero','08-amoled')]:
    tap('Impostazioni');tap('◐ Temi');tap(theme,True);shot(name)
tap('Apri menu laterale');tap('Mappa');shot('09-map-amoled')
tap('Apri menu laterale');tap('Tutti i libri');shot('10-library-amoled')
# The persisted theme must survive a process restart.
adb('shell','am','force-stop',package);adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(1)
tap('Impostazioni')
state=dump()
assert any('Temi · AMOLED nero' in n.get('text','') for n in state.iter('node')), 'Theme preference was not restored'
shot('11-settings-restored')
tap('Chiudi');shot('12-theme-restored')
# Manual cataloging must save only after confirmation, without requiring an ISBN.
def catalog(): return json.loads(adb('exec-out','run-as',package,'cat','files/library.json'))
count=len(catalog())
tap('＋ Aggiungi libro');tap('Annulla')
assert len(catalog()) == count, 'Canceled manual entry was saved'
tap('＋ Aggiungi libro');tap('Titolo',editable=True)
adb('shell','input','text','AtlanteManualeProva')
shot('13a-manual-form-filled')
# Android's keyboard may overlay floating-dialog controls even when they are in the UI tree.
adb('shell','input','keyevent','4');time.sleep(.4)
shot('13b-manual-ready-to-save');tap('Salva')
for _ in range(20):
    records=catalog()
    if len(records)==count+1: break
    time.sleep(.25)
if len(records)!=count+1:
    shot('failure-manual-save')
    (out/'failure-catalog.json').write_text(json.dumps(records))
    (out/'failure-logcat.txt').write_bytes(adb('logcat','-d','-t','150'))
    raise AssertionError('Manual book not saved')
manual=next(b for b in records if b['title']=='AtlanteManualeProva')
assert manual.get('isbn','')=='', 'Manual book unexpectedly requires ISBN'
shot('13-manual-book');tap('Chiudi');tap('Elenco');shot('14-list-view')
adb('shell','am','force-stop',package);adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity')
tap('Impostazioni');tap('Chiudi')
assert any(n.get('text','')=='Griglia' for n in dump().iter('node')), 'List preference was not restored'
assert any(b['title']=='AtlanteManualeProva' for b in catalog()), 'Manual book lost after restart'
shot('15-manual-and-list-restored')
print('Navigation, themes, canceled/confirmed manual entry and list persistence completed.')
