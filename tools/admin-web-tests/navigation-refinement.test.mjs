import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (error) {
  if (!process.env.FAM_JSDOM_PATH) throw error;
  ({ JSDOM } = require(process.env.FAM_JSDOM_PATH));
}
const tick = () => new Promise(resolve => setTimeout(resolve, 5));
async function until(predicate, message) {
  for (let index = 0; index < 150; index++) { if (predicate()) return; await tick(); }
  assert.fail(message || '页面没有完成预期更新');
}
let caseNumber = 0;
async function harness({ holdStatus = false, holdNewChildren = false } = {}) {
  const dom = new JSDOM('<!doctype html><body><div id="app"></div><div id="toasts"></div></body>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
  for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement', 'HTMLInputElement', 'FormData', 'File', 'DOMException', 'AbortController', 'location', 'history', 'navigator', 'XMLHttpRequest', 'sessionStorage'])
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
  const oldMember = { id: 'first-owner', name: '爸爸', is_family_admin: true }, newMember = { id: 'second-owner', name: '妈妈', is_family_admin: true };
  const oldChildren = [{ id: 'first-child', name: '姐姐' }], newChildren = [{ id: 'second-child', name: '弟弟' }];
  const calls = [], pending = {};
  let loggedNew = false;
  globalThis.fetch = async (path, options = {}) => {
    const method = options.method || 'GET';
    calls.push({ path, method, headers: options.headers || {}, newIdentity: loggedNew });
    let body = {};
    if (path === '/test-expire') return new Response(JSON.stringify({ detail: '登录已经失效' }), { status: 401, headers: { 'Content-Type': 'application/json' } });
    if (path === '/api/admin/session') {
      if (method === 'POST') loggedNew = true;
      body = { member: loggedNew ? newMember : oldMember, csrf: 'test-session' };
    } else if (String(path).startsWith('/api/children')) {
      if (loggedNew && holdNewChildren) body = await new Promise(resolve => { pending.children = () => resolve({ items: newChildren }); });
      else body = { items: loggedNew ? newChildren : oldChildren };
    } else if (path === '/api/status') {
      body = holdStatus ? await new Promise(resolve => { pending.status = () => resolve({ default_credentials: false }); }) : { default_credentials: false };
    } else if (path === '/api/admin/overview') body = { profile: { counts: {} }, recent: [] };
    else if (String(path).startsWith('/api/members')) body = { items: [] };
    else if (String(path).startsWith('/api/admin/media?')) body = { items: [], total: 0 };
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  };
  const ui = await import('../../server/web/ui.js');
  ui.dismissModals();
  const app = await import(`../../server/web/admin.js?navigation-regression=${++caseNumber}`);
  const { api } = await import('../../server/web/api.js');
  await until(() => document.querySelector('.stats'), '初始相册应成功打开');
  let finishClose;
  const view = ui.modal('仍在编辑的资料', () => {}, { beforeClose: () => new Promise(resolve => { finishClose = resolve; }) });
  app.navigate('media');
  await until(() => finishClose, '路由应等待弹窗关闭确认');
  return { calls, pending, view, expire: () => api('/test-expire').catch(() => {}), finishClose: () => finishClose(true), cleanup: () => { ui.dismissModals(); dom.window.close(); } };
}

test('关闭确认等待中登录失效，旧路由不能破坏登录页的异步加载', async () => {
  const state = await harness({ holdStatus: true });
  try {
    await state.expire();
    await until(() => state.pending.status, '失效后应读取登录页状态');
    assert.equal(state.view.dialog.isConnected, false, '失效时立即关闭旧身份弹窗');
    state.finishClose();
    await tick();
    assert.equal(document.querySelector('.shell'), null, '旧路由不得重新创建后台页面');
    state.pending.status();
    await until(() => document.querySelector('.auth-card h1')?.textContent === '欢迎回到家庭相册', '登录页加载应保持有效');
    assert.equal(state.calls.some(call => String(call.path).startsWith('/api/admin/media?')), false);
  } finally { state.cleanup(); }
});

test('重新登录后仍未完成的旧关闭确认不能借用新身份打开旧宝宝相册', async () => {
  const state = await harness({ holdNewChildren: true });
  try {
    await state.expire();
    await until(() => document.querySelector('.auth-card form'), '应显示新的登录表单');
    const fields = [...document.querySelectorAll('.auth-card label.field')];
    fields.find(field => field.querySelector('span')?.textContent === '用户名').querySelector('input').value = 'second-family';
    fields.find(field => field.querySelector('span')?.textContent === '密码').querySelector('input').value = 'test-password';
    document.querySelector('.auth-card form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await until(() => state.pending.children, '新身份应先读取自己的宝宝列表');
    state.finishClose();
    await tick();
    assert.equal(document.querySelector('.shell'), null, '新身份资料未读完时旧路由不能插入页面');
    assert.equal(state.calls.some(call => call.newIdentity && call.headers['X-FAM-Child'] === 'first-child'), false);
    state.pending.children();
    await until(() => document.querySelector('[aria-label="当前宝宝"]')?.value === 'second-child' && document.querySelector('.media-results'), '应打开新身份自己的宝宝相册');
    assert.match(document.querySelector('.identity').textContent, /妈妈/);
    assert.equal(state.calls.some(call => call.newIdentity && call.headers['X-FAM-Child'] === 'first-child'), false);
    assert.ok(state.calls.some(call => String(call.path).startsWith('/api/admin/media?') && call.headers['X-FAM-Child'] === 'second-child'));
  } finally { state.cleanup(); }
});
