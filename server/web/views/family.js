import { dateText, isFamilyAdmin } from '../utils.mjs';
import { childSummary, childPortrait } from '../children.js';
import { h, icon, art, avatar, button, iconButton, badge, heading, empty, note, loading, photo, field, selectField, errorBox, showError, modal, confirm, busy, toast } from '../ui.js';

const roles = [['viewer', '仅查看'], ['contributor', '可上传'], ['admin', '宝宝管理员']];
export async function familyPage(ctx) {
  const childId = ctx.childId || '';
  const [memberData, inviteData, childData, scopedData] = await Promise.all([ctx.request('/api/members'), ctx.request('/api/invitations'), ctx.request('/api/children?include_archived=true', { childId: '' }), childId ? ctx.request(`/api/children/${encodeURIComponent(childId)}/members`, { childId }) : Promise.resolve({ items: [] })]);
  if (!ctx.isCurrent() || (ctx.childId || '') !== childId) return;
  const members = memberData.items || [], invitations = inviteData.items || [], children = childData.items || [], scopedMembers = (scopedData.items || []).filter(member => member.registered);
  const child = children.find(item => item.id === childId) || ctx.child, childName = child?.name || '当前宝宝';
  const current = node => ctx.isCurrent() && (ctx.childId || '') === childId && (!node || node.isConnected);
  const canInvite = !!childId && !child?.archived;
  const relation = member => member.relationship || member.name || '家人';
  const familyAdminTarget = member => isFamilyAdmin(members.find(item => item.id === member.id) || member);
  const memberPath = member => `/api/children/${encodeURIComponent(childId)}/members/${encodeURIComponent(member.id)}`;
  const pendingMembers = new Set(), pendingRoles = new Map(), memberObservers = new Map();
  function observeMember(member, callback) { if (!memberObservers.has(member.id)) memberObservers.set(member.id, new Set()); const observers = memberObservers.get(member.id); observers.add(callback); return () => observers.delete(callback); }
  function notifyMember(member) { if (current()) for (const update of memberObservers.get(member.id) || []) update(); }
  const drafts = new Set();
  function editModal(title, build, options = {}) {
    const draft = { dirty: () => false, busy: () => false, dialog: null, close: null };
    async function mayClose() {
      if (!current(draft.dialog)) return true;
      if (draft.busy() || draft.dialog?.querySelector('[aria-busy="true"]')) { toast('正在保存，请稍等。'); return false; }
      return !draft.dirty() || confirm('放弃尚未保存的内容？', '关闭后，这次填写的内容不会保留。', '放弃修改');
    }
    drafts.add(draft);
    return modal(title, controls => {
      draft.dialog = controls.dialog; draft.close = controls.close; draft.mayClose = mayClose;
      build({ ...controls, draft });
    }, { ...options, beforeClose: mayClose, isDirty: () => draft.dirty() || draft.busy(), onClose: () => { drafts.delete(draft); options.onClose?.(); } });
  }
  const leave = async () => {
    for (const draft of [...drafts].reverse()) if (draft.dialog?.isConnected && !(await draft.mayClose())) return false;
    for (const draft of [...drafts]) draft.close?.();
    return true;
  };
  leave.dirty = () => [...drafts].some(draft => draft.dialog?.isConnected && (draft.dirty() || draft.busy()));
  ctx.setLeaveCheck?.(leave);

  let tab = ctx.state.inviteOpen ? 'family' : ctx.state.familyTab || 'children', showArchived = false;
  const content = h('div', { class: 'family-content' }), tabs = h('div', { class: 'tabs page-tabs' }), pageActions = h('div', { class: 'actions' });
  ctx.container.replaceChildren(heading('宝宝与家人', '', [pageActions]), tabs, content);
  function choose(value) {
    tab = value; ctx.state.familyTab = value;
    tabs.replaceChildren(...[['children', '宝宝管理'], ['family', '家人权限']].map(([key, label]) => button(label, () => choose(key), { ghost: true, class: key === tab ? 'tab active' : 'tab', attrs: { 'aria-pressed': key === tab } })));
    pageActions.replaceChildren(button('使用邀请码', joinDialog, { ghost: true, icon: 'people' }), tab === 'children'
      ? button('添加宝宝', () => childDialog(), { primary: true, icon: 'plus' })
      : button('添加家人', inviteDialog, { primary: true, icon: 'plus', attrs: { disabled: !canInvite } }));
    if (tab === 'children') renderChildren(); else renderFamily();
  }
  async function refreshChildren(preferred) { if (ctx.refreshChildren) await ctx.refreshChildren(preferred); else ctx.refresh(); }
  function renderChildren() {
    const visible = children.filter(child => !!child.archived === showArchived), archived = children.filter(child => child.archived).length;
    const list = h('section', { class: 'card family-child-list' });
    list.append(h('div', { class: 'section-heading' }, h('h2', {}, showArchived ? '收起的宝宝' : '宝宝相册'), h('span', { class: 'muted' }, `${visible.length} 位`)));
    for (const child of visible) {
      const row = h('article', { class: `family-child-row ${child.archived ? 'archived-child' : ''}` });
      const open = button(child.archived ? '恢复宝宝' : child.id === ctx.childId ? '查看相册' : '切换宝宝', async event => {
        if (!current(row)) return;
        if (child.archived) { archiveChild(child); return; }
        if (child.id === ctx.childId) { ctx.navigate('media'); return; }
        await busy(event.currentTarget, async () => { try { await refreshChildren(child.id); } catch (reason) { if (current(row)) toast(reason.message, true); } });
      }, { ghost: true });
      row.append(childPortrait(child), h('div', { class: 'family-child-copy' }, h('h3', {}, child.name, child.id === ctx.childId ? badge('正在看', 'warm') : null), h('p', { class: 'muted' }, child.age || (child.birthday ? `${dateText(child.birthday)}出生` : '生日待填写')), h('small', { class: 'muted' }, childSummary(child))), open);
      if (!child.archived) row.append(iconButton('more', `${child.name}的更多操作`, () => childMore(child)));
      list.append(row);
    }
    if (!visible.length) list.append(empty(showArchived ? '没有收起的宝宝' : '还没有宝宝相册', showArchived ? '收起的相册会保留在这里。' : '填好名字，就可以开始记录成长。', showArchived ? null : button('添加第一位宝宝', () => childDialog(), { primary: true })));
    content.replaceChildren(list, h('div', { class: 'family-secondary-nav' }, button(showArchived ? '返回宝宝列表' : `收起的宝宝${archived ? ` · ${archived} 位` : ''}`, () => { showArchived = !showArchived; renderChildren(); }, { ghost: true, icon: showArchived ? 'back' : 'folder' })));
  }
  function childMore(child) {
    if (!current()) return;
    modal(child.name, ({ body, close }) => {
      body.append(h('div', { class: 'family-detail-identity' }, childPortrait(child), h('div', {}, h('h3', {}, child.name), h('p', { class: 'muted' }, childSummary(child)))),
        entry('edit', '编辑资料', '名字、生日和头像', () => { close(); childDialog(child); }),
        entry('folder', '收起宝宝', '照片和成长记录都会保留', () => { close(); archiveChild(child); }));
    });
  }
  function entry(name, title, description, action) {
    return h('button', { type: 'button', class: 'family-entry', 'aria-label': title, onclick: action }, icon(name), h('span', {}, h('strong', {}, title), description ? h('small', { class: 'muted' }, description) : null), icon('arrow'));
  }
  function childDialog(child = null) {
    if (!current()) return;
    editModal(child ? '宝宝资料' : '添加宝宝', ({ body, footer, close, requestClose, dialog, draft }) => {
      const form = h('form', { class: 'stack' }), name = field('宝宝名字', 'text', child?.name || '', { required: true, maxLength: 40 });
      const today = new Date(), localToday = new Date(today.valueOf() - today.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
      const birthday = field('生日（可稍后填写）', 'date', child?.birthday || '', { max: localToday });
      const error = errorBox(), preview = h('div', { class: 'family-avatar-editor' }); let avatarId, saving = false;
      function draw(url) { preview.replaceChildren(childPortrait({ ...child, avatar_url: url, name: name.input.value || '宝宝' }, 'large')); }
      draw(child?.avatar_url);
      const avatarChoice = child ? button('更换头像', () => chooseAvatar(child, item => { if (!current(dialog) || saving) return; avatarId = item.id; draw(item.thumbnail_url); }), { ghost: true, icon: 'photo' }) : null;
      const avatarGroup = h('div', { class: 'family-avatar-group' }, preview, avatarChoice);
      draft.dirty = () => name.input.value !== (child?.name || '') || birthday.input.value !== (child?.birthday || '') || avatarId !== undefined;
      draft.busy = () => saving;
      form.append(avatarGroup, name.node, birthday.node, error);
      const save = button(child ? '保存宝宝资料' : '添加宝宝', () => form.requestSubmit(), { primary: true });
      body.append(form); footer.append(button('取消', requestClose), save);
      form.onsubmit = async event => {
        event.preventDefault(); if (!current(dialog) || saving) return; error.hidden = true;
        const value = name.input.value.trim();
        if (!value || Array.from(value).length > 40) { showError(error, new Error('请填写1到40个字的宝宝名字。')); name.input.focus(); return; }
        saving = true; name.input.disabled = birthday.input.disabled = true; if (avatarChoice) avatarChoice.disabled = true;
        await busy(save, async () => { try {
          const data = { name: value, birthday: birthday.input.value || null }; if (avatarId !== undefined) data.avatar_media_id = avatarId;
          const result = await ctx.request(child ? `/api/children/${encodeURIComponent(child.id)}` : '/api/children', { method: child ? 'PATCH' : 'POST', body: data, childId: child?.id || '' });
          if (!current(dialog)) return;
          close(); toast(child ? '宝宝资料已保存' : '宝宝已添加'); await refreshChildren(child ? undefined : result.id);
        } catch (reason) { if (current(dialog)) showError(error, reason); }
        finally { saving = false; if (current(dialog)) { name.input.disabled = birthday.input.disabled = false; if (avatarChoice) avatarChoice.disabled = false; } } });
      };
    });
  }
  function chooseAvatar(child, picked) {
    const view = modal('选择宝宝头像', ({ body }) => body.append(loading()), { wide: true }); let offset = 0, requestNumber = 0;
    async function load() {
      const revision = ++requestNumber; view.body.replaceChildren(loading());
      try {
        const data = await ctx.request(`/api/admin/media?kind=photo&offset=${offset}&limit=40`, { childId: child.id });
        if (!current(view.dialog) || revision !== requestNumber) return;
        view.body.replaceChildren(data.items?.length ? h('div', { class: 'avatar-picker' }, data.items.map(item => photo(item, () => { if (current(view.dialog)) { picked(item); view.close(); } }))) : empty('还没有可选的照片', '给这位宝宝上传照片后，就可以选择头像。'));
        view.footer.replaceChildren(button('上一页', () => { offset -= 40; load(); }, { attrs: { disabled: offset === 0 } }), button('下一页', () => { offset += 40; load(); }, { attrs: { disabled: offset + (data.items?.length || 0) >= data.total } }), button('取消', view.requestClose));
      } catch (reason) { if (current(view.dialog) && revision === requestNumber) view.body.replaceChildren(note(reason.message, 'error'), button('重新读取', load)); }
    }
    load();
  }
  async function archiveChild(child) {
    if (!current()) return;
    if (!(await confirm(child.archived ? `恢复${child.name}的相册？` : `收起${child.name}的相册？`, child.archived ? '恢复后，可以继续上传照片和整理回忆。' : '照片和成长记录都会保留，之后可以恢复。至少需要保留一位正在使用的宝宝。', child.archived ? '恢复宝宝' : '收起宝宝')) || !current()) return;
    try { await ctx.request(`/api/children/${encodeURIComponent(child.id)}`, { method: 'PATCH', body: { archived: !child.archived }, childId: child.id }); if (!current()) return; toast(child.archived ? '宝宝已恢复' : '宝宝已收起'); await refreshChildren(); } catch (reason) { if (current()) toast(reason.message, true); }
  }
  function renderFamily() {
    const active = invitations.filter(item => item.status === 'active');
    let legacyMembers = null, legacyRequest = null;
    function memberRow(member, index) {
      const label = h('span', {}, relation(member)), roleBadge = badge(''), row = h('div', { class: `member-row ${member.disabled ? 'disabled-member' : ''}` });
      function update() { label.textContent = relation(member); roleBadge.textContent = member.disabled ? '已停用' : familyAdminTarget(member) ? '家庭管理员' : roles.find(([role]) => role === member.role)?.[1] || '仅查看'; }
      update(); observeMember(member, () => { if (current(row)) update(); });
      row.append(avatar(relation(member), index), h('div', { class: 'member-copy' }, h('h3', {}, label, member.id === ctx.member.id ? badge('你', 'warm') : null), roleBadge), button('家人信息', () => memberInfoDialog(member, update), { ghost: true, icon: 'arrow' }));
      return row;
    }
    const memberList = h('section', { class: 'card member-list joined-members' }, h('div', { class: 'section-heading member-list-heading' }, h('h2', {}, `${childName}的家人`), h('span', { class: 'muted' }, `${scopedMembers.length} 位`)), scopedMembers.length ? scopedMembers.map(memberRow) : empty('还没有家人加入', childId ? '邀请家人，一起记录宝宝的成长。' : '先添加或选择一位宝宝。', canInvite ? button('添加家人', inviteDialog, { primary: true, icon: 'plus' }) : null, 'people'));
    const historyBody = h('div', { class: 'history-members-body' }), history = h('details', { class: 'card history-members family-fold' }, h('summary', {}, '历史上传者', h('span', { class: 'muted' }, '从原相册导入的称呼')), historyBody);
    async function showHistory() {
      if (!current(history)) return;
      historyBody.replaceChildren(loading('正在读取历史上传者…'));
      try {
        if (!legacyMembers) {
          if (!legacyRequest) legacyRequest = ctx.request('/api/members?include_legacy=true', { childId }).then(data => { legacyMembers = (data.items || []).filter(member => !member.registered); return legacyMembers; }).finally(() => { legacyRequest = null; });
          await legacyRequest;
        }
        if (!current(history)) return;
        historyBody.replaceChildren(h('p', { class: 'muted' }, '关联邀请后，原来的照片和上传记录都会保留。'), ...(legacyMembers.length ? legacyMembers.map((person, index) => h('div', { class: 'member-row' }, avatar(person.name, index), h('div', { class: 'member-copy' }, h('h3', {}, person.name), badge(person.disabled ? '已停用' : '尚未注册')), button('关联邀请', () => legacyInvitation(person), { ghost: true, attrs: { disabled: !!person.disabled || !canInvite } }))) : [h('p', { class: 'muted' }, '没有需要关联的历史上传者。')]));
      } catch (reason) { if (current(history)) historyBody.replaceChildren(note(reason.message, 'error'), button('重新读取', showHistory)); }
    }
    history.addEventListener('toggle', () => { if (history.open && !historyBody.childNodes.length) showHistory(); });
    const waiting = h('details', { class: 'card waiting-list family-fold' }, h('summary', {}, '等待加入', h('span', { class: 'muted' }, `${active.length} 份邀请`)), active.length ? active.map(invite => h('div', { class: 'invitation-row' }, avatar(invite.name, 2), h('div', {}, h('strong', {}, invite.name || '新家人'), h('p', { class: 'muted' }, `${dateText(invite.expires_at, true)}到期`), h('small', { class: 'muted' }, inviteScope(invite))), button('撤销', async event => {
      const target = event.currentTarget;
      if (!current(target) || !(await confirm('撤销这份邀请？', '撤销后，家人不能再用这份邀请加入。', '撤销邀请')) || !current(target)) return;
      await busy(target, async () => { try { await ctx.request(`/api/invitations/${encodeURIComponent(invite.id)}`, { method: 'DELETE', childId }); if (current(target)) { toast('邀请已撤销'); ctx.refresh(); } } catch (reason) { if (current(target)) toast(reason.message, true); } });
    }, { ghost: true, danger: true }))) : h('p', { class: 'muted' }, '目前没有待使用的邀请。'));
    const accounts = h('section', { class: 'card family-account-entry' }, h('div', {}, h('h2', {}, '全家庭账号'), h('p', { class: 'muted' }, '其他宝宝的访问权限、家庭身份和停用账号')), button('进入账号管理', accountManagement, { ghost: true, icon: 'arrow' }));
    content.replaceChildren(h('div', { class: 'stack family-members-layout' }, memberList, waiting, history, accounts));
  }
  function inviteDialog() {
    if (!current() || !canInvite) return;
    editModal('添加家人', ({ body, footer, close, requestClose, dialog, draft }) => {
      const panel = h('div', { class: 'invitation-panel family-invite-dialog' }), form = h('form', { class: 'stack simple-family-invite' }), name = field('家人称呼（和宝宝的关系）', 'text', '', { required: true, maxLength: 40, placeholder: '例如：奶奶、小姨' }), error = errorBox();
      const submit = button('生成邀请', () => form.requestSubmit(), { primary: true }); let sending = false, generated = false;
      draft.dirty = () => !generated && !!name.input.value; draft.busy = () => sending;
      form.append(h('p', { class: 'muted' }, `邀请家人一起看${childName}的成长。`), name.node, error); panel.append(form); body.append(panel); footer.append(button('取消', requestClose), submit);
      form.onsubmit = async event => {
        event.preventDefault(); if (!current(dialog) || sending || generated) return;
        error.hidden = true; const label = name.input.value.trim();
        if (!label || Array.from(label).length > 40) { showError(error, new Error(!label ? '请填写家人称呼，例如奶奶或小姨。' : '家人称呼请控制在40个字以内。')); name.input.focus(); return; }
        sending = true; name.input.disabled = true;
        await busy(submit, async () => { try {
          const result = await ctx.request('/api/invitations', { body: { name: label, role: 'viewer', expires_hours: 24, child_ids: [childId] }, childId });
          if (!current(dialog)) return;
          generated = true;
          invitations.push({ ...result, name: label, status: 'active', child_ids: [childId] });
          if (tab === 'family') renderFamily();
          panel.replaceChildren(h('h3', {}, '邀请已准备好'), h('p', { class: 'muted' }, `复制后发给${label}。`));
          invitationResult(panel, label, result);
          footer.replaceChildren(button('继续添加家人', () => { close(); inviteDialog(); }, { ghost: true }), button('完成', close, { primary: true }));
        } catch (reason) { if (current(dialog)) showError(error, reason); }
        finally { sending = false; if (current(dialog)) name.input.disabled = false; } });
      };
    });
  }
  function invitationResult(target, label, result) {
    const code = String(result.code ?? ''), numeric = /^[0-9]{6}$/.test(code);
    const text = `邀请${label}加入 FAM宝贝相册\n宝宝：${childName}\n服务器地址：${location.origin}\n邀请码：${code}\n新家人可在 App 登录页选择“使用邀请码加入”；已有账号可在宝宝管理中使用邀请码。邀请在${dateText(result.expires_at, true)}前有效，仅可使用一次。`;
    const fallback = new Map();
    async function copy(value, title, type) {
      if (!current(target)) return;
      try { await navigator.clipboard.writeText(value); if (current(target)) toast(`${title}已复制`); }
      catch {
        if (!current(target)) return;
        if (!fallback.has(title)) { const input = field(title, type, value, { readOnly: true, ...(type === 'textarea' ? { rows: 7 } : {}) }); fallback.set(title, input); target.append(input.node); }
        const input = fallback.get(title).input; input.focus(); input.select(); toast(`请选中并复制下面的${title}。`);
      }
    }
    target.append(h('div', { class: 'invitation-code-card' }, h('span', { class: 'muted' }, '邀请码'), h('p', { class: `invitation-code ${numeric ? 'numeric-code' : 'legacy-code'}`, 'aria-label': `邀请码：${numeric ? [...code].join(' ') : code}` }, code), h('small', { class: 'muted' }, `${dateText(result.expires_at, true)}前有效`)),
      button('复制邀请码', () => copy(code, '邀请码', 'text'), { primary: true, icon: 'copy', class: 'full', attrs: { disabled: !code } }),
      button('复制完整邀请', () => copy(text, '完整邀请', 'textarea'), { ghost: true, class: 'full', attrs: { disabled: !code } }),
      h('p', { class: 'invitation-address-hint muted' }, '新家人还需要服务器地址，可以把完整邀请一起发给对方。'));
  }
  function legacyInvitation(member) {
    if (!current() || !canInvite || member.registered || member.disabled) return;
    editModal('关联历史上传者', ({ body, footer, close, requestClose, dialog, draft }) => {
      const error = errorBox(), submit = button('生成关联邀请', null, { primary: true }); let sending = false;
      draft.busy = () => sending;
      body.append(h('h3', {}, member.name), note(`邀请这位家人加入${childName}的相册，并保留以前的照片和上传记录。`), error);
      submit.onclick = async () => {
        if (!current(dialog) || sending) return; sending = true; error.hidden = true;
        await busy(submit, async () => { try {
          const result = await ctx.request('/api/invitations', { body: { name: member.name.trim(), role: 'viewer', expires_hours: 24, child_ids: [childId], member_id: member.id }, childId });
          if (!current(dialog)) return;
          for (const invitation of invitations) if (invitation.member_id === member.id) invitation.status = 'revoked';
          invitations.push({ ...result, name: member.name, member_id: member.id, status: 'active', child_ids: [childId] });
          if (tab === 'family') renderFamily();
          body.replaceChildren(h('h3', {}, '邀请已准备好')); invitationResult(body, member.name, result); footer.replaceChildren(button('完成', close));
        } catch (reason) { if (current(dialog)) showError(error, reason); } finally { sending = false; } });
      };
      footer.append(button('取消', requestClose), submit);
    });
  }
  function memberInfoDialog(member, updateRow) {
    if (!current()) return;
    modal('家人信息', ({ body, footer, close, dialog }) => {
      const protectedRole = member.id === ctx.member.id || familyAdminTarget(member), base = !!childId && !child?.archived && !!member.registered;
      const editRelation = base && member.can_edit_relationship === true, changeRole = base && !protectedRole && member.can_change_role === true, remove = base && !protectedRole && member.can_remove === true;
      const label = h('strong', {}, relation(member)), headingName = h('h3', {}, relation(member)), error = errorBox(), status = h('p', { class: 'family-save-status muted', role: 'status', 'aria-live': 'polite' });
      const management = h('input', { type: 'checkbox', role: 'switch', 'aria-label': '管理' }), upload = h('input', { type: 'checkbox', role: 'switch', 'aria-label': '上传' });
      const edit = button('修改', () => editRelationship(), { ghost: true, attrs: { disabled: !editRelation } }), removeButton = button('移出当前宝宝', () => removeFromChild(), { ghost: true, danger: true, attrs: { disabled: !remove } });
      function switchRow(title, description, input) { return h('label', { class: 'family-switch-row' }, h('span', {}, h('strong', {}, title), h('small', { class: 'muted' }, description)), h('span', { class: 'family-switch' }, input, h('span', { class: 'family-switch-track', 'aria-hidden': 'true' }))); }
      function draw() {
        const confirmed = pendingRoles.get(member.id) || member.role, saving = pendingMembers.has(member.id);
        label.textContent = relation(member); headingName.textContent = relation(member);
        management.checked = confirmed === 'admin'; upload.checked = confirmed === 'admin' || confirmed === 'contributor';
        management.disabled = saving || !changeRole; upload.disabled = saving || !changeRole || confirmed === 'admin';
        edit.disabled = saving || !editRelation; removeButton.disabled = saving || !remove;
      }
      body.append(h('div', { class: 'family-person-heading' }, avatar(relation(member), 1), h('div', {}, headingName, h('p', { class: 'muted' }, `${childName}的家人`))), h('div', { class: 'family-relation-row' }, h('div', {}, h('span', { class: 'muted' }, '和宝宝的关系'), label), edit), h('h3', { class: 'family-permission-heading' }, '权限管理'), h('div', { class: 'family-switches' }, switchRow('管理', '邀请家人，整理相册和资料', management), switchRow('上传', '上传照片和视频', upload)), child?.archived ? note('宝宝已收起，恢复后可以修改。') : protectedRole ? h('p', { class: 'muted family-protection' }, familyAdminTarget(member) ? '家庭管理员的权限在账号管理中调整。' : '不能修改自己的权限。') : member.disabled ? note('账号已停用，调整这里的权限不会恢复登录。') : null, error, status);
      footer.append(removeButton, button('完成', close)); draw();
      const unsubscribe = observeMember(member, () => { if (current(dialog)) draw(); }); dialog.addEventListener('close', unsubscribe, { once: true });
      async function saveRole(next) {
        if (!current(dialog) || pendingMembers.has(member.id) || !changeRole || next === member.role) { draw(); return; }
        pendingMembers.add(member.id); pendingRoles.set(member.id, next); error.hidden = true; status.textContent = '正在保存…'; notifyMember(member);
        try {
          await ctx.request(memberPath(member), { method: 'PUT', body: { role: next }, childId });
          if (!current()) return; member.role = next;
          const global = members.find(item => item.id === member.id), scope = global?.children?.find(item => item.child_id === childId);
          if (scope) { scope.role = next; scope.role_label = roles.find(([value]) => value === next)?.[1]; }
          updateRow(); notifyMember(member); if (current(dialog)) status.textContent = '已保存';
        } catch (reason) { if (current(dialog)) { showError(error, reason); status.textContent = ''; } }
        finally { pendingMembers.delete(member.id); pendingRoles.delete(member.id); notifyMember(member); }
      }
      management.onchange = () => saveRole(management.checked ? 'admin' : 'contributor');
      upload.onchange = () => { if (member.role === 'admin') { draw(); return; } saveRole(upload.checked ? 'contributor' : 'viewer'); };
      function editRelationship() {
        if (!current(dialog) || pendingMembers.has(member.id) || !editRelation) return;
        editModal('修改和宝宝的关系', ({ body: inner, footer: actions, close: done, requestClose, dialog: editor, draft }) => {
          const input = field('和宝宝的关系', 'text', relation(member), { required: true, maxLength: 40, placeholder: '例如：奶奶、小姨' }), problem = errorBox(), save = button('保存关系', null, { primary: true }); let pending = false;
          const initial = relation(member); draft.dirty = () => input.input.value !== initial; draft.busy = () => pending;
          inner.append(input.node, problem);
          save.onclick = async () => {
            if (!current(editor) || !current(dialog) || pending || pendingMembers.has(member.id)) return;
            const value = input.input.value.trim(); if (!value || Array.from(value).length > 40) { showError(problem, new Error('请填写1到40个字的关系称呼。')); return; }
            pending = true; input.input.disabled = true; pendingMembers.add(member.id); notifyMember(member); problem.hidden = true;
            await busy(save, async () => { try {
              const result = await ctx.request(memberPath(member), { method: 'PATCH', body: { relationship: value }, childId });
              if (!current()) return; member.relationship = result.relationship || value; updateRow(); notifyMember(member); if (current(editor)) done(); if (current(dialog)) status.textContent = '关系已保存';
            } catch (reason) { if (current(editor)) showError(problem, reason); } finally { pending = false; if (current(editor)) input.input.disabled = false; pendingMembers.delete(member.id); notifyMember(member); } });
          };
          actions.append(button('取消', requestClose), save);
        });
      }
      async function removeFromChild() {
        if (!current(dialog) || pendingMembers.has(member.id) || !remove) return;
        if (!(await confirm(`将${relation(member)}移出${childName}的相册？`, '照片和留言都会保留，不会影响这位家人访问其他宝宝的相册。', '移出当前宝宝', true)) || !current(dialog)) return;
        pendingMembers.add(member.id); notifyMember(member); error.hidden = true;
        try { await ctx.request(memberPath(member), { method: 'PUT', body: { role: null }, childId }); if (current()) { if (current(dialog)) { close(); toast('已移出当前宝宝的相册'); } ctx.refresh(); } }
        catch (reason) { if (current(dialog)) showError(error, reason); }
        finally { pendingMembers.delete(member.id); notifyMember(member); }
      }
    }, { class: 'family-person-dialog' });
  }
  function accountManagement() {
    if (!current()) return;
    modal('全家账号管理', ({ body, footer, close, dialog }) => {
      body.append(h('p', { class: 'muted' }, '查看全家账号，包括暂时没有宝宝访问权限的家人。'), h('div', { class: 'account-member-list' }, members.filter(member => member.registered).map((member, index) => h('div', { class: 'member-row' }, avatar(member.name, index), h('div', { class: 'member-copy' }, h('h3', {}, member.name), h('p', { class: 'muted' }, member.disabled ? '已停用' : familyAdminTarget(member) ? '家庭管理员' : member.username || '家人账号')), button('账号设置', () => { if (current(dialog)) accountDetails(member); }, { ghost: true, icon: 'arrow' })))));
      footer.append(button('完成', close));
    }, { wide: true });
  }
  function accountDetails(member) {
    if (!current()) return;
    modal('账号设置', ({ body, footer, close, dialog }) => {
      const own = member.id === ctx.member.id;
      const access = entry('people', '宝宝访问', '分别设置可以访问的宝宝', () => { if (current(dialog)) permissionsDialog(member); });
      const role = entry('shield', '家庭身份', '普通家人或家庭管理员', () => { if (current(dialog)) familyRoleDialog(member); });
      const disable = entry(member.disabled ? 'refresh' : 'lock', member.disabled ? '恢复使用' : '停用', '对这个账号的所有宝宝相册生效', () => { if (current(dialog)) toggleMember(member); });
      access.disabled = own || familyAdminTarget(member); role.disabled = disable.disabled = own;
      body.append(h('div', { class: 'family-detail-identity' }, avatar(member.name, 1), h('div', {}, h('h3', {}, member.name), h('p', { class: 'muted' }, member.username || '家人账号'))), access, role, disable);
      if (own) body.append(h('small', { class: 'muted' }, '自己的家庭身份和访问权限不能在这里更改。'));
      footer.append(button('完成', close));
    });
  }
  function inviteScope(invite) { const ids = invite.child_ids || invite.children?.map(child => child.child_id || child.id) || []; return children.filter(child => ids.includes(child.id)).map(child => child.name).join('、') || '原有邀请，按生成时的范围加入'; }
  function permissionsDialog(member) {
    if (!current() || member.id === ctx.member.id || familyAdminTarget(member)) return;
    editModal('宝宝访问权限', ({ body, footer, close, requestClose, dialog, draft }) => {
      const originals = new Map((member.children || []).map(scope => [scope.child_id, scope.role])), controls = children.map(child => ({ child, input: selectField(`${child.name}${child.archived ? '（已收起）' : ''}`, [['', '不开放这个宝宝'], ...roles], originals.get(child.id) || '', { disabled: !!child.archived }) }));
      const error = errorBox(), submit = button('保存宝宝权限', null, { primary: true }); let pending = false;
      draft.dirty = () => controls.some(({ child, input }) => !child.archived && (originals.get(child.id) || '') !== input.input.value); draft.busy = () => pending;
      body.append(h('p', { class: 'muted' }, `为${member.name}选择可以访问的宝宝。移除权限会保留照片。`), ...controls.map(item => item.input.node), error);
      submit.onclick = async () => { if (!current(dialog) || pending) return; pending = true; controls.forEach(({ input }) => { input.input.disabled = true; }); await busy(submit, async () => { error.hidden = true; let saved = 0; try {
        for (const { child, input } of controls) {
          if (!current(dialog)) return;
          const role = input.input.value || null;
          if (child.archived || (originals.get(child.id) || null) === role) continue;
          await ctx.request(`/api/children/${encodeURIComponent(child.id)}/members/${encodeURIComponent(member.id)}`, { method: 'PUT', body: { role }, childId: child.id });
          if (!current(dialog)) return;
          originals.set(child.id, role);
          const scopes = member.children || [], previous = scopes.find(scope => scope.child_id === child.id);
          member.children = scopes.filter(scope => scope.child_id !== child.id);
          if (role) member.children.push({ ...previous, child_id: child.id, name: child.name, role });
          saved++;
        }
        if (current(dialog)) { close(); toast('宝宝访问权限已更新'); ctx.refresh(); }
      } catch (reason) { if (current(dialog)) showError(error, saved ? new Error(`已有 ${saved} 位宝宝的权限保存成功，其余尚未保存：${reason.message} 请重试。`) : reason); } finally { pending = false; if (current(dialog)) controls.forEach(({ child, input }) => { input.input.disabled = !!child.archived; }); } }); }; footer.append(button('取消', requestClose), submit);
    });
  }
  function familyRoleDialog(member) {
    if (!current() || member.id === ctx.member.id) return;
    editModal(`调整${member.name}的家庭身份`, ({ body, footer, close, requestClose, dialog, draft }) => {
      const role = selectField('家庭身份', [['member', '普通家人'], ['admin', '家庭管理员']], isFamilyAdmin(member) ? 'admin' : 'member'), error = errorBox(), save = button('保存家庭身份', null, { primary: true });
      let pending = false;
      const initial = role.input.value; draft.dirty = () => role.input.value !== initial; draft.busy = () => pending;
      body.append(role.node, h('p', { class: 'muted' }, '家庭管理员可以管理全部宝宝、存储和备份。'), error);
      save.onclick = async () => { if (!current(dialog) || pending) return; pending = true; await busy(save, async () => { try { await ctx.request(`/api/members/${encodeURIComponent(member.id)}`, { method: 'PATCH', body: { family_role: role.input.value === 'admin' ? 'admin' : 'contributor' }, childId }); if (current(dialog)) { close(); toast('家庭身份已更新'); ctx.refresh(); } } catch (reason) { if (current(dialog)) showError(error, reason); } finally { pending = false; } }); }; footer.append(button('取消', requestClose), save);
    });
  }
  async function toggleMember(member) {
    if (!current() || member.id === ctx.member.id) return;
    if (!(await confirm(member.disabled ? `恢复${member.name}的账号？` : `停用${member.name}的账号？`, member.disabled ? '恢复后，家人可以按原来的宝宝权限重新使用相册。' : '停用会影响这位家人的全部宝宝相册，已有照片和留言会保留。', member.disabled ? '恢复使用' : '停用账号', !member.disabled))) return;
    if (!current()) return;
    try { await ctx.request(`/api/members/${encodeURIComponent(member.id)}`, { method: 'PATCH', body: { disabled: !member.disabled }, childId }); if (current()) { toast(member.disabled ? '账号已恢复' : '账号已停用'); ctx.refresh(); } } catch (reason) { if (current()) toast(reason.message, true); }
  }
  function joinDialog() {
    if (!current()) return;
    editModal('使用邀请码', ({ body, footer, close, requestClose, dialog, draft }) => {
      const code = field('邀请码', 'text', '', { required: true, autocomplete: 'off', inputMode: 'numeric', placeholder: '输入6位邀请码', maxLength: 2000 }), error = errorBox(), form = h('form', { class: 'stack' }), save = button('加入宝宝相册', null, { primary: true, attrs: { type: 'submit' } });
      let joining = false, rejectedPaste = '';
      draft.dirty = () => !!code.input.value; draft.busy = () => joining;
      code.node.classList.add('family-code-field');
      const showCodeStyle = () => code.node.classList.toggle('numeric-code-field', /^[0-9]{0,6}$/.test(code.input.value)); showCodeStyle();
      function parse(value) {
        const text = String(value || '').trim(), valid = value => /^[0-9]{6}$/.test(value) || /^[A-Za-z0-9_-]{16,200}$/.test(value);
        if (valid(text)) return { code: text };
        const lines = text.split(/\r\n?|\n/).map(line => line.trim());
        const addresses = lines.map(line => line.match(/^(?:服务器地址|服务器|服务地址|相册地址)\s*[：:]\s*(.*)$/u)).filter(Boolean);
        for (const match of addresses) {
          const address = match[1].trim(); let server;
          try { server = new URL(address); } catch { return { error: '邀请中的服务器地址不正确，请重新复制完整邀请。' }; }
          if (!/^https?:\/\//i.test(address) || !['http:', 'https:'].includes(server.protocol) || server.username || server.password || /^https?:\/\/[^/]*@/i.test(address) || /[\s?#]/u.test(address)) return { error: '邀请中的服务器地址不正确，请重新复制完整邀请。' };
          if (server.origin !== new URL(location.origin).origin || server.pathname !== '/') return { error: '这份邀请来自另一台服务器，请在对应服务器使用。' };
        }
        const codes = lines.map(line => line.match(/^邀请码\s*[：:]\s*(.*)$/u)).filter(Boolean);
        const candidate = codes.length === 1 ? codes[0][1].trim() : '';
        return valid(candidate) ? { code: candidate } : { error: '请输入6位数字邀请码，或粘贴以前的完整长邀请码。' };
      }
      code.input.addEventListener('input', () => { rejectedPaste = ''; error.hidden = true; showCodeStyle(); });
      code.input.addEventListener('paste', event => {
        if (!current(dialog) || joining) return;
        const parsed = parse(event.clipboardData?.getData('text') || ''); event.preventDefault();
        code.input.value = parsed.code || ''; rejectedPaste = parsed.error || ''; showCodeStyle();
        if (rejectedPaste) showError(error, new Error(rejectedPaste)); else error.hidden = true;
      });
      form.append(code.node, h('small', { class: 'muted' }, '支持六位数字、旧长码和完整邀请。'), error); body.append(form); footer.append(button('取消', requestClose), save);
      save.type = 'button'; save.onclick = () => form.requestSubmit();
      form.onsubmit = async event => {
        event.preventDefault(); if (!current(dialog) || joining) return;
        error.hidden = true; const parsed = parse(code.input.value), value = parsed.code;
        if (!value) { showError(error, new Error(rejectedPaste || parsed.error)); code.input.focus(); return; }
        joining = true; code.input.value = value; code.input.disabled = true;
        await busy(save, async () => { try {
          await ctx.request('/api/invitations/join', { body: { code: value }, childId: '' });
          if (!current(dialog)) return; code.input.value = ''; close(); toast('邀请已接受，宝宝列表已更新'); await refreshChildren();
        } catch (reason) { if (current(dialog)) showError(error, reason); }
        finally { joining = false; if (current(dialog)) code.input.disabled = false; } });
      };
    });
  }
  choose(tab);
  if (ctx.state.inviteOpen) { ctx.state.inviteOpen = false; inviteDialog(); }
}
