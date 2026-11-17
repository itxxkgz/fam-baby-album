import { bytes, validUsername } from '../utils.mjs';
import { h, icon, button, badge, heading, field, errorBox, showError, modal, confirm, toast } from '../ui.js';

export async function settingsPage(ctx) {
  const [profile, settings] = await Promise.all([ctx.request('/api/profile'), ctx.request('/api/admin/settings')]);
  if (!ctx.isCurrent()) return;
  ctx.familyName(profile.name);
  let saving = false, dirty = false;
  const name = field('家庭相册名称（所有宝宝共用）', 'text', profile.name || '', { required: true, maxLength: 50 });
  name.input.disabled = !!ctx.child?.archived;
  const maxUpload = field('单个文件最大大小（MB）', 'number', Math.round(settings.max_upload_bytes / 1048576), { min: 1, max: Math.floor(settings.server_max_upload_bytes / 1048576), required: true });
  let savedName = name.input.value, savedLimit = maxUpload.input.value;
  const error = errorBox(), save = button('保存设置', null, { primary: true, attrs: { type: 'submit', disabled: true } });
  const status = h('span', { class: 'muted', role: 'status' }, '设置已保存');
  const markDirty = () => {
    dirty = name.input.value !== savedName || maxUpload.input.value !== savedLimit;
    save.disabled = saving || !dirty; status.textContent = saving ? '正在保存…' : dirty ? '有修改尚未保存' : '设置已保存'; status.className = dirty ? 'unsaved' : 'muted';
  };
  const leave = async () => {
    if (saving) { toast('正在保存，请稍等'); return false; }
    return !dirty || await confirm('还没有保存', '离开后，这次修改不会保留。', '放弃修改');
  };
  leave.dirty = () => dirty || saving; ctx.setLeaveCheck(leave);
  const form = h('form', { class: 'settings-form stack' });
  const familyCard = h('section', { class: 'card stack' }, h('h2', { class: 'card-title' }, icon('home'), '家庭相册'), name.node,
    ctx.child?.archived ? h('p', { class: 'muted' }, '当前宝宝已收起，恢复后可修改家庭相册名称。') : null,
    h('button', { type: 'button', class: 'setting-line setting-link settings-row', onclick: () => { ctx.state.familyTab = 'children'; ctx.navigate('family'); } }, icon('photo'), h('span', {}, '宝宝资料'), h('strong', {}, ctx.child?.name || profile.child_name || '管理宝宝'), icon('arrow')));
  const uploadCard = h('section', { class: 'card stack' }, h('h2', { class: 'card-title' }, icon('upload'), '上传设置'), maxUpload.node,
    h('p', { class: 'muted' }, `服务器最多允许 ${bytes(settings.server_max_upload_bytes)}，对所有宝宝生效。`),
    h('details', {}, h('summary', {}, '照片的拍摄时间会保留吗？'), h('p', { class: 'muted' }, '优先保留原拍摄时间；原文件没有时间信息时使用文件时间。')));
  const cancel = button('取消更改', async () => {
    if (saving || !dirty) return;
    if (!(await confirm('放弃这些修改？', '页面会恢复为已经保存的设置。', '放弃修改'))) return;
    name.input.value = savedName; maxUpload.input.value = savedLimit; error.hidden = true; markDirty();
  });
  form.append(familyCard, uploadCard, error, h('div', { class: 'save-bar' }, status, h('div', { class: 'actions' }, cancel, save)));
  form.addEventListener('input', markDirty); form.addEventListener('change', markDirty);
  const accountUsername = h('strong', {}, ctx.member.username);
  const accountCard = h('section', { class: 'card' }, h('h2', { class: 'card-title' }, icon('shield'), '账号与安全'),
    h('div', { class: 'setting-line' }, icon('people'), h('span', {}, '用户名'), accountUsername, badge('管理员', 'success')),
    h('button', { type: 'button', class: 'setting-line setting-link', onclick: () => accountDialog('username') }, icon('edit'), h('span', {}, '修改用户名'), icon('arrow')),
    h('button', { type: 'button', class: 'setting-line setting-link', onclick: () => accountDialog('password') }, icon('lock'), h('span', {}, '修改密码'), icon('arrow')));
  const aboutCard = h('section', { class: 'card stack' }, h('div', { class: 'settings-about' }, h('img', { src: './assets/fam-brand.png', alt: 'FAM宝贝相册' }), h('div', {}, h('h2', {}, 'FAM宝贝相册'), h('p', { class: 'muted' }, '自己掌管家人的回忆'))),
    h('p', { class: 'muted' }, `服务端版本 ${settings.version || '未提供'}`), h('details', {}, h('summary', {}, '开源与数据归属'), h('p', { class: 'muted' }, '相册可以自行部署，照片保存在自己选择的服务器或云存储中。备份和存放位置可在“存储与备份”管理。')));
  const tabs = h('div', { class: 'tabs page-tabs' }), sections = h('div', { class: 'settings-sections' }, form, accountCard, aboutCard);
  const pages = { general: form, account: accountCard, about: aboutCard };
  function choose(tab) {
    if (!pages[tab]) tab = 'general'; ctx.state.settingsTab = tab;
    for (const [key, page] of Object.entries(pages)) page.hidden = key !== tab;
    tabs.replaceChildren(...[['general', '基础设置'], ['account', '账号与安全'], ['about', '关于相册']].map(([key, title]) => button(title, () => choose(key), { ghost: true, class: key === tab ? 'tab active' : 'tab', attrs: { 'aria-pressed': key === tab } })));
  }
  ctx.container.replaceChildren(heading('相册设置', ''), tabs, sections); choose(ctx.state.settingsTab || 'general');
  form.onsubmit = async event => {
    event.preventDefault(); if (saving || !dirty || !ctx.isCurrent()) return;
    const limit = Number(maxUpload.input.value) * 1048576, familyName = name.input.value.trim();
    if (!familyName) { showError(error, new Error('请填写家庭相册名称。')); return; }
    if (!Number.isSafeInteger(limit) || limit < 1048576 || limit > settings.server_max_upload_bytes) { showError(error, new Error('请填写服务器允许范围内的上传大小。')); return; }
    const controls = [...form.querySelectorAll('input,button')].map(control => [control, control.disabled]);
    saving = true; error.hidden = true; markDirty(); controls.forEach(([control]) => { control.disabled = true; }); let savedProfile = false;
    try {
      if (!ctx.child?.archived && familyName !== savedName.trim()) {
        await ctx.request('/api/profile', { method: 'PUT', body: { name: familyName } });
        if (!ctx.isCurrent()) return;
        savedName = familyName; name.input.value = familyName; savedProfile = true; ctx.familyName(familyName);
      }
      if (limit !== settings.max_upload_bytes) {
        await ctx.request('/api/admin/settings', { method: 'PUT', body: { max_upload_bytes: limit } });
        if (!ctx.isCurrent()) return; settings.max_upload_bytes = limit;
      }
      savedName = name.input.value; savedLimit = maxUpload.input.value; toast('设置已保存');
    } catch (reason) { if (ctx.isCurrent()) showError(error, savedProfile ? new Error(`相册名称已保存，上传设置还未保存。${reason.message}`) : reason); }
    finally { saving = false; controls.forEach(([control, disabled]) => { control.disabled = disabled; }); markDirty(); }
  };
  function accountDialog(kind) {
    let working = false, changed = () => false;
    const guard = async () => { if (working) { toast('正在保存，请稍等'); return false; } return !changed() || await confirm('还没有保存', '离开后，这次修改不会保留。', '放弃修改'); };
    guard.dirty = () => working || changed();
    modal(kind === 'username' ? '修改用户名' : '修改密码', ({ body, footer, close, requestClose, dialog, setBusy }) => {
      const current = field('当前密码', 'password', '', { required: true, autocomplete: 'current-password' });
      const next = field(kind === 'username' ? '新用户名' : '新密码', kind === 'username' ? 'text' : 'password', kind === 'username' ? ctx.member.username : '', { required: true, maxLength: kind === 'username' ? 32 : 200, minLength: kind === 'username' ? 3 : 8, autocomplete: kind === 'username' ? 'username' : 'new-password' });
      const repeat = kind === 'password' ? field('再次输入新密码', 'password', '', { required: true, autocomplete: 'new-password', minLength: 8, maxLength: 200 }) : null;
      const baseline = next.input.value; changed = () => !!current.input.value || next.input.value !== baseline || !!repeat?.input.value;
      const localError = errorBox(), submit = button('保存修改', null, { primary: true, attrs: { type: 'submit' } });
      const accountForm = h('form', { class: 'stack' }, current.node, next.node, repeat?.node,
        h('p', { class: 'muted' }, kind === 'username' ? '3～32 位，可用字母、数字、点、下划线或短横线。' : '至少 8 个字符。'), h('p', { class: 'muted' }, '修改后，其他设备需要重新登录。'), localError, submit);
      body.append(accountForm); footer.append(button('取消', requestClose));
      accountForm.onsubmit = async event => {
        event.preventDefault(); if (working || !ctx.isCurrent()) return; localError.hidden = true;
        if (!current.input.value) { showError(localError, new Error('请填写当前密码。')); return; }
        if (kind === 'username' && !validUsername(next.input.value)) { showError(localError, new Error('用户名格式不正确，首位需为字母或数字。')); return; }
        if (repeat && (next.input.value.length < 8 || next.input.value.length > 200)) { showError(localError, new Error('新密码需要 8～200 个字符。')); return; }
        if (repeat && repeat.input.value !== next.input.value) { showError(localError, new Error('两次输入的新密码不一致。')); return; }
        const data = { current_password: current.input.value, [kind === 'username' ? 'new_username' : 'new_password']: kind === 'username' ? next.input.value.trim() : next.input.value };
        const controls = [...accountForm.querySelectorAll('input,button')]; working = true; setBusy(true); controls.forEach(control => { control.disabled = true; });
        try {
          const result = await ctx.request('/api/admin/account', { body: data, noExpire: true });
          if (!ctx.isCurrent() || !dialog.isConnected) return;
          if (typeof result?.member?.id !== 'string' || !result.member.id || typeof result.member.username !== 'string' || !result.member.username || typeof result.csrf !== 'string' || !result.csrf) throw new Error('保存结果暂时无法确认，请使用新账号信息重新登录。');
          current.input.value = ''; if (repeat) { repeat.input.value = ''; next.input.value = ''; }
          close(); ctx.session(result); accountUsername.textContent = result.member.username; toast('账号信息已更新');
        } catch (reason) { if (dialog.isConnected && ctx.isCurrent()) showError(localError, reason); }
        finally { working = false; setBusy(false); controls.forEach(control => { control.disabled = false; }); }
      };
    }, { beforeClose: guard, isDirty: guard.dirty });
  }
}
