// player.js - one <audio>, a queue, shuffle, repeat, volume, like (WEB3) plus, from WEB4: warm-up of the
// next track on a second hidden Audio, resume from localStorage (ids and position only), one automatic
// retry per track, a quality hint. Events (player.on): track, details, queue, state, time, volume, error.
// 'time' fires at most 4 times a second and never while the tab is hidden. No timers run when idle.
import { api, streamUrl } from './api.js';
import { shuffled, clamp } from './util.js';

const bus = new EventTarget();
const audio = new Audio();
const pre = new Audio(); // hidden, never played: only warms up the next track (one extra connection)

const K_RESUME = 'muses.resume';
const K_VOL = 'muses.vol';
const K_QUALITY = 'muses.quality';
const PRELOAD_AT = 20;       // seconds left when the next track is warmed up
const SAVE_EVERY_MS = 10000; // position is written at most this often while playing
const MAX_RESUME_IDS = 200;
const ID_RE = /^[\w-]{5,32}$/;

let queue = [];      // Track objects in play order
let original = [];   // the order before shuffling
let index = -1;
let shuffleOn = false;
let repeat = 'off';  // 'off' | 'all' | 'one'
let details = null;  // /api/track answer for the current song
let detailsCtl = null;
let lastTime = 0;
let lastSave = 0;
let failStreak = 0;
let retriedFor = null; // the Track that already had its one automatic retry
let startAt = 0;       // seconds to seek to when metadata arrives (resume and retry)
let preFor = '';       // videoId currently warmed up on `pre`
let hydrating = false;
let volumeProbe = null;
const tried = new WeakSet(); // tracks whose details were already asked for (no refetch loops)

pre.preload = 'none';
pre.muted = true;

const emit = (type, detail) => bus.dispatchEvent(new CustomEvent(type, { detail }));

// ---- storage (every access guarded: it can be blocked or missing) -------------------------------------
function read(key) {
  try { return typeof localStorage === 'undefined' ? null : localStorage.getItem(key); } catch { return null; }
}
function write(key, value) {
  try {
    if (typeof localStorage === 'undefined') return;
    if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value);
  } catch { /* storage blocked: nothing is resumed next time */ }
}

const quality = () => (read(K_QUALITY) === 'low' ? 'low' : 'auto');
const srcFor = (videoId) => streamUrl(videoId, quality() === 'low');

// ---- resume: ids, index and position only (no names, no search text) ----------------------------------
function saveResume(force, pos) {
  if (!queue.length || index < 0) return;
  const now = performance.now();
  if (!force && now - lastSave < SAVE_EVERY_MS) return;
  lastSave = now;
  let start = 0;
  if (queue.length > MAX_RESUME_IDS) start = clamp(index - 20, 0, queue.length - MAX_RESUME_IDS);
  const ids = queue.slice(start, start + MAX_RESUME_IDS).map((t) => t.videoId);
  const at = pos !== undefined ? pos : audio.currentTime || 0;
  write(K_RESUME, JSON.stringify({ v: 1, ids, i: index - start, pos: Math.floor(Math.max(0, at)), s: shuffleOn ? 1 : 0, r: repeat }));
}

/** Pure: a stored string -> { ids, i, pos, s, r } or null. Exported through `player.parseResume` for tests. */
function parseResume(raw) {
  if (!raw) return null;
  let o;
  try { o = JSON.parse(raw); } catch { return null; }
  if (!o || o.v !== 1 || !Array.isArray(o.ids)) return null;
  const ids = o.ids.filter((x) => typeof x === 'string' && ID_RE.test(x)).slice(0, MAX_RESUME_IDS);
  if (ids.length !== o.ids.length || !ids.length) return null;
  const i = Number.isInteger(o.i) && o.i >= 0 && o.i < ids.length ? o.i : 0;
  const pos = Number.isFinite(o.pos) && o.pos >= 0 ? o.pos : 0;
  return { ids, i, pos, s: o.s === 1, r: ['off', 'all', 'one'].includes(o.r) ? o.r : 'off' };
}

/** Puts the last queue back, paused, at the saved position. True when something was restored. */
function restore() {
  if (queue.length) return false;
  const saved = parseResume(read(K_RESUME));
  if (!saved) return false;
  queue = saved.ids.map((id) => ({ videoId: id, title: '', artists: '', durationMs: 0, art: null }));
  original = queue.slice();
  shuffleOn = saved.s;
  repeat = saved.r;
  emit('queue');
  load(saved.i, { autoplay: false, startAt: saved.pos });
  return true;
}

// ---- warm-up of the next track -------------------------------------------------------------------------
function nextIndex() {
  if (!queue.length || repeat === 'one') return -1;
  if (index + 1 < queue.length) return index + 1;
  return repeat === 'all' && queue.length > 1 ? 0 : -1;
}

function dropPreload() {
  preFor = '';
  if (pre.removeAttribute) pre.removeAttribute('src');
  if (pre.load) pre.load();
}

function maybePreload() {
  const d = audio.duration;
  const t = audio.currentTime;
  if (!Number.isFinite(d) || t < 1 || d - t > PRELOAD_AT) return;
  const at = nextIndex();
  if (at < 0) return;
  const next = queue[at];
  if (!next || preFor === next.videoId) return;
  const cur = queue[index];
  preFor = next.videoId;
  // Resolving the details first makes the server look the stream up; then one hidden element reads it.
  api.track(next.videoId).then((d2) => {
    fill(next, d2);
    if (preFor !== next.videoId || queue[index] !== cur) return;
    pre.preload = 'auto';
    pre.src = srcFor(next.videoId);
  }).catch(() => { if (preFor === next.videoId) preFor = ''; });
}

// ---- details and names for tracks that only have an id ------------------------------------------------
function fill(track, d) {
  if (!track || !d) return false;
  let changed = false;
  if (!track.title && typeof d.title === 'string') { track.title = d.title; changed = true; }
  if (!track.artists && Array.isArray(d.artists)) { track.artists = d.artists.join(', '); changed = true; }
  if (!track.art && d.art) { track.art = d.art.small || d.art.large || null; changed = true; }
  if (!track.durationMs && Number.isFinite(d.durationMs)) track.durationMs = d.durationMs;
  return changed;
}

/** Asks /api/track for up to `n` upcoming tracks that have no title yet, one at a time. */
async function hydrateQueue(n = 8) {
  if (hydrating) return;
  hydrating = true;
  try {
    const todo = queue.slice(Math.max(0, index), Math.max(0, index) + n).filter((t) => !t.title && !tried.has(t));
    for (const t of todo) {
      tried.add(t);
      try {
        const d = await api.track(t.videoId);
        if (fill(t, d)) emit('queue');
      } catch { /* the row stays "Song" */ }
    }
  } finally {
    hydrating = false;
  }
}

// ---- core -----------------------------------------------------------------------------------------------
function play() {
  if (!audio.src) return;
  const p = audio.play();
  if (p && p.catch) {
    p.catch((e) => {
      if (e && e.name !== 'AbortError') emit('error', 'Playback was blocked. Press play again.');
    });
  }
}

function load(i, opts = {}) {
  index = i;
  const track = queue[i];
  details = null;
  retriedFor = null;
  startAt = opts.startAt > 0 ? opts.startAt : 0;
  if (detailsCtl) detailsCtl.abort();
  detailsCtl = new AbortController();
  dropPreload();
  audio.preload = opts.autoplay === false ? 'metadata' : 'auto';
  audio.src = srcFor(track.videoId);
  emit('track');
  if (opts.autoplay !== false) play();
  tried.add(track);
  api.track(track.videoId, detailsCtl.signal)
    .then((d) => {
      if (queue[index] !== track) return;
      details = d;
      const named = fill(track, d);
      if (named) emit('track');
      emit('details');
    })
    .catch(() => { /* the title and art from the list are enough */ });
  saveResume(true, opts.startAt || 0);
}

function playList(tracks, start = 0, opts = {}) {
  failStreak = 0;
  const list = tracks.filter((t) => t && t.videoId);
  if (!list.length) return;
  if (opts.shuffle !== undefined) shuffleOn = !!opts.shuffle;
  const chosen = tracks[start];
  const first = list.includes(chosen) ? chosen : list[0];
  original = list.slice();
  if (shuffleOn) {
    queue = [first, ...shuffled(list.filter((t) => t !== first))];
    index = 0;
  } else {
    queue = list.slice();
    index = queue.indexOf(first);
  }
  emit('queue');
  load(index);
}

/** Play now: this one song, replacing the queue. */
const playNow = (track) => playList([track], 0);

function enqueue(track) {
  if (!track || !track.videoId) return;
  if (!queue.length) { playList([track], 0); return; }
  queue.push(track);
  original.push(track);
  emit('queue');
  saveResume(true);
}

function playNext(track) {
  if (!track || !track.videoId) return;
  if (!queue.length) { playList([track], 0); return; }
  queue.splice(index + 1, 0, track);
  const at = original.indexOf(queue[index]);
  if (at < 0) original.push(track); else original.splice(at + 1, 0, track);
  emit('queue');
  saveResume(true);
}

function jumpTo(i) {
  failStreak = 0;
  if (i >= 0 && i < queue.length) load(i);
}

function toggle() {
  if (!queue.length) return;
  if (audio.paused) {
    if (audio.ended) audio.currentTime = 0;
    play();
  } else {
    audio.pause();
  }
}

function duration() {
  const d = audio.duration;
  if (Number.isFinite(d) && d > 0) return d;
  const t = queue[index];
  return t && t.durationMs ? t.durationMs / 1000 : 0;
}

function seek(sec) {
  if (!Number.isFinite(sec) || !queue.length) return;
  const max = duration();
  audio.currentTime = max ? clamp(sec, 0, max) : Math.max(0, sec);
  emit('time');
}

const seekBy = (delta) => seek((audio.currentTime || 0) + delta);

function next() {
  failStreak = 0;
  if (index + 1 < queue.length) load(index + 1);
  else if (repeat === 'all' && queue.length) load(0);
}

function prev() {
  if (!queue.length) return;
  if (audio.currentTime > 3 || index === 0) seek(0);
  else load(index - 1);
}

/** Called when a track ends on its own. */
function advance() {
  if (repeat === 'one') { audio.currentTime = 0; play(); return; }
  if (index + 1 < queue.length) load(index + 1);
  else if (repeat === 'all' && queue.length) load(0);
  else { saveResume(true, 0); emit('state'); } // end of the queue: stay on the last track, paused
}

function toggleShuffle() {
  shuffleOn = !shuffleOn;
  if (queue.length) {
    const current = queue[index];
    if (shuffleOn) {
      queue = [current, ...shuffled(queue.filter((_, i) => i !== index))];
      index = 0;
    } else {
      const keep = new Set(queue);
      queue = original.filter((t) => keep.has(t));
      index = Math.max(0, queue.indexOf(current));
    }
    emit('queue');
  }
  emit('state');
}

function cycleRepeat() {
  repeat = repeat === 'off' ? 'all' : repeat === 'all' ? 'one' : 'off';
  emit('state');
}

/** Likes or unlikes the current song; resolves to the new state, rejects with ApiError. */
async function like(liked) {
  const track = queue[index];
  if (!track) return !!liked;
  const res = await api.like(track.videoId, liked);
  if (details && queue[index] === track) {
    details = { ...details, liked: !!res.liked };
    emit('details');
  }
  return !!res.liked;
}

// ---- volume ---------------------------------------------------------------------------------------------
const savedVolume = Number(read(K_VOL));
if (read(K_VOL) != null && Number.isFinite(savedVolume) && savedVolume >= 0 && savedVolume <= 1) audio.volume = savedVolume;

function setVolume(v) {
  const x = Number(v);
  if (!Number.isFinite(x)) return;
  audio.volume = clamp(x, 0, 1);
  if (audio.volume > 0 && audio.muted) audio.muted = false;
  write(K_VOL, audio.volume.toFixed(2));
}

function toggleMute() { audio.muted = !audio.muted; }

/** iOS Safari ignores `audio.volume` (the hardware buttons decide); then the page hides its slider. */
function canSetVolume() {
  if (volumeProbe !== null) return volumeProbe;
  try {
    const old = audio.volume;
    const test = old > 0.5 ? 0.4 : 0.6;
    audio.volume = test;
    volumeProbe = Math.abs(audio.volume - test) < 0.01;
    audio.volume = old;
  } catch { volumeProbe = false; }
  return volumeProbe;
}

// ---- audio element events -------------------------------------------------------------------------------
audio.addEventListener('play', () => emit('state'));
audio.addEventListener('pause', () => { saveResume(true); emit('state'); });
audio.addEventListener('waiting', () => emit('state'));
audio.addEventListener('playing', () => { failStreak = 0; emit('state'); });
audio.addEventListener('durationchange', () => emit('time'));
audio.addEventListener('seeked', () => emit('time'));
audio.addEventListener('volumechange', () => emit('volume'));
audio.addEventListener('ended', advance);
audio.addEventListener('loadedmetadata', () => {
  if (startAt > 0) {
    try { audio.currentTime = startAt; } catch { /* not seekable yet: start from 0 */ }
    startAt = 0;
    emit('time');
  }
});
audio.addEventListener('timeupdate', () => {
  maybePreload();
  saveResume(false);
  if (document.hidden) return;
  const now = performance.now();
  if (now - lastTime < 250) return;
  lastTime = now;
  emit('time');
});
audio.addEventListener('error', () => {
  const err = audio.error;
  if (!err || err.code === 1) return; // 1 = aborted by us (source changed)
  const track = queue[index];
  if (!track) return;
  if (retriedFor !== track) {
    // One automatic retry: the same URL makes the server resolve a fresh stream. Keep the position.
    retriedFor = track;
    const at = audio.currentTime || 0;
    const wasPlaying = !audio.paused;
    startAt = at > 1 ? at : 0;
    audio.src = srcFor(track.videoId);
    if (audio.load) audio.load();
    if (wasPlaying) play();
    return;
  }
  failStreak += 1;
  const name = track.title ? '"' + track.title + '"' : 'This song';
  const more = failStreak <= 3 && index + 1 < queue.length;
  emit('error', name + ' could not be played.' + (more ? ' Skipping to the next song.' : ''));
  if (more) load(index + 1);
  else emit('state');
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) saveResume(true); else emit('time');
});
if (typeof addEventListener === 'function') addEventListener('pagehide', () => saveResume(true));

export const player = {
  audio,
  on(type, fn) {
    bus.addEventListener(type, fn);
    return () => bus.removeEventListener(type, fn);
  },
  get current() { return queue[index] || null; },
  get queue() { return queue; },
  get index() { return index; },
  get details() { return details; },
  get playing() { return !audio.paused && !audio.ended; },
  get shuffle() { return shuffleOn; },
  get repeat() { return repeat; },
  get time() { return audio.currentTime || 0; },
  get duration() { return duration(); },
  get volume() { return audio.volume; },
  get muted() { return audio.muted; },
  get volumeSettable() { return canSetVolume(); },
  get quality() { return quality(); },
  setQuality(q) { write(K_QUALITY, q === 'low' ? 'low' : null); },
  playList, playNow, enqueue, playNext, jumpTo, toggle, play, pause: () => audio.pause(),
  seek, seekBy, next, prev, toggleShuffle, cycleRepeat, like,
  setVolume, toggleMute, restore, hydrateQueue, parseResume,
};
