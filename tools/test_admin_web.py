"""网页 DOM 交互测试；不启动或连接浏览器，不使用真实相册。"""
from pathlib import Path
import os
import shutil
import subprocess
import unittest


class AdminWebTests(unittest.TestCase):
    def test_admin_web_dom(self):
        root = Path(__file__).resolve().parents[1]
        node = shutil.which('node')
        self.assertTrue(node, '请先安装 Node.js 22.13 或更新版本，再运行网页测试。')
        dependency = root / 'tools' / 'admin-web-tests' / 'node_modules' / 'jsdom' / 'lib' / 'api.js'
        alternate = os.environ.get('FAM_JSDOM_PATH')
        self.assertTrue(dependency.is_file() or (alternate and Path(alternate).is_file()),
                        '网页测试依赖尚未安装，请在 tools/admin-web-tests 目录执行 npm ci。')
        cases = sorted((root / 'tools' / 'admin-web-tests').glob('*.test.mjs'))
        self.assertTrue(cases, '网页交互检查文件不完整。')
        result = subprocess.run([node, '--test', *(str(case) for case in cases)],
                                cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace',
                                timeout=60, env={**os.environ, 'NODE_DISABLE_COLORS': '1'})
        self.assertEqual(result.returncode, 0, f'网页交互测试未通过：\n{result.stdout}\n{result.stderr}')


if __name__ == '__main__':
    unittest.main()
