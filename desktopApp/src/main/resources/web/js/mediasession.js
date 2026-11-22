// mediasession.js - Media Session API: title, artist, cover, play/pause/next/previous/seek and the
// position, so OS media keys, the lock screen and Bluetooth buttons work with the tab in the background.
// The position is only updated on play, pause, seek, track change and duration change (the OS moves it
// on its own), never on a timer. Unsupported browsers simply do nothing.
import { player } from './player.js';
import { artSrc } from './util.js';

/** Pure: the metadata fields for a track and its /api/track details (either may be null). */
export function metaFor(track, details) {
  const t = track || {};
  const d = details || {};
  const artists = Array.isArray(d.artists) && d.artists.length ? d.artists.join(', ') : t.artists || '';
  const base = (d.art && (d.art.large || d.art.small)) || t.art || '';
  const artwork = [];
  if (base) {
    for (const px of [96, 256, 512]) artwork.push({ src: artSrc(base, px), sizes: px + 'x' + px });
  }
  return {
    title: String(d.title || t.title || ''),
    artist: artists,
    album: typeof d.album === 'string' ? d.album : '',
    artwork,
  };
}

/** Pure: a safe argument for setPositionState, or null when the numbers are not usable. */
export function positionFor(duration, position, rate) {
  if (!Number.isFinite(duration) || duration <= 0) return null;
  const pos = Number.isFinite(position) ? Math.min(Math.max(0, position), duration) : 0;
  const r = Number.isFinite(rate) && rate > 0 ? rate : 1;
  return { duration, position: pos, playbackRate: r };
}

export function initMediaSession() {
  if (!('mediaSession' in navigator)) return;
  const ms = navigator.mediaSession;
  const audio = player.audio;

  const setMeta = () => {
    const t = player.current;
    if (!t) { ms.metadata = null; return; }
    const m = metaFor(t, player.details);
    try { ms.metadata = new MediaMetadata(m); } catch { /* an old engine: no metadata */ }
  };
  const setPosition = () => {
    if (!ms.setPositionState) return;
    const p = positionFor(player.duration, audio.currentTime, audio.playbackRate);
    try { if (p) ms.setPositionState(p); else ms.setPositionState(); } catch { /* bad numbers: skip */ }
  };
  const setState = () => {
    ms.playbackState = !player.current ? 'none' : player.playing ? 'playing' : 'paused';
    setPosition();
  };

  const actions = {
    play: () => player.play(),
    pause: () => player.pause(),
    stop: () => { player.pause(); player.seek(0); },
    previoustrack: () => player.prev(),
    nexttrack: () => player.next(),
    seekbackward: (d) => player.seekBy(-((d && d.seekOffset) || 10)),
    seekforward: (d) => player.seekBy((d && d.seekOffset) || 10),
    seekto: (d) => { if (d && Number.isFinite(d.seekTime)) player.seek(d.seekTime); },
  };
  for (const [name, fn] of Object.entries(actions)) {
    try { ms.setActionHandler(name, fn); } catch { /* this action is not supported here */ }
  }

  player.on('track', () => { setMeta(); setState(); });
  player.on('details', setMeta);
  player.on('state', setState);
  for (const type of ['seeked', 'durationchange', 'ratechange', 'loadedmetadata']) audio.addEventListener(type, setPosition);
  setMeta();
  setState();
}
