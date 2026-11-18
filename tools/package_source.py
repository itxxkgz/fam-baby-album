"""Create a clean source archive from an explicit release allowlist."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ('README.md', 'LICENSE', 'CONTRIBUTING.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md',
              '.gitignore', '.gitattributes', '.env.example', 'compose.yaml', 'build.ps1', 'format-java.ps1',
              '启动本机相册.cmd')
TOOLS = ('start_local.py', 'test_start_local.py', 'check.py', 'package_source.py')
APP_ASSETS = ('android/res/drawable-nodpi/fam_launcher_art.png',
              'android/assets/legal/LICENSE.txt', 'android/assets/legal/THIRD_PARTY_NOTICES.txt')
# Only reviewed public examples belong here; private reference boards stay excluded.
PUBLIC_DESIGN_ASSETS = (
    '01-album-and-memories.png', '02-family-and-activity.png', '03-growing-up.png',
    '04-monthly-storybook.png', '05-babies-and-family.png', '06-photo-storage.png',
    '07-nas-and-cloud.png', '08-admin-overview.png', '09-admin-import-and-family.png',
    '10-admin-storage-and-settings.png',
)
SERVER = ('app.py', 'identity.py', 'storage.py', 'storage_api.py', 'media_inspection.py', 'memories.py', 'activity_feed.py',
          'admin_api.py', 'admin_imports.py', 'admin_backups.py', 'children.py',
          'import_album.py', 'requirements.txt', 'requirements-dev.txt', 'Dockerfile', '.dockerignore', 'API.md')
BAD_PARTS = {'.private', '.tools', '.build', 'data', 'library', 'qa', '__pycache__', '.git', 'node_modules'}


def source_files():
    paths = [ROOT / p for p in ROOT_FILES]
    paths += [ROOT / 'tools' / p for p in TOOLS]
    paths += [ROOT / p for p in APP_ASSETS]
    paths += [ROOT / 'docs' / 'design' / 'public' / p for p in PUBLIC_DESIGN_ASSETS]
    paths += [ROOT / 'server' / p for p in SERVER if (ROOT / 'server' / p).is_file()]
    paths += list((ROOT / 'server' / 'tests').glob('test_*.py'))
    paths += [p for p in (ROOT / 'server' / 'web').rglob('*')
              if p.is_file() and p.suffix in ('.html', '.css', '.js', '.mjs', '.svg', '.png')]
    paths += list((ROOT / 'tools').glob('test_admin_*'))
    paths += [p for p in (ROOT / 'tools' / 'admin-web-tests').glob('*')
              if p.is_file() and p.suffix in ('.json', '.mjs', '.js')]
    paths += list((ROOT / 'docs').glob('*.md'))
    for directory in ('android/src', 'android/res', 'android/test'):
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in ('.java', '.xml')]
    paths.append(ROOT / 'android' / 'AndroidManifest.xml')
    paths += list((ROOT / '.github' / 'workflows').glob('*.yml'))
    result = []
    for path in sorted(set(paths)):
        relative = path.relative_to(ROOT)
        if not path.is_file() or path.is_symlink() or BAD_PARTS.intersection(relative.parts):
            raise ValueError('源码清单中有缺失文件或不适合发布的文件，请检查后重试。')
        if not path.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError('源码清单中有项目以外的文件，已停止打包。')
        result.append(path)
    return result


def main():
    files = source_files()
    destination = ROOT / 'dist'
    destination.mkdir(exist_ok=True)
    archive = destination / 'FAM宝贝相册-开源源码.zip'
    temporary = archive.with_suffix('.tmp')
    manifest = {}
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as output:
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            data = path.read_bytes()
            output.writestr('fam-baby-album/' + relative, data)
            manifest[relative] = hashlib.sha256(data).hexdigest()
        output.writestr('fam-baby-album/source-manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
    temporary.replace(archive)
    print('可发布的源码包已生成，保存在 dist 文件夹。相册资料、本机配置和应用签名未打包。')


if __name__ == '__main__':
    main()
