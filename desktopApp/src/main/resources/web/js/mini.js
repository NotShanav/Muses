// mini.js - the bottom mini-player: cover, title, play/pause, next, thin progress bar (transform only).
import { player } from './player.js';
import { setIcon } from './dom.js';
import { artSrc } from './util.js';

export function initMini() {
  const box = document.getElementById('mini');
  const art = document.getElementById('mini-art');
  const title = document.getElementById('mini-title');
  const artist = document.getElementById('mini-artist');
  const fill = document.getElementById('mini-fill');
  const toggle = document.getElementById('mini-toggle');
  let shownArt = '';

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
    fill.style.setProperty('transform', 'scaleX(0)');
  };
  const syncDetails = () => {
    const d = player.details;
    const t = player.current;
    if (t && !t.art && d && d.art) setArt(d.art.small || d.art.large);
  };
  const syncState = () => {
    const playing = player.playing;
    setIcon(toggle, playing ? 'pause' : 'play');
    toggle.setAttribute('aria-label', playing ? 'Pause' : 'Play');
  };
  const syncTime = () => {
    const d = player.duration;
    const ratio = d ? Math.min(1, player.time / d) : 0;
    fill.style.setProperty('transform', 'scaleX(' + ratio.toFixed(4) + ')');
  };

  toggle.addEventListener('click', () => player.toggle());
  document.getElementById('mini-next').addEventListener('click', () => player.next());
  player.on('track', () => { syncTrack(); syncState(); });
  player.on('details', syncDetails);
  player.on('state', syncState);
  player.on('time', syncTime);
  syncTrack();
  syncState();
}
