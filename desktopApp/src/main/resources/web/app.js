// app.js - entry point (ES module). Wires the shell: theme, search box, mini-player, router,
// the SSE connection, the account dot and the "session expired" handling. Pages load on demand.
import { initTheme, setTint } from './js/theme.js';
import { api, authEvents } from './js/api.js';
import { bus, session, startEvents } from './js/events.js';
import { player } from './js/player.js';
import { startRouter } from './js/router.js';
import { initSearch, setQuery, focusSearch } from './js/searchbox.js';
import { initMini } from './js/mini.js';
import { initMediaSession } from './js/mediasession.js';
import { initVoice } from './js/voice.js';
import { initPwa } from './js/pwa.js';
import { initFullscreen, toggleFullscreen } from './js/fullscreen.js';
import { describeError, toast } from './js/ui.js';

const main = document.getElementById('main');
const avatar = document.getElementById('avatar');
const RELOAD_FLAG = 'muses.reloaded';

function flag(value) {
  try {
    if (value === undefined) return sessionStorage.getItem(RELOAD_FLAG) === '1';
    if (value) sessionStorage.setItem(RELOAD_FLAG, '1'); else sessionStorage.removeItem(RELOAD_FLAG);
  } catch { /* storage blocked */ }
  return false;
}

function updateAvatar() {
  const on = session.signedIn === true;
  avatar.classList.toggle('on', on);
  avatar.setAttribute('aria-label', (on ? 'Account: signed in. ' : 'Account: not signed in. ') + 'Open settings');
}

function onRoute(name, route) {
  document.body.dataset.route = name;
  setTint(player.details ? player.details.palette : null); // Now playing gets the album backdrop
  const section = name === 'playlist' ? 'library' : name;
  document.querySelectorAll('.nav a').forEach((a) => {
    if (a.dataset.route === section) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  if (name === 'search') setQuery(route.query.q || '');
}

// A 403 means the cookie belongs to an older launch of the desktop app. A page load renews it, so
// reload once; if it happens again, say so instead of looping.
authEvents.addEventListener('auth', () => {
  if (!flag()) { flag(true); location.reload(); return; }
  toast('This browser session is not allowed. Reload the page.', {
    action: 'Reload', sticky: true, onAction: () => location.reload(),
  });
});

initTheme();
initSearch();
player.restore(); // the last queue, paused at its position (ids and position only)
initMini();
initMediaSession();
initVoice();
initPwa();
initFullscreen();
document.querySelector('.nav a[data-route="search"]').addEventListener('click', () => focusSearch());
bus.addEventListener('authstate', updateAvatar);
bus.addEventListener('authchange', updateAvatar);
player.on('details', () => setTint(player.details ? player.details.palette : null));
player.on('error', (e) => toast(e.detail));

// Keyboard: Space play/pause, N next, P previous, M mute, F full screen, Shift+Left/Right seek 10 s, / search.
document.addEventListener('keydown', (e) => {
  if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || document.querySelector('dialog[open]')) return;
  const t = e.target;
  if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) return;
  const k = e.key.toLowerCase();
  if (e.key === '/') { e.preventDefault(); focusSearch(); }
  else if (e.code === 'Space' && !(t && /^(BUTTON|A|SUMMARY)$/.test(t.tagName))) { e.preventDefault(); player.toggle(); }
  else if (k === 'n') player.next();
  else if (k === 'p') player.prev();
  else if (k === 'm') player.toggleMute();
  else if (k === 'f' && !e.shiftKey) toggleFullscreen();
  else if (e.shiftKey && e.key === 'ArrowRight') player.seekBy(10);
  else if (e.shiftKey && e.key === 'ArrowLeft') player.seekBy(-10);
});

startRouter({ main, onRoute });
startEvents();
api.ping().then((p) => {
  flag(false);
  session.signedIn = !!p.signedIn;
  session.version = typeof p.version === 'string' ? p.version : '';
  updateAvatar();
}).catch((e) => {
  if (e && e.status !== 403) toast(describeError(e), { sticky: true });
});
