export class ApiError extends Error {
  constructor(message, status, data = {}) { super(message); this.status = status; this.data = data; }
}
let csrf = '';
let selectedChild = '';
let onExpired = () => {};
export function setSession(session) { csrf = session?.csrf || ''; if (!session) selectedChild = ''; }
export function setChild(childId) { selectedChild = childId || ''; }
export function expiredHandler(handler) { onExpired = handler; }
export async function api(path, options = {}) {
  const method = options.method || (options.body === undefined ? 'GET' : 'POST');
  const headers = { ...(options.headers || {}) };
  const childId = options.childId === undefined ? selectedChild : options.childId;
  if (childId) headers['X-FAM-Child'] = childId;
  let body = options.body;
  if (body !== undefined && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  if (csrf && !['GET', 'HEAD'].includes(method)) headers['X-FAM-CSRF'] = csrf;
  let response;
  try { response = await fetch(path, { ...options, method, body, headers, credentials: 'same-origin', cache: 'no-store' }); }
  catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new ApiError('暂时无法连接相册服务器，请检查网络后重试。', 0);
  }
  if (!response.ok) {
    let result = {};
    try { result = await response.json(); } catch {}
    if (response.status === 401 && !options.noExpire) onExpired();
    const message = typeof result.detail === 'string' ? result.detail : '操作没有完成，请稍后重试。';
    throw new ApiError(message, response.status, result);
  }
  if (options.blob) return response.blob();
  if (response.status === 204) return {};
  try { return await response.json(); }
  catch { throw new ApiError('服务器返回的结果暂时无法确认，请稍后重新读取。', 0); }
}
export function uploadFile(path, file, onProgress, signal, childId = selectedChild) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', path); request.withCredentials = true;
    if (csrf) request.setRequestHeader('X-FAM-CSRF', csrf);
    if (childId) request.setRequestHeader('X-FAM-Child', childId);
    const abort = () => request.abort();
    signal?.addEventListener('abort', abort, { once: true });
    request.upload.onprogress = event => onProgress?.(event.loaded, event.lengthComputable ? event.total : file.size);
    request.onload = () => {
      signal?.removeEventListener('abort', abort);
      let result = {};
      try { result = JSON.parse(request.responseText); } catch {}
      if (request.status >= 200 && request.status < 300) resolve(result);
      else {
        if (request.status === 401) onExpired();
        reject(new ApiError(typeof result.detail === 'string' ? result.detail : '文件没有上传成功，请重试。', request.status));
      }
    };
    request.onerror = () => { signal?.removeEventListener('abort', abort); reject(new ApiError('上传中断，请检查网络后重试。', 0)); };
    request.onabort = () => { signal?.removeEventListener('abort', abort); reject(new DOMException('上传已停止', 'AbortError')); };
    const data = new FormData(); data.append('file', file, file.name);
    if (file.lastModified) data.append('fallback_captured_at', new Date(file.lastModified).toISOString());
    request.send(data);
  });
}
export function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob), link = document.createElement('a');
  link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
