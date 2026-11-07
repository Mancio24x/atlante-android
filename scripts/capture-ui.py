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
                    if scroll and attempt < 6 and (bounds[1]+bounds[3])//2 > screen[3]*0.8: break
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
    books.append(dict(id=f'{i+1:064x}',title=title,author=['Elena Rossi','Luca Moretti','Anna Costa'][i%3],genre=['Epic Fantasy','Fantasy','Gialli','Cucina',''][i%5],format='CATALOGO',status='In lettura' if i==0 else 'Da leggere',currentPage=83 if i==0 else 0,totalPages=240 if i==0 else 0))
seed=out/'sample-library.json';seed.write_text(json.dumps(books))
adb('shell','am','force-stop',package)
adb('push',str(seed),'/data/local/tmp/atlante-sample.json')
adb('shell','run-as',package,'cp','/data/local/tmp/atlante-sample.json','files/library.json')
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(1)
tap('Biblioteca',exact=True);shot('01-library')
tap('Apri menu laterale');shot('02-drawer');tap('Chiudi menu laterale')
# The preset themes are in a section that opens and closes ("Temi predefiniti").
tap('Impostazioni');shot('03-settings');tap('Temi predefiniti',True);shot('04-theme-picker');tap('Rosa petalo',True);shot('05-rose')
for theme,name in [('Lavanda','06-lavender'),('Blu polvere','07-blue'),('AMOLED nero','08-amoled')]:
    tap('Impostazioni');tap('Temi predefiniti',True);tap(theme,True);shot(name)
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
tap('Impostazioni');tap('Temi predefiniti',True)
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
# After the restart the app opens on Home: go to the library first.
tap('Biblioteca',exact=True);time.sleep(.6)
tap('In lettura',exact=True);shot('12a-chip-reading')
tap('Il giardino delle storie');shot('12b-book-page')
tap('Letto',exact=True);time.sleep(.8)
book=next(b for b in catalog() if b['title']=='Il giardino delle storie')
assert book['status']=='Letto' and book.get('finished'), 'State change not saved'
shot('12c-book-read');tap('Aggiorna avanzamento',True);time.sleep(.4);shot('12d-progress-wheels');tap('Annulla',True,exact=True)
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
tap('Biblioteca',exact=True);time.sleep(.6)   # after a restart the app opens on Home
assert any('Griglia' in (n.get('text',''),n.get('content-desc','')) for n in dump().iter('node')), 'List preference was not restored'
assert any(b['title']=='AtlanteManualeProva' for b in catalog()), 'Manual book lost after restart'
shot('15-manual-and-list-restored')
# Bottom navigation: every destination draws and Home is restored.
tap('Home',exact=True);shot('16-home')
tap('Statistiche',exact=True);shot('17-statistics')
# Genres: one horizontal bar per main genre ("Epic Fantasy" and "Fantasy" together), counts at the end.
assert visible('Generi nella tua biblioteca',True), 'Genre chart missing'
def has_bar():
    return any(n.get('content-desc','').startswith('Fantasy:') for n in dump().iter('node'))
for _ in range(4):
    if has_bar(): break
    adb('shell','input','swipe','540','1250','540','650','350'); time.sleep(.5)
shot('17a-statistics-genres')
assert has_bar(), 'Genres not grouped into bars'
tap('Scopri',exact=True)
# Scopri: a grid of six covers (most read), then six of a chosen genre; the search opens empty.
for _ in range(40):
    if sum(1 for n in dump().iter('node') if ' di ' in n.get('content-desc',''))>=6: break
    time.sleep(1.5)
shot('18-discover')
assert sum(1 for n in dump().iter('node') if ' di ' in n.get('content-desc',''))>=6, 'Discover grid has fewer than six books'
tap('Fantasy',exact=True)
for _ in range(40):
    if any(n.get('text','')=='Consigliati · Fantasy' for n in dump().iter('node')) and sum(1 for n in dump().iter('node') if ' di ' in n.get('content-desc',''))>=6: break
    time.sleep(1.5)
shot('18a-discover-fantasy')
tap('Cerca un libro per titolo, autore o ISBN')
assert not any(n.get('text','') in ('Fantasy','Fantascienza') and n.get('class','').endswith('EditText') for n in dump().iter('node')), 'Search opened with a genre written in it'
shot('18b-discover-search');tap('Chiudi',exact=True)
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
tap('Visualizza saga completa',True);time.sleep(1.5);shot('51-saga-map')
assert any('Mappa verticale della saga' in n.get('content-desc','') for n in dump().iter('node')), 'Saga map missing'
# Each volume card says its number, state and where the position comes from.
assert any(n.get('content-desc','').startswith('Numero ') for n in dump().iter('node')), 'Numbered volumes missing on the map'
tap('Pubblicazione',exact=True);time.sleep(.6);shot('51a-saga-map-publication')
tap('Cambia l’ordine',exact=True);time.sleep(.6);shot('51b-saga-order-editor')
assert visible('Il tuo ordine'), 'Order editor missing'
tap('Annulla',exact=True);tap('Saga',exact=True)
tap('Elenco',exact=True);time.sleep(.6);shot('51b-full-saga-list')
assert any('Golden Son' in n.get('text','') for n in dump().iter('node')), 'Full saga does not list Golden Son'
tap('Chiudi',exact=True)
for _ in range(20):
    if any(n.get('text','').startswith('Altri libri di Pierce') for n in dump().iter('node')): break
    adb('shell','input','swipe','540','1250','540','750','300');time.sleep(1.5)
shot('52-book-author-carousel')
tap('Cambia copertina',True)
for _ in range(40):
    texts=[n.get('text','') for n in dump().iter('node')]
    descs=[n.get('content-desc','') for n in dump().iter('node')]
    if any(d.startswith('Copertina ') for d in descs) or any(t.startswith('Nessuna copertina') for t in texts): break
    time.sleep(1.5)
time.sleep(3);shot('52c-cover-picker')
assert any(n.get('content-desc','').startswith('Copertina ') for n in dump().iter('node')), 'No online cover found for Red Rising'
assert visible('Tutte ·'), 'Cover filters missing'
tap('Chiudi',exact=True)
back();tap('Apri menu laterale');tap('Universi',exact=True);time.sleep(1);shot('52a-universes')
assert any(n.get('text','')=='Universi narrativi' for n in dump().iter('node')), 'Universes view missing'
tap('Biblioteca',exact=True);tap('Tutti',exact=True)
# The filter chip is at the end of the horizontal chip strip: scroll the strip sideways first.
for node in dump().iter('node'):
    if node.get('text','')=='Letti':
        b=list(map(int,re.findall(r'\d+',node.get('bounds',''))));y=str((b[1]+b[3])//2)
        adb('shell','input','swipe','900',y,'100',y,'300');time.sleep(.5);break
tap('Altri filtri');shot('52b-filters')
assert visible('Lingua') or visible('Anno di pubblicazione'), 'Language / year filters missing'
tap('Annulla',exact=True)
# [Barra ricerca] [Genere] [Autore]: the two quick filters open their lists.
tap('Filtra per genere');time.sleep(.5);shot('52d-genre-filter');tap('Chiudi',exact=True)
tap('Filtra per autore');time.sleep(.5);shot('52e-author-filter')
assert visible('Tutti gli autori'), 'Author filter missing'
tap('Chiudi',exact=True)
print('Saga carousels completed.')

# Cache settings (Compose): space used, limits and manual cleanup; personal data is not touched.
back();tap('Impostazioni');tap('Cache e dati temporanei',True);tap('Svuota tutta la cache',True);time.sleep(1.5);shot('53-cache-settings')
assert any(n.get('text','')=='Cache svuotata' for n in dump().iter('node')), 'Cache cleanup did not confirm'
# Qualità del catalogo: every check with its count.
tap('Qualità del catalogo',True)
for _ in range(20):
    if any('libri controllati' in n.get('text','') for n in dump().iter('node')): break
    time.sleep(.5)
shot('53b-catalog-quality')
assert any('libri controllati' in n.get('text','') for n in dump().iter('node')), 'Catalog quality did not load'
tap('Chiudi',exact=True)
assert any(b['title']=='Red Rising' for b in catalog()), 'Cache cleanup touched the library'
print('Cache settings completed.')

# Ripara saghe: runs on the whole sample library and reports; the library itself is unchanged except empty saga fields.
before=len(catalog())
tap('Home',exact=True);tap('Impostazioni')   # back to the top of the settings
tap('Ripara saghe e mappe',True,exact=True)   # opens the section
time.sleep(.6);adb('shell','input','swipe','540','1250','540','750','300');time.sleep(.6)
buttons=[n for n in dump().iter('node') if n.get('text','')=='Ripara saghe']
b=list(map(int,re.findall(r'\d+',buttons[-1].get('bounds',''))))
adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
for _ in range(120):
    if any(n.get('text','').startswith('Fatto:') for n in dump().iter('node')): break
    time.sleep(2)
shot('54-repair-sagas')
assert any(n.get('text','').startswith('Fatto:') for n in dump().iter('node')), 'Ripara saghe did not finish'
assert len(catalog())==before, 'Ripara saghe changed the number of books'
tap('Controlla le mappe',True)
for _ in range(60):
    if any(('mappe' in n.get('text','') and ('coerenti' in n.get('text','') or 'cose da guardare' in n.get('text',''))) for n in dump().iter('node')): break
    time.sleep(1)
shot('54a-check-maps')
assert any(('coerenti' in n.get('text','') or 'cose da guardare' in n.get('text','')) for n in dump().iter('node')), 'Map check did not finish'
assert len(catalog())==before, 'Map check changed the library'
print('Repair completed.')

# Bookcase: spines on shelves; a tap on a spine opens the book.
back();tap('Biblioteca',exact=True);tap('Tutti',exact=True)
for node in dump().iter('node'):
    if node.get('content-desc','') in ('Griglia','Elenco'):
        b=list(map(int,re.findall(r'\d+',node.get('bounds',''))));adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));break
time.sleep(.6);tap('Scaffale',exact=True);time.sleep(1.2);shot('60-bookcase')
spines=[n for n in dump().iter('node') if n.get('content-desc','').startswith('Red Rising, di Pierce Brown')]
assert spines, 'Spine of Red Rising not found on the bookcase'
# Look of the bookcase: one compact button opens the options over the shelf; they apply at once, a tap outside closes them.
tap('Aspetto');time.sleep(.5);shot('60a-bookcase-look-menu')
tap('Noce scuro',exact=True);time.sleep(.6);shot('60b-bookcase-walnut')
tap('Colori del tema',exact=True);time.sleep(.6)
screen=list(map(int,re.findall(r'\d+',dump().find('node').get('bounds','0,0,1080,1920'))))
adb('shell','input','tap',str(screen[2]//2),str(screen[3]*92//100));time.sleep(.6)
assert not any(n.get('text','')=='Minimal' for n in dump().iter('node')), 'A tap outside did not close the bookcase options'
shot('60c-bookcase-theme-colors')
tap('Red Rising, di Pierce Brown');time.sleep(1.5);shot('61-bookcase-open')
assert any(n.get('text','').startswith('Avvia il timer') for n in dump().iter('node')), 'Tapping a spine did not open the book'
print('Bookcase completed.')

# Custom theme: colours chosen by touch in a square and a hue bar, no colour codes; saved and applied.
back();tap('Impostazioni');tap('I miei temi',True);time.sleep(.6)
assert not any('#RRGGBB' in n.get('text','') or re.fullmatch(r'#[0-9A-Fa-f]{6}',n.get('text','')) for n in dump().iter('node')), 'Colour codes still shown'
tap('Modifica il colore Accento',True);time.sleep(.4)
square=[n for n in dump().iter('node') if n.get('content-desc','').startswith('Saturazione e luminosità')]
assert square, 'Colour square missing'
b=list(map(int,re.findall(r'\d+',square[0].get('bounds',''))))
adb('shell','input','swipe',str(b[0]+20),str(b[3]-20),str(b[2]-20),str(b[1]+20),'500');time.sleep(.6)
shot('62-theme-editor')
tap('Salva e applica come',True);time.sleep(2);shot('63-custom-theme-applied')
assert json.loads(json.dumps(adb('exec-out','run-as',package,'sh','-c','cat shared_prefs/*.xml').decode(errors='replace'))).count('customPalette')>=1, 'Custom theme not saved'
print('Theme editor completed.')
