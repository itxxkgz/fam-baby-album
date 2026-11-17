import { dateText, duration, safeMediaUrl } from './utils.mjs';
import { createPhotoViewport } from './photo-viewport.js';

export function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false || key === 'value') continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'dataset') Object.assign(node.dataset, value);
    else if (key in node && !key.startsWith('aria')) node[key] = value;
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  if (props?.value !== undefined) node.value = props.value;
  return node;
}
const paths = {
  home: ['M3 11 12 3l9 8', 'M5 10v11h5v-7h4v7h5V10'],
  photo: ['M4 4h16v16H4z', 'M4 16l5-5 4 4 3-3 4 4', 'M8 8h.01'],
  upload: ['M8 18H6a4 4 0 0 1-1-7.9A7 7 0 0 1 18 8a5 5 0 0 1 0 10h-2', 'M12 21V11m-4 4 4-4 4 4'],
  people: ['M16 21v-3a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v3', 'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8', 'M17 4a4 4 0 0 1 0 7m1 3a4 4 0 0 1 4 4v3'],
  storage: ['M3 6c0-4 18-4 18 0s-18 4-18 0Zm0 0v12c0 4 18 4 18 0V6', 'M3 12c0 4 18 4 18 0'],
  settings: ['M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z', 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8'],
  video: ['M3 6h12v12H3z', 'm15 10 6-4v12l-6-4'],
  download: ['M12 3v12m-4-4 4 4 4-4', 'M3 16v5h18v-5'],
  plus: ['M12 4v16M4 12h16'], close: ['m6 6 12 12M6 18 18 6'],
  minus: ['M4 12h16'],
  arrow: ['m9 5 7 7-7 7'], back: ['m15 5-7 7 7 7'],
  check: ['m4 12 5 5L20 6'], clock: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18', 'M12 7v5l4 2'],
  calendar: ['M4 5h16v16H4z', 'M8 2v6m8-6v6M4 11h16'],
  folder: ['M3 6h7l2 3h9v11H3z'], search: ['M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14', 'm15 15 6 6'],
  trash: ['M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7'],
  edit: ['m16 3 5 5-12 12-6 1 1-6z', 'm13 6 5 5'],
  shield: ['m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6z', 'm7 12 3 3 7-7'],
  lock: ['M5 10h14v11H5z', 'M8 10V6a4 4 0 0 1 8 0v4M12 14v3'],
  bell: ['M5 17h14l-2-3V9a5 5 0 0 0-10 0v5z', 'M10 21h4M12 2v2'],
  logout: ['M10 3H3v18h7M8 12h13m-4-4 4 4-4 4'],
  refresh: ['M20 7A9 9 0 0 0 4 7L2 10m0-6v6h6', 'M4 17a9 9 0 0 0 16 0l2-3m0 6v-6h-6'],
  pause: ['M8 4v16m8-16v16'], play: ['m7 3 14 9-14 9z'],
  more: ['M5 12h.01M12 12h.01M19 12h.01'], cloud: ['M6 19a5 5 0 0 1-1-10 7 7 0 0 1 13-1 6 6 0 0 1 0 11z'],
  server: ['M3 3h18v7H3zm0 11h18v7H3z', 'M7 6h.01M7 17h.01M15 6h3m-3 11h3'],
  heart: ['M12 21S1 14 2 7c1-6 8-6 10-1 2-5 9-5 10 1 1 7-10 14-10 14z'],
  info: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18', 'M12 11v6m0-10h.01'],
  copy: ['M8 8h13v13H8z', 'M16 8V3H3v13h5'],
  eye: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z', 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6'],
  'eye-off': ['m3 3 18 18', 'M9 5.4A12 12 0 0 1 12 5c6.5 0 10 7 10 7a19 19 0 0 1-3 3.9', 'M6.2 6.2A22 22 0 0 0 2 12s3.5 7 10 7a13 13 0 0 0 5.8-1.4', 'M10 10a3 3 0 0 0 4 4'],
  filter: ['M3 6h18M3 12h18M3 18h18', 'M8 3v6m8 0v6M8 15v6'],
  book: ['M12 5v16M3 3c4 0 6 0 9 2 3-2 5-2 9-2v16c-4 0-6 0-9 2-3-2-5-2-9-2z'],
  baby: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18', 'M11 3c4 1 3 5 0 4M8 11h.01M16 11h.01M9 15q3 3 6 0'],
  restore: ['M3 4v6h6', 'M3 10a9 9 0 1 1 1 8', 'M12 7v6l4 2'],
  link: ['m9 15 6-6', 'm7 14-2 2a3 3 0 0 0 4 4l4-4a3 3 0 0 0-1-5', 'm17 10 2-2a3 3 0 0 0-4-4l-4 4a3 3 0 0 0 1 5'],
};
export function icon(name, extra = '') {
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [key, value] of Object.entries({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.65', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', class: `icon ${extra}` })) node.setAttribute(key, value);
  for (const d of paths[name] || paths.photo) { const path = document.createElementNS(node.namespaceURI, 'path'); path.setAttribute('d', d); node.append(path); }
  return node;
}
export function art(kind = 'photo', size = '') {
  return h('div', { class: `warm-art art-${kind} ${size}`, 'aria-hidden': 'true' },
    h('span', { class: 'art-glow' }), h('span', { class: 'art-back' }, icon(kind === 'people' ? 'heart' : 'photo')),
    h('span', { class: 'art-main' }, icon(kind)), h('i', { class: 'spark spark-a' }), h('i', { class: 'spark spark-b' }), h('i', { class: 'spark spark-c' }));
}
export function button(label, callback, options = {}) {
  return h('button', { type: 'button', class: `button ${options.primary ? 'primary' : options.ghost ? 'ghost' : ''} ${options.danger ? 'danger' : ''} ${options.class || ''}`, onclick: callback, ...options.attrs }, options.icon ? icon(options.icon) : null, label);
}
export function iconButton(name, label, callback) { return h('button', { type: 'button', class: 'icon-button', 'aria-label': label, title: label, onclick: callback }, icon(name)); }
export function badge(text, tone = '') { return h('span', { class: `badge ${tone}` }, text); }
export function avatar(name, index = 0) { return h('span', { class: `avatar avatar-${index % 4}`, 'aria-hidden': 'true' }, h('span', { class: 'avatar-hair' }), h('span', { class: 'avatar-face' }, h('i'), h('i')), h('span', { class: 'avatar-shirt' })); }
export function heading(title, subtitle, actions = []) { return h('div', { class: 'page-heading' }, h('div', {}, h('h1', {}, title), subtitle ? h('p', {}, subtitle) : null), h('div', { class: 'actions' }, actions)); }
export function empty(title, message, action = null, kind = 'photo') { return h('div', { class: 'empty' }, art(kind), h('h3', {}, title), h('p', {}, message), action); }
export function note(message, tone = '') { return h('div', { class: `note ${tone}` }, icon('info'), h('span', {}, message)); }
export function loading(message = '正在整理相册内容…') { return h('div', { class: 'loading', role: 'status' }, h('span', { class: 'spinner' }), message); }
export function progress(value, label = '') { const v = Math.max(0, Math.min(100, value || 0)); return h('div', { class: 'progress', role: 'progressbar', 'aria-valuenow': v, 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': label || '完成进度' }, h('span', { style: `width:${v}%` })); }
export function field(label, type = 'text', value = '', options = {}) {
  const id = `field-${++fieldNumber}`;
  const input = type === 'textarea' ? h('textarea', { id, rows: 3, value, ...options }) : h('input', { id, type, value, ...options });
  let control = input;
  if (type === 'password') {
    const reveal = iconButton('eye', `显示${label}`, () => {
      if (input.disabled) return;
      const start = input.selectionStart, end = input.selectionEnd;
      const visible = input.type === 'password';
      input.type = visible ? 'text' : 'password';
      reveal.replaceChildren(icon(visible ? 'eye-off' : 'eye'));
      reveal.setAttribute('aria-label', `${visible ? '隐藏' : '显示'}${label}`);
      reveal.title = `${visible ? '隐藏' : '显示'}${label}`;
      reveal.setAttribute('aria-pressed', String(visible));
      input.focus();
      if (start !== null && end !== null) input.setSelectionRange(start, end);
    });
    reveal.setAttribute('aria-controls', id);
    reveal.setAttribute('aria-pressed', 'false');
    control = h('span', { class: 'secret-field' }, input, reveal);
  }
  return { input, node: h('label', { class: 'field', htmlFor: id }, h('span', {}, label), control) };
}
let fieldNumber = 0;
export function selectField(label, choices, value, options = {}) {
  const id = `field-${++fieldNumber}`;
  const input = h('select', { id, ...options, value }, choices.map(choice => h('option', { value: choice[0] }, choice[1])));
  return { input, node: h('label', { class: 'field', htmlFor: id }, h('span', {}, label), input) };
}
export function errorBox() { return h('div', { class: 'form-error', role: 'alert', hidden: true }); }
export function showError(target, error) { target.hidden = false; target.textContent = error?.message || '操作未完成，请稍后重试。'; }
export function toast(message, error = false) {
  const node = h('div', { class: `toast ${error ? 'is-error' : ''}` }, icon(error ? 'info' : 'check'), h('span', {}, message));
  document.getElementById('toasts').append(node); setTimeout(() => node.remove(), error ? 6500 : 4200);
}
const openModals = new Set();
export function modal(title, build, options = {}) {
  const previous = document.activeElement;
  const body = h('div', { class: 'dialog-body' }), footer = h('div', { class: 'dialog-footer' });
  const dialog = h('dialog', { class: `dialog ${options.wide ? 'wide' : ''} ${options.drawer ? 'drawer' : ''} ${options.class || ''}`, 'aria-label': title });
  let closed = false, saving = false, closing = null;
  const isBusy = () => saving || !!dialog.querySelector('[aria-busy="true"]');
  const close = () => {
    if (closed) return;
    closed = true; openModals.delete(view);
    dialog.close(); dialog.remove();
    if (previous?.isConnected) previous.focus?.();
    options.onClose?.();
  };
  const requestClose = () => {
    if (closed) return Promise.resolve(true);
    if (closing) return closing;
    if (isBusy()) { toast('正在处理，请稍等。'); return Promise.resolve(false); }
    if (!options.beforeClose) { close(); return Promise.resolve(true); }
    const attempt = (async () => {
      try {
        if (await options.beforeClose() === false) return closed;
        if (!closed && isBusy()) return false;
        close(); return true;
      } catch {
        toast('暂时无法离开，请稍后重试。', true); return false;
      }
    })();
    closing = attempt;
    attempt.finally(() => { if (closing === attempt) closing = null; });
    return attempt;
  };
  const closeButton = iconButton('close', '关闭', requestClose);
  const setBusy = value => {
    saving = !!value;
    closeButton.disabled = saving;
    dialog.toggleAttribute('data-busy', saving);
  };
  const view = { body, footer, close, requestClose, setBusy, dialog,
    pending: () => isBusy() || !!options.isDirty?.() };
  dialog.append(h('div', { class: 'dialog-heading' }, h('h2', {}, title), closeButton), body, footer);
  dialog.addEventListener('cancel', event => { event.preventDefault(); requestClose(); });
  dialog.addEventListener('click', event => { if (event.target === dialog) { const r = dialog.getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) requestClose(); } });
  openModals.add(view); document.body.append(dialog);
  try { build(view); if (!closed) dialog.showModal(); }
  catch (error) { close(); throw error; }
  return view;
}
export function dismissModals() { for (const view of [...openModals].reverse()) view.close(); }
export async function closeModals() {
  for (const view of [...openModals].reverse()) if (!(await view.requestClose())) return false;
  return true;
}
export function hasPendingModalChanges() { return [...openModals].some(view => view.dialog.isConnected && view.pending()); }
export function confirm(title, message, action = '确认', danger = false) {
  return new Promise(resolve => { let settled = false; modal(title, ({ body, footer, close }) => {
    body.append(h('p', {}, message));
    footer.append(button('取消', close), button(action, () => { settled = true; resolve(true); close(); }, { primary: true, danger }));
  }, { onClose: () => { if (!settled) resolve(false); } }); });
}
export async function busy(node, action) {
  const label = node.textContent; node.disabled = true; node.setAttribute('aria-busy', 'true');
  try { return await action(); } finally { if (node.isConnected) { node.disabled = false; node.removeAttribute('aria-busy'); if (!node.textContent) node.textContent = label; } }
}
export function photo(item, callback, options = {}) {
  const image = h('img', { src: safeMediaUrl(item.thumbnail_url), alt: item.description || item.filename || '相册照片', loading: 'lazy' });
  const box = h('button', { type: 'button', class: `photo ${options.class || ''}`, onclick: callback, 'aria-label': `打开${item.kind === 'video' ? '视频' : '照片'}：${item.description || item.date_label || ''}` }, image);
  image.addEventListener('error', () => { image.hidden = true; if (!box.querySelector('.image-failed')) box.append(h('span', { class: 'image-failed' }, icon('photo'), '预览暂不可用')); });
  if (item.kind === 'video') box.append(h('span', { class: 'video-duration' }, icon('play'), duration(item.duration)));
  return box;
}
export function openViewer(items, initial = 0) {
  if (!items.length) return;
  let index = Math.max(0, Math.min(items.length - 1, Number.isInteger(initial) ? initial : 0)), viewport = null, view;
  const stopVideo = () => {
    view?.body.querySelectorAll('video').forEach(video => {
      video.pause(); video.removeAttribute('src'); video.load();
    });
  };
  view = modal('回看照片与视频', ({ body, footer }) => { body.replaceChildren(); footer.remove(); }, {
    class: 'viewer', wide: true,
    onClose: () => { viewport?.dispose(); viewport = null; stopVideo(); document.removeEventListener('keydown', key); },
  });
  const hint = h('span', { class: 'viewer-help' }, '滚轮缩放 · 放大后拖动');
  view.dialog.querySelector('.dialog-heading h2').after(hint);
  const render = () => {
    viewport?.dispose(); viewport = null; stopVideo();
    const item = items[index];
    const video = item.kind === 'video';
    hint.hidden = video;
    const content = video ? h('video', { src: safeMediaUrl(item.file_url), controls: true, playsInline: true, preload: 'metadata' }) : h('img', { src: safeMediaUrl(item.file_url), alt: item.description || item.date_label || '相册照片', class: 'viewer-photo', draggable: false });
    const stage = h('div', { class: `viewer-stage ${video ? 'viewer-video-stage' : 'viewer-photo-stage'}`,
      ...(video ? {} : { tabIndex: 0, 'aria-label': '照片，滚轮缩放，放大后拖动，双击放大或恢复' }) }, content);
    const out = iconButton('minus', '缩小照片', () => viewport?.zoomOut());
    const into = iconButton('plus', '放大照片', () => viewport?.zoomIn());
    const fit = button('适应窗口', () => viewport?.reset(), { ghost: true, class: 'viewer-fit' });
    const ratio = h('span', { class: 'viewer-scale', title: '相对整张显示的放大倍数' }, '1 倍');
    const controls = h('section', { class: 'viewer-zoom-controls', role: 'group', 'aria-label': '照片缩放' }, out, ratio, into, fit);
    view.body.replaceChildren(stage,
      h('div', { class: 'viewer-meta' }, h('div', { class: 'viewer-caption' }, h('strong', {}, item.age || item.date_label), h('p', {}, `${dateText(item.captured_at, true)} · ${item.uploader_name || '家人'}上传`), item.description ? h('p', {}, item.description) : null), video ? null : controls, h('span', { class: 'viewer-counter' }, `${index + 1} / ${items.length}`), h('a', { class: 'button', href: safeMediaUrl(item.file_url), download: item.filename || '相册原文件' }, icon('download'), '保存原文件')),
      h('div', { class: 'viewer-arrows' }, iconButton('back', '上一张', () => move(-1)), iconButton('arrow', '下一张', () => move(1))));
    if (!video) viewport = createPhotoViewport(stage, content, {
      width: item.width, height: item.height, onSwipe: move,
      onChange: state => {
        ratio.textContent = `${Number(state.scale.toFixed(1))} 倍`;
        out.disabled = !state.ready || state.scale <= 1.001;
        into.disabled = !state.ready || state.scale >= 7.999;
        fit.disabled = !state.ready || (state.scale <= 1.001 && !state.x && !state.y);
      },
    });
    view.body.querySelectorAll('.viewer-arrows button')[0].disabled = index === 0;
    view.body.querySelectorAll('.viewer-arrows button')[1].disabled = index === items.length - 1;
  };
  const move = direction => { const next = index + direction; if (view.dialog.isConnected && next >= 0 && next < items.length) { index = next; render(); } };
  function key(event) {
    const dialogs = [...document.querySelectorAll('dialog[open]')];
    if (dialogs[dialogs.length - 1] !== view.dialog || event.ctrlKey || event.metaKey || event.altKey
      || ['VIDEO', 'INPUT', 'TEXTAREA', 'SELECT'].includes(event.target?.tagName)
      || event.target?.closest?.('[contenteditable]:not([contenteditable="false"]),[role="textbox"]')) return;
    if (event.key === 'ArrowLeft') { event.preventDefault(); move(-1); }
    else if (event.key === 'ArrowRight') { event.preventDefault(); move(1); }
    else if (viewport && (event.key === '+' || event.key === '=')) { event.preventDefault(); viewport.zoomIn(); }
    else if (viewport && event.key === '-') { event.preventDefault(); viewport.zoomOut(); }
    else if (viewport && event.key === '0') { event.preventDefault(); viewport.reset(); }
  }
  document.addEventListener('keydown', key); render();
  return view;
}
export function validateDateRange(from, to) { return !from || !to || from <= to; }
