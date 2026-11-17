import { api, setSession, setChild, expiredHandler } from './api.js';
import { PAGES, pageFromHash, validUsername, dateText, isFamilyAdmin } from './utils.mjs';
import { h, icon, art, button, iconButton, avatar, field, note, loading, errorBox, showError, toast, confirm, busy, modal, closeModals, dismissModals, hasPendingModalChanges } from './ui.js';
import { overviewPage, mediaPage } from './views/media.js';
import { importsPage } from './views/imports.js';
import { familyPage } from './views/family.js';
import { storagePage } from './views/storage.js';
import { settingsPage } from './views/settings.js';

const root = document.getElementById('app');
let session = null, generation = 0, controller = null, cleanup = null, leaveCheck = null, activePage = '', routeSerial = 0;
const state = { media: { kind: 'all', deleted: false }, imports: {}, children: [], childId: '' };
const renders = { overview: overviewPage, media: mediaPage, imports: importsPage, family: familyPage, storage: storagePage, settings: settingsPage };
const brand = () => h('div', { class: 'brand' }, h('img', { src: './assets/fam-brand.png', alt: 'FAM' }), h('div', {}, h('strong', {}, 'FAM宝贝相册'), h('span', {}, '管理后台')));

function resetPage() { generation++; controller?.abort(); controller = null; cleanup?.(); cleanup = null; leaveCheck = null; dismissModals(); }
function acceptSession(result) {
  if (result && (!result.member?.id || !validUsername(result.member?.username) || !result.csrf || !isFamilyAdmin(result.member)))
    throw new Error('登录信息暂时无法确认，请重新登录。');
  session = result; setSession(result);
}
function expires() {
  if (!session) return;
  resetPage(); acceptSession(null); toast('登录已失效，请重新登录。', true); loginPage();
}
expiredHandler(expires);

async function loginPage() {
  resetPage(); activePage = '';
  root.replaceChildren(h('div', { class: 'auth-screen' }, h('div', { class: 'auth-brand' }, brand()), h('div', { class: 'auth-card' }, loading('正在连接相册…'))));
  const revision = generation;
  let status;
  try { status = await api('/api/status', { noExpire: true }); }
  catch (error) { if (revision === generation) root.querySelector('.auth-card').replaceChildren(note(error.message, 'error'), button('重新连接', loginPage, { primary: true })); return; }
  if (revision !== generation) return;
  const card = root.querySelector('.auth-card');
  const form = h('form', { class: 'stack' }), error = errorBox();
  const username = field('用户名', 'text', status.default_credentials ? 'admin' : '', { required: true, autocomplete: 'username', maxLength: 32 });
  const password = field('密码', 'password', '', { required: true, autocomplete: status.setup_required ? 'new-password' : 'current-password', maxLength: 200 });
  const code = status.setup_required ? field('首次设置口令', 'password', '', { required: true, autocomplete: 'off' }) : null;
  const name = status.setup_required ? field('家人称呼', 'text', '相册管理员', { required: true, maxLength: 40 }) : null;
  const submit = button(status.setup_required ? '创建管理员并进入' : '进入管理后台', null, { primary: true, class: 'full', attrs: { type: 'submit' } });
  form.append(username.node, password.node);
  if (code) form.append(code.node, name.node, note('使用服务器的首次设置口令认领已有相册，设置自己的密码，至少8个字符。'));
  else if (status.default_credentials) form.append(note('首次使用的用户名和密码都是 admin。登录后，请先设置自己的新密码。'));
  form.append(error, submit);
  form.onsubmit = event => { event.preventDefault(); busy(submit, async () => {
    error.hidden = true;
    try {
      if (status.setup_required) {
        if (!validUsername(username.input.value) || password.input.value.length < 8) throw new Error('请填写有效的用户名，并设置至少8个字符的密码。');
        await api('/api/setup', { body: { setup_code: code.input.value, username: username.input.value.trim(), password: password.input.value, name: name.input.value.trim() }, noExpire: true });
      }
      const result = await api('/api/admin/session', { body: { username: username.input.value.trim(), password: password.input.value }, noExpire: true });
      acceptSession(result); password.input.value = ''; startSession();
    } catch (reason) { showError(error, reason); }
  }); };
  card.replaceChildren(art('home'), h('h1', {}, status.setup_required ? '建立自己的家庭相册' : '欢迎回到家庭相册'), h('p', { class: 'muted auth-intro' }, '在电脑上整理回忆，也把家人的记录照顾好。'), form,
    h('p', { class: 'auth-footnote' }, '仅家庭管理员可进入 · 照片由自己的服务器保存'));
  (username.input.value ? password.input : username.input).focus();
}
function requiredPasswordPage() {
  resetPage();
  const form = h('form', { class: 'stack' }), error = errorBox();
  const next = field('新密码', 'password', '', { required: true, minLength: 8, maxLength: 200, autocomplete: 'new-password' });
  const repeat = field('再次输入新密码', 'password', '', { required: true, minLength: 8, maxLength: 200, autocomplete: 'new-password' });
  const submit = button('保存密码并进入相册', null, { primary: true, class: 'full', attrs: { type: 'submit' } });
  form.append(next.node, repeat.node, error, submit, button('退出登录', signOut, { ghost: true }));
  form.onsubmit = event => { event.preventDefault(); busy(submit, async () => {
    error.hidden = true;
    try {
      if (next.input.value !== repeat.input.value) throw new Error('两次输入的新密码不一致。');
      const result = await api('/api/admin/account', { body: { current_password: 'admin', new_password: next.input.value } });
      acceptSession(result); next.input.value = ''; repeat.input.value = ''; startSession();
    } catch (reason) { showError(error, reason); }
  }); };
  root.replaceChildren(h('div', { class: 'auth-screen' }, h('div', { class: 'auth-brand' }, brand()), h('div', { class: 'auth-card' }, art('shield'), h('h1', {}, '先设置密码，再开始记录'), h('p', { class: 'muted auth-intro' }, '初始密码只用于第一次登录。设置一个自己好记的密码，至少8个字符。'), form)));
  next.input.focus();
}
async function signOut() {
  const revision = generation, identity = session;
  const current = () => revision === generation && session === identity;
  if (leaveCheck && !(await leaveCheck())) return;
  if (!current() || !(await closeModals()) || !current()) return;
  try { await api('/api/admin/session', { method: 'DELETE', noExpire: true }); } catch (error) { toast(error.message, true); return; }
  if (!current()) return;
  resetPage(); acceptSession(null); loginPage();
}
async function startSession() {
  if (session?.password_change_required || session?.member?.password_change_required) { requiredPasswordPage(); return; }
  if (!isFamilyAdmin(session?.member)) { expires(); return; }
  resetPage(); const revision = generation;
  root.replaceChildren(h('div', { class: 'auth-screen' }, loading('正在打开宝宝的相册…')));
  try {
    await loadChildren(undefined, () => revision === generation); if (revision !== generation) return;
    if (!state.children.length) history.replaceState(null, '', '#family');
    activePage = ''; renderRoute();
  } catch (error) { if (revision === generation) root.replaceChildren(h('div', { class: 'auth-screen' }, h('div', { class: 'auth-card stack' }, note(error.message, 'error'), button('重新连接', startSession, { primary: true }), button('退出登录', signOut)))); }
}
async function loadChildren(preferredId, current = () => true) {
  const result = await api('/api/children?include_archived=true', { childId: '' });
  if (!current()) return;
  state.children = result.items || [];
  let remembered = ''; try { remembered = sessionStorage.getItem(`fam-child-${session.member.id}`) || ''; } catch {}
  const chosen = [preferredId, state.childId, remembered, result.selected_child_id, result.default_child_id].find(id => id && state.children.some(child => child.id === id));
  const previousChild = state.childId;
  state.childId = chosen || state.children.find(child => !child.archived)?.id || state.children[0]?.id || '';
  if (previousChild !== state.childId) state.media = { kind: 'all', deleted: false };
  setChild(state.childId); try { sessionStorage.setItem(`fam-child-${session.member.id}`, state.childId); } catch {}
}
async function switchChild(childId, input) {
  if (childId === state.childId) return;
  const revision = generation, previous = state.childId;
  input.disabled = true;
  if (leaveCheck && !(await leaveCheck())) { input.value = previous; input.disabled = false; return; }
  if (!(await closeModals())) { input.value = previous; input.disabled = false; return; }
  if (revision !== generation) return;
  state.childId = childId; setChild(childId); state.media = { kind: 'all', deleted: false }; leaveCheck = null;
  try { sessionStorage.setItem(`fam-child-${session.member.id}`, childId); } catch {}
  renderRoute(true);
}
function shell(page) {
  const navigation = h('nav', { class: 'navigation', 'aria-label': '后台功能' }, PAGES.map(([key, label, glyph]) => h('a', { href: `#${key}`, class: key === page ? 'active' : '', 'aria-current': key === page ? 'page' : null }, icon(glyph), h('span', {}, label))));
  const familyName = h('strong', { id: 'family-name' }, '我的家庭相册');
  const side = h('aside', { class: 'sidebar', id: 'admin-navigation' }, brand(), navigation,
    h('div', { class: 'sidebar-bottom' }, h('div', { class: 'family-tile' }, art('photo', 'small'), familyName), h('div', { class: 'identity' }, avatar(session.member.name), h('div', {}, h('strong', {}, session.member.name || '相册管理员'), h('span', {}, '管理员')), iconButton('logout', '退出登录', signOut))));
  const content = h('main', { id: 'page-content', class: 'page-content', tabIndex: -1 });
  const childSelect = h('select', { value: state.childId, 'aria-label': '当前宝宝', onchange: event => switchChild(event.target.value, event.target) }, state.children.map(child => h('option', { value: child.id }, `${child.name}${child.archived ? ' · 已收起' : ''}`)));
  const childSwitch = state.children.length ? h('label', { class: 'child-switch' }, icon('baby'), h('span', {}, '当前宝宝'), childSelect) : button('添加宝宝', () => { state.familyTab = 'children'; navigate('family'); }, { icon: 'plus' });
  const navToggle = iconButton('more', '展开或收起导航', () => {
    const expanded = document.querySelector('.shell')?.classList.toggle('nav-open');
    navToggle.setAttribute('aria-expanded', String(!!expanded));
  });
  navToggle.setAttribute('aria-controls', 'admin-navigation');
  navToggle.setAttribute('aria-expanded', 'false');
  const navBackdrop = h('button', { class: 'nav-backdrop', type: 'button', 'aria-label': '收起导航', onclick: () => {
    document.querySelector('.shell')?.classList.remove('nav-open'); navToggle.setAttribute('aria-expanded', 'false'); navToggle.focus();
  } });
  const top = h('header', { class: 'topbar' }, h('div', { class: 'top-title' }, navToggle, childSwitch),
    h('div', { class: 'top-actions' }, button('查看相册', () => navigate('media'), { icon: 'photo', ghost: true }), iconButton('bell', '查看运行记录', recordsDialog), h('span', { class: 'top-avatar', title: session.member.name }, avatar(session.member.name))));
  root.replaceChildren(h('div', { class: 'shell' }, navBackdrop, side, h('div', { class: 'workspace' }, top, content)));
  return content;
}
export function navigate(page) { if (pageFromHash(location.hash) === page && activePage === page) renderRoute(true); else location.hash = page; }
async function renderRoute(force = false) {
  if (!session) return;
  if (session.password_change_required || session.member?.password_change_required) { requiredPasswordPage(); return; }
  const next = pageFromHash(location.hash), serial = ++routeSerial, startedAt = generation, identity = session;
  const current = () => serial === routeSerial && startedAt === generation && session === identity;
  if (!force && next === activePage) return;
  const allowed = !leaveCheck || await leaveCheck();
  if (!current()) return;
  if (!allowed) { history.replaceState(null, '', `#${activePage}`); return; }
  const closed = await closeModals();
  if (!current()) return;
  if (!closed) { history.replaceState(null, '', `#${activePage}`); return; }
  resetPage(); activePage = next;
  const revision = generation, content = shell(next), pageController = new AbortController(), childId = state.childId; controller = pageController;
  const ctx = {
    container: content, member: session.member, state, navigate, childId, child: state.children.find(child => child.id === childId), children: state.children,
    isCurrent: () => revision === generation && content.isConnected,
    request: (path, options = {}) => api(path, { childId, ...options, signal: options.signal || pageController.signal }),
    refresh: () => { if (revision === generation) renderRoute(true); },
    session: result => { acceptSession(result); ctx.member = result.member; },
    refreshChildren: async preferredId => { await loadChildren(preferredId, () => revision === generation); if (revision === generation) renderRoute(true); },
    setLeaveCheck: check => { if (revision === generation) leaveCheck = check; },
    familyName: name => { const node = document.getElementById('family-name'); if (node && revision === generation) node.textContent = name || '我的家庭相册'; },
  };
  content.append(loading());
  try { const result = await renders[next](ctx); if (ctx.isCurrent()) { cleanup = typeof result === 'function' ? result : null; } else if (typeof result === 'function') result(); }
  catch (error) { if (error.name !== 'AbortError' && ctx.isCurrent()) content.replaceChildren(note(error.message, 'error'), button('重新加载', () => renderRoute(true), { primary: true, icon: 'refresh' })); }
}
async function recordsDialog() {
  const view = modal('运行记录', ({ body }) => body.append(loading()), { drawer: true });
  try {
    const result = await api('/api/admin/records');
    if (!view.dialog.isConnected) return;
    view.body.replaceChildren(...(result.items?.length ? result.items.map(item => h('div', { class: 'record' }, icon(item.level === 'error' ? 'info' : 'clock'), h('div', {}, h('strong', {}, item.title), h('p', {}, item.message), h('small', {}, dateText(item.created_at, true))))) : [note('目前没有需要处理的运行记录。')]));
  } catch (error) { if (view.dialog.isConnected) view.body.replaceChildren(note(error.message, 'error')); }
}
window.addEventListener('hashchange', () => renderRoute());
window.addEventListener('beforeunload', event => { if (leaveCheck?.dirty?.() || hasPendingModalChanges()) { event.preventDefault(); event.returnValue = ''; } });
try { acceptSession(await api('/api/admin/session', { noExpire: true })); startSession(); }
catch { await loginPage(); }
