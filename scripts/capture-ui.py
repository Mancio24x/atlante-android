#!/usr/bin/env python3
"""Smoke-check real Android navigation and capture themes on an emulator (debug app only)."""
from pathlib import Path
import json, re, subprocess, time, xml.etree.ElementTree as ET
out=Path('ui-captures');out.mkdir(exist_ok=True)
package='app.atlante.library.debug'
def adb(*args,**kw): return subprocess.check_output(['adb',*args],**kw)
def dump():
    adb('shell','uiautomator','dump','/sdcard/atlante-ui.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/atlante-ui.xml'))
def tap(label, scroll=False):
    for attempt in range(7 if scroll else 3):
        tree=dump()
        for node in tree.iter('node'):
            values=[node.get('text',''),node.get('content-desc','')]
            if any(v.casefold().startswith(label.casefold()) for v in values):
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                if len(bounds)==4 and bounds[3]>bounds[1] and bounds[2]>bounds[0]:
                    adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.4);return
        if scroll: adb('shell','input','swipe','500','1550','500','650','350')
        time.sleep(.3)
    raise RuntimeError('UI control missing: '+label)
def shot(name):
    time.sleep(.4); (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    (out/(name+'.xml')).write_bytes(ET.tostring(dump()))
adb('shell','am','start','-n',package+'/app.atlante.library.MainActivity');time.sleep(2)
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
print('Navigation, settings, theme selection and restart completed.')
