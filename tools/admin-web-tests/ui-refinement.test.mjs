import { test, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch { ({ JSDOM } = require(process.env.FAM_JSDOM_PATH)); }
const dom = new JSDOM('<!doctype html><html><body><button id="origin">打开设置</button><div id="toasts"></div></body></html>', { url: 'http://localhost/admin/' });
for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement'])
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
const { modal, field, button, busy, closeModals, dismissModals, hasPendingModalChanges } = await import('../../server/web/ui.js');
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
beforeEach(() => { dismissModals(); document.getElementById('toasts').replaceChildren(); });
after(() => { dismissModals(); dom.window.close(); });

test('取消关闭会保留输入，明确放弃后才关闭', async () => {
  let allow = false;
  const input = field('存放位置名称');
  const view = modal('编辑位置', ({ body }) => body.append(input.node), { beforeClose: () => allow });
  input.input.value = '家里的相册';
  view.dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
  await tick();
  assert.ok(view.dialog.isConnected);
  assert.equal(input.input.value, '家里的相册');
  allow = true;
  assert.equal(await view.requestClose(), true);
  assert.equal(view.dialog.isConnected, false);
});

test('重复关闭只询问一次，保留后还可以再次关闭', async () => {
  let finish, checks = 0;
  const view = modal('编辑资料', () => {}, { beforeClose: () => { checks++; return new Promise(resolve => { finish = resolve; }); } });
  const first = view.requestClose(), second = view.requestClose();
  assert.equal(checks, 1);
  finish(false);
  assert.equal(await first, false); assert.equal(await second, false);
  const again = view.requestClose();
  assert.equal(checks, 2);
  finish(true); assert.equal(await again, true);
});

test('提交中不能从关闭按钮或键盘离开，结束后恢复', async () => {
  const view = modal('检查连接', () => {});
  view.setBusy(true);
  assert.equal(view.dialog.querySelector('[aria-label="关闭"]').disabled, true);
  view.dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
  assert.equal(await view.requestClose(), false);
  assert.ok(view.dialog.isConnected);
  view.setBusy(false);
  assert.equal(await view.requestClose(), true);
});

test('共用提交按钮也会保护仍在处理的弹窗', async () => {
  let finish;
  const save = button('保存');
  const view = modal('编辑资料', ({ footer }) => footer.append(save));
  const processing = busy(save, () => new Promise(resolve => { finish = resolve; }));
  assert.equal(await view.requestClose(), false);
  assert.equal(hasPendingModalChanges(), true);
  finish(); await processing;
  assert.equal(await view.requestClose(), true);
});

test('保存成功可直接关闭，只执行一次收尾并返回原操作位置', () => {
  let closed = 0;
  const origin = document.getElementById('origin'); origin.focus();
  const view = modal('编辑资料', () => {}, { beforeClose: () => false, onClose: () => closed++ });
  view.setBusy(true);
  view.close(); view.close();
  assert.equal(closed, 1);
  assert.equal(document.activeElement, origin);
});

test('切换页面前逐层关闭，保留修改时阻止切换', async () => {
  const parent = modal('资料编辑', () => {}, { beforeClose: () => false });
  const child = modal('照片预览', () => {});
  assert.equal(await closeModals(), false);
  assert.equal(child.dialog.isConnected, false);
  assert.equal(parent.dialog.isConnected, true);
});

test('登录失效可强制清理全部弹窗，不被未保存确认挡住', () => {
  let checks = 0, closed = 0;
  const view = modal('私有资料', () => {}, { beforeClose: () => { checks++; return false; }, onClose: () => closed++ });
  view.setBusy(true);
  dismissModals();
  assert.equal(document.querySelectorAll('dialog').length, 0);
  assert.equal(checks, 0); assert.equal(closed, 1);
});

test('刷新网页只在确有修改或正在提交时提醒', () => {
  let dirty = false;
  const view = modal('修改账号', () => {}, { isDirty: () => dirty });
  assert.equal(hasPendingModalChanges(), false);
  dirty = true; assert.equal(hasPendingModalChanges(), true);
  dirty = false; view.setBusy(true); assert.equal(hasPendingModalChanges(), true);
  view.close(); assert.equal(hasPendingModalChanges(), false);
});

test('关闭判断异常时保留内容，并允许之后重新处理', async () => {
  let failed = true;
  const view = modal('编辑资料', () => {}, { beforeClose: () => { if (failed) throw new Error('检查暂未完成'); return true; } });
  assert.equal(await view.requestClose(), false);
  assert.ok(view.dialog.isConnected);
  failed = false; assert.equal(await view.requestClose(), true);
});

test('表单构建出错时不留下遮罩或待关闭状态', () => {
  assert.throws(() => modal('设置', () => { throw new Error('测试错误'); }));
  assert.equal(document.querySelectorAll('dialog').length, 0);
  assert.equal(hasPendingModalChanges(), false);
});

test('密码显示切换保留原文字和光标，禁用时不改变显示', () => {
  const password = field('当前密码', 'password', 'a-b-c-d');
  const view = modal('账号设置', ({ body }) => body.append(password.node));
  password.input.setSelectionRange(2, 4);
  const reveal = password.node.querySelector('button'); reveal.click();
  assert.equal(password.input.type, 'text'); assert.equal(password.input.value, 'a-b-c-d');
  assert.equal(reveal.title, '隐藏当前密码');
  assert.equal(password.input.selectionStart, 2); assert.equal(password.input.selectionEnd, 4);
  reveal.click(); assert.equal(password.input.type, 'password');
  password.input.disabled = true; reveal.click(); assert.equal(password.input.type, 'password');
  view.close();
});

test('无法读取服务器回包时给出可理解的提示，不泄露解析错误', async () => {
  const { api } = await import('../../server/web/api.js');
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('incomplete body', { status: 200 });
    await assert.rejects(api('/api/profile'), error => error.status === 0 && error.message === '服务器返回的结果暂时无法确认，请稍后重新读取。');
  } finally { globalThis.fetch = original; }
});
