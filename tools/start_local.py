"""Start a local album and optionally connect an Android device over ADB."""

from pathlib import Path
import json
import os
import shutil
import socket
import subprocess
import sys
import time

from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.private'
CONFIG_PATH = PRIVATE / 'local.json'


def configuration():
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if CONFIG_PATH.exists():
        return json.loads(CONFIG_PATH.read_text(encoding='utf-8-sig'))
    settings = {
        'host': '127.0.0.1', 'port': 8765,
        'library': str(ROOT / 'library'),
        'data': str(ROOT / 'data'),
    }
    CONFIG_PATH.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding='utf-8')
    return settings


def server_status(address):
    try:
        with urlopen(address + '/api/status', timeout=2) as response:
            # Older servers can keep running while the Android app is upgraded.
            result = json.load(response)
            if isinstance(result, dict) and result.get('app_name') in {'FAM宝贝相册', '小家时光'}:
                return result
    except (OSError, URLError, ValueError):
        pass
    return {}


def is_running(address):
    return bool(server_status(address))


def connection_instructions(settings, address, status):
    connection = '本机与已连接模拟器的地址\n' + address + '\n'
    connection += '\n电脑上的管理后台\n' + address + '/admin/\n使用现有管理员账号登录。\n'
    if status.get('default_credentials') is True:
        connection += ('\n首次登录账号：admin\n首次登录密码：admin\n'
                       '\n打开应用，填写服务地址和以上账号密码。首次登录后必须设置自己的新密码，'
                       '完成后才能进入相册。新密码需要8～200个字符。\n'
                       '请先完成首次改密，再向家人分享相册地址。\n')
    elif status.get('setup_required') is True:
        setup_file = Path(settings['data']) / 'setup-code.txt'
        if setup_file.is_file():
            connection += ('\n首次设置口令\n' + setup_file.read_text(encoding='utf-8').strip()
                           + '\n\n打开应用，选择首次设置，填入设置口令，创建管理员账号。设置成功后此口令失效。\n')
        else:
            connection += '\n相册尚未完成设置，请在服务端查看首次设置口令，再到应用中创建管理员账号。\n'
    else:
        connection += ('\n已有相册请使用当前的用户名和密码登录，升级不会将账号密码恢复为 admin。\n'
                       '修改用户名或密码：我的 → 账号与安全。家人可以通过管理员发出的邀请加入。\n')
    connection += '\n这份文件仅供自己使用，请不要放入公开源码或发送给陌生人。\n'
    return connection


def adb_path():
    if os.environ.get('ALBUM_ADB'):
        return os.environ['ALBUM_ADB']
    local_tools = PRIVATE / 'build-tools.json'
    if local_tools.is_file():
        value = json.loads(local_tools.read_text(encoding='utf-8-sig')).get('adb')
        if value:
            return value
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        value = Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
        if value.is_file():
            return str(value)
    return shutil.which('adb')


def main():
    settings = configuration()
    address = f"http://127.0.0.1:{settings['port']}"
    if not is_running(address):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', settings['port'])) == 0:
                raise RuntimeError('本机相册要使用的地址已被其他程序占用，请换一个端口后启动。')
        environment = os.environ.copy()
        environment.update(ALBUM_DATA=settings['data'], ALBUM_LIBRARY=settings['library'], PYTHONUTF8='1')
        # Only old installations have this field. The server consumes it once
        # during account migration, never as a recurring password reset.
        if settings.get('password'):
            environment['ALBUM_PASSWORD'] = settings['password']
        with (PRIVATE / 'server.log').open('ab') as log:
            process = subprocess.Popen(
                [sys.executable, '-X', 'utf8', '-m', 'uvicorn', 'app:app',
                 '--host', settings['host'], '--port', str(settings['port']), '--no-access-log'],
                cwd=ROOT / 'server', env=environment,
                stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
                start_new_session=os.name != 'nt',
            )
        (PRIVATE / 'server.pid').write_text(str(process.pid), encoding='ascii')
        for _ in range(30):
            if is_running(address):
                break
            if process.poll() is not None:
                raise RuntimeError('相册服务启动未完成，启动记录已保存在本机。')
            time.sleep(.5)
        else:
            raise RuntimeError('相册服务仍未就绪，请稍后再次启动。')
    adb = adb_path()
    if adb:
        try:
            result = subprocess.run([adb, '-s', os.environ.get('ANDROID_SERIAL', 'emulator-5554'), 'reverse',
                            f"tcp:{settings['port']}", f"tcp:{settings['port']}"],
                           capture_output=True, timeout=10,
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if result.returncode != 0:
                print('相册服务已经启动，但模拟器尚未连接。请检查设备是否打开；多个设备可用 ANDROID_SERIAL 指定。')
        except (OSError, subprocess.TimeoutExpired):
            print('相册服务已经启动，模拟器尚未连接，可以稍后再连接。')
    connection = connection_instructions(settings, address, server_status(address))
    (PRIVATE / '本机连接信息.txt').write_text(connection, encoding='utf-8-sig')
    print('本机相册服务已启动。电脑管理后台：' + address + '/admin/')
    print('也可以在模拟器打开“FAM宝贝相册”，继续使用原来的相册。')
    print('首次连接需要的信息保存在 .private 文件夹的“本机连接信息”中。')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(str(error) if isinstance(error, RuntimeError) else '启动时无法读取所需文件，请检查项目目录。')
        raise SystemExit(1)
