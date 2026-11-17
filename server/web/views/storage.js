import { bytes, count, dateText, percent } from '../utils.mjs';
import { h, icon, art, button, badge, heading, empty, note, loading, progress, field, selectField, errorBox, showError, modal, confirm, busy, toast } from '../ui.js';

const providers = [['local', '自己的服务器'], ['nas', 'NAS'], ['oss', '阿里云 OSS'], ['cos', '腾讯云 COS'], ['s3', '通用 S3']];
export async function storagePage(ctx) {
  let disposed = false;
  const current = () => !disposed && ctx.isCurrent();
  const storage = await ctx.request('/api/storage'); if (!current()) return;
  let tab = ctx.state.storageTab || 'locations', backups = [], timer, tabRevision = 0, planDirty = false, planSaving = false;
  const content = h('div'), tabs = h('div', { class: 'tabs page-tabs' });
  const addLocation = button('添加存放位置', () => chooseProvider(), { primary: true, icon: 'plus' });
  const leavePlan = async () => {
    if (planSaving) { toast('正在保存备份计划，请稍等'); return false; }
    return !planDirty || await confirm('还没有保存', '离开后，这次修改不会保留。', '放弃修改');
  };
  const leave = leavePlan;
  leave.dirty = () => planDirty || planSaving; ctx.setLeaveCheck?.(leave);
  ctx.container.replaceChildren(heading('存储与备份', '', [addLocation]), tabs, content);
  async function choose(next) {
    if (tab === 'plan' && !(await leavePlan())) return;
    if (!current()) return;
    planDirty = false;
    const revision = ++tabRevision; tab = next; ctx.state.storageTab = tab; clearInterval(timer);
    addLocation.hidden = tab !== 'locations';
    tabs.replaceChildren(...[['locations', '存放位置'], ['plan', '备份计划'], ['backups', '备份记录']].map(([key, label]) => button(label, () => choose(key), { ghost: true, class: key === tab ? 'tab active' : 'tab' })));
    content.replaceChildren(loading());
    try {
      if (tab === 'locations') renderLocations();
      else if (tab === 'plan') await renderPlan(revision);
      else { await refreshBackups(); if (current() && tab === 'backups') timer = setInterval(refreshBackups, 5000); }
    } catch (error) { if (error.name !== 'AbortError' && current() && revision === tabRevision) content.replaceChildren(note(error.message, 'error'), button('重试', () => choose(tab))); }
  }
  function renderLocations() {
    const items = [...(storage.items || [])].sort((a, b) => Number(!!b.is_default) - Number(!!a.is_default));
    content.replaceChildren(h('div', { class: 'storage-list-page stack' }, items.length ? h('section', { class: 'card storage-location-list' }, items.map(item => h('div', { class: 'storage-location-row' }, art(item.provider === 'local' ? 'server' : 'cloud', 'small'), h('div', { class: 'storage-copy' }, h('div', { class: 'storage-title' }, h('h2', {}, item.name), item.is_default ? badge('当前上传位置', 'warm') : null), h('p', { class: 'muted' }, `${item.provider_label || providers.find(([key]) => key === item.provider)?.[1] || '照片存放位置'} · ${count(item.media_count)} 份内容`), h('small', { class: 'muted' }, item.tested ? '上次检查通过' : '尚未检查连接')), button('查看设置', () => storageDetails(item), { ghost: true, icon: 'arrow', attrs: { 'aria-label': `查看${item.name}的设置` } })))) : empty('添加照片存放位置', '可以使用自己的服务器、NAS 或云存储。', button('选择存放方式', () => chooseProvider(), { primary: true }), 'server'), h('p', { class: 'muted' }, '存放位置由全家共用。')));
  }
  function chooseProvider(item = null) {
    modal('选择存放方式', ({ body, footer, close, requestClose }) => {
      const descriptions = { local: '后端和照片放在同一台服务器', nas: '使用服务器已挂载的 NAS 目录', oss: '使用阿里云对象存储', cos: '使用腾讯云对象存储', s3: '其他云存储或自建存储' };
      body.append(h('div', { class: 'storage-type-list' }, providers.map(([kind, label]) => h('button', { type: 'button', class: 'provider-choice', onclick: () => { close(); storageDialog(item, kind); } }, icon(kind === 'local' || kind === 'nas' ? 'server' : 'cloud'), h('div', {}, h('strong', {}, label), h('small', {}, descriptions[kind])), icon('arrow')))));
      footer.append(button('取消', requestClose));
    });
  }
  function storageDetails(item) {
    let checking = false;
    const beforeClose = () => { if (checking) { toast('正在检查连接，请稍等'); return false; } return true; };
    modal('存放位置', ({ body, footer, close, requestClose, dialog, setBusy }) => {
      const state = h('p', { class: 'muted', role: 'status' }, item.tested ? '上次检查通过' : '尚未检查连接'), error = errorBox();
      body.append(h('div', { class: 'storage-detail-summary' }, art(item.provider === 'local' ? 'server' : 'cloud', 'small'), h('div', {}, h('h3', {}, item.name), h('p', { class: 'muted' }, `${item.provider_label || '自己的服务器'} · ${count(item.media_count)} 份内容`), item.is_default ? badge('当前上传位置', 'warm') : null)), state,
        h('details', { class: 'storage-advanced' }, h('summary', {}, '查看存放地址'), h('div', { class: 'stack' }, h('p', { class: 'path-list' }, item.provider === 'local' ? item.local_path : item.bucket), item.prefix ? h('p', { class: 'path-list' }, item.prefix) : null)), error,
        h('div', { class: 'actions' }, button('编辑设置', () => { if (!checking) { close(); storageDialog(item, item.provider); } }), button('检查连接', () => check(false))));
      if (!item.is_default) footer.append(button('用于新上传', async () => {
        if (checking) return;
        if (!(await confirm('把新照片存到这里？', `之后上传的内容保存在“${item.name}”，已有照片和视频仍在原位置，不会自动搬迁。`, '用于新上传'))) return;
        if (dialog.isConnected && current()) check(true);
      }, { primary: true }));
      if (!item.is_default && !item.media_count) footer.append(button('移除位置', async () => {
        if (checking) return;
        if (!(await confirm('移除这个存放位置？', '这个位置没有相册文件，移除后可重新添加。', '移除', true)) || !dialog.isConnected) return;
        checking = true; setBusy(true);
        try { await ctx.request(`/api/storage/${encodeURIComponent(item.id)}`, { method: 'DELETE' }); if (!current() || !dialog.isConnected) return; close(); toast('存放位置已移除'); ctx.refresh(); }
        catch (reason) { if (dialog.isConnected) showError(error, reason); }
        finally { checking = false; setBusy(false); }
      }, { ghost: true, danger: true }));
      footer.append(button('完成', requestClose));
      async function check(makeDefault) {
        if (checking) return; checking = true; setBusy(true); error.hidden = true; state.textContent = '正在检查连接…';
        try {
          await ctx.request(`/api/storage/${encodeURIComponent(item.id)}/test`, { body: {} });
          if (!current() || !dialog.isConnected) return;
          if (makeDefault) { await ctx.request(`/api/storage/${encodeURIComponent(item.id)}/default`, { body: {} }); if (!current() || !dialog.isConnected) return; close(); toast('新上传位置已更新，已有文件保持原位'); ctx.refresh(); }
          else { item.tested = true; item.status_label = '连接正常'; state.textContent = '连接正常'; renderLocations(); toast('连接检查通过'); }
        } catch (reason) { if (dialog.isConnected) { state.textContent = '连接检查未完成'; showError(error, reason); } }
        finally { checking = false; setBusy(false); }
      }
    }, { beforeClose });
  }
  function storageDialog(item = null, kind = 'local') {
    let saving = false, uncertain = false, savedItem = item, changed = () => false, touchedServer = false, skipRefresh = false;
    const guard = async () => {
      if (saving) { toast('正在保存或检查连接，请稍等'); return false; }
      return uncertain || !changed() || await confirm('还没有保存', '离开后，这次修改不会保留。', '放弃修改');
    };
    guard.dirty = () => saving || (!uncertain && changed());
    modal(item ? '编辑存放位置' : `添加${providers.find(([key]) => key === kind)?.[1] || '存放位置'}`, ({ body, footer, close, requestClose, dialog, setBusy }) => {
      const local = kind === 'local' || kind === 'nas', locked = !!item?.media_count;
      const form = h('form', { class: 'stack storage-form' }), error = errorBox(), inputs = {};
      const name = field('位置名称', 'text', item?.name || (kind === 'nas' ? '家里的 NAS' : providers.find(([key]) => key === kind)?.[1] || ''), { required: true, maxLength: 80 });
      form.append(name.node);
      const advancedBody = h('div', { class: 'stack' });
      const advanced = h('details', { class: 'storage-advanced' }, h('summary', {}, local ? kind === 'nas' ? '目录帮助' : '手动填写目录' : '更多连接设置'), advancedBody);
      function input(key, label, type, value, options = {}, target = form) { const control = field(label, type, value, options); inputs[key] = control.input; target.append(control.node); return control; }
      if (local) {
        const defaultPath = storage.allowed_local_roots?.[0] || '';
        const path = input('local_path', kind === 'nas' ? '服务器上的挂载目录' : '服务器文件夹', 'text', item?.local_path || (kind === 'nas' ? '' : defaultPath), { required: true, readOnly: locked, maxLength: 1024 }, kind === 'nas' ? form : advancedBody);
        if (kind === 'nas') form.append(h('p', { class: 'muted' }, '先在服务器挂载 NAS，再填写目录。'));
        else {
          const label = h('p', { class: 'muted' });
          const describe = () => { label.textContent = locked ? '照片文件夹已设置' : path.input.value === defaultPath && defaultPath ? '使用服务器默认照片文件夹' : path.input.value ? '使用自定义照片文件夹' : '请填写照片文件夹'; };
          path.input.addEventListener('input', describe); describe(); form.append(label); advanced.open = !path.input.value;
          if (defaultPath && !locked) advancedBody.append(button('使用默认文件夹', () => { path.input.value = defaultPath; path.input.dispatchEvent(new Event('input', { bubbles: true })); }, { ghost: true }));
          if (locked) advanced.querySelector('summary').textContent = '查看照片目录';
        }
        if (storage.allowed_local_roots?.length) advancedBody.append(h('details', {}, h('summary', {}, '查看可用目录'), h('div', { class: 'path-list' }, storage.allowed_local_roots.map(path => h('p', {}, path)))));
      } else {
        if (kind === 's3') input('endpoint', '服务地址', 'url', item?.endpoint || '', { required: true, readOnly: locked, maxLength: 1024, placeholder: '例如 https://storage.example.com' });
        input('bucket', '存储桶名称', 'text', item?.bucket || '', { required: true, readOnly: locked, maxLength: 63, placeholder: kind === 'cos' ? '完整的存储桶名称' : '云控制台中的存储桶名称' });
        input('region', '存储区域', 'text', item?.region || (kind === 's3' ? 'us-east-1' : ''), { required: kind !== 's3', readOnly: locked, maxLength: 50, placeholder: kind === 'cos' ? '例如 ap-guangzhou' : kind === 's3' ? '例如 us-east-1' : '例如 cn-hangzhou' }, kind === 's3' ? advancedBody : form);
        input('access_key_id', '访问密钥 ID', 'password', '', { required: !item?.has_credentials, maxLength: 8192, autocomplete: 'off', placeholder: item?.has_credentials ? '留空保留原密钥' : kind === 'cos' ? 'SecretId' : 'AccessKey ID' });
        input('secret_access_key', '访问密钥 Secret', 'password', '', { required: !item?.has_credentials, maxLength: 8192, autocomplete: 'off', placeholder: item?.has_credentials ? '留空保留原密钥' : kind === 'cos' ? 'SecretKey' : 'AccessKey Secret' });
        if (item?.has_credentials) form.append(h('p', { class: 'muted' }, '更换密钥时，两项一起填写。'));
        input('prefix', '照片子目录', 'text', item?.prefix || '', { readOnly: locked, maxLength: 600, placeholder: '选填' }, advancedBody);
        if (kind !== 's3') {
          const automatic = kind === 'oss' ? `https://oss-${item?.region}.aliyuncs.com` : `https://cos.${item?.region}.myqcloud.com`;
          input('endpoint', '服务地址', 'url', item?.endpoint?.toLowerCase() === automatic.toLowerCase() && !locked ? '' : item?.endpoint || '', { readOnly: locked, maxLength: 1024, placeholder: '留空时按区域填写' }, advancedBody);
        }
        input('session_token', '临时凭证', 'password', '', { maxLength: 8192, autocomplete: 'off', placeholder: item?.has_credentials ? '留空保留原凭证' : '使用临时密钥时填写' }, advancedBody);
        const addressing = selectField('访问方式', [['auto', '自动选择'], ['path', '路径形式（适合自建）'], ['virtual', '域名形式']], item?.addressing_style || 'auto', { disabled: locked }); inputs.addressing_style = addressing.input; advancedBody.append(addressing.node);
      }
      form.append(advanced);
      if (locked) form.append(h('p', { class: 'muted' }, '这里已有照片，可改名称和密钥；换地方请新增位置。'));
      if (item && !locked) form.append(button('更换存放方式', async () => { if (await guard()) { skipRefresh = true; close(); chooseProvider(savedItem); } }, { ghost: true }));
      const snapshot = () => JSON.stringify([name.input.value, ...Object.values(inputs).map(control => control.value)]);
      let baseline = snapshot(); changed = () => snapshot() !== baseline;
      const save = button(item ? '保存并检查连接' : '连接并保存', null, { primary: true, attrs: { type: 'submit' } });
      const review = button('返回存放位置列表', () => { if (uncertain) close(); else requestClose(); }, { ghost: true, attrs: { hidden: true } });
      form.append(error, review, save); body.append(form); footer.append(button('取消', requestClose));
      form.onsubmit = async event => {
        event.preventDefault(); if (saving || uncertain || !current()) return;
        for (const control of form.querySelectorAll('input,select')) if (!control.checkValidity()) { for (let parent = control.parentElement; parent && parent !== form; parent = parent.parentElement) if (parent.tagName === 'DETAILS') parent.open = true; control.reportValidity(); return; }
        const data = { name: name.input.value.trim(), provider: kind === 'nas' ? 'local' : kind };
        for (const [key, control] of Object.entries(inputs)) if (!['access_key_id', 'secret_access_key', 'session_token'].includes(key) || control.value) data[key] = key === 'secret_access_key' ? control.value : control.value.trim();
        if (!data.name) { showError(error, new Error('请填写存放位置的名称。')); return; }
        if (!local && !!data.access_key_id !== !!data.secret_access_key) { showError(error, new Error('更换密钥时，请一起填写两项。')); return; }
        if (locked) for (const key of ['provider', 'local_path', 'endpoint', 'region', 'bucket', 'prefix', 'addressing_style']) delete data[key];
        const submitted = snapshot(), hadId = !!savedItem?.id; let saved = false;
        const controls = [...form.querySelectorAll('input,select,button')].map(control => [control, control.disabled]);
        saving = true; error.hidden = true; setBusy(true); controls.forEach(([control]) => { control.disabled = true; }); save.textContent = '正在保存并检查连接…';
        try {
          let result;
          try {
            result = await ctx.request(hadId ? `/api/storage/${encodeURIComponent(savedItem.id)}` : '/api/storage', { method: hadId ? 'PUT' : 'POST', body: data });
            if (!result?.id) throw new Error('暂时无法确认保存结果。');
          } catch (reason) { if (!hadId && !(reason.status >= 400 && reason.status < 500)) uncertain = true; throw reason; }
          savedItem = result; saved = true; touchedServer = true; baseline = submitted;
          if (inputs.access_key_id) { inputs.access_key_id.required = false; inputs.secret_access_key.required = false; }
          if (!current() || !dialog.isConnected) return;
          await ctx.request(`/api/storage/${encodeURIComponent(savedItem.id)}/test`, { body: {} });
          if (!current() || !dialog.isConnected) return;
          close(); toast('连接正常，存放位置已保存');
        } catch (reason) {
          if (dialog.isConnected && current()) { review.hidden = !(saved || uncertain); showError(error, uncertain ? new Error('暂时无法确认是否保存成功，请返回列表查看，避免重复添加。') : saved ? new Error(`设置已保存，连接检查未完成。${reason.message}`) : reason); }
        } finally {
          saving = false; setBusy(false); controls.forEach(([control, disabled]) => { control.disabled = disabled; }); save.disabled = uncertain; save.textContent = savedItem ? '保存并检查连接' : '连接并保存';
        }
      };
    }, { beforeClose: guard, isDirty: guard.dirty, onClose: async () => {
      if (skipRefresh || !(touchedServer || uncertain) || !current()) return;
      const revision = tabRevision;
      try { const latest = await ctx.request('/api/storage'); if (current() && revision === tabRevision && tab === 'locations') { Object.assign(storage, latest); renderLocations(); } }
      catch (reason) { if (current() && revision === tabRevision) toast('列表暂时未能更新，请刷新后确认存放位置。', true); }
    } });
  }
  function backupDialog() {
    modal('立即备份', ({ body, footer, close, requestClose, dialog, setBusy }) => {
      const mode = selectField('备份内容', [['full', '相册资料与全部原文件'], ['metadata', '仅相册资料']], 'full'), error = errorBox();
      body.append(mode.node, h('p', { class: 'muted' }, '包含所有宝宝，完成后可下载留存。'), h('details', { class: 'storage-advanced' }, h('summary', {}, '备份包含什么'), h('div', { class: 'stack' }, h('p', {}, '相册资料包含拍摄时间、故事、家人、收藏和留言。'), h('p', {}, '完整备份还包含照片和视频原件，需要足够的服务器空间。'))), error);
      const submit = button('开始备份', null, { primary: true });
      submit.onclick = () => busy(submit, async () => { setBusy(true); try { await ctx.request('/api/admin/backups', { body: { mode: mode.input.value } }); if (!dialog.isConnected || !current()) return; close(); toast('备份已开始，关闭页面也会继续'); choose('backups'); } catch (reason) { if (dialog.isConnected) showError(error, reason); } finally { setBusy(false); } });
      footer.append(button('取消', requestClose), submit);
    });
  }
  async function renderPlan(revision) {
    const plan = await ctx.request('/api/admin/backups/plan'); if (!current() || tab !== 'plan' || revision !== tabRevision) return;
    const form = h('form', { class: 'card backup-plan stack' }), error = errorBox();
    const enabled = h('input', { type: 'checkbox', checked: !!plan.enabled });
    const frequency = selectField('备份频率', [['daily', '每天'], ['weekly', '每周']], plan.frequency || 'daily');
    const time = field('开始时间', 'time', plan.time || '03:00', { required: true });
    const weekday = selectField('每周哪一天', [['0', '星期一'], ['1', '星期二'], ['2', '星期三'], ['3', '星期四'], ['4', '星期五'], ['5', '星期六'], ['6', '星期日']], String(plan.weekday || 0));
    const mode = selectField('备份内容', [['full', '相册资料与全部原文件'], ['metadata', '仅相册资料']], plan.mode || 'full');
    const keep = field('保留最近几份备份', 'number', plan.keep || 7, { min: 1, max: 90, required: true });
    const save = button('保存备份计划', null, { primary: true, attrs: { type: 'submit' } });
    const fields = h('div', { class: 'stack' }, h('div', { class: 'form-grid' }, frequency.node, time.node, weekday.node, mode.node, keep.node), h('p', { class: 'muted' }, '按中国标准时间执行，包含所有宝宝。'), h('details', { class: 'storage-advanced' }, h('summary', {}, '了解备份保留规则'), h('p', {}, '服务器自动执行备份，不需要保持网页打开。超过保留数量时，只清理较早且已完成的备份文件。')));
    const values = () => ({ enabled: enabled.checked, frequency: frequency.input.value, time: time.input.value, weekday: Number(weekday.input.value), mode: mode.input.value, keep: Number(keep.input.value) });
    let baseline = JSON.stringify(values());
    const draw = () => { weekday.node.hidden = frequency.input.value !== 'weekly'; fields.hidden = !enabled.checked; planDirty = JSON.stringify(values()) !== baseline; };
    form.addEventListener('input', draw); form.addEventListener('change', draw); draw();
    const nextRun = h('p', { class: 'muted', hidden: !plan.next_run }, plan.next_run ? `下一次：${dateText(plan.next_run, true)}` : '');
    form.append(h('h2', {}, '自动备份'), h('label', { class: 'switch-line' }, enabled, h('span', {}, '开启自动备份')), fields, nextRun, error, save);
    form.onsubmit = async event => {
      event.preventDefault(); if (planSaving) return;
      const data = values();
      if (!Number.isInteger(data.keep) || data.keep < 1 || data.keep > 90 || !/^\d{2}:\d{2}$/.test(data.time)) { showError(error, new Error('请填写备份时间，并保留 1～90 份备份。')); return; }
      const controls = [...form.querySelectorAll('input,select,button')]; planSaving = true; error.hidden = true; controls.forEach(control => { control.disabled = true; });
      try { const result = await ctx.request('/api/admin/backups/plan', { method: 'PUT', body: data }); if (!current() || revision !== tabRevision) return; baseline = JSON.stringify(data); planDirty = false; nextRun.hidden = !result.next_run; nextRun.textContent = result.next_run ? `下一次：${dateText(result.next_run, true)}` : ''; toast('备份计划已保存'); }
      catch (reason) { if (current() && revision === tabRevision) showError(error, reason); }
      finally { planSaving = false; controls.forEach(control => { control.disabled = false; }); }
    };
    content.replaceChildren(form);
  }
  let refreshing = false;
  async function refreshBackups() {
    if (refreshing || !current() || tab !== 'backups') return;
    refreshing = true;
    try {
      const result = await ctx.request('/api/admin/backups'); if (!current() || tab !== 'backups') return; backups = result.items || [];
      content.replaceChildren(h('div', { class: 'section-heading' }, h('h2', {}, '备份记录'), button('立即备份', backupDialog, { primary: true, icon: 'shield' })),
        backups.length ? h('div', { class: 'stack' }, backups.map(backup => {
          const done = ['completed', 'complete'].includes(backup.status), working = ['queued', 'running', 'processing'].includes(backup.status);
          return h('article', { class: 'card backup-record' }, h('div', { class: 'backup-icon' }, icon('shield')), h('div', { class: 'backup-copy' }, h('div', { class: 'task-heading' }, h('h3', {}, backup.name || (backup.mode === 'full' ? '完整相册备份' : '相册资料备份')), badge(backup.status_label || '等待处理', done ? 'success' : backup.status === 'failed' ? 'warning' : '')), h('p', {}, `${dateText(backup.created_at, true)} · ${backup.mode === 'full' ? '相册资料与原文件' : '仅相册资料'}${backup.bytes ? ` · ${bytes(backup.bytes)}` : ''}`), working ? progress(percent(backup.processed, backup.total)) : null, h('small', { class: 'muted' }, backup.message || (backup.verified ? '备份已核对，可以用于恢复' : done ? '可下载到其他设备另存一份' : '备份由服务器继续处理'))),
            done ? h('div', { class: 'backup-actions' }, h('a', { class: 'button', href: `/api/admin/backups/${encodeURIComponent(backup.id)}/download`, download: '' }, icon('download'), '下载'), button('核对备份', event => busy(event.target.closest('button'), async () => { try { const result = await ctx.request(`/api/admin/backups/${encodeURIComponent(backup.id)}/verify`, { body: {} }); toast(result.message || '备份完整性核对通过'); refreshBackups(); } catch (error) { toast(error.message, true); } })), button('恢复副本', () => restoreDialog(backup), { ghost: true })) : null);
        })) : empty('还没有备份记录', '可以先保存一份完整备份，再设置适合自己的自动备份计划。', button('创建第一份备份', backupDialog, { primary: true }), 'shield'));
    } catch (error) { if (error.name !== 'AbortError' && current() && tab === 'backups') content.replaceChildren(note(error.message, 'error'), button('重试', refreshBackups)); }
    finally { refreshing = false; }
  }
  async function restoreDialog(backup) {
    let restoring = false, passwordInput = null;
    const view = modal('恢复相册副本', ({ body }) => body.append(loading()), {
      isDirty: () => restoring || !!passwordInput?.value,
      beforeClose: async () => {
        if (restoring) { toast('正在恢复副本，请稍等'); return false; }
        return !passwordInput?.value || await confirm('还没有开始恢复', '离开后，输入的密码不会保留。', '离开');
      },
    });
    try {
      const preview = await ctx.request(`/api/admin/backups/${encodeURIComponent(backup.id)}/restore-preview`, { body: {} }); if (!view.dialog.isConnected || !current()) return;
      const password = field('当前管理员密码', 'password', '', { required: true, autocomplete: 'current-password' }), error = errorBox();
      passwordInput = password.input;
      const form = h('form', { class: 'stack' }, h('h3', {}, preview.name || backup.name), h('p', {}, `${dateText(preview.created_at, true)} · ${count(preview.photos)} 张照片 · ${count(preview.videos)} 个视频 · ${count(preview.members)} 位家人`), note('恢复为独立副本，当前相册不会被覆盖。需要由部署者切换数据目录后使用。'), preview.mode === 'metadata' ? note('这份备份没有原照片和视频，恢复后需接回原文件。', 'warning') : null, password.node, error);
      const submit = button('恢复为独立副本', null, { primary: true, attrs: { type: 'submit' } }); form.append(submit); view.body.replaceChildren(form); view.footer.append(button('取消', view.requestClose));
      form.onsubmit = async event => {
        event.preventDefault(); if (restoring || !current()) return;
        if (!password.input.value) { showError(error, new Error('请填写当前管理员密码。')); return; }
        const currentPassword = password.input.value;
        restoring = true; view.setBusy(true); error.hidden = true; password.input.disabled = true; submit.disabled = true;
        try {
          const result = await ctx.request(`/api/admin/backups/${encodeURIComponent(backup.id)}/restore`, { body: { current_password: currentPassword }, noExpire: true });
          if (!view.dialog.isConnected || !current()) return;
          password.input.value = '';
          view.body.replaceChildren(note(result.message || '相册副本已恢复，当前相册保持原样。', 'success'), h('p', {}, '恢复副本所在目录：'), h('p', { class: 'path-list' }, result.directory || ''), h('p', { class: 'muted' }, '由部署者切换到这个目录后，使用备份中的账号登录。')); view.footer.replaceChildren(button('完成', view.close, { primary: true }));
        } catch (reason) { if (view.dialog.isConnected && current()) showError(error, reason); }
        finally { restoring = false; view.setBusy(false); password.input.disabled = false; submit.disabled = false; }
      };
    } catch (error) { if (view.dialog.isConnected) view.body.replaceChildren(note(error.message, 'error')); }
  }
  await choose(tab);
  return () => { disposed = true; tabRevision++; clearInterval(timer); };
}
