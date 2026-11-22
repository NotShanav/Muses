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
document.querySelector('.nav a[data-route="search"]').addEventListener('click', () => focusSearch());
bus.addEventListener('authstate', updateAvatar);
bus.addEventListener('authchange', updateAvatar);
player.on('details', () => setTint(player.details ? player.details.palette : null));
player.on('error', (e) => toast(e.detail));

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
