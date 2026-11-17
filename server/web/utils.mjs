export const PAGES = Object.freeze([
  ['overview', '概览', 'home'], ['media', '照片与视频', 'photo'],
  ['imports', '导入与任务', 'upload'], ['family', '宝宝与家人', 'people'],
  ['storage', '存储与备份', 'storage'], ['settings', '设置', 'settings'],
]);
export function pageFromHash(hash) {
  const key = String(hash || '').replace(/^#\/?/, '').split('?')[0];
  return PAGES.some(([name]) => name === key) ? key : 'overview';
}
export function bytes(value) {
  const number = Math.max(0, Number(value) || 0);
  if (number < 1024) return `${number} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = number / 1024, unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
  return `${size.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ${units[unit]}`;
}
export function count(value) { return Math.max(0, Number(value) || 0).toLocaleString('zh-CN'); }
export function dateText(value, withTime = false) {
  if (!value) return '尚无记录';
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return '时间待确认';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}),
  }).format(date);
}
export function duration(value) {
  const seconds = Math.max(0, Math.floor(Number(value) || 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
export function percent(done, total) {
  return total > 0 ? Math.max(0, Math.min(100, Math.round(Number(done || 0) / Number(total) * 100))) : 0;
}
export function safeMediaUrl(value) {
  if (typeof value !== 'string' || !/^\/api\/media\/[a-zA-Z0-9_.~-]+\/(thumbnail|file)(\?child_id=[a-zA-Z0-9_-]+)?$/.test(value)) return '';
  return value;
}
export function safeAvatarUrl(value) {
  return safeMediaUrl(value) || (typeof value === 'string' && /^\/api\/family\/avatar(\?child_id=[a-zA-Z0-9_-]+)?$/.test(value) ? value : '');
}
export function isFamilyAdmin(member) { return member?.is_family_admin === true || member?.family_role === 'admin' || (member?.is_family_admin === undefined && member?.family_role === undefined && member?.role === 'admin'); }
export function localTimeInput(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return '';
  return new Date(date.valueOf() + 8 * 3600000).toISOString().slice(0, 16);
}
export function captureTime(value) { return value ? `${value}:00+08:00` : ''; }
export function mediaQuery(filters, offset = 0) {
  const query = new URLSearchParams({ kind: filters.kind || 'all', deleted: String(!!filters.deleted), offset: String(Math.max(0, offset)), limit: '40' });
  for (const name of ['search', 'uploader_id', 'date_from', 'date_to']) if (filters[name]) query.set(name, filters[name]);
  return query.toString();
}
export function validUsername(value) { return /^[a-z0-9][a-z0-9_.-]{2,31}$/i.test(String(value).trim()); }
export function jobActions(job) {
  if (job.status === 'receiving') return ['start', 'cancel'];
  if (['queued', 'running', 'processing'].includes(job.status)) return ['pause', 'cancel'];
  if (['paused', 'interrupted'].includes(job.status)) return job.failed > 0 ? ['resume', 'retry', 'cancel'] : ['resume', 'cancel'];
  if (job.status === 'failed') return ['retry', 'cancel'];
  if (job.failed > 0 && !['cancelled', 'canceled'].includes(job.status)) return ['retry'];
  return [];
}
export function normalizeFiles(files) {
  return Array.from(files || []).filter(file => file && Number.isFinite(file.size) && file.size >= 0);
}
