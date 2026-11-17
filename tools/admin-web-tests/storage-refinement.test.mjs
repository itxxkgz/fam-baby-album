import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); } catch { ({ JSDOM } = require(process.env.FAM_JSDOM_PATH)); }
const dom = new JSDOM('<body><main></main><div id="toasts"></div></body>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement', 'HTMLInputElement', 'FormData', 'File', 'DOMException', 'AbortController', 'location', 'history', 'navigator', 'XMLHttpRequest', 'sessionStorage']) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
const { storagePage } = await import('../../server/web/views/storage.js');
const { settingsPage } = await import('../../server/web/views/settings.js');
const { dismissModals, hasPendingModalChanges } = await import('../../server/web/ui.js');
const local = { id: 'local', name: '自己的服务器', provider: 'local', provider_label: '自己的服务器', local_path: '/data/photos', media_count: 23, is_default: true, tested: true };
const cloud = { id: 'cloud', name: '家庭云', provider: 's3', provider_label: '通用 S3', endpoint: 'https://s3.example.test', region: 'test-1', bucket: 'family', prefix: 'originals', addressing_style: 'path', has_credentials: true, media_count: 12, is_default: false };
const profile = { name: '家庭相册', child_name: '宝宝', birthday: '2020-01-01' };
const settings = { max_upload_bytes: 100 * 1048576, server_max_upload_bytes: 1024 * 1048576, version: '2.9.3' };
const plan = { enabled: true, frequency: 'weekly', weekday: 3, time: '03:00', mode: 'full', keep: 7 };
const calls = []; let answer;
function standard(path) { return structuredClone(({ '/api/storage': { items: [local, cloud], allowed_local_roots: ['/data/photos'] }, '/api/profile': profile, '/api/admin/settings': settings, '/api/admin/backups/plan': plan, '/api/admin/backups': { items: [] } })[path] || {}); }
function setup(state = {}) {
  dismissModals(); document.body.replaceChildren(document.createElement('main'), Object.assign(document.createElement('div'), { id: 'toasts' })); calls.length = 0; answer = call => standard(call.path);
  const ctx = { state, container: document.querySelector('main'), member: { username: 'admin-user' }, child: { id: 'baby', name: '宝宝' }, childId: 'baby', current: true, isCurrent: () => ctx.current, request: async (path, options = {}) => { const call = { path, method: options.method || (options.body === undefined ? 'GET' : 'POST'), ...options }; calls.push(call); return answer(call); }, refresh: () => { ctx.refreshed = true; }, familyName: value => { ctx.family = value; }, setLeaveCheck: value => { ctx.leave = value; }, navigate: value => { ctx.destination = value; }, session: value => { ctx.member = value.member; } }; return ctx;
}
const fail = (message, status = 400) => Object.assign(new Error(message), { status });
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
async function until(check) { for (let i = 0; i < 80; i++) { if (check()) return; await tick(); } assert.fail('页面未按预期完成更新'); }
function click(text, scope = document) { const node = [...scope.querySelectorAll('button')].find(node => node.textContent.trim() === text); assert.ok(node, `找到${text}`); node.click(); return node; }
function field(text, scope = document) { const label = [...scope.querySelectorAll('label.field')].find(node => node.querySelector('span')?.textContent === text); assert.ok(label, `找到${text}`); return label.querySelector('input,select'); }
function change(node, value) { if (node.type === 'checkbox') node.checked = value; else node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); }
function submit(scope = document.querySelector('dialog')) { scope.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); }
function selectProvider(label) { [...document.querySelectorAll('.provider-choice')].find(node => node.querySelector('strong').textContent === label).click(); return document.querySelector('dialog'); }
function fillS3() { change(field('位置名称'), '家庭新云'); change(field('存储桶名称'), 'family-new'); change(field('服务地址'), 'https://s3.example.test'); change(field('访问密钥 ID'), 'new-id'); change(field('访问密钥 Secret'), ' secret with spaces '); }
after(() => { dismissModals(); dom.window.close(); });

test('存储先展示已有位置，五种新增方式只展开必要字段', async () => {
  const ctx = setup(); await storagePage(ctx);
  assert.equal(document.querySelector('.provider-choice'), null); assert.equal(document.querySelectorAll('.storage-location-row').length, 2); assert.equal(document.body.textContent.includes('/data/photos'), false);
  for (const label of ['自己的服务器', 'NAS', '阿里云 OSS', '腾讯云 COS', '通用 S3']) {
    click('添加存放位置'); const dialog = selectProvider(label);
    if (label === '自己的服务器') { assert.equal(field('服务器文件夹').value, '/data/photos'); assert.equal(field('服务器文件夹').closest('details').open, false); }
    if (label === 'NAS') assert.equal(field('服务器上的挂载目录').value, '');
    if (label === '通用 S3') { assert.equal(field('服务地址').required, true); assert.equal(field('服务地址').closest('details'), null); assert.equal(field('存储区域').closest('details').open, false); }
    if (label.includes('OSS') || label.includes('COS')) { assert.equal(field('存储区域').closest('details'), null); assert.equal(field('服务地址').closest('details').open, false); }
    click('取消', dialog); await until(() => !dialog.isConnected);
  }
});

test('空列表也能从选择方式新增，不把点击事件误当已有配置', async () => {
  const ctx = setup(); answer = call => call.path === '/api/storage' ? { items: [], allowed_local_roots: ['/data/photos'] } : standard(call.path);
  await storagePage(ctx); click('选择存放方式'); const dialog = selectProvider('自己的服务器');
  assert.equal(dialog.getAttribute('aria-label'), '添加自己的服务器'); assert.equal(field('位置名称').value, '自己的服务器');
});

test('保存成功而连接失败会保留输入，重试更新同一位置且不重复新增', async () => {
  const ctx = setup(); let attempts = 0;
  answer = call => { if (call.path === '/api/storage' && call.method === 'POST') return { ...cloud, id: 'new-cloud', name: call.body.name, media_count: 0 }; if (call.path === '/api/storage/new-cloud' && call.method === 'PUT') return { ...cloud, id: 'new-cloud', media_count: 0 }; if (call.path.endsWith('/test') && ++attempts === 1) throw fail('密钥暂时无法连接', 400); return standard(call.path); };
  await storagePage(ctx); click('添加存放位置'); const dialog = selectProvider('通用 S3'); fillS3(); submit(dialog);
  await until(() => dialog.querySelector('.form-error').textContent.includes('设置已保存'));
  assert.equal(field('访问密钥 Secret').value, ' secret with spaces '); assert.equal(hasPendingModalChanges(), false);
  submit(dialog); await until(() => !dialog.isConnected);
  assert.equal(calls.filter(call => call.path === '/api/storage' && call.method === 'POST').length, 1);
  assert.equal(calls.filter(call => call.path === '/api/storage/new-cloud' && call.method === 'PUT').length, 1);
  assert.equal(calls.find(call => call.method === 'POST' && call.path === '/api/storage').body.secret_access_key, ' secret with spaces ');
});

test('新增结果不明确时禁止再次提交，允许直接返回列表核对', async () => {
  const ctx = setup(); answer = call => { if (call.path === '/api/storage' && call.method === 'POST') throw fail('网络断开', 0); return standard(call.path); };
  await storagePage(ctx); click('添加存放位置'); const dialog = selectProvider('自己的服务器'); change(field('位置名称'), '另一个位置'); submit(dialog);
  await until(() => dialog.querySelector('.form-error').textContent.includes('避免重复添加'));
  assert.equal(dialog.querySelector('button[type="submit"]').disabled, true); submit(dialog); await tick();
  assert.equal(calls.filter(call => call.method === 'POST').length, 1); click('返回存放位置列表'); await until(() => !dialog.isConnected);
  assert.equal(document.querySelector('dialog'), null); assert.ok(calls.filter(call => call.path === '/api/storage' && call.method === 'GET').length >= 2);
});

test('存储未保存返回可继续编辑，提交中不能关闭或重复保存', async () => {
  const ctx = setup(); let finish;
  answer = call => call.path === '/api/storage' && call.method === 'POST' ? new Promise(resolve => { finish = resolve; }) : standard(call.path);
  await storagePage(ctx); click('添加存放位置'); const dialog = selectProvider('自己的服务器'); change(field('位置名称'), '我的新位置'); click('取消', dialog);
  await until(() => document.querySelectorAll('dialog').length === 2); click('取消', [...document.querySelectorAll('dialog')].at(-1)); await tick(); assert.equal(field('位置名称').value, '我的新位置');
  submit(dialog); await until(() => !!finish); dialog.dispatchEvent(new Event('cancel', { cancelable: true })); submit(dialog); await tick();
  assert.equal(dialog.isConnected, true); assert.equal(calls.filter(call => call.method === 'POST').length, 1);
  finish({ ...local, id: 'new-local', media_count: 0 }); await until(() => !dialog.isConnected);
});

test('已有内容的目录和云配置锁定，失败后仍保留锁定和输入', async () => {
  const ctx = setup(); answer = call => { if (call.method === 'PUT') throw fail('暂时未保存', 409); return standard(call.path); };
  await storagePage(ctx); click('查看设置', document.querySelectorAll('.storage-location-row')[1]); click('编辑设置');
  const dialog = document.querySelector('dialog'); assert.equal(field('存储桶名称').readOnly, true); assert.equal(field('访问方式').disabled, true);
  change(field('位置名称'), '家庭云新名字'); change(field('访问密钥 ID'), 'new-key'); change(field('访问密钥 Secret'), 'new-secret'); submit(dialog);
  await until(() => dialog.querySelector('.form-error').textContent.includes('暂时未保存'));
  assert.deepEqual(calls.find(call => call.method === 'PUT').body, { name: '家庭云新名字', access_key_id: 'new-key', secret_access_key: 'new-secret' });
  assert.equal(field('存储桶名称').readOnly, true); assert.equal(field('访问方式').disabled, true); assert.equal(field('访问密钥 Secret').value, 'new-secret');
});

test('切换新上传位置说明旧文件不搬迁，并先检查连接', async () => {
  const ctx = setup(); let failTest = true;
  answer = call => { if (call.path.endsWith('/test') && failTest) throw fail('连接未通过'); return standard(call.path); };
  await storagePage(ctx); click('查看设置', document.querySelectorAll('.storage-location-row')[1]); const dialog = document.querySelector('dialog'); click('用于新上传', dialog);
  const confirmation = [...document.querySelectorAll('dialog')].at(-1); assert.match(confirmation.textContent, /不会自动搬迁/); click('用于新上传', confirmation);
  await until(() => dialog.querySelector('.form-error').textContent.includes('连接未通过')); assert.equal(calls.some(call => call.path.endsWith('/default')), false);
  failTest = false; click('用于新上传', dialog); click('用于新上传', [...document.querySelectorAll('dialog')].at(-1)); await until(() => ctx.refreshed);
  assert.equal(calls.filter(call => call.path.endsWith('/default')).length, 1);
});

test('备份计划有未保存离开保护，失败后保留计划', async () => {
  const ctx = setup({ storageTab: 'plan' }); answer = call => { if (call.method === 'PUT') throw fail('计划暂时未保存'); return standard(call.path); };
  const cleanup = await storagePage(ctx); change(field('保留最近几份备份'), '12'); click('备份记录');
  await until(() => document.querySelector('dialog')); click('取消', document.querySelector('dialog')); await tick(); assert.ok(document.querySelector('.backup-plan')); assert.equal(field('保留最近几份备份').value, '12');
  document.querySelector('.backup-plan').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await until(() => document.querySelector('.form-error').textContent.includes('暂时未保存'));
  assert.equal(ctx.leave.dirty(), true); assert.equal(field('保留最近几份备份').value, '12'); cleanup();
});

test('备份记录保留创建入口，完整备份提交后转到记录', async () => {
  const ctx = setup({ storageTab: 'backups' }); const cleanup = await storagePage(ctx); click('立即备份');
  assert.equal(field('备份内容').value, 'full'); click('开始备份'); await until(() => calls.some(call => call.method === 'POST' && call.path === '/api/admin/backups'));
  assert.deepEqual(calls.find(call => call.method === 'POST').body, { mode: 'full' }); await until(() => !document.querySelector('dialog')); cleanup();
});

test('基础设置和账号分开，部分保存只重试未保存项目，宝宝入口保留', async () => {
  const ctx = setup(); let failSettings = true;
  answer = call => { if (call.path === '/api/admin/settings' && call.method === 'PUT' && failSettings) throw fail('上传限制暂时未保存'); return standard(call.path); };
  await settingsPage(ctx); assert.equal(document.querySelector('.settings-form').hidden, false); click('账号与安全'); assert.equal(document.querySelector('.settings-form').hidden, true); click('基础设置');
  change(field('家庭相册名称（所有宝宝共用）'), '新的家庭名字'); change(field('单个文件最大大小（MB）'), '200'); submit(document);
  await until(() => document.querySelector('.settings-form .form-error').textContent.includes('相册名称已保存'));
  assert.equal(ctx.leave.dirty(), true); assert.equal(field('单个文件最大大小（MB）').value, '200'); failSettings = false; submit(document); await until(() => !ctx.leave.dirty());
  assert.equal(calls.filter(call => call.method === 'PUT' && call.path === '/api/profile').length, 1);
  document.querySelector('.settings-row').click(); assert.equal(ctx.destination, 'family'); assert.equal(ctx.state.familyTab, 'children');
});

test('改密码失败保留输入和主页面草稿，离开可取消且不清除登录', async () => {
  const ctx = setup(); answer = call => { if (call.path === '/api/admin/account') throw fail('当前密码不正确', 401); return standard(call.path); };
  await settingsPage(ctx); change(field('家庭相册名称（所有宝宝共用）'), '未保存的名字'); click('账号与安全'); click('修改密码');
  const dialog = document.querySelector('dialog'); change(field('当前密码'), 'wrong'); change(field('新密码'), 'valid-password'); change(field('再次输入新密码'), 'valid-password'); submit(dialog);
  await until(() => dialog.querySelector('.form-error').textContent.includes('不正确')); assert.equal(field('新密码').value, 'valid-password'); assert.equal(calls.find(call => call.path === '/api/admin/account').noExpire, true);
  click('取消', dialog); await until(() => document.querySelectorAll('dialog').length === 2); click('取消', [...document.querySelectorAll('dialog')].at(-1)); await tick();
  assert.equal(dialog.isConnected, true); assert.equal(ctx.leave.dirty(), true); assert.equal(field('家庭相册名称（所有宝宝共用）').value, '未保存的名字');
});

test('账号保存回包不完整时保留表单和密码，不误清除或替换登录', async () => {
  const ctx = setup(); answer = call => call.path === '/api/admin/account' ? { member: { username: 'new-name' } } : standard(call.path);
  await settingsPage(ctx); click('账号与安全'); click('修改用户名'); const dialog = document.querySelector('dialog');
  change(field('当前密码'), 'entered-password'); change(field('新用户名'), 'new-name'); submit(dialog);
  await until(() => dialog.querySelector('.form-error').textContent.includes('保存结果暂时无法确认'));
  assert.equal(field('当前密码').value, 'entered-password'); assert.equal(field('新用户名').value, 'new-name'); assert.equal(ctx.member.username, 'admin-user'); assert.equal(dialog.isConnected, true);
});
