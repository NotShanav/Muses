// api.js - the only place the page talks to the server. Same origin, session cookie, X-Muses-Web on
// every fetch, the {error, code} envelope turned into ApiError, one retry for GET on network trouble.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Fires 'auth' when the server answers 403 (cookie from an older launch, or a host that is not allowed). */
export const authEvents = new EventTarget();

const BASE_HEADERS = { 'X-Muses-Web': '1', Accept: 'application/json' };
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function errorFrom(res) {
  let code = 'error';
  let message = res.statusText || 'Request failed';
  try {
    const body = await res.json();
    if (body && typeof body.code === 'string') code = body.code;
    if (body && typeof body.error === 'string') message = body.error;
  } catch { /* not JSON */ }
  return new ApiError(res.status, code, message);
}

async function send(method, path, { body, signal, raw, type } = {}) {
  const init = { method, headers: { ...BASE_HEADERS }, credentials: 'same-origin', signal };
  if (raw !== undefined) {
    init.headers['Content-Type'] = type || 'application/octet-stream';
    init.body = raw;
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  if (!res.ok) {
    const err = await errorFrom(res);
    if (res.status === 403) authEvents.dispatchEvent(new Event('auth'));
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

const retryable = (e) => e instanceof TypeError || (e instanceof ApiError && [502, 503, 504].includes(e.status));

/** GET JSON. Retries once after 400 ms on a network error or 502/503/504. Never retries an abort. */
export async function get(path, opts = {}) {
  try {
    return await send('GET', path, opts);
  } catch (e) {
    if (!retryable(e) || (opts.signal && opts.signal.aborted)) throw e;
    await sleep(400);
    return send('GET', path, opts);
  }
}

/** POST JSON, no retry (adding to a playlist twice is not harmless). */
export const post = (path, body, opts = {}) => send('POST', path, { ...opts, body });

/** POST raw bytes (WEB4 uses this for /api/voice). */
export const postRaw = (path, raw, type, opts = {}) => send('POST', path, { ...opts, raw, type });

const enc = encodeURIComponent;

export const api = {
  ping: (signal) => get('/api/ping', { signal }),
  home: (signal) => get('/api/home', { signal }),
  search: (q, signal) => get('/api/search?q=' + enc(q), { signal }),
  suggest: (q, signal) => get('/api/suggest?q=' + enc(q), { signal }),
  library: (signal) => get('/api/library', { signal }),
  playlist: (id, signal) => get('/api/playlist/' + enc(id), { signal }),
  track: (videoId, signal) => get('/api/track/' + enc(videoId), { signal }),
  lyrics: (videoId, signal) => get('/api/lyrics/' + enc(videoId), { signal }),
  like: (videoId, liked) => post('/api/like/' + enc(videoId), { liked: !!liked }),
  add: (playlistId, videoId) => post('/api/playlist/' + enc(playlistId) + '/add', { videoId }),
};

/**
 * The audio URL for <audio src>. The browser sends the cookie itself (no custom header possible).
 * `low` adds the "?q=low" quality hint; the server ignores it today (WEB4 setting, see docs/WEB_SERVER.md).
 */
export const streamUrl = (videoId, low = false) => '/api/stream/' + enc(videoId) + (low ? '?q=low' : '');
