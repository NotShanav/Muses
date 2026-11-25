// mini.js - the player bar: cover, title, transport, thin progress bar (phones) or seek + volume (desktop).
// Updates come from player events (time: at most 4 a second, never while the tab is hidden). Transform and
// CSS variables only, no timers.
import { player } from './player.js';
import { setIcon } from './dom.js';
import { artSrc, fmtTime } from './util.js';

export function initMini() {
  const $ = (id) => document.getElementById(id);
  const box = $('mini'); const art = $('mini-art'); const title = $('mini-title'); const artist = $('mini-artist');
  const fill = $('mini-fill'); const toggle = $('mini-toggle'); const seek = $('mini-seek');
  const cur = $('mini-cur'); const dur = $('mini-dur'); const vol = $('mini-vol'); const mute = $('mini-mute');
  let shownArt = ''; let dragging = false;

  const setArt = (url) => {
    const src = url ? artSrc(url, 96) : '';
    if (src === shownArt) return;
    shownArt = src;
    if (src) art.setAttribute('src', src); else art.removeAttribute('src');
  };
  const syncTrack = () => {
    const t = player.current;
    box.hidden = !t;
    document.body.classList.toggle('has-mini', !!t);
    if (!t) return;
    title.textContent = t.title || '';
    artist.textContent = t.artists || '';
    setArt(t.art);
    syncTime();
  };
  const syncDetails = () => {
    const d = player.details; const t = player.current;
    if (t && !t.art && d && d.art) setArt(d.art.small || d.art.large);
  };
  const syncState = () => {
    setIcon(toggle, player.playing ? 'pause' : 'play');
    toggle.setAttribute('aria-label', player.playing ? 'Pause' : 'Play');
  };
  const syncTime = () => {
    if (document.hidden || dragging) return;
    const d = player.duration; const t = player.time;
    const ratio = d ? Math.min(1, t / d) : 0;
    fill.style.setProperty('transform', 'scaleX(' + ratio.toFixed(4) + ')');
    seek.value = Math.round(ratio * 1000);
    seek.style.setProperty('--p', (ratio * 100).toFixed(2) + '%');
    cur.textContent = fmtTime(t);
    dur.textContent = fmtTime(d);
  };
  const syncVolume = () => {
    const muted = player.muted;
    vol.value = muted ? 0 : Math.round(player.volume * 100);
    vol.style.setProperty('--p', vol.value + '%');
    setIcon(mute, muted || player.volume === 0 ? 'mute' : 'volume');
    mute.setAttribute('aria-label', muted ? 'Unmute' : 'Mute');
  };

  toggle.addEventListener('click', () => player.toggle());
  $('mini-next').addEventListener('click', () => player.next());
  $('mini-prev').addEventListener('click', () => player.prev());
  mute.addEventListener('click', () => player.toggleMute());
  vol.addEventListener('input', () => player.setVolume(Number(vol.value) / 100));
  seek.addEventListener('input', () => {
    dragging = true;
    seek.style.setProperty('--p', Number(seek.value) / 10 + '%');
    cur.textContent = fmtTime((Number(seek.value) / 1000) * player.duration);
  });
  seek.addEventListener('change', () => { player.seek((Number(seek.value) / 1000) * player.duration); dragging = false; });
  $('mini-right').classList.toggle('off', !player.volumeSettable); // iOS: the hardware buttons decide

  player.on('track', () => { syncTrack(); syncState(); });
  player.on('details', syncDetails);
  player.on('state', syncState);
  player.on('time', syncTime);
  player.on('volume', syncVolume);
  document.addEventListener('visibilitychange', syncTime);
  syncTrack(); syncState(); syncVolume();
}
