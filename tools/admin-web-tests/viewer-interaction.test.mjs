import { test, beforeEach, afterEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (error) { if (!process.env.FAM_JSDOM_PATH) throw error; ({ JSDOM } = require(process.env.FAM_JSDOM_PATH)); }
const dom = new JSDOM('<!doctype html><body><button id="origin">打开照片</button><div id="toasts"></div></body>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
for (const key of ['window', 'document', 'Node', 'Event', 'HTMLElement']) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value: dom.window[key] });
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
const paused = [], unloaded = [];
dom.window.HTMLMediaElement.prototype.pause = function () { paused.push(this); };
dom.window.HTMLMediaElement.prototype.load = function () { unloaded.push(this); };
let frame = { left: 100, top: 50, width: 800, height: 600 };
const originalBounds = window.HTMLElement.prototype.getBoundingClientRect;
window.HTMLElement.prototype.getBoundingClientRect = function () {
  if (this.classList.contains('viewer-stage')) return { ...frame, right: frame.left + frame.width, bottom: frame.top + frame.height, x: frame.left, y: frame.top };
  return originalBounds.call(this);
};
const captures = new Map(), released = [];
window.HTMLElement.prototype.setPointerCapture = function (id) { if (!captures.has(this)) captures.set(this, new Set()); captures.get(this).add(id); };
window.HTMLElement.prototype.hasPointerCapture = function (id) { return captures.get(this)?.has(id) || false; };
window.HTMLElement.prototype.releasePointerCapture = function (id) { captures.get(this)?.delete(id); released.push([this, id]); };
const observers = [];
window.ResizeObserver = class {
  constructor(callback) { this.callback = callback; this.targets = new Set(); this.disconnected = false; observers.push(this); }
  observe(target) { this.targets.add(target); }
  unobserve(target) { this.targets.delete(target); }
  disconnect() { this.targets.clear(); this.disconnected = true; }
  trigger() { this.callback([...this.targets].map(target => ({ target })), this); }
};
const globalListeners = new Map();
for (const [target, type] of [[document, 'keydown'], [window, 'resize']]) {
  const add = target.addEventListener.bind(target), remove = target.removeEventListener.bind(target), tracked = new Set();
  globalListeners.set(type, tracked);
  target.addEventListener = (name, listener, options) => { if (name === type) tracked.add(listener); return add(name, listener, options); };
  target.removeEventListener = (name, listener, options) => { if (name === type) tracked.delete(listener); return remove(name, listener, options); };
}
const { openViewer, modal, dismissModals } = await import('../../server/web/ui.js');
const photo = { id: 'first', kind: 'photo', width: 1600, height: 1200, filename: '春游.jpg', age: '6岁4个月', date_label: '2026年5月17日', captured_at: '2026-05-17T02:00:00Z', uploader_name: '妈妈', description: '一起去公园', thumbnail_url: '/api/media/first/thumbnail', file_url: '/api/media/first/file' };
const nextPhoto = { ...photo, id: 'second', filename: '花园.jpg', file_url: '/api/media/second/file' };
const video = { ...photo, id: 'video', kind: 'video', filename: '笑声.mp4', file_url: '/api/media/video/file' };
// CSS serialization rounds transform values to six significant figures in JSDOM.
const near = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 1e-4, `${message || '数值'}：${actual} ≈ ${expected}`);
function transform(image) {
  const match = image.style.transform.match(/translate3d\(([-.\de+]+)px,\s*([-.\de+]+)px,\s*0(?:px)?\)\s*scale\(([-.\de+]+)\)/i);
  assert.ok(match, `照片使用以屏幕像素平移后再缩放的变换：${image.style.transform}`);
  return { x: Number(match[1]), y: Number(match[2]), scale: Number(match[3]) };
}
const stageOf = view => view.body.querySelector('.viewer-stage');
const imageOf = view => view.body.querySelector('.viewer-photo');
const button = (view, label) => { const node = view.dialog.querySelector(`button[aria-label="${label}"]`); assert.ok(node, `找到${label}`); return node; };
function wheel(stage, deltaY, clientX = 500, clientY = 350, deltaMode = 0) { const event = new window.WheelEvent('wheel', { deltaY, deltaMode, clientX, clientY, bubbles: true, cancelable: true }); stage.dispatchEvent(event); return event; }
function pointer(stage, name, x, y, id = 1, type = 'mouse') { const event = new window.MouseEvent(name, { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true }); Object.defineProperties(event, { pointerId: { value: id }, pointerType: { value: type } }); stage.dispatchEvent(event); return event; }
function doubleClick(stage, x = 500, y = 350) { stage.dispatchEvent(new window.MouseEvent('dblclick', { clientX: x, clientY: y, bubbles: true, cancelable: true })); }
function key(target, value, options = {}) { const event = new window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options }); target.dispatchEvent(event); return event; }
beforeEach(() => { dismissModals(); document.getElementById('toasts').replaceChildren(); frame = { left: 100, top: 50, width: 800, height: 600 }; observers.length = released.length = paused.length = unloaded.length = 0; captures.clear(); });
afterEach(() => { dismissModals(); assert.equal(globalListeners.get('keydown').size, 0); assert.equal(globalListeners.get('resize').size, 0); assert.ok(observers.every(observer => observer.targets.size === 0)); });
after(() => dom.window.close());

test('滚轮围绕鼠标所在照片位置缩放，保留同一张原图节点和元资料', () => {
  const view = openViewer([photo]), stage = stageOf(view), image = imageOf(view);
  assert.equal(image.style.width, '800px'); assert.equal(image.style.height, '600px');
  const event = wheel(stage, -Math.log(2) / .002, 700, 400);
  assert.equal(event.defaultPrevented, true); assert.equal(imageOf(view), image);
  const state = transform(image); near(state.scale, 2); near(state.x, -200); near(state.y, -50);
  near((200 - state.x) / state.scale, 200, '水平方向鼠标锚点'); near((50 - state.y) / state.scale, 50, '竖直方向鼠标锚点');
  assert.equal(view.body.querySelector('.viewer-scale').textContent, '2 倍'); assert.equal(image.src, 'http://localhost/api/media/first/file');
  assert.match(view.body.querySelector('.viewer-meta').textContent, /6岁4个月.*妈妈上传.*一起去公园/);
  assert.equal(view.body.querySelector('a[download]').getAttribute('download'), '春游.jpg');
});

test('滚轮行与页单位正常工作，最大倍数不漂移并能缩回整张', () => {
  const view = openViewer([photo]), stage = stageOf(view), image = imageOf(view);
  wheel(stage, -1, 500, 350, 1); near(transform(image).scale, Math.exp(.032));
  wheel(stage, -1, 500, 350, 2); near(transform(image).scale, Math.exp(.032 + 1.2));
  wheel(stage, -1e6, 600, 450); const maximum = transform(image); near(maximum.scale, 8); assert.equal(button(view, '放大照片').disabled, true);
  wheel(stage, -100, 150, 100); assert.deepEqual(transform(image), maximum);
  wheel(stage, 1e6, 600, 450); assert.deepEqual(transform(image), { x: 0, y: 0, scale: 1 }); assert.equal(button(view, '缩小照片').disabled, true);
});

test('放大后鼠标实时拖动且被边界夹住，松手后不再跟随', () => {
  const view = openViewer([photo]), stage = stageOf(view), image = imageOf(view); doubleClick(stage);
  pointer(stage, 'pointerdown', 500, 350); assert.equal(stage.hasPointerCapture(1), true); assert.equal(stage.classList.contains('is-grabbing'), true);
  pointer(stage, 'pointermove', 550, 375); assert.deepEqual(transform(image), { x: 50, y: 25, scale: 2 });
  pointer(stage, 'pointermove', 5000, 5000); assert.deepEqual(transform(image), { x: 400, y: 300, scale: 2 });
  pointer(stage, 'pointerup', 5000, 5000); assert.equal(stage.hasPointerCapture(1), false); assert.equal(stage.classList.contains('is-grabbing'), false);
  pointer(stage, 'pointermove', -5000, -5000); assert.deepEqual(transform(image), { x: 400, y: 300, scale: 2 });
  pointer(stage, 'pointerdown', 500, 350, 2); pointer(stage, 'pointermove', -5000, -5000, 2); assert.deepEqual(transform(image), { x: -400, y: -300, scale: 2 });
  pointer(stage, 'pointercancel', -5000, -5000, 2); assert.equal(stage.hasPointerCapture(2), false); assert.equal(stage.classList.contains('is-grabbing'), false);
});

test('双击与适应窗口按钮都能恢复整张，按钮缩放不会重新创建图片', () => {
  const view = openViewer([photo]), stage = stageOf(view), image = imageOf(view);
  doubleClick(image, 700, 350); near(transform(image).scale, 2); doubleClick(image, 700, 350); assert.deepEqual(transform(image), { scale: 1, x: 0, y: 0 });
  button(view, '放大照片').click(); near(transform(image).scale, 1.25); assert.equal(imageOf(view), image);
  view.body.querySelector('.viewer-fit').click(); assert.deepEqual(transform(image), { scale: 1, x: 0, y: 0 }); assert.equal(view.body.querySelector('.viewer-fit').disabled, true);
  key(stage, '+'); near(transform(image).scale, 1.25); key(stage, '0'); near(transform(image).scale, 1);
});

test('图片没有超出的一轴保持居中，视口变化重新计算适应尺寸并清理拖动', () => {
  const view = openViewer([{ ...photo, width: 600, height: 1200 }]), stage = stageOf(view), image = imageOf(view);
  doubleClick(stage); pointer(stage, 'pointerdown', 500, 350); pointer(stage, 'pointermove', 4000, 4000); assert.deepEqual(transform(image), { scale: 2, x: 0, y: 300 });
  frame = { left: 100, top: 50, width: 800, height: 300 }; observers.at(-1).trigger();
  assert.equal(image.style.width, '150px'); assert.equal(image.style.height, '300px'); assert.deepEqual(transform(image), { scale: 2, x: 0, y: 150 });
  assert.equal(stage.hasPointerCapture(1), false); assert.equal(stage.classList.contains('is-grabbing'), false);
});

test('照片尚未有尺寸时不拦截滚轮，原图加载后再启用缩放', () => {
  const view = openViewer([{ ...photo, width: 0, height: 0 }]), stage = stageOf(view), image = imageOf(view);
  assert.equal(button(view, '放大照片').disabled, true); assert.equal(wheel(stage, -100).defaultPrevented, false);
  Object.defineProperties(image, { naturalWidth: { value: 1600 }, naturalHeight: { value: 1200 } }); image.dispatchEvent(new Event('load'));
  assert.equal(button(view, '放大照片').disabled, false); assert.equal(wheel(stage, -100).defaultPrevented, true); assert.ok(transform(image).scale > 1);
});

test('翻页重置缩放并丢弃旧图手势、图片加载与观察器回调', () => {
  const view = openViewer([photo, nextPhoto]), oldStage = stageOf(view), oldImage = imageOf(view), oldObserver = observers.at(-1);
  doubleClick(oldStage); pointer(oldStage, 'pointerdown', 500, 350); pointer(oldStage, 'pointermove', 550, 380);
  const oldState = transform(oldImage); button(view, '下一张').click();
  const image = imageOf(view); assert.notEqual(image, oldImage); assert.deepEqual(transform(image), { scale: 1, x: 0, y: 0 });
  assert.equal(oldStage.hasPointerCapture(1), false); assert.equal(oldObserver.targets.size, 0); assert.equal(button(view, '下一张').disabled, true);
  wheel(oldStage, -100); oldImage.dispatchEvent(new Event('load')); oldObserver.trigger(); pointer(oldStage, 'pointermove', 3000, 3000);
  assert.deepEqual(transform(oldImage), oldState); assert.deepEqual(transform(image), { scale: 1, x: 0, y: 0 });
  key(document, 'ArrowLeft'); assert.equal(imageOf(view).getAttribute('src'), '/api/media/first/file'); assert.equal(button(view, '上一张').disabled, true);
});

test('视频原生操作不被照片滚轮拖拽或键盘接管，离开和关闭停止播放', () => {
  const view = openViewer([photo, video, nextPhoto], 1), stage = stageOf(view), player = stage.querySelector('video');
  assert.equal(player.controls, true); assert.equal(player.playsInline, true); assert.equal(player.preload, 'metadata');
  assert.equal(view.body.querySelector('.viewer-zoom-controls'), null); assert.equal(view.dialog.querySelector('.viewer-help').hidden, true);
  assert.equal(wheel(player, -200).defaultPrevented, false); pointer(player, 'pointerdown', 500, 350); assert.equal(captures.has(stage), false);
  assert.equal(key(player, 'ArrowRight').defaultPrevented, false); assert.equal(stageOf(view), stage);
  key(document, 'ArrowRight'); assert.ok(paused.includes(player)); assert.ok(unloaded.includes(player)); assert.equal(player.hasAttribute('src'), false); assert.ok(imageOf(view));
  button(view, '上一张').click(); const secondPlayer = stageOf(view).querySelector('video'); view.close(); assert.ok(paused.includes(secondPlayer)); assert.ok(unloaded.includes(secondPlayer));
});

test('键盘只控制最上层看图弹窗，输入框和可编辑区域保持原按键行为', () => {
  const view = openViewer([photo, nextPhoto]), image = imageOf(view);
  const top = modal('另一份资料', () => {}); assert.equal(key(document, 'ArrowRight').defaultPrevented, false); assert.equal(imageOf(view), image); top.close();
  for (const element of [document.createElement('input'), Object.assign(document.createElement('div'), { contentEditable: 'true' })]) {
    if (element.tagName === 'DIV') element.setAttribute('contenteditable', 'true');
    view.body.append(element); assert.equal(key(element, 'ArrowRight').defaultPrevented, false); assert.equal(imageOf(view), image); element.remove();
  }
  assert.equal(key(document, 'ArrowRight', { ctrlKey: true }).defaultPrevented, false); assert.equal(imageOf(view), image);
  assert.equal(key(document, 'ArrowRight').defaultPrevented, true); assert.notEqual(imageOf(view), image);
});

test('手机单指横滑仍翻页，竖向划动和放大拖动不误翻页', () => {
  const view = openViewer([photo, nextPhoto]); let stage = stageOf(view);
  pointer(stage, 'pointerdown', 650, 350, 1, 'touch'); pointer(stage, 'pointerup', 500, 355, 1, 'touch');
  assert.equal(imageOf(view).getAttribute('src'), '/api/media/second/file'); stage = stageOf(view);
  pointer(stage, 'pointerdown', 500, 350, 2, 'touch'); pointer(stage, 'pointerup', 650, 600, 2, 'touch'); assert.equal(stageOf(view), stage);
  doubleClick(stage); pointer(stage, 'pointerdown', 500, 350, 3, 'touch'); pointer(stage, 'pointermove', 700, 350, 3, 'touch'); pointer(stage, 'pointerup', 700, 350, 3, 'touch'); assert.equal(stageOf(view), stage);
  view.body.querySelector('.viewer-fit').click(); pointer(stage, 'pointerdown', 500, 350, 4, 'touch'); pointer(stage, 'pointerup', 650, 350, 4, 'touch'); assert.equal(imageOf(view).getAttribute('src'), '/api/media/first/file');
});

test('旧版触摸事件仍可横滑，双指缩放后抬手不额外翻页', () => {
  const previous = window.PointerEvent; window.PointerEvent = undefined;
  try {
    const view = openViewer([photo, nextPhoto]); let stage = stageOf(view);
    const touch = (name, points) => { const event = new Event(name, { bubbles: true, cancelable: true }); Object.defineProperty(event, 'changedTouches', { value: points.map(([identifier, clientX, clientY]) => ({ identifier, clientX, clientY })) }); stage.dispatchEvent(event); };
    touch('touchstart', [[1, 650, 350]]); touch('touchend', [[1, 500, 350]]); assert.equal(imageOf(view).getAttribute('src'), '/api/media/second/file');
    stage = stageOf(view); touch('touchstart', [[1, 450, 350], [2, 550, 350]]); touch('touchmove', [[1, 400, 350], [2, 600, 350]]); near(transform(imageOf(view)).scale, 2);
    touch('touchend', [[1, 400, 350], [2, 600, 350]]); assert.equal(stageOf(view), stage); assert.equal(stage.classList.contains('is-grabbing'), false);
  } finally { window.PointerEvent = previous; }
});

test('双指在边缘同时缩小和平移，最终位置只按新边界夹住一次', () => {
  const view = openViewer([photo]), stage = stageOf(view), image = imageOf(view);
  wheel(stage, -Math.log(4) / .002);
  pointer(stage, 'pointerdown', 500, 350); pointer(stage, 'pointermove', 1500, 350); pointer(stage, 'pointerup', 1500, 350);
  assert.deepEqual(transform(image), { scale: 4, x: 1000, y: 0 });
  pointer(stage, 'pointerdown', 300, 350, 10, 'touch'); pointer(stage, 'pointerdown', 700, 350, 11, 'touch');
  pointer(stage, 'pointermove', 500, 350, 11, 'touch');
  assert.deepEqual(transform(image), { scale: 2, x: 400, y: 0 });
  pointer(stage, 'pointerup', 300, 350, 10, 'touch'); pointer(stage, 'pointerup', 500, 350, 11, 'touch');
  assert.equal(imageOf(view), image); assert.equal(captures.get(stage).size, 0);
});

test('关闭看图释放全局事件、观察器和指针捕获，旧节点事件不再改变照片', () => {
  const origin = document.getElementById('origin'); origin.focus();
  const view = openViewer([photo, nextPhoto]), stage = stageOf(view), image = imageOf(view), observer = observers.at(-1);
  doubleClick(stage); pointer(stage, 'pointerdown', 500, 350, 9); const state = transform(image);
  assert.equal(globalListeners.get('keydown').size, 1); assert.equal(globalListeners.get('resize').size, 1);
  view.dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
  assert.equal(view.dialog.isConnected, false); assert.equal(stage.hasPointerCapture(9), false); assert.ok(released.some(([target, id]) => target === stage && id === 9));
  assert.equal(observer.targets.size, 0); assert.equal(globalListeners.get('keydown').size, 0); assert.equal(globalListeners.get('resize').size, 0); assert.equal(document.activeElement, origin);
  wheel(stage, -300); pointer(stage, 'pointermove', 3000, 3000, 9); observer.trigger(); window.dispatchEvent(new Event('resize')); key(document, 'ArrowRight'); assert.deepEqual(transform(image), state);
});
