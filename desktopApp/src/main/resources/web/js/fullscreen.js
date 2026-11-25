// fullscreen.js - the full-screen button (top bar) and the F key. Uses the Fullscreen API on the whole page,
// and also notices browser full screen (F11) and an installed app window in fullscreen display mode.
// While either is on, <html> gets the class "fs" (CSS uses it for the roomier, chrome-free layout).
// Event driven only: fullscreenchange, resize, display-mode change. No timers.
import { setIcon } from './dom.js';

const root = document.documentElement;

function api() {
  const el = root;
  return {
    can: !!(document.fullscreenEnabled || document.webkitFullscreenEnabled),
    active: () => !!(document.fullscreenElement || document.webkitFullscreenElement),
    enter: () => (el.requestFullscreen ? el.requestFullscreen({ navigationUI: 'hide' }) : el.webkitRequestFullscreen && el.webkitRequestFullscreen()),
    exit: () => (document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen && document.webkitExitFullscreen()),
  };
}

/** True for API full screen, F11 full screen (window fills the screen) and display-mode: fullscreen. */
export function isFull() {
  const f = api();
  if (f.active()) return true;
  if (window.matchMedia && matchMedia('(display-mode: fullscreen)').matches) return true;
  return window.innerWidth >= screen.width - 1 && window.innerHeight >= screen.height - 1 && screen.width > 600;
}

export function toggleFullscreen() {
  const f = api();
  if (!f.can) return;
  const run = f.active() ? f.exit() : f.enter();
  if (run && run.catch) run.catch(() => {}); // refused (no user gesture, policy): nothing to do
}

export function initFullscreen() {
  const btn = document.getElementById('fs-btn');
  const f = api();
  const sync = () => {
    const on = isFull();
    root.classList.toggle('fs', on);
    if (!btn) return;
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.setAttribute('aria-label', on ? 'Exit full screen' : 'Full screen');
    btn.title = on ? 'Exit full screen (F)' : 'Full screen (F)';
    setIcon(btn, on ? 'fullscreen-exit' : 'fullscreen');
  };
  if (btn) {
    btn.hidden = !f.can; // iPhone Safari has no page full screen
    btn.addEventListener('click', toggleFullscreen);
  }
  document.addEventListener('fullscreenchange', sync);
  document.addEventListener('webkitfullscreenchange', sync);
  addEventListener('resize', sync);
  if (window.matchMedia) {
    const mq = matchMedia('(display-mode: fullscreen)');
    if (mq.addEventListener) mq.addEventListener('change', sync);
  }
  sync();
}
