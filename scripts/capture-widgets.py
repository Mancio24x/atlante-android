#!/usr/bin/env python3
"""Home screen widgets on a real emulator (debug app only): placed through a debug AppWidgetHost, drawn,
tapped, and checked against the library file the app itself uses."""
from pathlib import Path
import json, re, subprocess, time, xml.etree.ElementTree as ET
out=Path('ui-captures');out.mkdir(exist_ok=True)
package='app.atlante.library.debug'
def adb(*args,**kw): return subprocess.check_output(['adb',*args],**kw)
def dump():
    for _ in range(6):
        try:
            o=adb('shell','uiautomator','dump','/sdcard/w.xml',stderr=subprocess.STDOUT)
            if b'dumped' in o.lower(): return ET.fromstring(adb('exec-out','cat','/sdcard/w.xml'))
        except (subprocess.CalledProcessError,ET.ParseError): pass
        time.sleep(.5)
    raise RuntimeError('No UI tree')
def nodes(): return list(dump().iter('node'))
def find(label,exact=False,desc=True):
    for n in nodes():
        for v in ([n.get('text','')]+([n.get('content-desc','')] if desc else [])):
            if (v==label) if exact else v.startswith(label): return n
    return None
def tap_node(n):
    b=list(map(int,re.findall(r'\d+',n.get('bounds',''))));adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def tap(label,exact=False,scroll=False):
    for i in range(10):
        n=find(label,exact)
        if n is not None: tap_node(n);time.sleep(.8);return
        if scroll: adb('shell','input','swipe','540','1300','540','600','300')
        time.sleep(.6)
    raise RuntimeError('Widget control missing: '+label)
def shot(name):
    time.sleep(.6);(out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));(out/(name+'.xml')).write_bytes(ET.tostring(dump()))
def library(): return json.loads(adb('exec-out','run-as',package,'cat','files/library.json'))
def wait_for_app_files():
    for _ in range(60):
        try: adb('shell','run-as',package,'ls','files',stderr=subprocess.STDOUT);return
        except subprocess.CalledProcessError: time.sleep(.5)
    raise RuntimeError('App data directory not ready')

adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(2);wait_for_app_files()
reading='a'*64
books=[dict(id=reading,title='Dune',author='Frank Herbert',format='CATALOGO',status='In lettura',currentPage=100,totalPages=400,percent=25,started='2026-11-01'),
       dict(id='b'*64,title='Il nome della rosa',author='Umberto Eco',format='CATALOGO',status='In lettura',currentPage=0,totalPages=0,percent=40),
       dict(id='c'*64,title='Fondazione',author='Isaac Asimov',format='CATALOGO',status='Letto',finished='2026-11-02')]
seed=out/'widget-library.json';seed.write_text(json.dumps(books))
adb('shell','am','force-stop',package)
adb('push',str(seed),'/data/local/tmp/atlante-widgets.json')
adb('shell','run-as',package,'cp','/data/local/tmp/atlante-widgets.json','files/library.json')
# Goals: daily pages and monthly/yearly books on, so the dashboard shows them.
adb('shell','appwidget','grantbind','--package',package,'--user','0')
adb('shell','am','start','-n',package+'/app.atlante.library.WidgetHostActivity');time.sleep(5)
shot('70-widgets')
texts=[n.get('text','') for n in nodes()]
assert not any(t.startswith('Bind non permesso') for t in texts), 'Widgets could not be placed'
assert texts.count('Dune')>=3, 'The three widgets do not all show the book being read: '+str([t for t in texts if t])
assert any(t.startswith('25%') for t in texts), 'Progress missing'
assert find('Aggiungi 5 pagine') is not None and find('Aggiungi 1 per cento') is not None, 'Quick buttons missing'
assert find('Questo mese') is not None or find('Quest’anno') is not None, 'Goals missing on the dashboard'
print('Widgets drawn.')

# +5 pages from the medium widget, three quick taps: all saved, one journal session.
for _ in range(3): tap_node(find('Aggiungi 5 pagine'));time.sleep(.15)
time.sleep(3)
dune=next(b for b in library() if b['id']==reading)
assert dune['currentPage']==115, 'Pages from the widget not saved: '+str(dune['currentPage'])
sessions=dune.get('journal',{}).get('sessions',[])
assert len(sessions)==1 and sessions[0]['pages']==15, 'Journal sessions: '+json.dumps(sessions)
shot('71-widgets-after-pages')
assert any(n.get('text','').startswith('29%') for n in nodes()), 'Widget not redrawn after +5 pages'
tap_node(find('Aggiungi 1 per cento'));time.sleep(3)
dune=next(b for b in library() if b['id']==reading)
assert dune['percent']==30 and dune['currentPage']==120, 'Percent from the widget: '+str((dune['percent'],dune['currentPage']))
print('Quick progress saved.')

# Settings of one widget: another book and dark colours, the others unchanged.
tap('Scegli il libro e i colori del widget');time.sleep(1.5);shot('72-widget-settings')
tap('Il nome della rosa');tap('Scuro',exact=True,scroll=True);shot('72a-widget-settings-colors');tap('Salva',exact=True);time.sleep(4)
shot('73-widget-other-book')
texts=[n.get('text','') for n in nodes()]
assert 'Il nome della rosa' in texts and texts.count('Dune')>=2, 'Per-widget book choice: '+str([t for t in texts if t])
print('Per-widget settings work.')

# A tap on the small widget opens the book page in the app (single instance).
small=find('widget-SmallWidgetReceiver');tap_node(small);time.sleep(2.5);shot('74-widget-opens-book')
assert any(n.get('text','').startswith('Avvia il timer') for n in nodes()), 'The widget did not open the book page'
tasks=adb('shell','dumpsys','activity','activities').decode(errors='replace').count('app.atlante.library.MainActivity}')
print('Widget opened the book page.', 'MainActivity records:', tasks)
