#!/usr/bin/env python3
"""Fallback checks using installed SDK and cached Gradle dependencies; does not run lint or a device."""
from pathlib import Path
import subprocess,zipfile,os,shutil,json
root=Path(__file__).resolve().parents[1]
out=root/'app/build/companion-validation';out.mkdir(parents=True,exist_ok=True)
sdk=Path.home()/'Library/Android/sdk';bt=sdk/'build-tools/35.0.0';jdk=Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))/'bin';android=sdk/'platforms/android-35/android.jar'
def run(args):
 r=subprocess.run([str(x) for x in args],capture_output=True,text=True)
 if r.returncode: print(r.stdout+r.stderr);raise SystemExit(r.returncode)
 if r.stdout.strip():print(r.stdout[-5000:])
 if r.stderr.strip():print(r.stderr[-3000:])
 return r
cache=Path.home()/'.gradle/caches/8.9/transforms'
# Compile cached dependency resource trees, then overlay the existing merged app resources.
archives=[]
for i,res in enumerate(sorted(cache.glob('*/transformed/*/res'))):
 if not res.is_dir():continue
 dest=out/f'dependency-res-{i}.zip';run([bt/'aapt2','compile','--dir',res,'-o',dest]);archives+=['-R',str(dest)]
new=out/'new-res';new.mkdir(exist_ok=True)
for res in (root/'app/src/main/res/drawable').glob('ic_*.xml'):
 run([bt/'aapt2','compile',res,'-o',new])
run([bt/'aapt2','compile',root/'app/src/main/res/xml/file_paths.xml','-o',new])
merged=root/'app/build/intermediates/merged_res/debug/mergeDebugResources'
for res in sorted(merged.glob('*.flat')): archives+=['-R',str(res)]
for res in sorted(new.glob('*.flat')):archives+=['-R',str(res)]
# Keep the processed manifest's dependency declarations, inserting only the new FileProvider.
import xml.etree.ElementTree as ET
ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
manifest=ET.parse(root/'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml');app=manifest.getroot().find('application')
for item in list(app):
 if item.tag=='provider' and item.get('{'+ns+'}authorities','').endswith('.files'):app.remove(item)
provider=ET.SubElement(app,'provider',{f'{{{ns}}}name':'androidx.core.content.FileProvider',f'{{{ns}}}authorities':'app.atlante.library.debug.files',f'{{{ns}}}exported':'false',f'{{{ns}}}grantUriPermissions':'true'})
ET.SubElement(provider,'meta-data',{f'{{{ns}}}name':'android.support.FILE_PROVIDER_PATHS',f'{{{ns}}}resource':'@xml/file_paths'})
manifest.write(out/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
gen=out/'generated';gen.mkdir(exist_ok=True)
run([bt/'aapt2','link','--auto-add-overlay','-I',android,'--manifest',out/'AndroidManifest.xml','--java',gen,'--custom-package','app.atlante.library','--stable-ids',root/'app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt','-o',out/'resources.ap_']+archives)
# Reuse unchanged dependencies, with stable resource IDs; compile every current application source.
jars=list(cache.glob('*/transformed/*/jars/classes.jar'))+[p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1').glob('*/*/*/*/*.jar') if p.relative_to(Path.home()/'.gradle/caches/modules-2/files-2.1').parts[0].startswith(('androidx.', 'org.jetbrains.kotlin', 'org.jetbrains/'))]
oldR=root/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
filtered=out/'dependency-R.jar'
with zipfile.ZipFile(oldR) as z,zipfile.ZipFile(filtered,'w') as dest:
 for n in z.namelist():
  if not n.startswith('app/atlante/library/'):dest.writestr(n,z.read(n))
cp=os.pathsep.join(str(x) for x in [android,filtered]+jars)
classes=out/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(exist_ok=True)
java=list((root/'app/src/main/java').rglob('*.java'))+list(gen.rglob('*.java'))
run([jdk/'javac','-source','8','-target','8','-encoding','UTF-8','-cp',cp,'-d',classes]+java)
print('ALL ANDROID APPLICATION SOURCES COMPILED')
# Unit tests run against the same current classes and a real JSON implementation.
mods=Path.home()/'.gradle/caches/modules-2/files-2.1'
testjars=[next(mods.glob('org.json/json/*/*/*.jar')),next(mods.glob('junit/junit/*/*/*.jar')),next(mods.glob('org.hamcrest/hamcrest-core/*/*/*.jar')),next(mods.glob('net.sf.kxml/kxml2/*/*/*.jar'))]
testclasses=out/'testclasses';testclasses.mkdir(exist_ok=True)
testcp=os.pathsep.join(str(x) for x in [testclasses,classes,root/'app/src/test/resources']+testjars+[android,filtered]+jars)
tests=list((root/'app/src/test/java').rglob('*.java'));run([jdk/'javac','-source','8','-target','8','-cp',testcp,'-d',testclasses]+tests)
names=['app.atlante.library.'+x.stem for x in tests]
run([jdk/'java','-cp',testcp,'org.junit.runner.JUnitCore']+names)
print('RESOURCE LINK AND ALL JVM TESTS PASSED')
# Optional development APK: reuse the unchanged, previously built dependency DEX.
# This is a debug preview, never a replacement for the normal Gradle release/lint workflow.
import sys
if '--package-debug' in sys.argv:
    ext=root/'app/build/intermediates/dex/debug/mergeExtDexDebug/classes.dex'
    if not ext.is_file(): raise SystemExit('Cached dependency DEX missing; run the normal Gradle debug build first.')
    appjar=out/'application.jar'
    with zipfile.ZipFile(appjar,'w',zipfile.ZIP_DEFLATED) as dest:
        for p in classes.rglob('*.class'): dest.write(p,str(p.relative_to(classes)))
    dex=out/'dex';shutil.rmtree(dex,ignore_errors=True);dex.mkdir()
    run([jdk/'java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,'--output',dex,appjar,filtered,ext])
    unsigned=out/'preview-unsigned.apk';shutil.copy2(out/'resources.ap_',unsigned)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as dest:
        for p in dex.glob('*.dex'): dest.write(p,p.name)
        assets=root/'app/src/main/assets'
        if assets.exists():
            for p in assets.rglob('*'):
                if p.is_file(): dest.write(p,'assets/'+str(p.relative_to(assets)))
    aligned=out/'preview-aligned.apk';run([bt/'zipalign','-f','-p','4',unsigned,aligned])
    dist=root/'dist';dist.mkdir(exist_ok=True);apk=dist/'Atlante-Reading-Companion-debug.apk'
    env=os.environ.copy();env['JAVA_HOME']=str(jdk.parent)
    command=[str(bt/'apksigner'),'sign','--ks',str(Path.home()/'.android/debug.keystore'),'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',str(apk),str(aligned)]
    subprocess.run(command,env=env,check=True,capture_output=True)
    subprocess.run([str(bt/'apksigner'),'verify','--verbose',str(apk)],env=env,check=True)
    run([bt/'zipalign','-c','-p','4',apk])
    import hashlib
    apk.with_suffix('.apk.sha256').write_text(hashlib.sha256(apk.read_bytes()).hexdigest()+'  '+apk.name+'\n')
    print('SIGNED DEBUG PREVIEW: '+str(apk))
