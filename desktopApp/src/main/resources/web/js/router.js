// router.js - hash router (#/home, #/search?q=, #/library, #/playlist/<id>, #/now, #/settings).
// Each page module exports render({ main, id, query, signal }); it may return (or resolve to) a
// dispose function. `signal` is aborted when the user navigates away, so pending fetches stop.
import { h } from './dom.js';
import { parseHash } from './util.js';

const pages = {
  home: () => import('./pages/home.js'),
  search: () => import('./pages/search.js'),
  library: () => import('./pages/library.js'),
  playlist: () => import('./pages/playlist.js'),
  now: () => import('./pages/now.js'),
  settings: () => import('./pages/settings.js'),
};

const titles = {
  home: 'Home', search: 'Search', library: 'Library', playlist: 'Playlist', now: 'Now playing', settings: 'Settings',
};

let navId = 0;
let dispose = null;
let controller = null;

export function startRouter({ main, onRoute }) {
  let first = true;

  async function run() {
    const route = parseHash(location.hash);
    const known = Object.prototype.hasOwnProperty.call(pages, route.name);
    const id = ++navId;
    if (dispose) { try { dispose(); } catch { /* a page failed to clean up: carry on */ } dispose = null; }
    if (controller) controller.abort();
    controller = new AbortController();
    onRoute(known ? route.name : 'home', route);
    document.title = (known ? titles[route.name] : 'Not found') + ' · Muses';
    if (!known) {
      main.replaceChildren(h('h1', { text: 'Page not found' }), h('p', {}, h('a', { href: '#/home', text: 'Go to Home' })));
      return;
    }
    let mod;
    try {
      mod = await pages[route.name]();
    } catch {
      main.replaceChildren(h('h1', { text: titles[route.name] }), h('p', { class: 'msg', role: 'alert', text: 'Could not load this page. Reload and try again.' }));
      return;
    }
    if (id !== navId) return;
    const cleanup = await mod.render({ main, id: route.id, query: route.query, signal: controller.signal });
    if (id !== navId) { if (typeof cleanup === 'function') cleanup(); return; }
    dispose = typeof cleanup === 'function' ? cleanup : null;
    window.scrollTo(0, 0);
    if (!first) main.focus({ preventScroll: true });
    first = false;
  }

  addEventListener('hashchange', run);
  run();
}
