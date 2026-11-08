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
    for attempt in range(16 if scroll else 3):
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
                    # (On the last attempt tap anyway: the page may not scroll any further.)
                    if scroll and attempt < 15 and (bounds[1]+bounds[3])//2 > screen[3]*0.8: break
                    adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.4);return
        if scroll: adb('shell','input','swipe','540','1250','540','550','350')
        time.sleep(.3)
    raise RuntimeError('UI control missing: '+label)
def shot(name):
    time.sleep(.4); dismiss_system_dialog(dump()); (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    (out/(name+'.xml')).write_bytes(ET.tostring(dump()))

adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(2);wait_for_app_files()
book=dict(id='f'*64,title='Libro prova UI',author='Autore di prova',format='CATALOGO',status='In lettura',currentPage=20,totalPages=100,percent=20)
seed=out/'sample-update.json';seed.write_text(json.dumps([book]))
adb('shell','am','force-stop',package);adb('push',str(seed),'/data/local/tmp/update.json')
adb('shell','run-as',package,'cp','/data/local/tmp/update.json','files/library.json')
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(2)
def back(): adb('shell','input','keyevent','4');time.sleep(.5)
def catalog(): return json.loads(adb('exec-out','run-as',package,'cat','files/library.json'))
tap('Biblioteca',exact=True);tap('Apri menu laterale');tap('Impostazioni');shot('update-settings')
def page_texts():
    texts=set()
    for _ in range(12):
        for n in dump().iter('node'): texts.update(v for v in (n.get('text',''),n.get('content-desc','')) if v)
        adb('shell','input','swipe','540','1250','540','550','250');time.sleep(.2)
    return texts
# Settings are an index (design 6a): no backup entries there; the import is in "Libreria e catalogo".
texts=page_texts()
assert any(t.startswith('Libreria e catalogo') for t in texts) and any(t.startswith('Obiettivi di lettura') for t in texts), 'Settings index missing'
assert not any(x in texts for x in ['Importa catalogo','Catalogo e backup','Esporta backup','Ripristina o importa un catalogo'])
tap('Libreria e catalogo',True);texts=page_texts()
assert 'Importa eBook' in texts
back();tap('Obiettivi di lettura',True);texts=page_texts()
assert any(t.startswith('Pagine al giorno') for t in texts), 'Goal wheels missing'
# Back from the settings index goes Home, then the library.
back();back();tap('Biblioteca',exact=True);tap('Libro prova UI');tap('Aggiorna avanzamento',True);shot('update-wheels-pages')
tap('Percentuale',exact=True);tap('20%',exact=True)
adb('shell','input','keyevent','KEYCODE_MOVE_END');adb('shell','input','keyevent','KEYCODE_DEL');adb('shell','input','keyevent','KEYCODE_DEL');adb('shell','input','text','75')
tap('OK',exact=True);shot('update-wheels-percent');tap('Salva',True,exact=True);time.sleep(1)
assert catalog()[0]['currentPage']==75 and catalog()[0]['percent']==75
# Confirm that touching the horizontal wheel actually changes its centred value.
tap('Aggiorna avanzamento',True);tap('Pagine',exact=True)
tree=dump();node=next(n for n in tree.iter('node') if n.get('content-desc','')=='Pagina')
b=list(map(int,re.findall(r'\d+',node.get('bounds',''))));y=(b[1]+b[3])//2
adb('shell','input','swipe',str(b[2]-80),str(y),str(b[0]+80),str(y),'450');time.sleep(1)
shot('update-wheel-swiped');tap('Salva',True,exact=True);time.sleep(1)
assert 0<=catalog()[0]['currentPage']<=100 and catalog()[0]['currentPage']!=75
# Pause and rotate, then finish exactly once.
tap('Avvia il timer di lettura',True);time.sleep(3);tap('Pausa',True,exact=True);shot('update-timer-paused')
adb('shell','settings','put','system','accelerometer_rotation','0');adb('shell','settings','put','system','user_rotation','1');time.sleep(1)
adb('shell','settings','put','system','user_rotation','0');time.sleep(1)
tap('Riprendi',True,exact=True);time.sleep(2);tap('Termina',True,exact=True);tap('Salva la sessione',True);time.sleep(1)
# The wheel update above is a session too (no time); the timed one must be there exactly once, rotation included.
sessions=catalog()[0]['journal']['sessions'];timed=[x for x in sessions if x.get('timerId')]
assert len(timed)==1 and timed[0]['seconds']>=5, sessions
(out/'update-verified.json').write_text(json.dumps({'settings':True,'wheels':True,'timer_rotation':True,'sessions':sessions},indent=2))
print('SETTINGS, WHEELS AND TIMER CHECKED')
