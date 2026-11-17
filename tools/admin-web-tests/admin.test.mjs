import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { existsSync, readFileSync } from 'node:fs';
const require = createRequire(import.meta.url);
const jsdomPath = process.env.FAM_JSDOM_PATH;
let JSDOM;
try { ({ JSDOM } = require('jsdom')); } catch (error) { if (!jsdomPath) throw new Error('请先在 tools/admin-web-tests 目录执行 npm ci，再运行前端测试。', { cause: error }); ({ JSDOM } = require(jsdomPath)); }
const dom = new JSDOM('<!doctype html><html><body><div id="app"></div><div id="toasts"></div></body></html>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement', 'HTMLInputElement', 'FormData', 'File', 'DOMException', 'AbortController', 'location', 'history', 'navigator', 'XMLHttpRequest', 'sessionStorage']) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
dom.window.HTMLMediaElement.prototype.pause = function () {};
dom.window.HTMLMediaElement.prototype.load = function () {};
const calls = [];
let respond = () => ({}), expired = 0;
globalThis.fetch = async (path, options = {}) => {
  const call = { path: String(path), method: options.method || 'GET', body: options.body ? JSON.parse(options.body) : undefined, options };
  calls.push(call);
  const result = await respond(call);
  if (result instanceof Response) return result;
  return new Response(JSON.stringify(result ?? {}), { status: 200, headers: { 'Content-Type': 'application/json' } });
};
const { api, setSession, setChild, expiredHandler } = await import('../../server/web/api.js');
const utils = await import('../../server/web/utils.mjs');
const { mediaPage, overviewPage } = await import('../../server/web/views/media.js');
const { familyPage } = await import('../../server/web/views/family.js');
const { storagePage } = await import('../../server/web/views/storage.js');
const { settingsPage } = await import('../../server/web/views/settings.js');
const { importsPage } = await import('../../server/web/views/imports.js');
const { openViewer } = await import('../../server/web/ui.js');
const member = { id: 'owner', name: '爸爸', username: 'family-admin', role: 'admin', family_role: 'admin', is_family_admin: true, registered: true, role_label: '管理员', uploads_count: 2 };
const legacyMember = { id: 'old-mom', name: '妈妈', registered: false, role: 'contributor', family_role: 'contributor', is_family_admin: false, children: [{ child_id: 'older', name: '姐姐', role: 'contributor' }], uploads_count: 5 };
const grandmother = { id: 'grandma', name: '陈女士', username: 'grandma', role: 'admin', registered: true, is_family_admin: false, family_role: 'contributor', children: [{ child_id: 'older', name: '姐姐', role: 'viewer' }, { child_id: 'younger', name: '弟弟', role: 'contributor' }] };
const scopedGrandmother = { ...grandmother, role: 'viewer', relationship: '奶奶', can_edit_relationship: true, can_change_role: true, can_remove: true };
const children = [{ id: 'older', name: '姐姐', birthday: '2020-01-01', archived: false, age: '6岁', counts: { photos: 2, videos: 1 } }, { id: 'younger', name: '弟弟', birthday: '2024-01-01', archived: false, counts: { photos: 0, videos: 0 } }, { id: 'archived', name: '旧记录', archived: true, counts: {} }];
const profile = { name: '测试相册', child_name: '宝贝', birthday: '2020-01-01', storage_bytes: 2048, counts: { photos: 2, videos: 1, members: 2 } };
const media = [
  { id: 'first', kind: 'photo', filename: '春游.jpg', description: '<img src=x onerror=alert(1)>', captured_at: '2026-05-17T02:00:00Z', date_label: '2026年5月17日', age: '6岁4个月16天', uploader_name: '妈妈', thumbnail_url: '/api/media/first/thumbnail', file_url: '/api/media/first/file' },
  { id: 'second', kind: 'video', filename: '笑声.mp4', captured_at: '2026-05-18T02:00:00Z', date_label: '2026年5月18日', age: '6岁4个月17天', duration: 63, thumbnail_url: '/api/media/second/thumbnail', file_url: '/api/media/second/file' },
];
const settings = { max_upload_bytes: 104857600, server_max_upload_bytes: 1073741824, version: '2.7.0' };
const plan = { enabled: false, frequency: 'weekly', time: '04:15', weekday: 6, mode: 'metadata', keep: 9 };
const localStorage = { items: [{ id: 'local', name: '自己的服务器', provider: 'local', media_count: 2, is_default: true, tested: true, status_label: '连接正常', local_path: '/photos' }], allowed_local_roots: ['/photos'] };
const overview = { profile, recent: media, week_count: 2, storage: { total_bytes: 20480, free_bytes: 10240 }, default_storage: localStorage.items[0], tasks: {}, backups: {} };
const error = (status, detail) => new Response(JSON.stringify({ detail }), { status, headers: { 'Content-Type': 'application/json' } });
function standard(call) {
  if (call.path.startsWith('/api/admin/media?')) return { items: media, total: 2, has_more: false };
  return ({ '/api/children?include_archived=true': { items: children, selected_child_id: 'older', default_child_id: 'older', can_create: true, is_family_admin: true }, '/api/admin/overview': overview, '/api/members': { items: [member, grandmother] }, '/api/children/older/members': { items: [{ ...member, relationship: '爸爸', can_edit_relationship: true, can_change_role: false, can_remove: false }, scopedGrandmother] }, '/api/members?include_legacy=true': { items: [member, grandmother, legacyMember] }, '/api/invitations': { items: [] }, '/api/storage': localStorage, '/api/admin/imports': { items: [] }, '/api/admin/backups': { items: [] }, '/api/admin/backups/plan': plan, '/api/profile': profile, '/api/admin/settings': settings })[call.path] || {};
}
async function until(predicate, message = '等待页面更新') {
  for (let i = 0; i < 100; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  assert.fail(message);
}
const click = (text, scope = document) => { const node = [...scope.querySelectorAll('button,a')].find(node => node.textContent.trim() === text); assert.ok(node, `找到按钮 ${text}`); node.click(); return node; };
const field = (text, scope = document) => { const label = [...scope.querySelectorAll('label.field')].find(label => label.querySelector('span')?.textContent === text); assert.ok(label, `找到字段 ${text}`); return label.querySelector('input,textarea,select'); };
function change(node, value) { if (node.type === 'checkbox' || node.type === 'radio') node.checked = value; else node.value = value; node.dispatchEvent(new window.Event('input', { bubbles: true })); node.dispatchEvent(new window.Event('change', { bubbles: true })); }
function submit(scope) { scope.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true })); }
function expand(selector) { const node = document.querySelector(selector); assert.ok(node, `找到折叠区域 ${selector}`); node.open = true; node.dispatchEvent(new window.Event('toggle')); return node; }
function context(state = {}) {
  document.body.replaceChildren(Object.assign(document.createElement('main'), { id: 'test-page' }), Object.assign(document.createElement('div'), { id: 'toasts' }));
  const ctx = { container: document.getElementById('test-page'), member: { ...member }, childId: 'older', child: children[0], children, state: { media: { kind: 'all', deleted: false }, ...state }, request: (path, options = {}) => api(path, { childId: ctx.childId, ...options }), isCurrent: () => true, familyName: () => {}, navigate: () => {}, refresh: () => { ctx.refreshed = true; }, setLeaveCheck: check => { ctx.leave = check; }, session: result => { ctx.member = result.member; setSession(result); } };
  calls.length = 0; expired = 0; expiredHandler(() => expired++); setSession({ csrf: 'test-csrf' }); setChild('older'); respond = standard; return ctx;
}
after(() => dom.window.close());

test('格式化、查询与任务操作保持用户语义和安全边界', () => {
  assert.equal(utils.safeMediaUrl('javascript:alert(1)'), '');
  assert.equal(utils.safeMediaUrl('//example.com/api/media/a/file'), '');
  assert.equal(utils.safeMediaUrl('/api/media/first/file'), '/api/media/first/file');
  assert.equal(utils.safeMediaUrl('/api/media/first/file?child_id=older'), '/api/media/first/file?child_id=older');
  for (const path of ['/api/media/first/file?child_id=older&token=secret', '/api/media/first/file?child_id=older&child_id=younger', '/api/media/first/file?child_id=older#anything', '/api/media/first/file?child_id=..%2Fother', '/api/media/first\\..\\file/file']) assert.equal(utils.safeMediaUrl(path), '');
  assert.equal(utils.safeAvatarUrl('/api/family/avatar?child_id=younger'), '/api/family/avatar?child_id=younger');
  assert.equal(utils.isFamilyAdmin({ role: 'admin', family_role: 'contributor', is_family_admin: false }), false);
  assert.equal(utils.bytes(1048576), '1 MB');
  assert.equal(utils.duration(63), '1:03');
  assert.equal(utils.percent(4, 3), 100);
  assert.match(utils.dateText('2026-05-17T18:00:00Z'), /2026年5月18日/);
  assert.equal(utils.captureTime('2026-05-17T10:20'), '2026-05-17T10:20:00+08:00');
  assert.deepEqual(utils.jobActions({ status: 'paused', failed: 2 }), ['resume', 'retry', 'cancel']);
  assert.deepEqual(utils.jobActions({ status: 'failed', failed: 2 }), ['retry', 'cancel']);
  const query = new URLSearchParams(utils.mediaQuery({ search: '妈妈 & 宝贝', deleted: true }, 40));
  assert.equal(query.get('search'), '妈妈 & 宝贝'); assert.equal(query.get('deleted'), 'true'); assert.equal(query.get('offset'), '40');
});

test('真实登录表单、首次改密门禁、六页导航以及退出', async () => {
  let logged = false, changed = false;
  respond = call => {
    if (call.path === '/api/admin/session') {
      if (call.method === 'DELETE') return {};
      if (call.method === 'POST') { logged = true; return { member, csrf: 'pending', password_change_required: true }; }
      return logged ? { member, csrf: 'normal' } : error(401, '请先登录');
    }
    if (call.path === '/api/status') return { default_credentials: true };
    if (call.path === '/api/admin/account') { changed = true; return { member, csrf: 'normal', password_change_required: false }; }
    return standard(call);
  };
  await import('../../server/web/admin.js');
  assert.match(document.body.textContent, /用户名和密码都是 admin/);
  change(field('密码'), 'admin'); submit(document.querySelector('form'));
  await until(() => document.body.textContent.includes('先设置密码'));
  assert.equal(calls.some(call => call.path === '/api/admin/overview'), false);
  change(field('新密码'), 'a-new-password'); change(field('再次输入新密码'), 'does-not-match'); submit(document.querySelector('form'));
  await until(() => document.querySelector('.form-error')?.textContent.includes('不一致'));
  assert.equal(changed, false);
  change(field('再次输入新密码'), 'a-new-password'); submit(document.querySelector('form'));
  await until(() => document.querySelector('.stats'));
  const passwordCall = calls.find(call => call.path === '/api/admin/account');
  assert.deepEqual(passwordCall.body, { current_password: 'admin', new_password: 'a-new-password' });
  assert.equal(passwordCall.options.headers['X-FAM-CSRF'], 'pending');
  assert.match(document.querySelector('.overview-storage').textContent, /上次检查通过/);
  assert.equal(document.querySelector('[aria-label="当前宝宝"]').value, 'older');
  change(document.querySelector('[aria-label="当前宝宝"]'), 'younger');
  await until(() => document.querySelector('.stats') && calls.some(call => call.path === '/api/admin/overview' && call.options.headers['X-FAM-Child'] === 'younger'));
  assert.equal(document.querySelector('[aria-label="当前宝宝"]').value, 'younger');
  for (const [key, label] of utils.PAGES) {
    document.querySelector(`.navigation a[href="#${key}"]`).click();
    await until(() => document.querySelector(`.navigation a[aria-current="page"]`)?.textContent === label && !document.querySelector('#page-content > .loading'));
    assert.ok(document.querySelector('#page-content h1'), `${label} 渲染标题`);
  }
  change(field('家庭相册名称（所有宝宝共用）'), '还没有保存');
  change(document.querySelector('[aria-label="当前宝宝"]'), 'older'); await until(() => document.querySelector('dialog'));
  click('取消', document.querySelector('dialog')); await until(() => document.querySelector('[aria-label="当前宝宝"]').value === 'younger');
  assert.equal(field('家庭相册名称（所有宝宝共用）').value, '还没有保存');
  change(document.querySelector('[aria-label="当前宝宝"]'), 'older'); await until(() => document.querySelector('dialog'));
  click('放弃修改', document.querySelector('dialog')); await until(() => document.querySelector('[aria-label="当前宝宝"]')?.value === 'older' && field('家庭相册名称（所有宝宝共用）').value !== '还没有保存');
  assert.equal(window.localStorage.length, 0);
  document.querySelector('[aria-label="退出登录"]').click();
  await until(() => document.querySelector('.auth-card'));
  assert.ok(calls.some(call => call.method === 'DELETE' && call.path === '/api/admin/session'));
});

test('照片筛选、批量编辑、回收站恢复和用户文本转义', async () => {
  const ctx = context(); await mediaPage(ctx);
  assert.equal(document.querySelectorAll('.media-tile').length, 2);
  assert.equal(document.querySelectorAll('img[onerror]').length, 0);
  assert.match(document.querySelector('.tile-copy').textContent, /<img src=x/);
  change(field('搜索说明或文件名'), '春游'); change(field('开始日期'), '2026-05-01'); change(field('结束日期'), '2026-05-31'); submit(document.querySelector('.media-filters'));
  await until(() => document.querySelectorAll('.media-tile').length === 2);
  const query = new URLSearchParams(calls.at(-1).path.split('?')[1]); assert.equal(query.get('search'), '春游'); assert.equal(query.get('date_from'), '2026-05-01');
  change(document.querySelector('.media-check input'), true); click('修改时间与说明');
  const dialog = document.querySelector('dialog');
  const checks = dialog.querySelectorAll('.check-line input'); change(checks[0], true); change(checks[1], true);
  change(field('新的拍摄时间', dialog), '2026-05-02T08:30'); change(field('新的照片说明', dialog), '第一次春游'); submit(dialog.querySelector('form'));
  await until(() => !document.querySelector('dialog'));
  assert.deepEqual(calls.find(call => call.path === '/api/admin/media/batch').body, { ids: ['first'], action: 'edit', captured_at: '2026-05-02T08:30:00+08:00', description: '第一次春游' });
  click('回收站'); await until(() => document.querySelector('.media-check input'));
  change(document.querySelector('.media-check input'), true); click('恢复到相册'); click('恢复', document.querySelector('dialog'));
  await until(() => calls.filter(call => call.path === '/api/admin/media/batch').length === 2);
  assert.equal(calls.filter(call => call.path === '/api/admin/media/batch').at(-1).body.action, 'restore');
});

test('添加家人固定默认权限，失败保留称呼并可重试', async () => {
  const ctx = context({ familyTab: 'family' }); let tries = 0;
  respond = call => call.path === '/api/invitations' && call.method === 'POST' ? (++tries === 1 ? error(400, '这位家人已经有一份邀请') : { code: '001234', expires_at: '2026-12-01T00:00:00Z' }) : standard(call);
  await familyPage(ctx); click('添加家人'); change(field('家人称呼（和宝宝的关系）'), '妈妈'); submit(document.querySelector('.invitation-panel form'));
  await until(() => document.querySelector('.form-error').textContent.includes('已经有'));
  assert.equal([...document.querySelectorAll('dialog button')].find(node => node.textContent === '生成邀请').disabled, false);
  submit(document.querySelector('.invitation-panel form'));
  await until(() => document.body.textContent.includes('邀请已准备好'));
  assert.deepEqual(calls.filter(call => call.method === 'POST').at(-1).body, { name: '妈妈', role: 'viewer', expires_hours: 24, child_ids: ['older'] });
  assert.equal(document.querySelector('.invitation-code').textContent, '001234', '独立显示完整邀请码并保留前导0');
});

test('添加家人只填称呼，默认当前宝宝仅查看并支持复制邀请', async () => {
  const ctx = context({ familyTab: 'family' }); let copied = '';
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { copied = text; } } });
  respond = call => call.path === '/api/invitations' && call.method === 'POST' ? { code: '001234', expires_at: '2026-12-01T00:00:00Z' } : standard(call);
  await familyPage(ctx);
  assert.equal(document.querySelector('.invite-more'), null);
  assert.equal(document.querySelector('.history-members').open, false);
  assert.equal(calls.some(call => call.path.includes('include_legacy')), false);
  assert.equal(document.querySelector('.joined-members').textContent.includes('妈妈'), false);
  assert.equal(document.querySelector('.invitation-panel'), null); click('添加家人');
  const form = document.querySelector('.invitation-panel form'), name = field('家人称呼（和宝宝的关系）');
  assert.equal(name.value, ''); assert.equal(name.maxLength, 40); assert.equal(name.readOnly, false);
  assert.deepEqual([...form.querySelectorAll('input,select')], [name]);
  change(name, '   '); submit(form); await until(() => form.querySelector('.form-error').textContent.includes('请填写家人称呼'));
  assert.equal(calls.some(call => call.method === 'POST'), false);
  change(name, '姨'.repeat(41)); submit(form); await until(() => form.querySelector('.form-error').textContent.includes('40个字'));
  assert.equal(calls.some(call => call.method === 'POST'), false);
  change(name, '  小姨  '); submit(form); await until(() => document.body.textContent.includes('邀请已准备好'));
  assert.deepEqual(calls.find(call => call.method === 'POST').body, { name: '小姨', role: 'viewer', expires_hours: 24, child_ids: ['older'] });
  assert.equal(document.querySelector('.invitation-code.numeric-code').textContent, '001234');
  click('复制邀请码'); await until(() => copied === '001234');
  click('复制完整邀请'); await until(() => copied.includes('邀请码：001234'));
  assert.ok(copied.includes('宝宝：姐姐')); assert.ok(copied.includes('http://localhost'));
  assert.ok(copied.includes('小姨')); assert.ok(document.querySelector('.invitation-panel').textContent.includes('001234'));
  delete navigator.clipboard;
});

test('历史上传者单独按需读取，可重试并明确关联邀请', async () => {
  const ctx = context({ familyTab: 'family' }); let attempts = 0;
  respond = call => call.path === '/api/members?include_legacy=true' ? (++attempts === 1 ? error(503, '暂时无法读取，请重试') : { items: [member, legacyMember] }) : call.path === '/api/invitations' && call.method === 'POST' ? { code: '000042', expires_at: '2026-12-01' } : standard(call);
  await familyPage(ctx); assert.equal(attempts, 0); const history = expand('.history-members');
  await until(() => history.textContent.includes('暂时无法读取')); click('重新读取', history);
  await until(() => history.querySelector('.member-row')); assert.equal(attempts, 2);
  assert.equal(document.querySelector('.joined-members .member-row h3').textContent, '爸爸你');
  assert.equal(history.querySelector('.member-row h3').textContent, '妈妈');
  assert.ok(history.textContent.includes('尚未注册'));
  click('关联邀请', history); const dialog = document.querySelector('dialog');
  assert.equal(dialog.querySelectorAll('input,select').length, 0);
  assert.equal(document.querySelector('.simple-family-invite'), null);
  click('生成关联邀请', dialog); await until(() => dialog.textContent.includes('邀请已准备好'));
  assert.deepEqual(calls.find(call => call.method === 'POST').body, { name: '妈妈', role: 'viewer', expires_hours: 24, child_ids: ['older'], member_id: 'old-mom' });
  assert.equal(calls.some(call => call.method === 'DELETE' || call.method === 'PATCH'), false);
});

test('旧长邀请码独立显示，复制失败仍能选择完整原文', async () => {
  const ctx = context({ familyTab: 'family' }), legacy = 'FAM_legacy-0123456789_ABCDEFGHIJKL';
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('Clipboard unavailable'); } } });
  respond = call => call.path === '/api/invitations' && call.method === 'POST' ? { code: legacy, expires_at: '2026-12-01' } : standard(call);
  await familyPage(ctx); click('添加家人'); change(field('家人称呼（和宝宝的关系）'), '小姨'); submit(document.querySelector('.invitation-panel form'));
  await until(() => document.querySelector('.invitation-code'));
  assert.equal(document.querySelector('.invitation-code.legacy-code').textContent, legacy);
  click('复制邀请码'); await until(() => document.querySelector('.invitation-panel input[readonly]'));
  const fallback = field('邀请码', document.querySelector('.invitation-panel')); assert.equal(fallback.type, 'text'); assert.equal(fallback.value, legacy); assert.equal(fallback.selectionEnd, legacy.length);
  click('复制邀请码'); await new Promise(resolve => setTimeout(resolve, 1)); assert.equal(document.querySelectorAll('.invitation-panel input[readonly]').length, 1);
  click('复制完整邀请'); await until(() => document.querySelector('.invitation-panel textarea'));
  assert.ok(field('完整邀请').value.includes(`邀请码：${legacy}`)); assert.ok(field('完整邀请').value.includes('服务器地址：http://localhost'));
  delete navigator.clipboard;
});

test('六位邀请码保留前导0加入，完整邀请粘贴能提取且失败可重试', async () => {
  const ctx = context({ familyTab: 'family' }); let resolveJoin;
  respond = call => call.path === '/api/invitations/join' ? new Promise(resolve => { resolveJoin = resolve; }) : standard(call);
  await familyPage(ctx); click('使用邀请码'); const dialog = document.querySelector('dialog'), input = field('邀请码', dialog), form = dialog.querySelector('form');
  assert.equal(input.type, 'text'); assert.equal(input.inputMode, 'numeric'); assert.equal(input.placeholder, '输入6位邀请码'); assert.ok(input.maxLength >= 200);
  change(input, '12345'); submit(form); assert.ok(dialog.querySelector('.form-error').textContent.includes('6位数字')); assert.equal(calls.some(call => call.path === '/api/invitations/join'), false);
  const paste = new window.Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(paste, 'clipboardData', { value: { getData: () => '邀请小姨加入 FAM宝贝相册\n服务器地址：http://localhost\n邀请码：000042\n有效至：明天' } }); input.dispatchEvent(paste);
  assert.equal(paste.defaultPrevented, true); assert.equal(input.value, '000042');
  submit(form); submit(form); assert.equal(calls.filter(call => call.path === '/api/invitations/join').length, 1); assert.equal(input.disabled, true);
  assert.deepEqual(calls.find(call => call.path === '/api/invitations/join').body, { code: '000042' }); assert.equal(calls.find(call => call.path === '/api/invitations/join').options.headers['X-FAM-Child'], undefined);
  resolveJoin(error(400, '邀请码尚未生效，请重试')); await until(() => !input.disabled); assert.equal(input.value, '000042');
  submit(form); resolveJoin({ message: '已加入' }); await until(() => ctx.refreshed); assert.equal(dialog.isConnected, false);
});

test('旧码可从完整邀请提取，提交中阻止关闭，切宝宝后结果不触碰新页面', async () => {
  const ctx = context({ familyTab: 'family' }), legacy = 'legacy_Code-0123456789_AB'; let resolveJoin;
  respond = call => call.path === '/api/invitations/join' ? new Promise(resolve => { resolveJoin = resolve; }) : standard(call);
  await familyPage(ctx); click('使用邀请码'); let dialog = document.querySelector('dialog');
  const paste = new window.Event('paste', { bubbles: true, cancelable: true }); Object.defineProperty(paste, 'clipboardData', { value: { getData: () => `服务器：http://localhost/\n邀请码：${legacy}\n已有账号可加入` } }); field('邀请码', dialog).dispatchEvent(paste); submit(dialog.querySelector('form'));
  assert.deepEqual(calls.find(call => call.path === '/api/invitations/join').body, { code: legacy });
  click('取消', dialog); assert.equal(dialog.isConnected, true); resolveJoin(error(400, '请稍后重试')); await until(() => !field('邀请码', dialog).disabled);
  click('取消', dialog); await until(() => document.querySelectorAll('dialog').length === 2); click('放弃修改', document.querySelector('dialog:last-of-type')); await until(() => !dialog.isConnected); assert.equal(ctx.refreshed, undefined);
  click('使用邀请码'); dialog = document.querySelector('dialog'); change(field('邀请码', dialog), '001234'); submit(dialog.querySelector('form'));
  ctx.childId = 'younger'; ctx.isCurrent = () => false; resolveJoin({ message: '已加入' }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(ctx.refreshed, undefined); assert.equal(field('邀请码', dialog).value, '001234'); assert.equal(document.querySelector('#toasts').textContent.includes('邀请已接受'), false);
});

test('完整邀请按整行识别，不把错误长度或后缀截成有效邀请码', async () => {
  const ctx = context({ familyTab: 'family' }); await familyPage(ctx); click('使用邀请码');
  const dialog = document.querySelector('dialog'), input = field('邀请码', dialog), form = dialog.querySelector('form');
  for (const text of ['邀请码：12345', '邀请码：1234567', '邀请码：123456.0', '邀请码：123456/abc', '邀请码：123456 extra', '邀请码：123456\n邀请码：654321']) {
    change(input, '001234'); const paste = new window.Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => text } }); input.dispatchEvent(paste);
    assert.equal(paste.defaultPrevented, true); assert.equal(input.value, '', '失败粘贴必须清掉之前的有效码');
    assert.equal(dialog.querySelector('.form-error').hidden, false); submit(form);
    assert.equal(calls.some(call => call.path === '/api/invitations/join'), false);
  }
});

test('完整邀请核对服务器，异站或异常地址不会把码发给当前服务器', async () => {
  const ctx = context({ familyTab: 'family' }); await familyPage(ctx); click('使用邀请码');
  const dialog = document.querySelector('dialog'), input = field('邀请码', dialog), form = dialog.querySelector('form');
  const labels = ['服务器', '服务器地址', '服务地址', '相册地址'];
  for (const label of labels) {
    change(input, '001234'); const paste = new window.Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => `${label}：https://other.example.test\n邀请码：000042` } }); input.dispatchEvent(paste);
    assert.equal(paste.defaultPrevented, true); assert.equal(input.value, ''); assert.match(dialog.querySelector('.form-error').textContent, /这份邀请来自另一台服务器/);
    submit(form); assert.match(dialog.querySelector('.form-error').textContent, /这份邀请来自另一台服务器/);
    assert.equal(calls.some(call => call.path === '/api/invitations/join'), false);
  }
  for (const address of ['http://user:secret@localhost/', 'http://@localhost/', 'http://localhost/?query=1', 'http://localhost/#fragment', 'http://localhost?', 'http://localhost/another-album', 'ftp://localhost/', 'localhost', '']) {
    change(input, '001234'); const paste = new window.Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => `服务器地址：${address}\n邀请码：000042` } }); input.dispatchEvent(paste); submit(form);
    assert.equal(input.value, ''); assert.equal(calls.some(call => call.path === '/api/invitations/join'), false);
  }
  const valid = new window.Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(valid, 'clipboardData', { value: { getData: () => '服务器地址：HTTP://LOCALHOST:80/\r\n邀请码：000042\r\n宝宝：姐姐' } }); input.dispatchEvent(valid);
  assert.equal(input.value, '000042'); submit(form); await until(() => ctx.refreshed);
  assert.deepEqual(calls.find(call => call.path === '/api/invitations/join').body, { code: '000042' });
});

test('旧接口的未注册记录不会混入已加入名单，收起宝宝不会自动邀请其他宝宝', async () => {
  const ctx = context({ familyTab: 'family' }); ctx.childId = 'archived'; ctx.child = children[2];
  respond = call => call.path === '/api/members' ? { items: [member, legacyMember] } : standard(call);
  await familyPage(ctx);
  assert.equal(document.querySelector('.joined-members .member-row'), null);
  assert.equal(document.querySelector('.history-members').open, false);
  const add = [...document.querySelectorAll('button')].find(node => node.textContent === '添加家人'); assert.equal(add.disabled, true); add.click();
  assert.equal(document.querySelector('.invitation-panel'), null);
  assert.equal(document.querySelector('.invite-more'), null);
  assert.equal(calls.some(call => call.method === 'POST'), false);
  assert.equal(document.querySelectorAll('.invitation-panel input[type="checkbox"]:checked').length, 0);
});

test('照片筛选仍可选择历史上传者，概览使用添加家人入口', async () => {
  const ctx = context(); await mediaPage(ctx); await until(() => document.querySelector('.media-check input'));
  assert.equal(calls.some(call => call.path === '/api/members?include_legacy=true'), true);
  assert.equal(field('上传家人').querySelector('option[value="old-mom"]').textContent, '妈妈');
  change(field('上传家人'), 'old-mom'); submit(document.querySelector('.media-filters'));
  await until(() => calls.some(call => call.path.includes('uploader_id=old-mom')));
  let destination; ctx.navigate = value => { destination = value; }; await overviewPage(ctx); click('添加家人');
  assert.equal(ctx.state.inviteOpen, true); assert.equal(destination, 'family');
});

test('存储表单覆盖所有方式，密钥只写以及错误后保留输入', async () => {
  const ctx = context(); let fail = true;
  respond = call => call.path === '/api/storage' && call.method === 'POST' ? (fail ? error(400, '存储桶名称有误') : { id: 'saved' }) : standard(call);
  await storagePage(ctx); click('添加存放位置');
  assert.deepEqual([...document.querySelectorAll('dialog .provider-choice strong')].map(node => node.textContent), ['自己的服务器', 'NAS', '阿里云 OSS', '腾讯云 COS', '通用 S3']);
  [...document.querySelectorAll('dialog .provider-choice')].find(node => node.querySelector('strong').textContent === '通用 S3').click();
  const dialog = document.querySelector('dialog');
  assert.equal(field('服务地址', dialog).closest('details'), null);
  change(field('位置名称', dialog), '家庭云'); change(field('存储桶名称', dialog), 'family-bucket'); change(field('服务地址', dialog), 'https://s3.example.test'); change(field('访问密钥 ID', dialog), 'write-only-id'); change(field('访问密钥 Secret', dialog), 'write-only-secret');
  submit(dialog.querySelector('form')); await until(() => dialog.querySelector('.form-error').textContent.includes('存储桶'));
  assert.equal(field('访问密钥 Secret', dialog).value, 'write-only-secret'); assert.equal(dialog.querySelector('button[type="submit"]').disabled, false);
  fail = false; submit(dialog.querySelector('form')); await until(() => !document.querySelector('dialog'));
  const sent = calls.filter(call => call.method === 'POST' && call.path === '/api/storage').at(-1); assert.equal(sent.body.provider, 's3'); assert.equal(sent.body.secret_access_key, 'write-only-secret'); assert.equal(sent.options.headers['X-FAM-CSRF'], 'test-csrf');
});

test('备份计划、核对与独立恢复副本的真实参数和错密码恢复', async () => {
  const ctx = context({ storageTab: 'plan' }); let restoreFails = true;
  respond = call => {
    if (call.path === '/api/admin/backups') return { items: [{ id: 'backup', name: '家庭备份', mode: 'metadata', status: 'completed', status_label: '已完成', created_at: '2026-05-17T10:00:00Z' }] };
    if (call.path.endsWith('/verify')) return { verified: true, message: '核对完成' };
    if (call.path.endsWith('/restore-preview')) return { name: '家庭备份', photos: 2, videos: 1, members: 2, mode: 'metadata' };
    if (call.path.endsWith('/restore')) return restoreFails ? error(401, '当前密码不正确') : { message: '恢复完成', directory: '/data/restores/family-copy' };
    return standard(call);
  };
  const cleanup = await storagePage(ctx);
  assert.equal(field('每周哪一天').value, '6'); assert.equal(field('备份内容').value, 'metadata');
  change(document.querySelector('.switch-line input'), true); change(field('保留最近几份备份'), '12'); submit(document.querySelector('.backup-plan'));
  await until(() => calls.some(call => call.method === 'PUT') && !ctx.leave.dirty());
  assert.deepEqual(calls.find(call => call.method === 'PUT').body, { enabled: true, frequency: 'weekly', time: '04:15', weekday: 6, mode: 'metadata', keep: 12 });
  click('备份记录'); await until(() => document.querySelector('.backup-record'));
  assert.match(document.body.textContent, /仅相册资料/);
  click('核对备份'); await until(() => calls.some(call => call.path.endsWith('/verify')));
  click('恢复副本'); await until(() => document.querySelector('dialog form'));
  assert.match(document.querySelector('dialog').textContent, /没有原照片和视频/);
  change(field('当前管理员密码'), 'incorrect'); submit(document.querySelector('dialog form'));
  await until(() => document.querySelector('dialog .form-error')?.textContent.includes('不正确'));
  assert.equal(expired, 0); assert.equal(document.querySelector('dialog button[type="submit"]').disabled, false);
  restoreFails = false; change(field('当前管理员密码'), 'correct-password'); submit(document.querySelector('dialog form'));
  await until(() => document.querySelector('dialog').textContent.includes('恢复完成'));
  assert.equal(calls.filter(call => call.path.endsWith('/restore')).at(-1).body.current_password, 'correct-password'); cleanup();
});

test('设置保存、修改账号失败不退出且成功保留未保存资料', async () => {
  const ctx = context(); let accountFails = true;
  respond = call => call.path === '/api/admin/account' ? (accountFails ? error(401, '当前密码不正确') : { member: { ...member, username: 'new-owner' }, csrf: 'rotated' }) : standard(call);
  await settingsPage(ctx); change(field('家庭相册名称（所有宝宝共用）'), '宝宝的新家'); assert.equal(ctx.leave.dirty(), true);
  click('账号与安全');
  click('修改用户名'); change(field('当前密码'), 'wrong'); change(field('新用户名'), 'new-owner'); submit(document.querySelector('dialog form'));
  await until(() => document.querySelector('dialog .form-error').textContent.includes('不正确'));
  assert.equal(expired, 0); accountFails = false; change(field('当前密码'), 'correct-password'); submit(document.querySelector('dialog form'));
  await until(() => !document.querySelector('dialog'));
  assert.equal(field('家庭相册名称（所有宝宝共用）').value, '宝宝的新家'); assert.equal(ctx.leave.dirty(), true); assert.equal(ctx.member.username, 'new-owner');
  click('基础设置'); change(field('单个文件最大大小（MB）'), '200'); submit(document.querySelector('.settings-form'));
  await until(() => !ctx.leave.dirty());
  assert.equal(calls.find(call => call.path === '/api/profile' && call.method === 'PUT').body.name, '宝宝的新家');
  const update = calls.find(call => call.path === '/api/admin/settings' && call.method === 'PUT'); assert.equal(update.body.max_upload_bytes, 200 * 1048576); assert.equal(update.options.headers['X-FAM-CSRF'], 'rotated');
});

test('目录扫描导入、暂停与查看超过千份的结果', async () => {
  const ctx = context();
  const job = { id: 'test-job', name: '旧照片', source: 'directory', status: 'running', status_label: '导入中', total: 1200, processed: 20, imported: 20 };
  respond = call => {
    if (call.path === '/api/admin/imports') return { items: [job] };
    if (call.path.endsWith('/roots')) return { items: [{ id: 'library', name: '服务器图库' }] };
    if (call.path.endsWith('/scan')) return { count: 12, bytes: 1024, duplicates: 2 };
    if (call.path === '/api/admin/imports/test-job') return { ...job, items_truncated: true, items: [{ name: '春游.jpg', status: 'imported', status_label: '已导入' }] };
    return {};
  };
  const cleanup = await importsPage(ctx); click('暂停'); await until(() => calls.some(call => call.path.endsWith('/pause')));
  click('查看结果'); await until(() => document.querySelector('dialog').textContent.includes('前一千份'));
  document.querySelector('dialog [aria-label="关闭"]').click();
  click('从服务器目录导入'); await until(() => document.querySelector('dialog select'));
  change(field('子文件夹（可留空）'), '旅行/2025'); click('扫描文件'); await until(() => document.querySelector('dialog').textContent.includes('找到 12'));
  assert.deepEqual(calls.find(call => call.path.endsWith('/scan')).body, { root_id: 'library', path: '旅行/2025' });
  click('开始导入', document.querySelector('dialog')); await until(() => calls.some(call => call.path.endsWith('/directory')));
  assert.equal(calls.find(call => call.path.endsWith('/directory')).body.path, '旅行/2025'); cleanup();
});

test('文件传输期间禁止提前开始，显示真实字节进度并保留文件时间', async () => {
  const ctx = context(); const pending = []; let created = false, started = false;
  class FakeXHR {
    constructor() { this.upload = {}; this.headers = {}; pending.push(this); }
    open(method, path) { this.path = path; }
    setRequestHeader(key, value) { this.headers[key] = value; }
    send(body) { this.body = body; }
    abort() { this.onabort?.(); }
    complete() { this.status = 200; this.responseText = '{}'; this.onload(); }
  }
  globalThis.XMLHttpRequest = FakeXHR;
  respond = call => {
    if (call.path === '/api/admin/imports' && call.method === 'POST') { created = true; return { id: 'upload' }; }
    if (call.path.endsWith('/start')) { started = true; return {}; }
    if (call.path === '/api/admin/imports') return { items: created ? [{ id: 'upload', name: '测试上传', source: 'upload', status: started ? 'running' : 'receiving', total: 1 }] : [] };
    return {};
  };
  const cleanup = await importsPage(ctx);
  const input = document.querySelector('input[type="file"]'), file = new File(['abc'], '宝贝.jpg', { type: 'image/jpeg', lastModified: 1778983200000 });
  change(document.querySelector('.child-choices input[aria-label="弟弟"]'), true);
  Object.defineProperty(input, 'files', { configurable: true, value: [file] }); input.dispatchEvent(new window.Event('change', { bubbles: true }));
  await until(() => pending.length === 1);
  assert.deepEqual(calls.find(call => call.method === 'POST' && call.path === '/api/admin/imports').body.child_ids, ['older', 'younger']);
  assert.equal(pending[0].headers['X-FAM-Child'], 'older');
  assert.equal(document.querySelector('.child-choices input[aria-label="弟弟"]').disabled, true);
  const startButton = [...document.querySelectorAll('.task-actions button')].find(node => node.textContent === '开始导入'); assert.equal(startButton.disabled, true);
  assert.equal(pending[0].body.get('fallback_captured_at'), new Date(file.lastModified).toISOString()); assert.equal(pending[0].headers['X-FAM-CSRF'], 'test-csrf');
  pending[0].upload.onprogress({ loaded: 2, total: 3, lengthComputable: true }); assert.equal(document.querySelector('.upload-progress [role="progressbar"]').getAttribute('aria-valuenow'), '67');
  pending[0].complete(); await until(() => started && document.querySelector('.upload-progress').textContent.includes('现在可以关闭页面'));
  assert.equal(ctx.leave.dirty(), false); cleanup(); globalThis.XMLHttpRequest = dom.window.XMLHttpRequest;
});

test('沉浸预览键盘切换、视频直接读取与对话框关闭', () => {
  context(); const view = openViewer(media);
  assert.equal(view.body.querySelector('.viewer-stage img').getAttribute('src'), '/api/media/first/file');
  document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
  assert.equal(view.body.querySelector('video').getAttribute('src'), '/api/media/second/file'); assert.equal(view.body.querySelector('video').controls, true);
  view.dialog.dispatchEvent(new window.Event('cancel', { cancelable: true })); assert.equal(document.querySelector('dialog'), null);
});

test('较早照片请求晚到时不会覆盖新的筛选结果', async () => {
  const ctx = context(); let resolveOld;
  await mediaPage(ctx);
  respond = call => call.path.includes('kind=photo') ? new Promise(resolve => { resolveOld = resolve; }) : call.path.includes('kind=video') ? { items: [media[1]], total: 1 } : standard(call);
  click('照片'); click('视频'); await until(() => document.querySelectorAll('.media-tile').length === 1);
  resolveOld({ items: [media[0]], total: 1 }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(document.querySelector('.media-tile img').getAttribute('src'), '/api/media/second/thumbnail');
});

test('扫描期间改了目录，旧扫描结果不能开始导入新目录', async () => {
  const ctx = context(); let resolveScan, scanCount = 0;
  respond = call => {
    if (call.path.endsWith('/roots')) return { items: [{ id: 'library', name: '服务器图库' }] };
    if (call.path.endsWith('/scan')) return ++scanCount === 1 ? new Promise(resolve => { resolveScan = resolve; }) : { count: 3, duplicates: 0, bytes: 20 };
    return { items: [] };
  };
  const cleanup = await importsPage(ctx); click('从服务器目录导入'); await until(() => document.querySelector('dialog select'));
  change(field('子文件夹（可留空）'), '旧目录'); click('扫描文件'); await until(() => resolveScan);
  change(field('子文件夹（可留空）'), '新目录'); resolveScan({ count: 999, bytes: 100, duplicates: 0 }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal([...document.querySelectorAll('dialog button')].find(node => node.textContent === '开始导入').disabled, true);
  assert.equal(document.querySelector('dialog').textContent.includes('999'), false);
  click('扫描文件'); await until(() => document.querySelector('dialog').textContent.includes('找到 3'));
  click('开始导入', document.querySelector('dialog')); await until(() => calls.some(call => call.path.endsWith('/directory')));
  assert.equal(calls.find(call => call.path.endsWith('/directory')).body.path, '新目录'); cleanup();
});

test('正式入口资源存在、样式可解析且不请求外部依赖', () => {
  const base = new URL('../../server/web/', import.meta.url);
  const page = new JSDOM(readFileSync(new URL('index.html', base), 'utf8'));
  for (const node of page.window.document.querySelectorAll('[src],link[href]')) {
    const path = node.getAttribute('src') || node.getAttribute('href'); assert.ok(path.startsWith('./')); assert.ok(existsSync(new URL(path, base)), `资源存在 ${path}`);
  }
  const style = page.window.document.createElement('style'); style.textContent = readFileSync(new URL('admin.css', base), 'utf8'); page.window.document.head.append(style);
  assert.ok(style.sheet.cssRules.length > 100, '样式完整解析');
  assert.ok([...style.sheet.cssRules].some(rule => rule.conditionText?.includes('720px')), '包含窄屏布局');
  assert.ok([...style.sheet.cssRules].some(rule => rule.conditionText?.includes('prefers-reduced-motion')), '支持减少动画');
  page.window.close();
});

test('新增宝宝、为不同宝宝改资料与收起恢复不会混用当前宝宝', async () => {
  const ctx = context(); ctx.refreshChildren = async id => { ctx.updatedChild = id; ctx.refreshed = true; };
  respond = call => call.path === '/api/children' && call.method === 'POST' ? { id: 'new-baby' } : standard(call);
  await familyPage(ctx); assert.equal(document.querySelectorAll('.family-child-row').length, 2); assert.equal(document.querySelector('.archived-child'), null);
  click('添加宝宝'); change(field('宝宝名字'), '小宝'); change(field('生日（可稍后填写）'), '2026-01-02'); submit(document.querySelector('dialog form'));
  await until(() => ctx.refreshed); assert.deepEqual(calls.find(call => call.path === '/api/children' && call.method === 'POST').body, { name: '小宝', birthday: '2026-01-02' }); assert.equal(ctx.updatedChild, 'new-baby');
  const cards = document.querySelectorAll('.family-child-row'); cards[1].querySelector('[aria-label="弟弟的更多操作"]').click(); clickFamilyEntry('编辑资料'); change(field('宝宝名字'), '弟弟新名字'); submit(document.querySelector('dialog form'));
  await until(() => calls.some(call => call.path === '/api/children/younger' && call.method === 'PATCH'));
  const updated = calls.find(call => call.path === '/api/children/younger'); assert.equal(updated.options.headers['X-FAM-Child'], 'younger'); assert.equal(updated.body.name, '弟弟新名字');
  await until(() => !document.querySelector('dialog')); click('收起的宝宝 · 1 位'); click('恢复宝宝', document.querySelector('.archived-child')); click('恢复宝宝', document.querySelector('dialog'));
  await until(() => calls.some(call => call.path === '/api/children/archived'));
  assert.deepEqual(calls.find(call => call.path === '/api/children/archived').body, { archived: false });
});

test('逐宝宝授权支持移除、宝宝管理员且不会晋升家庭管理员', async () => {
  const ctx = context({ familyTab: 'family' }); await familyPage(ctx);
  click('进入账号管理'); const account = document.querySelector('.account-member-list .member-row:last-child'); click('账号设置', account); clickFamilyEntry('宝宝访问'); const dialog = document.querySelector('dialog:last-of-type');
  assert.equal(field('姐姐', dialog).value, 'viewer'); assert.equal(field('弟弟', dialog).value, 'contributor');
  change(field('姐姐', dialog), ''); change(field('弟弟', dialog), 'admin'); click('保存宝宝权限', dialog);
  await until(() => ctx.refreshed);
  const changes = calls.filter(call => call.method === 'PUT');
  assert.deepEqual(changes.map(call => [call.path, call.body.role, call.options.headers['X-FAM-Child']]), [['/api/children/older/members/grandma', null, 'older'], ['/api/children/younger/members/grandma', 'admin', 'younger']]);
  assert.equal(calls.some(call => call.path === '/api/members/grandma' && call.method === 'PATCH'), false);
});

test('已登录家人仍可接受其他宝宝邀请，不携带旧宝宝范围', async () => {
  const ctx = context({ familyTab: 'family' }); await familyPage(ctx);
  click('使用邀请码'); change(field('邀请码'), 'FAM_INVITE_LEGACY_2026'); submit(document.querySelector('dialog form'));
  await until(() => calls.some(call => call.path === '/api/invitations/join'));
  const join = calls.find(call => call.path === '/api/invitations/join'); assert.deepEqual(join.body, { code: 'FAM_INVITE_LEGACY_2026' }); assert.equal(join.options.headers['X-FAM-Child'], undefined);
});

function clickFamilyEntry(label) { const node = document.querySelector('dialog:last-of-type button[aria-label="' + label + '"]'); assert.ok(node, '找到家人操作 ' + label); node.click(); }
function openFamilyInfo(name = '奶奶') {
  const row = [...document.querySelectorAll('.joined-members .member-row')].find(item => item.querySelector('h3').textContent.includes(name));
  assert.ok(row); click('家人信息', row); return document.querySelector('dialog.family-person-dialog');
}
const familySwitch = (name, dialog = document.querySelector('dialog.family-person-dialog')) => dialog.querySelector(`input[role="switch"][aria-label="${name}"]`);

test('家人信息用当前宝宝角色，管理上传联动且失败恢复确认状态', async () => {
  const ctx = context({ familyTab: 'family' }); let resolveRole;
  respond = call => call.method === 'PUT' ? new Promise(resolve => { resolveRole = resolve; }) : standard(call);
  await familyPage(ctx); const dialog = openFamilyInfo(), manage = familySwitch('管理'), upload = familySwitch('上传');
  assert.equal(dialog.querySelector('.family-person-heading h3').textContent, '奶奶');
  assert.equal(dialog.querySelectorAll('[role="switch"]').length, 2);
  assert.equal(manage.checked, false); assert.equal(upload.checked, false, '全局role=admin不应覆盖宝宝viewer');
  change(manage, true); assert.equal(manage.disabled, true); assert.equal(upload.disabled, true); assert.equal(upload.checked, true);
  assert.deepEqual(calls.find(call => call.method === 'PUT').body, { role: 'admin' });
  resolveRole(error(503, '没有保存成功，请重试')); await until(() => !manage.disabled);
  assert.equal(manage.checked, false); assert.equal(upload.checked, false); assert.ok(dialog.textContent.includes('没有保存成功'));
  change(manage, true); resolveRole({ message: '已保存' }); await until(() => !manage.disabled);
  assert.equal(manage.checked, true); assert.equal(upload.checked, true); assert.equal(upload.disabled, true);
  change(manage, false); resolveRole({ message: '已保存' }); await until(() => !manage.disabled);
  assert.equal(manage.checked, false); assert.equal(upload.checked, true); assert.equal(upload.disabled, false);
  change(upload, false); resolveRole({ message: '已保存' }); await until(() => !manage.disabled);
  assert.equal(upload.checked, false);
  assert.deepEqual(calls.filter(call => call.method === 'PUT').map(call => [call.path, call.body.role, call.options.headers['X-FAM-Child']]), [['/api/children/older/members/grandma', 'admin', 'older'], ['/api/children/older/members/grandma', 'admin', 'older'], ['/api/children/older/members/grandma', 'contributor', 'older'], ['/api/children/older/members/grandma', 'viewer', 'older']]);
});

test('关系修改只PATCH当前宝宝，同步头像旁和列表称呼，不改全家账号', async () => {
  const ctx = context({ familyTab: 'family' });
  respond = call => call.method === 'PATCH' ? { ...scopedGrandmother, relationship: '外婆' } : standard(call);
  await familyPage(ctx); const info = openFamilyInfo(); click('修改', info); const editor = document.querySelector('dialog:last-of-type');
  change(field('和宝宝的关系', editor), ' 外婆 '); click('保存关系', editor);
  await until(() => !editor.isConnected);
  const patch = calls.find(call => call.method === 'PATCH'); assert.equal(patch.path, '/api/children/older/members/grandma'); assert.deepEqual(patch.body, { relationship: '外婆' }); assert.equal(patch.options.headers['X-FAM-Child'], 'older');
  assert.equal(info.querySelector('.family-person-heading h3').textContent, '外婆'); assert.ok(document.querySelector('.joined-members').textContent.includes('外婆'));
  click('完成', info); click('进入账号管理'); assert.ok(document.querySelector('.account-member-list').textContent.includes('陈女士'));
  assert.equal(calls.some(call => call.path === '/api/members/grandma' && call.method === 'PATCH'), false);
});

test('移出家人只移出当前宝宝，照片保留且不会停用全家账号', async () => {
  const ctx = context({ familyTab: 'family' }); await familyPage(ctx); const info = openFamilyInfo();
  click('移出当前宝宝', info); const confirmation = document.querySelector('dialog:last-of-type');
  assert.ok(confirmation.textContent.includes('照片和留言都会保留')); assert.ok(confirmation.textContent.includes('不会影响'));
  click('移出当前宝宝', confirmation); await until(() => ctx.refreshed);
  const writes = calls.filter(call => call.method !== 'GET'); assert.equal(writes.length, 1); assert.equal(writes[0].path, '/api/children/older/members/grandma'); assert.deepEqual(writes[0].body, { role: null }); assert.equal(writes[0].options.headers['X-FAM-Child'], 'older');
});

test('请求中关闭并重开家人信息，成功状态不会留在旧缓存', async () => {
  const ctx = context({ familyTab: 'family' }); let resolveRole, resolveRelation;
  respond = call => call.method === 'PUT' ? new Promise(resolve => { resolveRole = resolve; }) : call.method === 'PATCH' ? new Promise(resolve => { resolveRelation = resolve; }) : standard(call);
  await familyPage(ctx); let info = openFamilyInfo(); change(familySwitch('上传'), true); click('完成', info); info = openFamilyInfo();
  assert.equal(familySwitch('管理').disabled, true); assert.equal(familySwitch('上传').disabled, true);
  resolveRole({ message: '已保存' }); await until(() => !familySwitch('管理').disabled); assert.equal(familySwitch('上传').checked, true);
  click('修改', info); let editor = document.querySelector('dialog:last-of-type'); change(field('和宝宝的关系', editor), '外婆'); click('保存关系', editor); click('取消', editor); assert.equal(editor.isConnected, true);
  assert.equal(familySwitch('管理').disabled, true); resolveRelation({ ...scopedGrandmother, relationship: '外婆' });
  await until(() => !editor.isConnected); click('完成', info); info = openFamilyInfo('外婆');
  assert.equal(info.querySelector('.family-person-heading h3').textContent, '外婆'); assert.equal(familySwitch('管理').disabled, false);
});

test('宝宝或页面已改变时，迟到的权限结果不能覆盖当前页面', async () => {
  const ctx = context({ familyTab: 'family' }); let resolveRole;
  respond = call => call.method === 'PUT' ? new Promise(resolve => { resolveRole = resolve; }) : standard(call);
  await familyPage(ctx); const info = openFamilyInfo(); change(familySwitch('上传'), true);
  ctx.childId = 'younger'; ctx.isCurrent = () => false; resolveRole({ message: '已保存' }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(info.querySelector('.family-save-status').textContent, '正在保存…'); assert.equal(ctx.refreshed, undefined);
  assert.equal(calls.find(call => call.method === 'PUT').options.headers['X-FAM-Child'], 'older');
});

test('本人家庭管理员及归档宝宝保护权限，停用账号仍按can字段编辑', async () => {
  let ctx = context({ familyTab: 'family' }); await familyPage(ctx); let info = openFamilyInfo('爸爸');
  assert.equal(familySwitch('管理').disabled, true); assert.equal(familySwitch('上传').disabled, true); assert.equal([...info.querySelectorAll('button')].find(node => node.textContent === '移出当前宝宝').disabled, true);
  assert.equal([...info.querySelectorAll('button')].find(node => node.textContent === '修改').disabled, false);
  ctx = context({ familyTab: 'family' }); ctx.childId = 'archived'; ctx.child = children[2];
  respond = call => call.path === '/api/children/archived/members' ? { items: [scopedGrandmother] } : standard(call);
  await familyPage(ctx); info = openFamilyInfo(); assert.equal(familySwitch('管理').disabled, true); assert.equal([...info.querySelectorAll('button')].find(node => node.textContent === '修改').disabled, true);
  ctx = context({ familyTab: 'family' }); respond = call => call.path === '/api/children/older/members' ? { items: [{ ...scopedGrandmother, disabled: true }] } : standard(call);
  await familyPage(ctx); info = openFamilyInfo(); assert.equal(familySwitch('管理').disabled, false); assert.ok(info.textContent.includes('不会恢复登录'));
  change(familySwitch('上传'), true); await until(() => !familySwitch('管理').disabled); assert.equal(calls.some(call => call.path === '/api/members/grandma' && call.method === 'PATCH'), false);
});

test('已移出全部宝宝的账号仍在次级账号管理，可重新授权', async () => {
  const ctx = context({ familyTab: 'family' }); const aunt = { ...grandmother, id: 'aunt', name: '小姨', username: 'aunt', children: [] };
  respond = call => call.path === '/api/members' ? { items: [member, grandmother, aunt] } : standard(call);
  await familyPage(ctx); assert.equal(document.querySelector('.joined-members').textContent.includes('小姨'), false); click('进入账号管理');
  const row = [...document.querySelectorAll('.account-member-list .member-row')].find(node => node.textContent.includes('小姨')); assert.ok(row); click('账号设置', row); clickFamilyEntry('宝宝访问');
  const editor = document.querySelector('dialog:last-of-type'); assert.equal(field('姐姐', editor).value, ''); change(field('弟弟', editor), 'contributor'); click('保存宝宝权限', editor);
  await until(() => ctx.refreshed); const changeCall = calls.find(call => call.method === 'PUT'); assert.equal(changeCall.path, '/api/children/younger/members/aunt'); assert.deepEqual(changeCall.body, { role: 'contributor' });
});

test('合照批量加入其他宝宝和共享资料提示', async () => {
  const ctx = context(); await mediaPage(ctx); change(document.querySelector('.media-check input'), true); click('加入其他宝宝');
  const dialog = document.querySelector('dialog'); assert.equal(dialog.querySelector('input[aria-label="姐姐"]'), null); assert.equal(dialog.querySelector('input[aria-label="旧记录"]'), null);
  change(dialog.querySelector('input[aria-label="弟弟"]'), true); click('加入所选宝宝', dialog);
  await until(() => calls.some(call => call.path === '/api/admin/media/batch'));
  const link = calls.find(call => call.path === '/api/admin/media/batch'); assert.deepEqual(link.body, { ids: ['first'], action: 'link', child_ids: ['younger'] }); assert.equal(link.options.headers['X-FAM-Child'], 'older');
  await until(() => !document.querySelector('dialog') && document.querySelector('.media-check input')); change(document.querySelector('.media-check input'), true); click('修改时间与说明');
  assert.match(document.querySelector('dialog').textContent, /修改会同步到这些宝宝/);
});

test('已收起宝宝只看和下载，不能改时间、关联或删除', async () => {
  const ctx = context(); ctx.child = children[2]; ctx.childId = 'archived'; await mediaPage(ctx);
  assert.match(document.body.textContent, /这位宝宝已收起/); assert.equal([...document.querySelectorAll('button')].some(node => node.textContent === '上传照片'), false);
  change(document.querySelector('.media-check input'), true);
  for (const text of ['修改时间与说明', '加入其他宝宝', '移入回收站']) assert.equal([...document.querySelectorAll('.selection-bar button')].find(node => node.textContent === text).disabled, true);
  assert.equal([...document.querySelectorAll('.selection-bar button')].find(node => node.textContent === '下载原文件').disabled, false);
});

test('页面请求保留原宝宝上下文，默认宝宝切换不改变已有写入目标', async () => {
  const ctx = context(); setChild('younger'); await ctx.request('/api/admin/media/batch', { body: { ids: ['first'], action: 'trash' } });
  assert.equal(calls.at(-1).options.headers['X-FAM-Child'], 'older');
  await api('/api/profile'); assert.equal(calls.at(-1).options.headers['X-FAM-Child'], 'younger');
  await ctx.request('/api/children?include_archived=true', { childId: '' }); assert.equal(calls.at(-1).options.headers['X-FAM-Child'], undefined);
});

test('家庭身份通过独立字段修改，不顺带改动宝宝授权', async () => {
  const ctx = context({ familyTab: 'family' });
  respond = call => call.path === '/api/members' ? { items: [member, { id: 'grandma', name: '奶奶', username: 'grandma', registered: true, is_family_admin: false, family_role: 'contributor', children: [{ child_id: 'older', name: '姐姐', role: 'viewer' }] }] } : standard(call);
  await familyPage(ctx); click('进入账号管理'); click('账号设置', document.querySelector('.account-member-list .member-row:last-child')); clickFamilyEntry('家庭身份'); change(field('家庭身份'), 'admin'); click('保存家庭身份'); await until(() => ctx.refreshed);
  const identityChange = calls.find(call => call.path === '/api/members/grandma'); assert.deepEqual(identityChange.body, { family_role: 'admin' });
  assert.equal(calls.some(call => call.method === 'PUT' && call.path.includes('/children/')), false);
});

test('归档宝宝资料不可编辑，但全家的上传限制仍可保存', async () => {
  const ctx = context(); ctx.childId = 'archived'; ctx.child = children[2]; await settingsPage(ctx);
  assert.equal(field('家庭相册名称（所有宝宝共用）').disabled, true); assert.equal(document.querySelector('.settings-row').textContent.includes('宝宝资料'), true);
  change(field('单个文件最大大小（MB）'), '250'); submit(document.querySelector('.settings-form')); await until(() => !ctx.leave.dirty());
  assert.equal(calls.some(call => call.path === '/api/profile' && call.method === 'PUT'), false);
  assert.equal(calls.find(call => call.path === '/api/admin/settings' && call.method === 'PUT').body.max_upload_bytes, 250 * 1048576);
});
