// library.js - Liked music, the account's playlists and saved ones from /api/library.
import { api } from '../api.js';
import { bus } from '../events.js';
import { h } from '../dom.js';
import { card, renderAsync } from '../ui.js';

export function render(ctx) {
  const load = (silent) => renderAsync(ctx, 'Library', () => api.library(ctx.signal), (data) => {
    const liked = data.liked || { id: 'LM', title: 'Liked music' };
    const likedItem = {
      id: liked.id || 'LM', type: 'playlist', title: liked.title || 'Liked music', art: null,
      subtitle: typeof liked.count === 'number' ? liked.count + ' songs' : '',
    };
    const own = (data.playlists || []).filter((p) => p.id !== likedItem.id);
    const saved = data.saved || [];
    const grid = (items) => h('div', { class: 'grid' }, items.map((i) => card(i)));
    return [
      h('h1', { text: 'Library' }),
      h('section', { class: 'sec' }, h('h2', { text: 'Your playlists' }), grid([likedItem, ...own])),
      saved.length ? h('section', { class: 'sec' }, h('h2', { text: 'Saved' }), grid(saved)) : null,
    ];
  }, silent);
  const onChange = () => load(true);
  bus.addEventListener('libraryChanged', onChange);
  bus.addEventListener('authchange', onChange);
  load(false);
  return () => {
    bus.removeEventListener('libraryChanged', onChange);
    bus.removeEventListener('authchange', onChange);
  };
}
