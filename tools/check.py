"""Run independent test modules without sharing application process state."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    environment = os.environ.copy()
    environment['PYTHONUTF8'] = '1'
    modules = sorted((ROOT / 'server' / 'tests').glob('test_*.py'))
    modules += sorted((ROOT / 'tools').glob('test_*.py'))
    if not modules:
        print('没有找到自动化检查，请确认源码是否完整。')
        return 1
    failed = False
    for module in modules:
        result = subprocess.run([sys.executable, '-X', 'utf8', '-m', 'unittest', 'discover',
                                 '-s', str(module.parent.relative_to(ROOT)), '-p', module.name, '-v'],
                                cwd=ROOT, env=environment)
        failed |= result.returncode != 0
    print('检查未全部通过，请查看上面的失败说明。' if failed else '自动化检查已通过。')
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
