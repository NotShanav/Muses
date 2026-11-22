// now.js - Now Playing: cover, title, scrubber, transport, like; tabs for Lyrics and Queue.
// The lyrics highlight uses requestAnimationFrame ONLY while the Lyrics tab is shown, the song is
// playing and the page is visible. Everything else updates from player events (max 4 per second).
import { api } from '../api.js';
import { player } from '../player.js';
import { h, icon, setIcon, img } from '../dom.js';
import { fmtTime, findLyricIndex, artSrc } from '../util.js';
import { describeError, toast } from '../ui.js';
import { saverOn } from '../theme.js';
import { attach as attachMic } from '../voice.js';

export function render(ctx) {
  const { main } = ctx;
  let tab = 'player';
  let dragging = false;
  let raf = 0;
  let userScrollAt = 0;
  let lyr = { id: null, state: 'idle', synced: null, plain: null, active: -2 };
  let lyrCtl = null;
  let shownArt = '';

  // ---- structure -------------------------------------------------------------------------------
  const artEl = h('img', { class: 'np-art', width: 360, height: 360, alt: '', decoding: 'async' });
  const titleEl = h('h2', { class: 'np-title' });
  const artistEl = h('p', { class: 'np-artist muted' });
  const seek = h('input', { type: 'range', min: 0, max: 1000, step: 1, value: 0, 'aria-label': 'Seek' });
  const curEl = h('span', { text: '0:00' });
  const durEl = h('span', { text: '0:00' });
  const btn = (label, name, fn, cls = 'icon-btn') => h('button', { class: cls, type: 'button', 'aria-label': label, onclick: fn }, icon(name));
  const shuffleBtn = btn('Shuffle', 'shuffle', () => player.toggleShuffle());
  const repeatBtn = btn('Repeat', 'repeat', () => player.cycleRepeat());
  const playBtn = btn('Play', 'play', () => player.toggle(), 'icon-btn big');
  const likeBtn = btn('Like', 'heart', onLike);
  const micBtn = h('button', { class: 'icon-btn big mic', type: 'button', 'aria-label': 'Speak a command', 'aria-pressed': 'false' }, icon('mic'));
  const muteBtn = btn('Mute', 'volume', () => player.toggleMute());
  const volEl = h('input', { class: 'vol', type: 'range', min: 0, max: 100, step: 1, value: 100, 'aria-label': 'Volume' });
  const volRow = h('div', { class: 'transport vol-row', hidden: !player.volumeSettable }, muteBtn, volEl);
  const lyricsBox = h('div', { class: 'lyr', tabindex: '0', 'aria-label': 'Lyrics' });
  const queueBox = h('ul', { class: 'tracks' });

  const panels = {
    player: h('div', { class: 'np-panel', id: 'np-player', role: 'tabpanel' },
      artEl, titleEl, artistEl,
      h('div', { class: 'scrub' }, curEl, seek, durEl),
      h('div', { class: 'transport' }, shuffleBtn, btn('Previous', 'prev', () => player.prev()), playBtn, btn('Next', 'next', () => player.next()), repeatBtn),
      h('div', { class: 'transport' }, likeBtn, micBtn),
      volRow),
    lyrics: h('div', { class: 'np-panel', id: 'np-lyrics', role: 'tabpanel', hidden: true }, lyricsBox),
    queue: h('div', { class: 'np-panel', id: 'np-queue', role: 'tabpanel', hidden: true }, queueBox),
  };
  const tabBtns = {};
  const tablist = h('div', { class: 'np-tabs', role: 'tablist', 'aria-label': 'Now playing views' },
    [['player', 'Player'], ['lyrics', 'Lyrics'], ['queue', 'Queue']].map(([name, label]) => {
      tabBtns[name] = h('button', {
        class: 'tab', type: 'button', role: 'tab', 'aria-controls': 'np-' + name,
        text: label, onclick: () => selectTab(name),
      });
      return tabBtns[name];
    }));
  tablist.addEventListener('keydown', (e) => {
    const names = Object.keys(tabBtns);
    const at = names.indexOf(tab);
    if (e.key === 'ArrowRight') { selectTab(names[(at + 1) % names.length]); tabBtns[tab].focus(); }
    if (e.key === 'ArrowLeft') { selectTab(names[(at + names.length - 1) % names.length]); tabBtns[tab].focus(); }
  });
  const content = h('div', { class: 'np', hidden: true }, tablist, panels.player, panels.lyrics, panels.queue);
  const empty = h('p', { class: 'muted' }, 'Nothing is playing yet. Pick a song from ',
    h('a', { href: '#/home', text: 'Home' }), ' or ', h('a', { href: '#/search', text: 'Search' }), '.');
  main.replaceChildren(h('h1', { class: 'sr-only', text: 'Now playing' }), empty, content);

  // ---- tabs ------------------------------------------------------------------------------------
  function selectTab(name) {
    tab = name;
    for (const key of Object.keys(panels)) {
      panels[key].hidden = key !== name;
      tabBtns[key].setAttribute('aria-selected', String(key === name));
      tabBtns[key].tabIndex = key === name ? 0 : -1;
    }
    if (name === 'lyrics') ensureLyrics();
    if (name === 'queue') buildQueue();
    syncLoop();
  }

  // ---- player sync -----------------------------------------------------------------------------
  function setArt(url) {
    const src = url ? artSrc(url, 720) : '';
    if (src === shownArt) return;
    shownArt = src;
    if (src) artEl.setAttribute('src', src); else artEl.removeAttribute('src');
  }

  function syncTrack() {
    const t = player.current;
    empty.hidden = !!t;
    content.hidden = !t;
    if (!t) return;
    titleEl.textContent = t.title || '';
    artistEl.textContent = t.artists || '';
    setArt(t.art);
    syncDetails();
    syncState();
    syncTime();
    lyr = { id: null, state: 'idle', synced: null, plain: null, active: -2 };
    if (tab === 'lyrics') ensureLyrics();
    if (tab === 'queue') buildQueue();
    syncLoop();
  }

  function syncDetails() {
    const d = player.details;
    if (d && d.art) setArt(d.art.large || d.art.small);
    const liked = !!(d && d.liked);
    setIcon(likeBtn, liked ? 'heart-fill' : 'heart');
    likeBtn.setAttribute('aria-pressed', String(liked));
    likeBtn.setAttribute('aria-label', liked ? 'Remove like' : 'Like');
    if (d && Array.isArray(d.artists) && d.artists.length && !artistEl.textContent) artistEl.textContent = d.artists.join(', ');
  }

  function syncState() {
    const playing = player.playing;
    setIcon(playBtn, playing ? 'pause' : 'play');
    playBtn.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    shuffleBtn.setAttribute('aria-pressed', String(player.shuffle));
    const r = player.repeat;
    setIcon(repeatBtn, r === 'one' ? 'repeat-one' : 'repeat');
    repeatBtn.setAttribute('aria-pressed', String(r !== 'off'));
    repeatBtn.setAttribute('aria-label', r === 'off' ? 'Repeat off' : r === 'all' ? 'Repeat all' : 'Repeat one');
    syncLoop();
  }

  function syncTime() {
    if (document.hidden || dragging) return;
    const d = player.duration;
    const t = player.time;
    seek.value = d ? Math.round((t / d) * 1000) : 0;
    curEl.textContent = fmtTime(t);
    durEl.textContent = fmtTime(d);
    seek.setAttribute('aria-valuetext', fmtTime(t) + ' of ' + fmtTime(d));
    if (tab === 'lyrics') highlight(false);
  }

  seek.addEventListener('input', () => {
    dragging = true;
    curEl.textContent = fmtTime((Number(seek.value) / 1000) * player.duration);
  });
  seek.addEventListener('change', () => {
    player.seek((Number(seek.value) / 1000) * player.duration);
    dragging = false;
  });

  async function onLike() {
    const liked = !!(player.details && player.details.liked);
    likeBtn.disabled = true;
    try { await player.like(!liked); } catch (e) { toast(describeError(e)); }
    likeBtn.disabled = false;
  }

  function syncVolume() {
    const muted = player.muted;
    volEl.value = muted ? 0 : Math.round(player.volume * 100);
    setIcon(muteBtn, muted || player.volume === 0 ? 'mute' : 'volume');
    muteBtn.setAttribute('aria-pressed', String(muted));
    muteBtn.setAttribute('aria-label', muted ? 'Unmute' : 'Mute');
  }
  volEl.addEventListener('input', () => player.setVolume(Number(volEl.value) / 100));

  // ---- queue -----------------------------------------------------------------------------------
  function buildQueue() {
    queueBox.replaceChildren(...player.queue.map((t, i) => h('li', { class: 'row' + (i === player.index ? ' cur' : '') },
      h('button', { class: 'row-main', type: 'button', onclick: () => player.jumpTo(i) },
        img(t.art, 44, 'art'),
        h('span', { class: 'rt' }, h('span', { class: 't', text: t.title || 'Song' }), h('span', { class: 's', text: t.artists || '' }))))));
    player.hydrateQueue(8); // names for tracks restored from ids only
  }

  // ---- lyrics ----------------------------------------------------------------------------------
  async function ensureLyrics() {
    const t = player.current;
    if (!t || lyr.id === t.videoId) return;
    lyr = { id: t.videoId, state: 'loading', synced: null, plain: null, active: -2 };
    lyricsBox.replaceChildren(h('p', { class: 'muted', text: 'Loading lyrics…' }));
    if (lyrCtl) lyrCtl.abort();
    lyrCtl = new AbortController();
    const mine = lyr;
    try {
      const res = await api.lyrics(t.videoId, lyrCtl.signal);
      if (lyr !== mine) return;
      mine.synced = Array.isArray(res.synced) && res.synced.length ? res.synced : null;
      mine.plain = typeof res.plain === 'string' && res.plain.trim() ? res.plain : null;
      mine.state = mine.synced || mine.plain ? 'ready' : 'none';
    } catch (e) {
      if (lyr !== mine || (e && e.name === 'AbortError')) return;
      mine.state = 'error';
      lyricsBox.replaceChildren(h('p', { class: 'muted', text: describeError(e) }));
      return;
    }
    drawLyrics();
    syncLoop();
  }

  function drawLyrics() {
    if (lyr.state === 'none') { lyricsBox.replaceChildren(h('p', { class: 'muted', text: 'No lyrics for this song.' })); return; }
    if (lyr.synced) {
      lyricsBox.replaceChildren(...lyr.synced.map((line, i) => h('button', {
        class: 'ln', type: 'button', 'data-i': i, text: line.text || '♪',
        onclick: () => player.seek(line.ms / 1000),
      })));
      highlight(true);
    } else {
      lyricsBox.replaceChildren(h('pre', { class: 'plain', text: lyr.plain }));
    }
  }

  function highlight(force) {
    if (!lyr.synced || lyr.state !== 'ready') return;
    const i = findLyricIndex(lyr.synced, player.time * 1000);
    if (i === lyr.active && !force) return;
    const lines = lyricsBox.children;
    if (lines[lyr.active]) lines[lyr.active].classList.remove('on');
    lyr.active = i;
    const line = lines[i];
    if (!line) return;
    line.classList.add('on');
    if (performance.now() - userScrollAt < 4000) return; // the reader is scrolling: do not fight them
    const smooth = !saverOn() && !matchMedia('(prefers-reduced-motion: reduce)').matches;
    lyricsBox.scrollTo({ top: line.offsetTop - lyricsBox.clientHeight / 2 + line.clientHeight / 2, behavior: smooth ? 'smooth' : 'auto' });
  }

  const loopWanted = () => tab === 'lyrics' && player.playing && !document.hidden && lyr.state === 'ready' && !!lyr.synced;
  function tick() {
    raf = 0;
    highlight(false);
    if (loopWanted()) raf = requestAnimationFrame(tick);
  }
  function syncLoop() {
    const want = loopWanted();
    if (want && !raf) raf = requestAnimationFrame(tick);
    else if (!want && raf) { cancelAnimationFrame(raf); raf = 0; }
  }
  const noteScroll = () => { userScrollAt = performance.now(); };
  lyricsBox.addEventListener('wheel', noteScroll, { passive: true });
  lyricsBox.addEventListener('touchmove', noteScroll, { passive: true });

  // ---- wiring ----------------------------------------------------------------------------------
  const offs = [
    player.on('track', syncTrack),
    player.on('details', syncDetails),
    player.on('state', syncState),
    player.on('time', syncTime),
    player.on('queue', () => { if (tab === 'queue') buildQueue(); }),
    player.on('volume', syncVolume),
    attachMic(micBtn),
  ];
  document.addEventListener('visibilitychange', syncLoop);
  selectTab('player');
  syncTrack();
  syncVolume();

  return () => {
    offs.forEach((off) => off());
    document.removeEventListener('visibilitychange', syncLoop);
    if (raf) cancelAnimationFrame(raf);
    if (lyrCtl) lyrCtl.abort();
  };
}
