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
def tap(label, scroll=False, editable=False, exact=False):
    for attempt in range(7 if scroll else 3):
        tree=dump()
        if dismiss_system_dialog(tree): tree=dump()
        for node in tree.iter('node'):
            if editable and not node.get('class','').endswith('EditText'): continue
            values=[node.get('text',''),node.get('content-desc','')]
            if any((v.casefold()==label.casefold()) if exact else v.casefold().startswith(label.casefold()) for v in values):
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                if len(bounds)==4 and bounds[3]>bounds[1] and bounds[2]>bounds[0]:
                    screen=list(map(int,re.findall(r'\d+',tree.find('node').get('bounds','0,0,1080,1920'))))
                    # A control at the bottom edge may sit under the system bar: scroll first, then tap.
                    if scroll and (bounds[1]+bounds[3])//2 > screen[3]*0.8: break
                    adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.4);return
        if scroll: adb('shell','input','swipe','540','1250','540','550','350')
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
tap('Biblioteca',exact=True);shot('01-library')
tap('Apri menu laterale');shot('02-drawer');tap('Chiudi menu laterale')
tap('Impostazioni');shot('03-settings');shot('04-theme-picker');tap('Rosa petalo',True);shot('05-rose')
for theme,name in [('Lavanda','06-lavender'),('Blu polvere','07-blue'),('AMOLED nero','08-amoled')]:
    tap('Impostazioni');tap(theme,True);shot(name)
tap('Apri menu laterale');tap('Mappa');shot('09-map-amoled')
# The map is full screen with an author search on top.
tap('Anna Costa');time.sleep(.6);shot('09a-map-authors')
# The suggestion list is a separate popup window that uiautomator cannot see: choose with the keyboard.
adb('shell','input','text','Luca');time.sleep(.8)
adb('shell','input','keyevent','KEYCODE_DPAD_DOWN');adb('shell','input','keyevent','KEYCODE_ENTER');time.sleep(.6)
shot('09b-map-author')
assert any('Luca Moretti' in n.get('text','') for n in dump().iter('node')), 'Map author not selected'
tap('Apri menu laterale');tap('Tutti i libri');shot('10-library-amoled')
# The persisted theme must survive a process restart.
adb('shell','am','force-stop',package);adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(1)
tap('Impostazioni')
state=dump()
def visible(fragment, scroll=False):
    for _ in range(8 if scroll else 1):
        if any(fragment in n.get('text','') for n in dump().iter('node')): return True
        if scroll: adb('shell','input','swipe','540','1250','540','550','350'); time.sleep(.4)
    return False
# After the restart the settings page must mark AMOLED as the chosen theme.
if not visible('AMOLED nero  ✓', True):
    (out/'failure-prefs.xml').write_bytes(adb('exec-out','run-as',package,'sh','-c','cat shared_prefs/*.xml'))
    raise AssertionError('Theme preference was not restored')
shot('11-settings-restored')
adb('shell','input','keyevent','4');time.sleep(.5);shot('12-theme-restored')
# Manual cataloging must save only after confirmation, without requiring an ISBN.
def catalog(): return json.loads(adb('exec-out','run-as',package,'cat','files/library.json'))
def back(): adb('shell','input','keyevent','4');time.sleep(.5)
def status(title): return next(b for b in catalog() if b['title']==title)['status']
# Reading states: chips filter the library; the full-screen book page changes state with one tap.
tap('In lettura',exact=True);shot('12a-chip-reading')
tap('Il giardino delle storie');shot('12b-book-page')
tap('Letto',exact=True);time.sleep(.8)
book=next(b for b in catalog() if b['title']=='Il giardino delle storie')
assert book['status']=='Letto' and book.get('finished'), 'State change not saved'
shot('12c-book-read');tap('Aggiorna avanzamento',True);time.sleep(.4);shot('12d-progress-wheels');tap('Annulla',exact=True)
tap('In pausa',True,exact=True);time.sleep(.6)
assert status('Il giardino delle storie')=='In pausa', 'Paused state not saved'
tap('Date, voto e note',True);shot('12e-paused-editor');tap('Salva',exact=True);time.sleep(.6)
assert status('Il giardino delle storie')=='In pausa', 'Editor changed the paused state'
back();tap('Tutti',exact=True)
# Chapters: added with a wheel, then rated with stars and a comment.
tap('Il sentiero di casa');tap('Capitoli',True);tap('Aggiungi capitoli',True);tap('Aggiungi',exact=True)
tap('Capitolo 1',True,exact=True);shot('12f-chapter');tap('Salva',exact=True)
book=next(b for b in catalog() if b['title']=='Il sentiero di casa')
assert len(book.get('chapters',[]))==10, 'Chapters not added'
shot('12g-chapters');back()
# Manual cataloging must save only after confirmation, without requiring an ISBN.
count=len(catalog())
tap('Aggiungi libro');tap('Annulla')
assert len(catalog()) == count, 'Canceled manual entry was saved'
tap('Aggiungi libro');tap('Titolo',editable=True)
adb('shell','input','text','AtlanteManualeProva')
shot('13a-manual-form-filled')
# Android's keyboard may overlay floating-dialog controls even when they are in the UI tree.
back();shot('13b-manual-ready-to-save');tap('Salva',exact=True)
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
shot('13-manual-book');back()
tap('Elenco');tap('Elenco',exact=True);shot('14-list-view')
adb('shell','am','force-stop',package);adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity')
tap('Impostazioni');back()
assert any('Griglia' in (n.get('text',''),n.get('content-desc','')) for n in dump().iter('node')), 'List preference was not restored'
assert any(b['title']=='AtlanteManualeProva' for b in catalog()), 'Manual book lost after restart'
shot('15-manual-and-list-restored')
# Bottom navigation: every destination draws and Home is restored.
tap('Home',exact=True);shot('16-home')
tap('Statistiche',exact=True);shot('17-statistics')
tap('Scopri',exact=True);shot('18-discover')
tap('Sync',exact=True);shot('19-sync');tap('Collega Calibre',True);shot('19a-calibre-settings');tap('Annulla',exact=True)
tap('Home',exact=True);shot('20-home-restored')
print('Navigation, themes, reading states, progress wheels, chapters, manual entry, list view, bottom navigation and Sync completed.')

# Dedicated settings page, returning to the originating destination, and KOReader first in Sync.
# Home is the root: Back there would close the app, so go straight to Sync.
tap('Sync',exact=True)
# Sync keeps its scroll position: scroll back to the top, where KOReader is the first section.
for _ in range(4): adb('shell','input','swipe','540','600','540','1500','300')
time.sleep(.4);shot('30-sync-koreader')
assert any('KOREADER' in n.get('text','') for n in dump().iter('node')), 'KOReader not first/visible in Sync'
tap('Configura KOReader');shot('31-koreader-settings');tap('Annulla',exact=True)
tap('Impostazioni');shot('32-settings-page')
assert any('La tua biblioteca, come piace a te.' in n.get('text','') for n in dump().iter('node')), 'Settings is not a dedicated page'
back();assert any('KOREADER' in n.get('text','') for n in dump().iter('node')), 'Back did not restore Sync'

# Multi-source metadata: the review page opens and lists the catalogs consulted (nothing is applied automatically).
tap('Biblioteca',exact=True);tap('Tutti',exact=True);tap('Il sentiero di casa',True)   # list view: the book may be further down
tap('Completa i dati online',True)
for _ in range(60):
    if any('Fonti consultate' in n.get('text','') for n in dump().iter('node')): break
    time.sleep(1)
shot('40-metadata-review')
assert any('Fonti consultate' in n.get('text','') for n in dump().iter('node')), 'Metadata review did not open'
tap('Annulla',True,exact=True);back()
print('Metadata review completed.')


# Sagas: a real book is recognized online, its saga rebuilt and shown as a carousel (nothing is added to the library).
records=catalog()
records.append(dict(id='f'*64,title='Red Rising',author='Pierce Brown',genre='Fantascienza',year='2014',format='CATALOGO',status='Letto'))
seed.write_text(json.dumps(records));adb('shell','am','force-stop',package)
adb('push',str(seed),'/data/local/tmp/atlante-sample.json')
adb('shell','run-as',package,'cp','/data/local/tmp/atlante-sample.json','files/library.json')
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(1.5)
tap('Biblioteca',exact=True);tap('Tutti',exact=True);tap('Red Rising',True,exact=True)
found=False
for _ in range(45):
    texts=[n.get('text','') for n in dump().iter('node')]
    if any(t in ('Altri libri della saga','Saga non identificata') for t in texts): found=True;break
    adb('shell','input','swipe','540','1250','540','750','300');time.sleep(1.5)
shot('50-book-saga')
assert found, 'Saga section did not appear'
tap('Visualizza saga completa',True);time.sleep(1);shot('51-full-saga')
assert any('Golden Son' in n.get('text','') for n in dump().iter('node')), 'Full saga does not list Golden Son'
tap('Chiudi',exact=True)
for _ in range(20):
    if any(n.get('text','').startswith('Altri libri di Pierce') for n in dump().iter('node')): break
    adb('shell','input','swipe','540','1250','540','750','300');time.sleep(1.5)
shot('52-book-author-carousel')
print('Saga carousels completed.')

# Cache settings (Compose): space used, limits and manual cleanup; personal data is not touched.
back();tap('Impostazioni');tap('Svuota tutta la cache',True);time.sleep(1.5);shot('53-cache-settings')
assert any(n.get('text','')=='Cache svuotata' for n in dump().iter('node')), 'Cache cleanup did not confirm'
assert any(b['title']=='Red Rising' for b in catalog()), 'Cache cleanup touched the library'
print('Cache settings completed.')
