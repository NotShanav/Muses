// util.js - pure helpers. No DOM access at import time, so `node desktopApp/web-tests/util.test.mjs` can test them.

/** 83 -> "1:23", 3725 -> "1:02:05"; bad input -> "0:00". */
export function fmtTime(sec) {
  if (!Number.isFinite(sec) || sec < 0) return '0:00';
  const s = Math.floor(sec);
  const m = Math.floor(s / 60);
  const r = String(s % 60).padStart(2, '0');
  if (m >= 60) return Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0') + ':' + r;
  return m + ':' + r;
}

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** Calls fn once, `ms` after the last call. `.cancel()` drops a pending call. */
export function debounce(fn, ms) {
  let t = 0;
  const wrapped = (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  wrapped.cancel = () => clearTimeout(t);
  return wrapped;
}

/**
 * "#/playlist/PL%20x?q=a" -> { name: "playlist", id: "PL x", query: { q: "a" } }.
 * An empty hash is the home page. A bad percent escape gives id null.
 */
export function parseHash(hash) {
  let raw = String(hash || '').replace(/^#/, '');
  if (!raw.startsWith('/')) raw = '/' + raw;
  const q = raw.indexOf('?');
  const path = q < 0 ? raw : raw.slice(0, q);
  const query = {};
  if (q >= 0) {
    for (const [k, v] of new URLSearchParams(raw.slice(q + 1))) if (!(k in query)) query[k] = v;
  }
  const parts = path.split('/').filter(Boolean);
  let id = null;
  if (parts[1]) {
    try { id = decodeURIComponent(parts.slice(1).join('/')); } catch { id = null; }
  }
  return { name: parts[0] || 'home', id, query };
}

/**
 * A smaller version of a proxied cover. `url` is "/api/art?u=<encoded>"; Google cover URLs end in
 * "=w544-h544-l90-rj" or "=s544", which can be asked for at another size. Anything else is returned
 * unchanged. The server still checks the host, so this cannot reach anywhere new.
 */
export function artSrc(url, px) {
  const prefix = '/api/art?';
  if (!url || !px || !url.startsWith(prefix)) return url || '';
  try {
    const params = new URLSearchParams(url.slice(prefix.length));
    const raw = params.get('u');
    if (!raw || !/googleusercontent\.com|ggpht\.com/.test(raw)) return url;
    const size = clamp(Math.ceil(px / 32) * 32, 32, 1200);
    let next = raw.replace(/=w\d+-h\d+(-[^/?=]*)?$/, (_, tail) => '=w' + size + '-h' + size + (tail || ''));
    if (next === raw) next = raw.replace(/=s\d+(-[^/?=]*)?$/, (_, tail) => '=s' + size + (tail || ''));
    if (next === raw) return url;
    return prefix + 'u=' + encodeURIComponent(next);
  } catch {
    return url;
  }
}

/** The last line whose start time is <= ms, or -1 before the first line. `lines` is [{ms,text}] sorted by ms. */
export function findLyricIndex(lines, ms) {
  let lo = 0;
  let hi = lines.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].ms <= ms) { found = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return found;
}

/** A shuffled copy (Fisher-Yates). `rand` is injectable for tests. */
export function shuffled(list, rand = Math.random) {
  const out = list.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

export const isHex = (c) => typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c);

/** The static two or three colour background for the "Album tint" theme, or null without 2 valid colours. */
export function tintGradient(palette) {
  if (!Array.isArray(palette)) return null;
  const c = palette.filter(isHex).slice(0, 3);
  if (c.length < 2) return null;
  const stops = c.length === 2 ? c[0] + ', ' + c[1] : c[0] + ', ' + c[1] + ' 55%, ' + c[2];
  return 'linear-gradient(rgba(0,0,0,.5), rgba(0,0,0,.5)), linear-gradient(165deg, ' + stops + ')';
}

/** A song Item from /api/home or /api/search as a queue Track. */
export function toTrack(item) {
  return {
    videoId: item.videoId || item.id,
    title: String(item.title || ''),
    artists: String(item.subtitle || ''),
    durationMs: 0,
    art: item.art || null,
  };
}

/** Where a non-song Item opens: albums, artists and playlists all use /api/playlist/{id}. */
export const itemHref = (item) => '#/playlist/' + encodeURIComponent(item.id);
