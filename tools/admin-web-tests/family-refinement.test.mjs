import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let JSDOM;
try { ({ JSDOM } = require('jsdom')); }
catch (error) {
  if (!process.env.FAM_JSDOM_PATH) throw error;
  ({ JSDOM } = require(process.env.FAM_JSDOM_PATH));
}
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/admin/', pretendToBeVisual: true });
for (const name of ['window', 'document', 'Node', 'Event', 'HTMLElement', 'HTMLInputElement', 'location', 'navigator']) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value: dom.window[name] });
}
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new window.Event('close')); };
const { familyPage } = await import('../../server/web/views/family.js');
const { childChoices, childPortrait } = await import('../../server/web/children.js');
const { dismissModals, hasPendingModalChanges } = await import('../../server/web/ui.js');

const babies = [
  { id: 'first', name: '姐姐', birthday: '2020-01-02', age: '6岁', counts: { photos: 12, videos: 3 } },
  { id: 'second', name: '弟弟', birthday: '2024-02-03', counts: {} },
  { id: 'old', name: '旧相册', archived: true, counts: { photos: 5 } },
];
const owner = { id: 'owner', name: '爸爸', registered: true, is_family_admin: true };
const aunt = { id: 'aunt', name: '小姨', username: 'aunt', registered: true, family_role: 'contributor', children: [{ child_id: 'first', role: 'viewer' }, { child_id: 'second', role: 'contributor' }] };
function context(state = {}) {
  dismissModals();
  document.body.replaceChildren(Object.assign(document.createElement('main'), { id: 'page' }), Object.assign(document.createElement('div'), { id: 'toasts' }));
  const calls = [], children = structuredClone(babies), members = structuredClone([owner, aunt]);
  const ctx = { container: document.getElementById('page'), member: structuredClone(owner), childId: 'first', child: children[0], state, children, current: true,
    isCurrent: () => ctx.current, setLeaveCheck: leave => { ctx.leave = leave; },
    refresh: () => { ctx.refreshed = true; }, refreshChildren: async id => { ctx.selected = id; ctx.refreshed = true; }, navigate: page => { ctx.destination = page; },
    request: async (path, options = {}) => {
      const call = { path, ...options }; calls.push(call);
      const result = ctx.respond ? await ctx.respond(call) : undefined;
      if (result !== undefined) return result;
      if (path === '/api/children?include_archived=true') return { items: children };
      if (path === '/api/members') return { items: members };
      if (path === '/api/invitations') return { items: [] };
      if (path === '/api/children/first/members') return { items: [{ ...owner, role: 'admin', relationship: '爸爸' }, { ...aunt, role: 'viewer', relationship: '小姨', can_change_role: true, can_edit_relationship: true, can_remove: true }] };
      if (path.startsWith('/api/admin/media?')) return { items: [], total: 0 };
      return {};
    } };
  return { ctx, calls };
}
const lastDialog = () => [...document.querySelectorAll('dialog')].at(-1);
function click(label, scope = document) {
  const node = [...scope.querySelectorAll('button')].find(node => node.getAttribute('aria-label') === label || node.textContent.trim() === label);
  assert.ok(node, '找到操作：' + label); node.click(); return node;
}
function input(label, scope = document) {
  const field = [...scope.querySelectorAll('label.field')].find(node => node.querySelector('span')?.textContent === label);
  assert.ok(field, '找到输入项：' + label); return field.querySelector('input,select,textarea');
}
function change(node, value) { node.value = value; node.dispatchEvent(new window.Event('input', { bubbles: true })); node.dispatchEvent(new window.Event('change', { bubbles: true })); }
function submit(dialog = lastDialog()) { dialog.querySelector('form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true })); }
async function until(predicate) {
  for (let i = 0; i < 100; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  assert.fail('页面没有完成预期更新');
}
function editBaby(name = '弟弟') { click(name + '的更多操作'); click('编辑资料', lastDialog()); return lastDialog(); }
function openAccess() { click('进入账号管理'); click('账号设置', document.querySelector('.account-member-list .member-row:last-child')); click('宝宝访问', lastDialog()); return lastDialog(); }
after(() => { dismissModals(); dom.window.close(); });

test('宝宝列表区分使用中与收起，切换不改其他宝宝资料', async () => {
  const { ctx, calls } = context(); await familyPage(ctx);
  assert.equal(document.querySelectorAll('.family-child-row').length, 2);
  assert.equal(document.querySelector('.archived-child'), null);
  assert.equal(document.querySelector('.family-content').textContent.includes('照片、留言和成长回忆按宝宝分别整理'), false);
  click('切换宝宝'); await until(() => ctx.refreshed); assert.equal(ctx.selected, 'second');
  assert.equal(calls.some(call => call.method === 'PATCH'), false);
  click('收起的宝宝 · 1 位'); assert.equal(document.querySelectorAll('.family-child-row').length, 1);
  click('恢复宝宝'); click('恢复宝宝', lastDialog()); await until(() => calls.some(call => call.method === 'PATCH'));
  assert.deepEqual(calls.find(call => call.method === 'PATCH'), { path: '/api/children/old', method: 'PATCH', body: { archived: false }, childId: 'old' });
});

test('宝宝编辑失败保留填写内容，取消和关闭都先确认未保存内容', async () => {
  const { ctx } = context(); ctx.respond = call => { if (call.method === 'PATCH') throw new Error('暂时无法保存'); };
  await familyPage(ctx); const editor = editBaby(); change(input('宝宝名字', editor), '弟弟的新名字'); change(input('生日（可稍后填写）', editor), '2024-02-04');
  assert.equal(hasPendingModalChanges(), true); submit(editor);
  await until(() => editor.querySelector('.form-error').textContent.includes('暂时无法保存'));
  assert.equal(input('宝宝名字', editor).value, '弟弟的新名字'); assert.equal(input('生日（可稍后填写）', editor).value, '2024-02-04');
  click('取消', editor); await until(() => lastDialog() !== editor); click('取消', lastDialog()); await new Promise(resolve => setTimeout(resolve, 1));
  assert.equal(editor.isConnected, true);
  click('关闭', editor); await until(() => lastDialog() !== editor); click('放弃修改', lastDialog()); await until(() => !editor.isConnected);
  assert.equal(hasPendingModalChanges(), false);
});

test('保存宝宝资料期间阻止重复和离开，迟到结果不覆盖切换后的页面', async () => {
  const { ctx, calls } = context(); let finish;
  ctx.respond = call => call.method === 'PATCH' ? new Promise(resolve => { finish = resolve; }) : undefined;
  await familyPage(ctx); const editor = editBaby(); change(input('宝宝名字', editor), '弟弟的新名字'); submit(editor); submit(editor);
  await until(() => !!finish); assert.equal(calls.filter(call => call.method === 'PATCH').length, 1);
  click('关闭', editor); assert.equal(editor.isConnected, true); assert.equal(await ctx.leave(), false);
  ctx.current = false; ctx.childId = 'second'; finish({ id: 'second' }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(ctx.refreshed, undefined); assert.equal(input('宝宝名字', editor).value, '弟弟的新名字');
  assert.equal(document.getElementById('toasts').textContent.includes('宝宝资料已保存'), false);
});

test('关闭头像选择后不接收迟到照片，编辑草稿保持原样', async () => {
  const { ctx } = context(); let finish;
  ctx.respond = call => call.path.startsWith('/api/admin/media?') ? new Promise(resolve => { finish = resolve; }) : undefined;
  await familyPage(ctx); const editor = editBaby(); click('更换头像', editor); const picker = lastDialog();
  await until(() => !!finish); click('关闭', picker); assert.equal(picker.isConnected, false);
  finish({ items: [{ id: 'photo', thumbnail_url: '/api/media/photo/thumbnail' }], total: 1 }); await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(editor.querySelector('img'), null); assert.equal(ctx.leave.dirty(), false); assert.equal(editor.querySelector('button[aria-label^="打开照片"]'), null);
});

test('添加家人是单字段弹窗，生成后更新待加入列表并解除草稿提示', async () => {
  const { ctx, calls } = context({ familyTab: 'family' });
  ctx.respond = call => call.path === '/api/invitations' && call.body ? { id: 'invite', code: '000123', expires_at: '2026-12-01' } : undefined;
  await familyPage(ctx); assert.equal(document.querySelector('.invitation-panel'), null); click('添加家人');
  const editor = lastDialog(); assert.equal(editor.querySelectorAll('input,select').length, 1); change(input('家人称呼（和宝宝的关系）', editor), '姑姑');
  assert.equal(ctx.leave.dirty(), true); submit(editor); await until(() => editor.querySelector('.invitation-code'));
  assert.equal(editor.querySelector('.invitation-code').textContent, '000123'); assert.equal(ctx.leave.dirty(), false);
  assert.ok(document.querySelector('.waiting-list').textContent.includes('姑姑')); assert.equal(document.querySelector('.waiting-list').open, false);
  assert.deepEqual(calls.find(call => call.body).body, { name: '姑姑', role: 'viewer', expires_hours: 24, child_ids: ['first'] });
  click('完成', editor); assert.equal(document.querySelector('.invitation-panel'), null);
});

test('批量宝宝访问部分成功后重试只保存剩余项目，并保留输入', async () => {
  const { ctx, calls } = context({ familyTab: 'family' }); let fail = true;
  ctx.respond = call => { if (call.method === 'PUT' && call.childId === 'second' && fail) throw new Error('稍后再试'); };
  await familyPage(ctx); const editor = openAccess();
  change(input('姐姐', editor), ''); change(input('弟弟', editor), 'admin'); click('保存宝宝权限', editor);
  await until(() => editor.querySelector('.form-error').textContent.includes('已有 1 位'));
  assert.equal(input('姐姐', editor).value, ''); assert.equal(input('弟弟', editor).value, 'admin'); assert.equal(input('弟弟', editor).disabled, false);
  assert.equal(input('旧相册（已收起）', editor).disabled, true);
  fail = false; click('保存宝宝权限', editor); await until(() => ctx.refreshed);
  assert.deepEqual(calls.filter(call => call.method === 'PUT').map(call => call.childId), ['first', 'second', 'second']);
  assert.equal(calls.some(call => call.path === '/api/members/aunt'), false);
});

test('宝宝头像地址和选择列表保留安全与无障碍边界', () => {
  const bad = childPortrait({ name: '宝宝', avatar_url: 'javascript:alert(1)' }); assert.equal(bad.querySelector('img'), null);
  const portrait = childPortrait({ name: '宝宝', avatar_url: '/api/media/one/thumbnail' }); portrait.querySelector('img').dispatchEvent(new window.Event('error'));
  assert.equal(portrait.querySelector('img'), null); assert.ok(portrait.querySelector('svg'));
  const choices = childChoices(babies, ['second']);
  assert.deepEqual(choices.values(), ['second']); assert.equal(choices.node.querySelector('[aria-label="旧相册"]'), null);
  assert.equal(choices.node.querySelectorAll('input[aria-label]').length, 2); choices.disable(true);
  assert.ok([...choices.node.querySelectorAll('input')].every(input => input.disabled));
});
