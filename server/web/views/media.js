import { saveBlob } from '../api.js';
import { bytes, count, dateText, duration, mediaQuery, percent, captureTime, localTimeInput } from '../utils.mjs';
import { childChoices } from '../children.js';
import { h, icon, button, badge, heading, empty, note, loading, progress, photo, openViewer, field, selectField, errorBox, showError, modal, confirm, busy, toast, validateDateRange } from '../ui.js';

export async function overviewPage(ctx) {
  const data = await ctx.request('/api/admin/overview'); if (!ctx.isCurrent()) return;
  const profile = data.profile || {}, stats = profile.counts || {}, recent = data.recent || [];
  ctx.familyName(profile.name);
  const summary = h('div', { class: 'stats' }, [
    ['photo', count(stats.photos), '张照片'], ['video', count(stats.videos), '个视频'],
    ['people', count(stats.members), '位家人'], ['storage', bytes(profile.storage_bytes), '相册已保存'],
  ].map(([type, amount, label]) => h('div', { class: 'stat' }, h('span', { class: 'stat-icon' }, icon(type)), h('strong', {}, amount), h('span', {}, label))));
  const gallery = h('section', { class: 'card recent-card' }, h('div', { class: 'section-heading' }, h('h2', {}, '最近上传'), button('查看全部', () => ctx.navigate('media'), { ghost: true, icon: 'arrow' })),
    recent.length ? h('div', { class: 'recent-grid' }, recent.slice(0, 6).map((item, index) => h('div', {}, photo(item, () => openViewer(recent.slice(0, 6), index)), h('strong', { class: 'photo-caption' }, item.date_label || dateText(item.captured_at)), h('small', { class: 'muted' }, `${item.uploader_name || '家人'}上传`)))) : empty('第一份回忆，从这里开始', '导入手机或电脑里的照片，慢慢装满这个家。', button('导入照片', () => ctx.navigate('imports'), { primary: true, icon: 'upload' })));
  const latest = data.backups?.latest, running = data.backups?.running;
  const storage = h('section', { class: 'card overview-storage overview-storage-quiet' }, h('div', { class: 'section-heading' }, h('h2', {}, '存储与备份'), icon('storage')), h('h3', {}, data.default_storage?.name || '自己的服务器'), badge(data.default_storage?.tested ? '上次检查通过' : '尚未检查'),
    progress(percent((data.storage?.total_bytes || 0) - (data.storage?.free_bytes || 0), data.storage?.total_bytes)),
    h('small', { class: 'muted' }, `服务器可用 ${bytes(data.storage?.free_bytes)} / 共 ${bytes(data.storage?.total_bytes)}`),
    h('div', { class: 'backup-summary' }, icon(running ? 'clock' : latest ? 'shield' : 'info'), h('p', {}, running ? '正在备份' : latest ? `最近备份 ${dateText(latest.finished_at || latest.created_at, true)}` : '还没有备份')),
    latest ? h('small', { class: 'muted' }, latest.mode === 'full' ? '包含相册资料与原文件' : '仅相册资料，原文件需另行备份') : null,
    button('管理备份', () => { ctx.state.storageTab = 'backups'; ctx.navigate('storage'); }, { ghost: true, class: 'full', icon: 'arrow' }));
  ctx.container.replaceChildren(heading('相册概览', `${ctx.child?.name || '当前宝宝'} · 本周新增 ${count(data.week_count)} 份回忆`, [button('导入照片', () => ctx.navigate('imports'), { primary: true, icon: 'upload' }), button('添加家人', () => { ctx.state.familyTab = 'family'; ctx.state.inviteOpen = true; ctx.navigate('family'); }, { icon: 'people' })]), summary,
    h('div', { class: 'overview-grid' }, gallery, storage),
    ...(data.tasks?.failed ? [h('div', { class: 'overview-attention' }, note(`${count(data.tasks.failed)} 项导入需要处理`, 'warning'), button('查看任务', () => ctx.navigate('imports'), { ghost: true, icon: 'arrow' }))] : []));
}

export async function mediaPage(ctx) {
  const filters = ctx.state.media;
  const members = await ctx.request('/api/members?include_legacy=true'); if (!ctx.isCurrent()) return;
  let offset = Math.max(0, Number(filters.offset) || 0), items = [], total = 0, requestNumber = 0, selection = new Set();
  const results = h('div', { class: 'media-results' }), pager = h('div', { class: 'pager' }), selectionBar = h('div', { class: 'selection-bar', hidden: true });
  const title = heading('照片与视频', ctx.child?.name ? `${ctx.child.name}的相册` : '', ctx.child?.archived ? [badge('宝宝已收起')] : [button('上传照片', () => ctx.navigate('imports'), { primary: true, icon: 'upload' })]);
  const search = field('搜索说明或文件名', 'search', filters.search || '', { placeholder: '搜索照片说明', maxLength: 100 });
  const uploader = selectField('上传家人', [['', '全部家人'], ...(members.items || []).map(member => [member.id, member.name])], filters.uploader_id || '');
  const from = field('开始日期', 'date', filters.date_from || ''), to = field('结束日期', 'date', filters.date_to || '');
  const advanced = h('details', { class: 'media-advanced', open: !!(filters.uploader_id || filters.date_from || filters.date_to) }, h('summary', {}, icon('settings'), '更多筛选'), h('div', { class: 'media-advanced-fields' }, uploader.node, from.node, to.node));
  const filterSummary = h('span', { class: 'muted media-filter-summary' });
  const queryForm = h('form', { class: 'media-filters media-filters-quiet' }, h('div', { class: 'media-search-row' }, search.node, button('筛选', null, { attrs: { type: 'submit' }, icon: 'search' }), button('清除', () => {
    search.input.value = ''; uploader.input.value = ''; from.input.value = ''; to.input.value = ''; Object.assign(filters, { search: '', uploader_id: '', date_from: '', date_to: '' }); load(0);
  }, { ghost: true })), advanced, filterSummary);
  const tabs = h('div', { class: 'tabs' });
  const renderTabs = () => tabs.replaceChildren(...[['all', '全部'], ['photo', '照片'], ['video', '视频']].map(([value, label]) => button(label, () => { filters.kind = value; filters.deleted = false; renderTabs(); load(0); }, { class: !filters.deleted && filters.kind === value ? 'tab active' : 'tab', ghost: true })), button('回收站', () => { filters.deleted = true; renderTabs(); load(0); }, { icon: 'trash', ghost: true, class: filters.deleted ? 'tab active' : 'tab' }));
  queryForm.onsubmit = event => { event.preventDefault(); if (!validateDateRange(from.input.value, to.input.value)) { toast('结束日期不能早于开始日期。', true); return; } Object.assign(filters, { search: search.input.value.trim(), uploader_id: uploader.input.value, date_from: from.input.value, date_to: to.input.value }); load(0); };
  ctx.container.replaceChildren(title, ...(ctx.child?.archived ? [note('这位宝宝已收起，可以继续查看和下载。需要整理或上传时，请先在“宝宝与家人”恢复。')] : []), tabs, queryForm, results, pager, selectionBar); renderTabs();
  function renderSelection() {
    selectionBar.hidden = !selection.size;
    const selected = items.filter(item => selection.has(item.id)), editable = !ctx.child?.archived && selected.every(item => item.can_edit !== false);
    const all = h('input', { type: 'checkbox', checked: !!items.length && items.every(item => selection.has(item.id)), 'aria-label': '选择当前页全部内容', onchange: event => { items.forEach(item => event.target.checked ? selection.add(item.id) : selection.delete(item.id)); renderItems(); } });
    selectionBar.replaceChildren(h('strong', {}, `已选择 ${selection.size} 项`),
      filters.deleted ? button('恢复到相册', () => batch('restore'), { icon: 'refresh', attrs: { disabled: !!ctx.child?.archived || selected.some(item => item.can_restore === false) } }) : button('修改时间与说明', editSelection, { icon: 'edit', attrs: { disabled: !editable } }),
      !filters.deleted && (ctx.children || []).some(child => !child.archived && child.id !== ctx.childId) ? button('加入其他宝宝', linkSelection, { icon: 'people', attrs: { disabled: !editable } }) : null,
      button('下载原文件', event => download(event.currentTarget), { icon: 'download' }),
      !filters.deleted ? button('移入回收站', () => batch('trash'), { icon: 'trash', danger: true, ghost: true, attrs: { disabled: !!ctx.child?.archived || selected.some(item => item.can_delete === false) } }) : null,
      button('取消选择', () => { selection.clear(); renderItems(); }, { ghost: true }));
    const old = results.querySelector('.select-page');
    const label = h('label', { class: 'select-page' }, all, '选择本页');
    if (old) old.replaceWith(label);
  }
  function renderItems() {
    results.replaceChildren(h('div', { class: 'section-heading' }, h('p', { class: 'muted' }, `${filters.deleted ? '回收站中共有' : '找到'} ${count(total)} 份内容`), h('label', { class: 'select-page' })));
    if (!items.length) results.append(empty(filters.deleted ? '回收站是空的' : '这里还没有照片', filters.deleted ? '移入回收站的内容可以在这里恢复。' : '可以调整筛选条件，或导入第一份回忆。', filters.deleted ? null : button('导入照片', () => ctx.navigate('imports'), { primary: true })));
    else results.append(h('div', { class: 'media-grid' }, items.map((item, index) => {
      const check = h('input', { type: 'checkbox', checked: selection.has(item.id), 'aria-label': `选择${item.filename || item.date_label}`, onchange: event => { event.target.checked ? selection.add(item.id) : selection.delete(item.id); tile.classList.toggle('selected', selection.has(item.id)); renderSelection(); } });
      const tile = h('article', { class: `media-tile ${selection.has(item.id) ? 'selected' : ''}` }, photo(item, () => openViewer(items, index)), h('label', { class: 'media-check' }, check), h('div', { class: 'tile-copy' },
        h('div', { class: 'tile-title' }, h('strong', {}, item.age || item.date_label), button('信息', () => showInfo(item), { ghost: true, class: 'media-info', attrs: { 'aria-label': `查看${item.kind === 'video' ? '视频' : '照片'}信息：${item.date_label || dateText(item.captured_at)}` } })),
        h('small', {}, item.date_label), item.shared_count > 1 ? badge('多个宝宝的合照') : null, item.description ? h('p', {}, item.description) : null));
      return tile;
    })));
    renderSelection();
    pager.replaceChildren(button('上一页', () => load(Math.max(0, offset - 40)), { attrs: { disabled: offset === 0 } }), h('span', {}, total ? `第 ${Math.floor(offset / 40) + 1} / ${Math.ceil(total / 40)} 页` : ''), button('下一页', () => load(offset + 40), { attrs: { disabled: offset + items.length >= total } }));
  }
  async function load(nextOffset, preservePosition = false) {
    const savedY = preservePosition ? (filters.scrollY ?? window.scrollY) : 0;
    const number = ++requestNumber; offset = nextOffset; filters.offset = offset; results.replaceChildren(loading()); pager.replaceChildren(); selection.clear(); selectionBar.hidden = true;
    const selectedUploader = (members.items || []).find(member => member.id === filters.uploader_id)?.name;
    filterSummary.textContent = [selectedUploader, filters.date_from ? `${dateText(filters.date_from)}起` : '', filters.date_to ? `至${dateText(filters.date_to)}` : ''].filter(Boolean).join(' · ');
    filterSummary.hidden = !filterSummary.textContent;
    try { const data = await ctx.request(`/api/admin/media?${mediaQuery(filters, offset)}`); if (!ctx.isCurrent() || number !== requestNumber) return; items = data.items || []; total = data.total || 0;
      if (!items.length && total > 0 && offset >= total) return load(Math.floor((total - 1) / 40) * 40, preservePosition);
      renderItems();
      if (preservePosition && savedY > 0) window.requestAnimationFrame(() => { if (ctx.isCurrent() && number === requestNumber) window.scrollTo(0, savedY); });
    }
    catch (error) { if (error.name !== 'AbortError' && ctx.isCurrent() && number === requestNumber) results.replaceChildren(note(error.message, 'error'), button('重试', () => load(offset))); }
  }
  async function batch(action) {
    const ids = [...selection]; if (!ids.length) return;
    if (!(await confirm(action === 'trash' ? '移入这位宝宝的回收站？' : '恢复这些内容？', action === 'trash' ? `选中的 ${ids.length} 份内容只会从当前宝宝的相册隐藏，其他宝宝的记录不受影响，之后仍可在回收站恢复。` : `将选中的 ${ids.length} 份内容恢复到当前宝宝相册原来的拍摄日期。`, action === 'trash' ? '移入回收站' : '恢复', action === 'trash'))) return;
    if (!ctx.isCurrent()) return;
    filters.scrollY = window.scrollY;
    try { const result = await ctx.request('/api/admin/media/batch', { body: { ids, action } }); toast(result.message || '已完成'); if (ctx.isCurrent()) load(offset, true); } catch (error) { if (ctx.isCurrent()) toast(error.message, true); }
  }
  function showInfo(item) {
    const rows = [['拍摄时间', dateText(item.captured_at, true)], ['上传家人', item.uploader_name || '家人'], ['文件名', item.filename || ''], ['文件大小', item.size_bytes > 0 ? bytes(item.size_bytes) : ''], ['尺寸', item.width > 0 && item.height > 0 ? `${item.width} × ${item.height}` : ''], ['视频时长', item.kind === 'video' ? duration(item.duration) : '']];
    modal(item.kind === 'video' ? '视频信息' : '照片信息', ({ body, footer, close }) => {
      body.append(h('div', { class: 'media-detail-preview' }, photo(item, () => openViewer([item]))), h('dl', { class: 'media-metadata' }, rows.filter(([, value]) => value).map(([label, value]) => h('div', {}, h('dt', {}, label), h('dd', {}, value)))), item.description ? h('section', { class: 'media-story' }, h('h3', {}, '这一刻的故事'), h('p', {}, item.description)) : h('p', { class: 'muted' }, '还没有写下故事'), ...(item.shared_count > 1 ? [note('这份内容由多个宝宝共用。修改时间或说明会一起更新。')] : []));
      footer.append(button('下载原文件', event => download(event.currentTarget, [item.id]), { icon: 'download' }));
      if (!filters.deleted && !ctx.child?.archived && item.can_edit !== false) footer.append(button('编辑', () => { close(); editSelection([item.id]); }, { primary: true, icon: 'edit' }));
    }, { drawer: true });
  }
  function editSelection(chosen) {
    const ids = Array.isArray(chosen) ? chosen : [...selection]; if (!ids.length) return;
    const single = ids.length === 1 ? items.find(item => item.id === ids[0]) : null;
    let dirty = false, saving = false;
    modal(ids.length === 1 ? '编辑时间与说明' : `修改选中的 ${ids.length} 份内容`, ({ body, footer, close, requestClose, setBusy }) => {
      const time = field('新的拍摄时间', 'datetime-local', single ? localTimeInput(single.captured_at) : ''), description = field('新的照片说明', 'textarea', single?.description || '', { maxLength: 5000 });
      const changeTime = h('input', { type: 'checkbox' }), changeDescription = h('input', { type: 'checkbox' });
      const error = errorBox(), form = h('form', { class: 'stack' }, note('只修改勾选的项目。照片的时间和说明由所有关联宝宝共用，修改会同步到这些宝宝的相册；原文件保持不变。'), h('label', { class: 'check-line' }, changeTime, ids.length > 1 ? '统一修改拍摄时间' : '修改拍摄时间'), time.node, h('label', { class: 'check-line' }, changeDescription, ids.length > 1 ? '统一修改说明' : '修改说明'), description.node, error);
      time.node.hidden = description.node.hidden = true;
      changeTime.onchange = () => { time.node.hidden = !changeTime.checked; dirty = true; };
      changeDescription.onchange = () => { description.node.hidden = !changeDescription.checked; dirty = true; };
      form.oninput = () => { dirty = true; };
      const submit = button('保存修改', null, { primary: true, attrs: { type: 'submit' } }); form.append(submit); body.append(form); footer.append(button('取消', requestClose));
      form.onsubmit = event => { event.preventDefault(); busy(submit, async () => { error.hidden = true; try {
        if (!changeTime.checked && !changeDescription.checked) throw new Error('请勾选想要修改的项目。');
        if (changeTime.checked && !time.input.value) throw new Error('请选择新的拍摄时间。');
        const request = { ids, action: 'edit' };
        if (changeTime.checked) request.captured_at = captureTime(time.input.value);
        if (changeDescription.checked) request.description = description.input.value;
        saving = true; setBusy(true); form.querySelectorAll('input,textarea').forEach(input => { input.disabled = true; }); filters.scrollY = window.scrollY;
        await ctx.request('/api/admin/media/batch', { body: request }); dirty = false; close(); if (ctx.isCurrent()) { toast('选中内容已更新'); load(offset, true); }
      } catch (reason) { showError(error, reason); } finally { saving = false; setBusy(false); form.querySelectorAll('input,textarea').forEach(input => { input.disabled = false; }); } }); };
    }, { drawer: true, isDirty: () => dirty, beforeClose: () => saving ? false : !dirty || confirm('还没有保存', '关闭后，这次填写的时间和说明不会保留。', '放弃修改') });
  }
  function linkSelection() {
    const ids = [...selection]; if (!ids.length) return;
    modal('把合照加入其他宝宝的相册', ({ body, footer, close, requestClose, setBusy }) => {
      const choices = childChoices((ctx.children || []).filter(child => child.id !== ctx.childId), [], '选择宝宝'), error = errorBox(), save = button('加入所选宝宝', null, { primary: true });
      body.append(note(`选中的 ${ids.length} 份照片或视频会出现在所选宝宝的相册中。原来的相册仍保留，原文件只保存一份。`), choices.node, error);
      save.onclick = () => busy(save, async () => { error.hidden = true; try { const child_ids = choices.values(); if (!child_ids.length) throw new Error('请选择至少一位宝宝。'); setBusy(true); choices.disable(true); filters.scrollY = window.scrollY; await ctx.request('/api/admin/media/batch', { body: { ids, action: 'link', child_ids } }); close(); if (ctx.isCurrent()) { toast('已加入所选宝宝的相册'); load(offset, true); } } catch (reason) { showError(error, reason); } finally { setBusy(false); choices.disable(false); } });
      footer.append(button('取消', requestClose), save);
    }, { drawer: true });
  }
  async function download(node, ids = [...selection]) {
    if (!ids.length) return;
    await busy(node, async () => { try { toast('正在整理原文件。视频较多时，建议分几批下载。'); const blob = await ctx.request('/api/admin/media/download', { body: { ids }, blob: true }); if (!ctx.isCurrent()) return; saveBlob(blob, 'FAM相册原文件.zip'); toast('原文件已准备好'); } catch (error) { if (ctx.isCurrent()) toast(error.message, true); } });
  }
  await load(offset, true);
  return () => { filters.scrollY = window.scrollY; };
}
