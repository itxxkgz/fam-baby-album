import { uploadFile } from '../api.js';
import { bytes, count, dateText, percent, jobActions, normalizeFiles } from '../utils.mjs';
import { childChoices } from '../children.js';
import { h, icon, button, badge, heading, empty, note, loading, progress, field, selectField, errorBox, showError, modal, confirm, busy, toast } from '../ui.js';

export async function importsPage(ctx) {
  let jobs = [], filter = 'all', uploadController = null, uploading = false, uploadingJobId = null, refreshBusy = false;
  const expandedJobs = new Set(), activeActions = new Set();
  const list = h('div', { class: 'task-list' }), uploadPanel = h('div', { class: 'upload-progress', hidden: true });
  const targets = childChoices(ctx.children || [], [ctx.childId], '这批照片属于哪些宝宝');
  const files = h('input', { type: 'file', multiple: true, hidden: true, accept: 'image/jpeg,image/png,image/webp,image/gif,video/mp4,video/quicktime,video/x-m4v,video/3gpp', onchange: event => { receiveFiles(normalizeFiles(event.target.files)); event.target.value = ''; } });
  const folder = h('input', { type: 'file', multiple: true, hidden: true, webkitdirectory: true, onchange: event => { receiveFiles(normalizeFiles(event.target.files)); event.target.value = ''; } });
  const drop = h('div', { class: 'drop-zone drop-zone-quiet', tabIndex: 0, role: 'button', 'aria-label': '选择照片、视频或拖入文件夹', onkeydown: event => { if (event.target === drop && ['Enter', ' '].includes(event.key)) { event.preventDefault(); files.click(); } } },
    icon('upload'), h('div', {}, h('h2', {}, '拖入照片、视频或文件夹'), h('div', { class: 'actions' }, button('选择文件', () => files.click(), { primary: true, icon: 'upload' }), button('选择文件夹', () => folder.click(), { icon: 'folder' })), h('p', { class: 'muted' }, '保留拍摄时间，重复文件自动跳过。')), files, folder);
  const computer = h('section', { class: 'import-computer', hidden: true }, targets.node, drop);
  const computerButton = button('从电脑导入', () => { computer.hidden = false; computerButton.setAttribute('aria-expanded', 'true'); }, { icon: 'photo', class: 'import-source-card', attrs: { 'aria-expanded': 'false' } });
  const serverButton = button('从服务器目录导入', directoryDialog, { icon: 'server', class: 'import-source-card' });
  const sources = h('div', { class: 'import-source-options' }, h('div', {}, computerButton, h('p', { class: 'muted' }, '选择这台电脑上的照片或文件夹')), h('div', {}, serverButton, h('p', { class: 'muted' }, '整理已经放在服务器上的照片')));
  drop.addEventListener('dragover', event => { event.preventDefault(); drop.classList.add('drag-over'); });
  drop.addEventListener('dragleave', event => { if (!drop.contains(event.relatedTarget)) drop.classList.remove('drag-over'); });
  drop.addEventListener('drop', async event => { event.preventDefault(); drop.classList.remove('drag-over'); try { const selected = await droppedFiles(event.dataTransfer); receiveFiles(selected); } catch (error) { toast(error.message, true); } });
  const tabs = h('div', { class: 'tabs' });
  function renderTabs() { tabs.replaceChildren(...[['all', '全部'], ['active', '进行中'], ['done', '已完成'], ['failed', '需要处理']].map(([value, name]) => button(name, () => { filter = value; renderTabs(); renderJobs(); }, { ghost: true, class: filter === value ? 'tab active' : 'tab' }))); }
  ctx.container.replaceChildren(heading('导入与任务', '照片现在放在哪里？'), sources, computer, uploadPanel, h('div', { class: 'section-heading' }, h('h2', {}, `${ctx.child?.name || '当前宝宝'}的导入任务`), button('刷新', refresh, { ghost: true, icon: 'refresh' })), tabs, list);
  renderTabs();
  const leave = async () => {
    if (!uploading) return true;
    if (!(await confirm('停止本次文件传输？', '文件还没有全部传到服务器。离开此页会停止传输；已经传好的部分仍会保留，可以稍后开始导入。', '停止并离开'))) return false;
    uploadController?.abort(); return true;
  };
  leave.dirty = () => uploading; ctx.setLeaveCheck(leave);
  function renderJobs() {
    if (!ctx.isCurrent()) return;
    const visible = jobs.filter(job => filter === 'all' || (filter === 'done' && ['completed', 'complete'].includes(job.status)) || (filter === 'active' && ['receiving', 'queued', 'running', 'processing', 'paused'].includes(job.status)) || (filter === 'failed' && (job.failed > 0 || ['failed', 'interrupted'].includes(job.status))));
    list.replaceChildren(...(visible.length ? visible.map(job => {
      const p = percent(job.processed, job.total), active = ['queued', 'running', 'processing'].includes(job.status), sending = uploading && job.id === uploadingJobId;
      const actions = jobActions(job).map(action => button(({ start: '开始导入', pause: '暂停', resume: '继续', retry: '重试失败项', cancel: '取消任务' })[action], event => taskAction(job, action, event.currentTarget), { attrs: { disabled: (uploading && job.id === uploadingJobId) || activeActions.has(job.id) }, primary: action === 'retry' || action === 'start', ghost: action === 'cancel', icon: action === 'pause' ? 'pause' : action === 'resume' ? 'play' : undefined }));
      const next = sending ? '文件仍在传输，请保持页面打开' : active ? '服务器正在导入，现在可以关闭页面' : job.status === 'receiving' ? '传好的文件已保留，可以开始导入' : ['paused', 'interrupted'].includes(job.status) ? '可以继续处理尚未导入的文件' : job.failed > 0 || job.status === 'failed' ? '部分文件未成功，可以重试失败项' : ['cancelled', 'canceled'].includes(job.status) ? '任务已取消，已导入的照片仍保留' : '';
      const details = h('details', { class: 'task-summary-details', open: expandedJobs.has(job.id), ontoggle: event => { if (event.currentTarget.isConnected) event.currentTarget.open ? expandedJobs.add(job.id) : expandedJobs.delete(job.id); } }, h('summary', {}, '任务详情'),
        job.child_names?.length ? h('p', { class: 'task-children' }, icon('people'), `加入：${job.child_names.join('、')}`) : null,
        h('p', { class: 'muted' }, `导入 ${count(job.imported)} 份 · 重复 ${count(job.duplicates)} 份 · 未成功 ${count(job.failed)} 份`),
        h('small', { class: 'muted' }, dateText(job.updated_at || job.created_at, true)), job.message ? h('p', { class: 'muted' }, job.message) : null);
      return h('article', { class: 'task-row task-row-quiet' }, h('div', { class: 'task-type-icon' }, icon(job.source === 'upload' ? 'photo' : 'server')), h('div', { class: 'task-copy' }, h('div', { class: 'task-heading' }, h('h3', {}, job.name || '照片导入'), badge(sending ? '传输中' : job.status_label || '等待处理', job.failed ? 'warning' : ['completed', 'complete'].includes(job.status) ? 'success' : '')),
        h('p', { class: 'task-main-result' }, job.status === 'receiving' ? `已收到 ${count(job.total)} 份文件` : active || ['paused', 'interrupted'].includes(job.status) ? `已处理 ${count(job.processed)} / ${count(job.total)} 份` : `已导入 ${count(job.imported)} 份${job.failed ? ` · ${count(job.failed)} 份未成功` : ''}`), active ? progress(p) : null,
        next ? h('small', { class: 'muted task-next-step' }, next) : null, details), h('div', { class: 'task-actions' }, actions, button('查看结果', () => taskDetails(job), { ghost: true })));
    }) : [empty('暂无这类导入任务', '选择文件后，传输与导入进度会显示在这里。', null, 'folder')]));
  }
  async function refresh() {
    if (refreshBusy || !ctx.isCurrent()) return;
    refreshBusy = true;
    try { const result = await ctx.request('/api/admin/imports'); if (ctx.isCurrent()) { jobs = result.items || []; renderJobs(); } }
    catch (error) { if (error.name !== 'AbortError' && ctx.isCurrent()) list.replaceChildren(note(error.message, 'error'), button('重试', refresh)); }
    finally { refreshBusy = false; }
  }
  async function taskAction(job, action, node) {
    if (!ctx.isCurrent() || activeActions.has(job.id) || (uploading && job.id === uploadingJobId)) return;
    if (action === 'cancel' && !(await confirm('取消这个导入任务？', '已导入的照片会保留，尚未处理的文件将停止导入。', '取消任务'))) return;
    if (!ctx.isCurrent() || activeActions.has(job.id)) return;
    activeActions.add(job.id);
    await busy(node, async () => { try { await ctx.request(`/api/admin/imports/${encodeURIComponent(job.id)}/${action}`, { body: {} }); await refresh(); } catch (error) { if (ctx.isCurrent()) toast(error.message, true); } finally { activeActions.delete(job.id); if (ctx.isCurrent()) renderJobs(); } });
  }
  async function taskDetails(job) {
    const view = modal(job.name || '导入结果', ({ body }) => body.append(loading()), { drawer: true });
    try {
      const result = await ctx.request(`/api/admin/imports/${encodeURIComponent(job.id)}`); if (!ctx.isCurrent() || !view.dialog.isConnected) return;
      const statusNames = { pending: '等待导入', receiving: '接收中', uploaded: '等待导入', queued: '等待导入', running: '处理中', imported: '已导入', completed: '已完成', failed: '未成功', duplicate: '已跳过重复', skipped: '已跳过', cancelled: '已取消', canceled: '已取消' };
      if (result.items_truncated) view.footer.append(note('文件较多，这里展示前一千份的结果。上方任务进度包含全部文件。'));
      view.body.replaceChildren(note(result.message || `已导入 ${count(result.imported)} 份，跳过 ${count(result.duplicates)} 份重复内容。`), h('div', { class: 'detail-list' }, (result.items || []).map(item => h('div', { class: 'record' }, icon(item.status === 'failed' ? 'info' : 'photo'), h('div', {}, h('strong', {}, item.name || '文件'), h('p', {}, item.message || item.status_label || statusNames[item.status] || '等待处理'))))));
    } catch (error) { if (view.dialog.isConnected) view.body.replaceChildren(note(error.message, 'error')); }
  }
  async function receiveFiles(selected) {
    if (!selected.length) return;
    if (!ctx.isCurrent()) return;
    if (uploading) { toast('当前文件还在传输，请等这一批完成后再添加。', true); return; }
    const childIds = targets.values(); if (!childIds.length) { toast('请先选择这批照片所属的宝宝。', true); return; }
    const targetNames = (ctx.children || []).filter(child => childIds.includes(child.id)).map(child => child.name).join('、');
    const targetLink = () => childIds.includes(ctx.childId) ? [] : [button('查看这些宝宝的导入任务', () => ctx.refreshChildren?.(childIds[0]), { icon: 'arrow' })];
    const acceptable = selected.filter(file => /\.(jpe?g|png|webp|gif|mp4|mov|m4v|3gp)$/i.test(file.name));
    if (!acceptable.length) { toast('没有找到支持的照片或视频，请选择常见照片、MP4 或 MOV 文件。', true); return; }
    if (acceptable.length > 10000) { toast('这批文件较多，请分批选择，每次不超过一万份。', true); return; }
    if (acceptable.length < selected.length) toast(`已忽略 ${selected.length - acceptable.length} 份不支持的文件。`);
    const uploadChildId = ctx.childId;
    uploadController = new AbortController(); uploading = true; targets.disable(true); computerButton.disabled = serverButton.disabled = true;
    let uploaded = 0, completedBytes = 0, job;
    const totalBytes = acceptable.reduce((sum, file) => sum + file.size, 0), status = h('p'), bar = progress(0), current = h('small', { class: 'muted' });
    uploadPanel.hidden = false;
    uploadPanel.replaceChildren(h('div', { class: 'section-heading' }, h('h3', {}, '正在把文件传到服务器'), button('停止传输', () => uploadController.abort(), { ghost: true })), h('p', { class: 'muted' }, `加入：${targetNames}`), status, bar, current, note('这个阶段请保持页面打开。所有文件传好后，会在服务器继续导入。'));
    try {
      const folderName = acceptable[0].webkitRelativePath?.split('/')[0];
      job = await ctx.request('/api/admin/imports', { body: { name: folderName || `${dateText(new Date().toISOString())}导入`, source: 'upload', child_ids: childIds } });
      uploadingJobId = job.id; await refresh();
      for (const file of acceptable) {
        if (uploadController.signal.aborted) throw new DOMException('停止传输', 'AbortError');
        current.textContent = file.name; status.textContent = `${uploaded} / ${acceptable.length} 份 · ${bytes(completedBytes)} / ${bytes(totalBytes)}`;
        await uploadFile(`/api/admin/imports/${encodeURIComponent(job.id)}/files`, file, (loaded, size) => {
          const ratio = size ? Math.min(1, loaded / size) : 0, done = completedBytes + file.size * ratio;
          bar.firstChild.style.width = `${percent(done, totalBytes)}%`; bar.setAttribute('aria-valuenow', percent(done, totalBytes));
          status.textContent = `${uploaded} / ${acceptable.length} 份 · ${bytes(done)} / ${bytes(totalBytes)}`;
        }, uploadController.signal, uploadChildId);
        uploaded++; completedBytes += file.size;
      }
      await ctx.request(`/api/admin/imports/${encodeURIComponent(job.id)}/start`, { body: {} });
      if (ctx.isCurrent()) uploadPanel.replaceChildren(note(`${uploaded} 份文件已传好，服务器正在导入到${targetNames}的相册。现在可以关闭页面。`, 'success'), ...targetLink());
      toast('文件已传好，导入会在服务器继续');
    } catch (error) {
      if (ctx.isCurrent()) uploadPanel.replaceChildren(note(error.name === 'AbortError' ? `已停止传输。${uploaded} 份已传好的文件仍保留在${targetNames}的任务中，可点击“开始导入”。` : `${error.message} 已传好的 ${uploaded} 份文件仍保留在${targetNames}的任务中，可以先导入这一部分。`, 'warning'), ...targetLink());
    } finally { uploading = false; uploadingJobId = null; targets.disable(false); computerButton.disabled = serverButton.disabled = false; if (ctx.isCurrent()) await refresh(); }
  }
  async function directoryDialog() {
    if (uploading) { toast('当前文件还在传输，请等这一批完成后再添加。', true); return; }
    let dirty = false, saving = false;
    const view = modal('从服务器目录导入', ({ body }) => body.append(loading()), { drawer: true, isDirty: () => dirty, beforeClose: () => saving ? false : !dirty || confirm('还没有开始导入', '关闭后，这次选择的目录和宝宝需要重新填写。', '放弃填写') });
    try {
      const data = await ctx.request('/api/admin/imports/roots'); if (!ctx.isCurrent() || !view.dialog.isConnected) return;
      if (!data.items?.length) { view.body.replaceChildren(note('服务器尚未开放可导入的目录，请先在部署配置中添加。')); return; }
      const root = selectField('选择服务器目录', data.items.map(item => [item.id, item.name || '服务器图库']), data.items[0].id), path = field('子文件夹（可留空）', 'text', '', { placeholder: '例如 旅行照片/2025年' }), name = field('本次导入名称', 'text', '服务器照片导入', { required: true, maxLength: 80 });
      const result = h('div'), error = errorBox(); let scanned = null, scanRevision = 0;
      const scope = childChoices(ctx.children || [], targets.values(), '加入哪些宝宝的相册');
      const submit = button('扫描文件', null, { primary: true }), start = button('开始导入', null, { primary: true, attrs: { disabled: true, hidden: true } });
      const reset = () => { scanRevision++; scanned = null; start.disabled = true; start.hidden = true; submit.textContent = '扫描文件'; submit.classList.add('primary'); result.replaceChildren(); }; root.input.onchange = reset; path.input.oninput = reset;
      view.body.addEventListener('input', () => { dirty = true; }); view.body.addEventListener('change', () => { dirty = true; });
      submit.onclick = () => busy(submit, async () => { error.hidden = true; try { const revision = ++scanRevision, source = { root_id: root.input.value, path: path.input.value.trim() }; scanned = null; start.disabled = true; const scan = await ctx.request('/api/admin/imports/scan', { body: source }); if (revision !== scanRevision || !ctx.isCurrent() || !view.dialog.isConnected) return; scanned = source; result.replaceChildren(note(`找到 ${count(scan.count)} 份照片和视频，共 ${bytes(scan.bytes)}。其中 ${count(scan.duplicates)} 份已存在${scan.unsupported ? `，另有 ${count(scan.unsupported)} 份不支持的文件` : ''}。`)); start.disabled = !scan.count; start.hidden = !scan.count; submit.textContent = '重新扫描'; submit.classList.remove('primary'); } catch (reason) { if (view.dialog.isConnected) showError(error, reason); } });
      start.onclick = () => busy(start, async () => { error.hidden = true; try { if (!scanned || scanned.root_id !== root.input.value || scanned.path !== path.input.value.trim()) throw new Error('目录已更改，请重新扫描后再导入。'); const child_ids = scope.values(); if (!child_ids.length) throw new Error('请选择至少一位宝宝。'); saving = true; view.setBusy(true); scope.disable(true); root.input.disabled = path.input.disabled = name.input.disabled = submit.disabled = true; await ctx.request('/api/admin/imports/directory', { body: { ...scanned, name: name.input.value.trim() || '服务器照片导入', child_ids } }); dirty = false; view.close(); if (!ctx.isCurrent()) return; const names = (ctx.children || []).filter(child => child_ids.includes(child.id)).map(child => child.name).join('、'); uploadPanel.hidden = false; uploadPanel.replaceChildren(note(`目录导入已开始，照片将加入${names}的相册。宝宝范围已固定，现在可以关闭页面。`, 'success'), ...(child_ids.includes(ctx.childId) ? [] : [button('查看这些宝宝的导入任务', () => ctx.refreshChildren?.(child_ids[0]), { icon: 'arrow' })])); toast('目录导入已开始'); refresh(); } catch (reason) { if (view.dialog.isConnected) showError(error, reason); } finally { saving = false; view.setBusy(false); scope.disable(false); root.input.disabled = path.input.disabled = name.input.disabled = submit.disabled = false; } });
      view.body.replaceChildren(note('先扫描，再导入。服务器上的原文件会保留。'), root.node, path.node, name.node, scope.node, error, result);
      view.footer.append(button('取消', view.requestClose), submit, start);
    } catch (error) { if (view.dialog.isConnected) view.body.replaceChildren(note(error.message, 'error')); }
  }
  await refresh(); const timer = setInterval(refresh, 4000);
  return () => { clearInterval(timer); if (uploading) uploadController?.abort(); };
}
async function droppedFiles(transfer) {
  const entries = Array.from(transfer.items || []).map(item => item.webkitGetAsEntry?.()).filter(Boolean);
  if (!entries.length) return normalizeFiles(transfer.files);
  const result = [];
  async function visit(entry) {
    if (result.length > 10000) throw new Error('这批文件较多，请分批选择，每次不超过一万份。');
    if (entry.isFile) result.push(await new Promise((resolve, reject) => entry.file(resolve, reject)));
    else if (entry.isDirectory) {
      const reader = entry.createReader();
      while (true) { const children = await new Promise((resolve, reject) => reader.readEntries(resolve, reject)); if (!children.length) break; for (const child of children) await visit(child); }
    }
  }
  for (const entry of entries) await visit(entry);
  return result;
}
