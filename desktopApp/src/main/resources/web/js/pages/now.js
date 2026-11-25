// now.js - Now Playing (Apple-Music-like): cover left, title / like / more / current lyric line / seek / transport /
// volume on the right, bottom row with Lyrics, output pill and Queue. Phones keep one column with tabs.
// The lyrics highlight uses requestAnimationFrame ONLY while the Lyrics tab is shown, the song is
// playing and the page is visible. Everything else updates from player events (max 4 per second).
import { api } from '../api.js';
import { player } from '../player.js';
import { h, icon, setIcon, img } from '../dom.js';
import { fmtTime, findLyricIndex, artSrc } from '../util.js';
import { describeError, toast, menuDialog, choosePlaylist } from '../ui.js';
import { toggleFullscreen } from '../fullscreen.js';
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
  let lineShown = null;

  // ---- structure -------------------------------------------------------------------------------
  const root = document.documentElement;
  const DEVICE = 'This browser'; // the web page has no device name
  const artEl = h('img', { class: 'np-art', width: 390, height: 390, alt: '', decoding: 'async' });
  const fromEl = h('p', { class: 'np-from', text: 'Playing from Queue' });
  const titleEl = h('h2', { class: 'np-title' });
  const artistEl = h('p', { class: 'np-artist muted' });
  const seek = h('input', { class: 'seek', type: 'range', min: 0, max: 1000, step: 1, value: 0, 'aria-label': 'Seek' });
  const curEl = h('span', { text: '0:00' });
  const remEl = h('span', { text: '-0:00' });
  const btn = (label, name, fn, cls = 'icon-btn') => h('button', { class: cls, type: 'button', 'aria-label': label, onclick: fn }, icon(name));
  const shuffleBtn = btn('Shuffle', 'shuffle', () => player.toggleShuffle());
  const repeatBtn = btn('Repeat', 'repeat', () => player.cycleRepeat());
  const prevBtn = btn('Previous', 'prev', () => player.prev(), 'icon-btn glyph');
  const playBtn = btn('Play', 'play', () => player.toggle(), 'icon-btn glyph play');
  const nextBtn = btn('Next', 'next', () => player.next(), 'icon-btn glyph');
  const likeBtn = btn('Like', 'heart', onLike, 'icon-btn round');
  const moreBtn = btn('More actions', 'more', onMore, 'icon-btn round');
  const micBtn = h('button', { class: 'icon-btn mic', type: 'button', 'aria-label': 'Speak a command', 'aria-pressed': 'false' }, icon('mic'));
  const muteBtn = btn('Mute', 'volume-low', () => player.toggleMute());
  const volEl = h('input', { class: 'vol seek', type: 'range', min: 0, max: 100, step: 1, value: 100, 'aria-label': 'Volume' });
  const volRow = h('div', { class: 'np-vol', hidden: !player.volumeSettable }, muteBtn, volEl, h('span', { class: 'vol-hi', 'aria-hidden': 'true' }, icon('volume')));
  const lyricsBox = h('div', { class: 'lyr', tabindex: '0', 'aria-label': 'Lyrics' });
  const queueBox = h('ul', { class: 'tracks' });
  const lyricText = h('span', { class: 'np-lyric-t' });
  const lyricLine = h('button', { class: 'np-lyric', type: 'button', hidden: true, 'aria-label': 'Open lyrics', onclick: () => selectTab('lyrics') }, lyricText, icon('chevron'));
  const lyricsBtn = btn('Lyrics', 'lyrics', () => selectTab(tab === 'lyrics' ? 'player' : 'lyrics'), 'icon-btn np-lyrbtn');
  const queueBtn = btn('Queue', 'queue', () => selectTab(tab === 'queue' ? 'player' : 'queue'), 'icon-btn np-queuebtn');
  const outBtn = h('button', { class: 'seg', type: 'button', 'aria-label': 'Audio output: ' + DEVICE, onclick: () => toast('Playing on ' + DEVICE.toLowerCase()) }, icon('headphones'));
  const acctBtn = h('a', { class: 'seg', href: '#/settings', 'aria-label': 'Account and settings' }, icon('user'));
  const exitBtn = h('button', { class: 'icon-btn np-exit', type: 'button', 'aria-label': 'Exit full screen', title: 'Exit full screen (F)', onclick: toggleFullscreen }, icon('fullscreen-exit'));

  const panels = {
    player: h('div', { class: 'np-panel', id: 'np-player', role: 'tabpanel' }, lyricLine),
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
  const info = h('div', { class: 'np-info' }, fromEl,
    h('div', { class: 'np-head' }, h('div', { class: 'np-titles' }, titleEl, artistEl), h('div', { class: 'np-acts' }, likeBtn, moreBtn)));
  const ctl = h('div', { class: 'np-ctl' },
    h('div', { class: 'scrub' }, seek, h('div', { class: 'times' }, curEl, remEl)),
    h('div', { class: 'transport main' }, shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn),
    volRow);
  const bottom = h('div', { class: 'np-bottom' },
    h('div', { class: 'np-bl' }, lyricsBtn, micBtn),
    h('div', { class: 'np-bc' }, h('div', { class: 'np-pill', role: 'group', 'aria-label': 'Output and account' }, outBtn, acctBtn), h('span', { class: 'np-device', text: DEVICE })),
    h('div', { class: 'np-br' }, queueBtn));
  const content = h('div', { class: 'np', hidden: true, 'data-tab': 'player' },
    tablist, h('div', { class: 'np-cover' }, artEl),
    h('div', { class: 'np-side' }, info, panels.player, panels.lyrics, panels.queue, ctl),
    bottom);
  const empty = h('p', { class: 'muted' }, 'Nothing is playing yet. Pick a song from ',
    h('a', { href: '#/home', text: 'Home' }), ' or ', h('a', { href: '#/search', text: 'Search' }), '.');
  main.replaceChildren(h('h1', { class: 'sr-only', text: 'Now playing' }), empty, content, exitBtn);

  // ---- tabs ------------------------------------------------------------------------------------
  function selectTab(name) {
    tab = name;
    content.dataset.tab = name; // CSS: phones show one view at a time; wide screens swap the middle of the right column
    lyricsBtn.setAttribute('aria-pressed', String(name === 'lyrics'));
    queueBtn.setAttribute('aria-pressed', String(name === 'queue'));
    for (const key of Object.keys(panels)) {
      panels[key].hidden = key !== name;
      tabBtns[key].setAttribute('aria-selected', String(key === name));
      tabBtns[key].tabIndex = key === name ? 0 : -1;
    }
    if (name === 'lyrics') ensureLyrics();
    if (name === 'queue') buildQueue();
    syncLyricLine();
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
    // The title arrives in a second 'track' event for the same song: keep the lyrics, only reset on a new song.
    if (lyr.id !== t.videoId) { lyr = { id: null, state: 'idle', synced: null, plain: null, active: -2 }; lineShown = null; }
    ensureLyrics(); // also feeds the one-line lyric under the title
    syncLyricLine();
    if (tab === 'queue') scheduleQueue();
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
    seek.style.setProperty('--p', (d ? Math.min(100, (t / d) * 100) : 0).toFixed(2) + '%');
    curEl.textContent = fmtTime(t);
    remEl.textContent = '-' + fmtTime(Math.max(0, d - t));
    seek.setAttribute('aria-valuetext', fmtTime(t) + ' of ' + fmtTime(d));
    if (tab === 'lyrics') highlight(false);
    syncLyricLine();
  }

  seek.addEventListener('input', () => {
    dragging = true;
    seek.style.setProperty('--p', Number(seek.value) / 10 + '%');
    const at = (Number(seek.value) / 1000) * player.duration;
    curEl.textContent = fmtTime(at);
    remEl.textContent = '-' + fmtTime(Math.max(0, player.duration - at));
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

  function onMore() {
    const t = player.current;
    if (!t) return;
    const liked = !!(player.details && player.details.liked);
    menuDialog(t.title || 'Song', [
      { label: liked ? 'Remove like' : 'Like', run: onLike },
      { label: 'Add to playlist…', run: () => choosePlaylist(t.videoId) },
      { label: 'Show lyrics', run: () => selectTab('lyrics') },
      { label: 'Show queue', run: () => selectTab('queue') },
    ]);
  }

  function syncVolume() {
    const muted = player.muted;
    volEl.value = muted ? 0 : Math.round(player.volume * 100);
    volEl.style.setProperty('--p', volEl.value + '%');
    setIcon(muteBtn, muted || player.volume === 0 ? 'mute' : 'volume-low');
    muteBtn.setAttribute('aria-pressed', String(muted));
    muteBtn.setAttribute('aria-label', muted ? 'Unmute' : 'Mute');
  }
  volEl.addEventListener('input', () => player.setVolume(Number(volEl.value) / 100));

  // ---- queue -----------------------------------------------------------------------------------
  let qRaf = 0;
  let lastQ = -1;
  function scheduleQueue() { // one rebuild per frame: names arriving one by one no longer make the list flicker
    if (!qRaf) qRaf = requestAnimationFrame(() => { qRaf = 0; if (tab === 'queue') buildQueue(); });
  }
  function buildQueue() {
    const rows = [];
    player.queue.forEach((t, i) => {
      if (i === player.index + 1) rows.push(h('li', { class: 'qh', text: 'Next up' }));
      rows.push(h('li', { class: 'row' + (i === player.index ? ' cur' : '') },
        h('button', { class: 'row-main', type: 'button', onclick: () => player.jumpTo(i) },
          img(t.art, 44, 'art'),
          h('span', { class: 'rt' }, h('span', { class: 't', text: t.title || 'Song' }), h('span', { class: 's', text: t.artists || '' })))));
    });
    queueBox.replaceChildren(...rows);
    const cur = queueBox.querySelector('.cur');
    if (cur && lastQ !== player.index) { lastQ = player.index; cur.scrollIntoView({ block: 'nearest' }); }
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
      syncLyricLine();
      return;
    }
    drawLyrics();
    syncLyricLine();
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

  function syncLyricLine() { // the current line under the title; only for time-synced lyrics
    const on = lyr.state === 'ready' && !!lyr.synced;
    lyricLine.hidden = !on;
    if (!on) return;
    const i = findLyricIndex(lyr.synced, player.time * 1000);
    const text = (i >= 0 && lyr.synced[i] && lyr.synced[i].text) || '♪';
    if (text !== lineShown) { lineShown = text; lyricText.textContent = text; }
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
    player.on('queue', scheduleQueue),
    player.on('volume', syncVolume),
    attachMic(micBtn),
  ];
  document.addEventListener('visibilitychange', syncLoop);

  // Full screen: after 3 s without mouse or key activity hide the cursor and the bottom row (CSS class np-idle).
  // Events only (mousemove, keydown); one timeout; nothing runs unless the page is in full screen.
  let idleT = 0;
  let lastAct = 0;
  const stopIdle = () => { clearTimeout(idleT); idleT = 0; root.classList.remove('np-idle'); };
  const wake = () => {
    const now = performance.now();
    if (root.classList.contains('np-idle')) root.classList.remove('np-idle');
    else if (idleT && now - lastAct < 400) return; // do not re-arm on every pixel of movement
    lastAct = now;
    clearTimeout(idleT);
    idleT = 0;
    if (root.classList.contains('fs') && !document.hidden) idleT = setTimeout(() => { idleT = 0; root.classList.add('np-idle'); }, 3000);
  };
  const onFsChange = () => { if (root.classList.contains('fs')) wake(); else stopIdle(); };
  const onHide = () => { if (document.hidden) stopIdle(); };
  document.addEventListener('mousemove', wake, { passive: true });
  document.addEventListener('keydown', wake);
  document.addEventListener('fullscreenchange', onFsChange);
  document.addEventListener('webkitfullscreenchange', onFsChange);
  addEventListener('resize', onFsChange);
  document.addEventListener('visibilitychange', onHide);
  selectTab('player');
  syncTrack();
  syncVolume();

  return () => {
    offs.forEach((off) => off());
    document.removeEventListener('visibilitychange', syncLoop);
    document.removeEventListener('visibilitychange', onHide);
    document.removeEventListener('mousemove', wake);
    document.removeEventListener('keydown', wake);
    document.removeEventListener('fullscreenchange', onFsChange);
    document.removeEventListener('webkitfullscreenchange', onFsChange);
    removeEventListener('resize', onFsChange);
    stopIdle();
    if (raf) cancelAnimationFrame(raf);
    if (lyrCtl) lyrCtl.abort();
  };
}
