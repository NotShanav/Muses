// pwa.js - registers sw.js (secure contexts only: localhost or HTTPS) and offers "Reload" when a new
// version of the app shell has been installed. No timers: it checks on load and when the tab is shown.
import { toast } from './ui.js';

export function initPwa() {
  if (!('serviceWorker' in navigator) || !window.isSecureContext) return;
  addEventListener('load', async () => {
    let reg;
    try { reg = await navigator.serviceWorker.register('/sw.js'); } catch { return; }
    let reloadWanted = false;
    const offer = (worker) => toast('A new version of Muses Web is ready.', {
      action: 'Reload', sticky: true,
      onAction: () => { reloadWanted = true; worker.postMessage('skip'); },
    });
    if (reg.waiting && navigator.serviceWorker.controller) offer(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const worker = reg.installing;
      if (!worker) return;
      worker.addEventListener('statechange', () => {
        if (worker.state === 'installed' && navigator.serviceWorker.controller) offer(worker);
      });
    });
    navigator.serviceWorker.addEventListener('controllerchange', () => { if (reloadWanted) location.reload(); });
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) reg.update().catch(() => {});
    });
  });
}
