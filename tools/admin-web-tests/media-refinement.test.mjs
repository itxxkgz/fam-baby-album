import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); } catch (error) {
  if (!process.env.FAM_JSDOM_PATH) throw error;
  ({ JSDOM } = require(process.env.FAM_JSDOM_PATH));
}
const dom = new JSDOM('<!doctype html><body><main></main><div id="toasts"></div></body>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement', 'HTMLInputElement', 'FormData', 'File', 'DOMException', 'AbortController', 'XMLHttpRequest']) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
window.scrollTo = (x, y) => { window.scrollY = y; };
const { mediaPage, overviewPage } = await import('../../server/web/views/media.js');
const { importsPage } = await import('../../server/web/views/imports.js');
const { dismissModals, hasPendingModalChanges } = await import('../../server/web/ui.js');
const children = [{ id: 'first-child', name: '姐姐', archived: false }, { id: 'second-child', name: '弟弟', archived: false }];
const sample = { id: 'media-private-id', kind: 'photo', filename: '公园.jpg', description: '<script>一场春游</script>', captured_at: '2026-05-17T02:00:00Z', date_label: '2026年5月17日', age: '6岁4个月', uploader_name: '妈妈', shared_count: 2, thumbnail_url: '/api/media/a/thumbnail', file_url: '/api/media/a/file', width: 1920, height: 1280, size_bytes: 2048 };
let calls = [], respond;
function normal(path) {
  if (path.startsWith('/api/members')) return { items: [{ id: 'mom', name: '妈妈' }, { id: 'legacy', name: '外婆', registered: false }] };
  if (path.startsWith('/api/admin/media?')) return { items: [sample], total: 1 };
  if (path === '/api/admin/imports') return { items: [] };
  if (path.endsWith('/roots')) return { items: [{ id: 'root-private-id', name: '家庭图库' }] };
  if (path.endsWith('/scan')) return { count: 2, duplicates: 0, bytes: 2048 };
  return {};
}
function context(media = {}) {
  dismissModals();
  document.body.replaceChildren(document.createElement('main'), Object.assign(document.createElement('div'), { id: 'toasts' }));
  window.scrollY = 0; calls = []; respond = normal;
  const ctx = { container: document.querySelector('main'), childId: children[0].id, child: children[0], children, state: { media: { kind: 'all', deleted: false, ...media } }, familyName() {}, navigate() {}, isCurrent: () => true, setLeaveCheck(check) { this.leave = check; }, request: async (path, options = {}) => { calls.push({ path, ...options }); return respond(path, options); } };
  return ctx;
}
const click = (text, scope = document) => { const button = [...scope.querySelectorAll('button')].find(node => node.textContent.trim() === text); assert.ok(button, `找到${text}`); button.click(); return button; };
const input = (label, scope = document) => { const node = [...scope.querySelectorAll('label.field')].find(node => node.querySelector('span')?.textContent === label)?.querySelector('input,textarea,select'); assert.ok(node, `找到${label}`); return node; };
function change(node, value) { if (node.type === 'checkbox') node.checked = value; else node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); node.dispatchEvent(new Event('change', { bubbles: true })); }
function submit(form) { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); }
async function until(check) { for (let i = 0; i < 120; i++) { if (check()) return; await new Promise(resolve => setTimeout(resolve, 5)); } assert.fail('页面未更新到预期状态'); }
after(() => { dismissModals(); dom.window.close(); });

test('照片默认收起高级条件，筛选后保留家人日期并可一次清除', async () => {
  const ctx = context(); await mediaPage(ctx);
  assert.equal(document.querySelector('.media-advanced').open, false);
  assert.equal(document.querySelector('.selection-bar').hidden, true);
  const advanced = document.querySelector('.media-advanced'); advanced.open = true;
  change(input('上传家人'), 'legacy'); change(input('开始日期'), '2026-05-01'); change(input('结束日期'), '2026-05-31'); submit(document.querySelector('.media-filters'));
  await until(() => document.querySelector('.media-tile'));
  assert.match(document.querySelector('.media-filter-summary').textContent, /外婆.*2026年5月1日/);
  assert.equal(new URLSearchParams(calls.at(-1).path.split('?')[1]).get('uploader_id'), 'legacy');
  click('清除'); await until(() => document.querySelector('.media-tile'));
  assert.equal(new URLSearchParams(calls.at(-1).path.split('?')[1]).has('uploader_id'), false);
  assert.equal(document.querySelector('.media-filter-summary').hidden, true);
});

test('照片信息与编辑侧栏不泄露编号，失败保留草稿并保护关闭', async () => {
  const ctx = context(); let rejectSave;
  respond = (path, options) => path.endsWith('/batch') ? new Promise((resolve, reject) => { rejectSave = reject; }) : normal(path, options);
  await mediaPage(ctx); click('信息');
  let drawer = document.querySelector('dialog.drawer');
  assert.ok(drawer); assert.match(drawer.textContent, /公园.jpg.*2 KB/); assert.equal(drawer.textContent.includes(sample.id), false); assert.equal(drawer.querySelector('script'), null);
  click('编辑', drawer); drawer = document.querySelector('dialog.drawer');
  assert.match(drawer.textContent, /修改会同步到这些宝宝/);
  const checks = drawer.querySelectorAll('.check-line input'); change(checks[1], true);
  const description = input('新的照片说明', drawer); change(description, '这一刻要留下');
  submit(drawer.querySelector('form')); await until(() => rejectSave);
  assert.equal(description.disabled, true); assert.equal(hasPendingModalChanges(), true);
  drawer.dispatchEvent(new Event('cancel', { cancelable: true })); assert.equal(drawer.isConnected, true);
  rejectSave(new Error('网络暂时中断')); await until(() => !description.disabled);
  assert.equal(description.value, '这一刻要留下'); assert.match(drawer.querySelector('.form-error').textContent, /网络/);
  drawer.dispatchEvent(new Event('cancel', { cancelable: true })); await until(() => document.querySelectorAll('dialog').length === 2);
  click('取消', document.querySelector('dialog:last-of-type')); await until(() => document.querySelectorAll('dialog').length === 1);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(description.value, '这一刻要留下');
  drawer.dispatchEvent(new Event('cancel', { cancelable: true })); await until(() => document.querySelectorAll('dialog').length === 2);
  click('放弃修改', document.querySelector('dialog:last-of-type')); await until(() => !document.querySelector('dialog'));
});

test('媒体整理保留分页，末页清空后回到上一页', async () => {
  const ctx = context({ offset: 40 }); let removed = false;
  respond = (path, options) => {
    if (path.endsWith('/batch')) { removed = true; return {}; }
    if (path.startsWith('/api/admin/media?')) { const offset = Number(new URLSearchParams(path.split('?')[1]).get('offset')); return { items: removed && offset >= 40 ? [] : [sample], total: removed ? 40 : 41 }; }
    return normal(path, options);
  };
  const cleanup = await mediaPage(ctx); assert.equal(ctx.state.media.offset, 40);
  change(document.querySelector('.media-check input'), true); click('移入回收站'); click('移入回收站', document.querySelector('dialog'));
  await until(() => removed && ctx.state.media.offset === 0 && document.querySelector('.media-tile'));
  assert.equal(ctx.state.media.kind, 'all'); cleanup();
});

test('导入先选择来源，任务只突出结果且详情展开状态在刷新后保留', async () => {
  const ctx = context(); const job = { id: 'job-private', name: '周末回忆', source: 'directory', status: 'running', status_label: '导入中', total: 20, processed: 6, imported: 5, duplicates: 1, child_names: ['姐姐', '弟弟'] };
  respond = path => path === '/api/admin/imports' ? { items: [job] } : normal(path);
  const cleanup = await importsPage(ctx);
  try {
    assert.equal(document.querySelector('.import-computer').hidden, true);
    click('从电脑导入'); assert.equal(document.querySelector('.import-computer').hidden, false);
    assert.match(document.querySelector('.task-main-result').textContent, /6 \/ 20/);
    assert.equal(document.querySelector('.task-summary-details').open, false);
    const details = document.querySelector('.task-summary-details'); details.open = true; details.dispatchEvent(new Event('toggle'));
    click('刷新'); await until(() => document.querySelector('.task-summary-details') !== details);
    assert.equal(document.querySelector('.task-summary-details').open, true);
    assert.match(document.querySelector('.task-next-step').textContent, /服务器正在导入.*可以关闭/);
  } finally { cleanup(); }
});

test('电脑文件传输不能伪装后台任务，离开确认可取消且目标宝宝固定', async () => {
  const ctx = context(); let xhr, created = false;
  class FakeXHR { constructor() { xhr = this; this.upload = {}; this.headers = {}; } open(method, path) { this.path = path; } setRequestHeader(key, value) { this.headers[key] = value; } send(body) { this.body = body; } abort() { this.onabort?.(); } }
  globalThis.XMLHttpRequest = FakeXHR;
  respond = (path, options) => {
    if (path === '/api/admin/imports' && options.body) { created = true; return { id: 'pending-upload' }; }
    if (path === '/api/admin/imports') return { items: created ? [{ id: 'pending-upload', name: '电脑照片', source: 'upload', status: 'receiving', total: 0 }] : [] };
    return normal(path);
  };
  const cleanup = await importsPage(ctx);
  try {
    click('从电脑导入'); change(document.querySelector('.child-choices input[aria-label="弟弟"]'), true);
    const picker = document.querySelector('input[type=file]'); Object.defineProperty(picker, 'files', { configurable: true, value: [new File(['abc'], '春游.jpg', { lastModified: 1778983200000 })] }); picker.dispatchEvent(new Event('change'));
    await until(() => xhr?.body);
    assert.deepEqual(calls.find(call => call.path === '/api/admin/imports' && call.body).body.child_ids, ['first-child', 'second-child']);
    assert.equal(xhr.headers['X-FAM-Child'], 'first-child');
    assert.match(document.querySelector('.task-next-step').textContent, /保持页面打开/);
    assert.equal(document.querySelector('.task-actions button').disabled, true);
    const stay = ctx.leave(); click('取消', document.querySelector('dialog')); assert.equal(await stay, false); assert.equal(ctx.leave.dirty(), true);
    const leave = ctx.leave(); click('停止并离开', document.querySelector('dialog')); assert.equal(await leave, true);
    await until(() => !ctx.leave.dirty()); assert.equal(calls.some(call => call.path.endsWith('/start')), false);
  } finally { cleanup(); globalThis.XMLHttpRequest = dom.window.XMLHttpRequest; }
});

test('目录导入扫描后才出现开始按钮，失败后保留选择且请求使用目标快照', async () => {
  const ctx = context(); let rejectImport;
  respond = (path, options) => path.endsWith('/directory') ? new Promise((resolve, reject) => { rejectImport = reject; }) : normal(path, options);
  const cleanup = await importsPage(ctx);
  try {
    click('从服务器目录导入'); await until(() => document.querySelector('dialog select'));
    const drawer = document.querySelector('dialog.drawer'); assert.ok(drawer);
    let start = [...drawer.querySelectorAll('button')].find(button => button.textContent === '开始导入'); assert.equal(start.hidden, true);
    change(input('子文件夹（可留空）', drawer), '2026/春天'); change(drawer.querySelector('input[aria-label="弟弟"]'), true);
    click('扫描文件', drawer); await until(() => !start.hidden);
    click('开始导入', drawer); await until(() => rejectImport);
    assert.equal(input('子文件夹（可留空）', drawer).disabled, true);
    assert.deepEqual(calls.find(call => call.path.endsWith('/directory')).body.child_ids, ['first-child', 'second-child']);
    drawer.dispatchEvent(new Event('cancel', { cancelable: true })); assert.equal(drawer.isConnected, true);
    rejectImport(new Error('暂时无法开始')); await until(() => !input('子文件夹（可留空）', drawer).disabled);
    assert.equal(input('子文件夹（可留空）', drawer).value, '2026/春天'); assert.equal(drawer.querySelector('input[aria-label="弟弟"]').checked, true);
  } finally { cleanup(); dismissModals(); }
});

test('概览保留真实统计、最近照片、备份状态与待处理入口', async () => {
  const ctx = context(); let destination;
  ctx.navigate = value => { destination = value; };
  respond = () => ({ profile: { counts: { photos: 123, videos: 9, members: 4 }, storage_bytes: 2048 }, recent: [sample], week_count: 7, storage: { total_bytes: 100, free_bytes: 20 }, default_storage: { name: '家庭服务器', tested: true }, backups: { latest: { mode: 'metadata', created_at: '2026-05-18T02:00:00Z' } }, tasks: { failed: 2 } });
  await overviewPage(ctx);
  assert.equal(document.querySelectorAll('.stat').length, 4); assert.match(document.querySelector('.stats').textContent, /123.*9.*4.*2 KB/);
  assert.match(document.querySelector('.page-heading').textContent, /本周新增 7/); assert.equal(document.querySelectorAll('.recent-grid .photo').length, 1);
  assert.match(document.querySelector('.overview-storage').textContent, /原文件需另行备份/);
  click('查看任务'); assert.equal(destination, 'imports'); click('添加家人'); assert.equal(destination, 'family'); assert.equal(ctx.state.inviteOpen, true);
});

test('任务暂停、继续、重试与取消仍对应同一任务，处理中不可重复提交', async () => {
  const ctx = context(); let finishPause;
  const job = { id: 'job-control', name: '孩子的夏天', source: 'directory', status: 'running', status_label: '导入中', total: 8, processed: 4, failed: 1, imported: 3 };
  respond = (path, options) => {
    if (path === '/api/admin/imports') return { items: [job] };
    if (path.endsWith('/pause')) return new Promise(resolve => { finishPause = () => { job.status = 'paused'; resolve({}); }; });
    if (path.endsWith('/resume')) { job.status = 'running'; return {}; }
    if (path.endsWith('/retry')) { job.status = 'completed'; job.failed = 0; return {}; }
    if (path.endsWith('/cancel')) { job.status = 'cancelled'; return {}; }
    return normal(path, options);
  };
  const cleanup = await importsPage(ctx);
  try {
    click('暂停'); await until(() => finishPause); click('刷新'); await until(() => [...document.querySelectorAll('.task-actions button')].find(node => node.textContent === '暂停')?.disabled);
    assert.equal(calls.filter(call => call.path.endsWith('/pause')).length, 1);
    finishPause(); await until(() => [...document.querySelectorAll('button')].some(node => node.textContent === '继续'));
    click('继续'); await until(() => job.status === 'running' && [...document.querySelectorAll('button')].some(node => node.textContent === '暂停'));
    click('暂停'); finishPause(); await until(() => [...document.querySelectorAll('button')].some(node => node.textContent === '重试失败项'));
    click('重试失败项'); await until(() => job.status === 'completed' && ![...document.querySelectorAll('button')].some(node => node.textContent === '重试失败项'));
    job.status = 'failed'; job.failed = 2; click('刷新'); await until(() => [...document.querySelectorAll('button')].some(node => node.textContent === '取消任务'));
    click('取消任务'); click('取消', document.querySelector('dialog')); assert.equal(calls.some(call => call.path.endsWith('/cancel')), false);
    click('取消任务'); click('取消任务', document.querySelector('dialog')); await until(() => job.status === 'cancelled');
    assert.ok(calls.filter(call => call.body).every(call => call.path.startsWith('/api/admin/imports/job-control/')));
  } finally { cleanup(); }
});
