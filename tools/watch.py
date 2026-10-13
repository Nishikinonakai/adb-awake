#!/usr/bin/env python3
"""Start the phone helper whenever this paired phone is already connected over Wi-Fi."""
import argparse, json, os, pathlib, plistlib, shutil, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = pathlib.Path(os.environ.get('ADB', str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')))
CONFIG = ROOT/'device.json'
TARGET_SERIAL = os.environ.get('ADB_AWAKE_DEVICE', '')
if not TARGET_SERIAL and CONFIG.exists():
    TARGET_SERIAL = json.loads(CONFIG.read_text()).get('serial', '')
LABEL = 'cn.local.adbawake.watch'
AGENT = pathlib.Path.home()/'Library/LaunchAgents'/f'{LABEL}.plist'
INSTALLDIR = pathlib.Path.home()/'Library/Application Support/ADB-Awake'
LOGDIR = pathlib.Path.home()/'Library/Logs/ADB-Awake'
LOG = LOGDIR/'watch.log'

def run(*args, timeout=20):
    p = subprocess.run([str(ADB), *args], capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError('ADB operation failed')
    return p.stdout.strip()

def log(message):
    LOGDIR.mkdir(parents=True, exist_ok=True)
    if LOG.exists() and LOG.stat().st_size > 256000:
        LOG.replace(LOG.with_suffix('.previous.log'))
    with LOG.open('a') as f:
        f.write(time.strftime('%Y-%m-%d %H:%M:%S ')+message+'\n')

def start_once():
    started = 0
    if not TARGET_SERIAL:
        return started
    for line in run('devices').splitlines()[1:]:
        parts = line.split()
        if len(parts)<2 or parts[1]!='device':
            continue
        serial = parts[0]
        known = serial.startswith('adb-'+TARGET_SERIAL+'-') and '_adb-tls-connect._tcp' in serial
        if not known and ':' in serial:
            # An explicit adb connect IP:port has a different transport name.
            known = run('-s', serial, 'shell', 'getprop ro.serialno') == TARGET_SERIAL
        if not known:
            continue
        # No connect command: manually disconnecting stays disconnected.
        p = subprocess.run([str(ADB), '-s', serial, 'shell', 'pidof adbawake.helper'], capture_output=True, text=True, timeout=10)
        if p.returncode == 0 and p.stdout.strip():
            return started
        if not run('-s', serial, 'shell', 'pm path cn.local.adbawake').startswith('package:'):
            log('Phone connected, APK missing'); continue
        run('-s', serial, 'push', str(ROOT/'ADB-Awake.apk'), '/data/local/tmp/adbawake.apk')
        run('-s', serial, 'shell', 'CLASSPATH=/data/local/tmp/adbawake.apk nohup app_process /system/bin --nice-name=adbawake.helper cn.local.adbawake.Daemon >/data/local/tmp/adbawake-daemon.log 2>&1 </dev/null &')
        log('Phone helper started'); started += 1
        return started
    return started

def install():
    if not TARGET_SERIAL:
        raise SystemExit("请先指定手机序列号：--device SERIAL --install")
    AGENT.parent.mkdir(parents=True, exist_ok=True)
    LOGDIR.mkdir(parents=True, exist_ok=True)
    (INSTALLDIR/'tools').mkdir(parents=True, exist_ok=True)
    if ROOT != INSTALLDIR:
        shutil.copy2(ROOT/'ADB-Awake.apk', INSTALLDIR/'ADB-Awake.apk')
        shutil.copy2(pathlib.Path(__file__).resolve(), INSTALLDIR/'tools/watch.py')
    (INSTALLDIR/'device.json').write_text(json.dumps({'serial': TARGET_SERIAL}))
    data = dict(Label=LABEL, ProgramArguments=[sys.executable, str(INSTALLDIR/'tools/watch.py'), '--watch'],
                RunAtLoad=True, KeepAlive=True, ProcessType='Background', ThrottleInterval=15,
                WorkingDirectory=str(INSTALLDIR), StandardOutPath=str(LOGDIR/'watcher-stdout.log'),
                StandardErrorPath=str(LOGDIR/'watcher-stderr.log'))
    AGENT.write_bytes(plistlib.dumps(data))
    target=f'gui/{os.getuid()}'
    subprocess.run(['launchctl','bootout',target,str(AGENT)],capture_output=True)
    subprocess.run(['launchctl','bootstrap',target,str(AGENT)],check=True)
    print('已启用电脑端自动启动。无线 ADB 连上时会启动手机辅助进程。')

def uninstall():
    subprocess.run(['launchctl','bootout',f'gui/{os.getuid()}',str(AGENT)],capture_output=True)
    AGENT.unlink(missing_ok=True)
    print('电脑端自动启动已关闭。手机当前常亮请在应用里关闭。')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--watch',action='store_true');p.add_argument('--install',action='store_true');p.add_argument('--uninstall',action='store_true');p.add_argument('--device',help='指定目标手机的 ro.serialno');args=p.parse_args()
    if args.device:
        TARGET_SERIAL=args.device
        CONFIG.write_text(json.dumps({'serial': TARGET_SERIAL}))
    if args.install:install()
    elif args.uninstall:uninstall()
    elif args.watch:
        log('Watcher running')
        while True:
            try:start_once()
            except Exception as e:log(type(e).__name__)
            time.sleep(10)
    else:
        print('启动检查完成，新增辅助进程：',start_once())
