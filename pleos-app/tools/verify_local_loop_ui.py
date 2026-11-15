"""Capture current-location loop handoff/lifecycle on the explicit Pleos emulator.
Run only after the other shared-AVD UI checks release control.
"""
from pathlib import Path
import base64, json, re, shlex, subprocess, sys, threading, time
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/current-location-loop-20261115/ui'
OUT.mkdir(parents=True,exist_ok=True)
ADB=[r'C:\Users\juhyu\AppData\Local\Android\Sdk\platform-tools\adb.exe','-s','emulator-5592']
APP='kr.juhyu.licensenote'
USER='10'

def adb(*parts): return subprocess.check_output(ADB+list(parts),timeout=30)
def shell(*parts): return adb('shell',shlex.join(parts)).decode('utf8','replace').strip()
def probe(*options):
    raw=shell('am','instrument','--user',USER,'-w',*options,APP+'.driver/.UiDriver')
    if 'INSTRUMENTATION_RESULT: error=' in raw: raise RuntimeError(raw)
    return ET.fromstring(base64.b64decode(re.search(r'xml64=(\S+)',raw)[1]))
def texts(tree): return [n.get('text') for n in tree.iter('node') if n.get('text')]
def tap(label): probe('-e','click',label);time.sleep(.4)
def shot(name):
    tree=probe()
    (OUT/(name+'.xml')).write_bytes(ET.tostring(tree,encoding='utf8'))
    (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
    print(json.dumps({'capture':name,'text':texts(tree)},ensure_ascii=False),flush=True)
    return tree

def signals():
    return json.loads(shell('run-as',APP,'--user',USER,'cat','files/sdk-state.json'))
def save_signals(name):
    value=signals();(OUT/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8');return value

def wait(label, seconds=65):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        labels=texts(probe())
        if label in labels:return
        if any('찾지 못했어요' in s for s in labels):raise RuntimeError(labels)
        time.sleep(.5)
    raise RuntimeError('Missing '+label+': '+repr(labels))

if __name__=='__main__':
    assert shell('getprop','ro.kernel.qemu')=='1'
    mode=sys.argv[1] if len(sys.argv)>1 else 'inspect'
    if mode=='inspect':
        shot('current');save_signals('current-signals')
    elif mode=='lifecycle':
        shell('am','start','--user',USER,'-n',APP+'/.MainActivity');time.sleep(2)
        before=save_signals('visible-before')
        shell('input','keyevent','KEYCODE_HOME');time.sleep(2)
        hidden=save_signals('hidden-not-recording')
        assert hidden['location']['quality']=='UNAVAILABLE',hidden.get('location')
        shell('am','start','--user',USER,'-n',APP+'/.MainActivity');time.sleep(3)
        after=save_signals('visible-after')
        assert after['location']['quality']=='VALID',after.get('location')
        print(json.dumps({'hiddenStopsLocation':True,'visibleResumesLocation':True}),flush=True)
    elif mode=='signals':
        save_signals('native-handoff-signals');shot('native-handoff-app')
    else:raise ValueError(mode)
