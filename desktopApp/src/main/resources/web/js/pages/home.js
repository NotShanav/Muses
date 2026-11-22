// home.js - the shelves from /api/home (Recents, mixes, Listen again, ...). Re-renders when the
// account signs in or out in the desktop app.
import { api } from '../api.js';
import { bus } from '../events.js';
import { h } from '../dom.js';
import { renderAsync, shelf } from '../ui.js';

export function render(ctx) {
  const load = (silent) => renderAsync(ctx, 'Home', () => api.home(ctx.signal), (data) => {
    const shelves = (data.shelves || []).filter((s) => s.items && s.items.length);
    return [
      h('h1', { text: 'Home' }),
      shelves.length
        ? shelves.map(shelf)
        : h('p', { class: 'muted', text: 'Nothing to show yet. Sign in to YouTube Music inside the Muses desktop app to get your recommendations.' }),
    ];
  }, silent);
  const onAuth = () => load(false);
  bus.addEventListener('authchange', onAuth);
  load(false);
  return () => bus.removeEventListener('authchange', onAuth);
}
