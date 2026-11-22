// search.js - results from /api/search?q=. The text typed is in the URL hash only; nothing is stored.
import { api } from '../api.js';
import { h } from '../dom.js';
import { renderAsync, shelf, trackList } from '../ui.js';
import { toTrack } from '../util.js';

export function render(ctx) {
  const q = (ctx.query.q || '').trim();
  const disposers = [];
  if (!q) {
    ctx.main.replaceChildren(
      h('h1', { text: 'Search' }),
      h('p', { class: 'muted', text: 'Type in the box at the top to find songs, albums, artists and playlists.' }));
    return;
  }
  renderAsync(ctx, 'Search', () => api.search(q, ctx.signal), (data) => {
    const sections = (data.sections || []).filter((s) => s.items && s.items.length);
    if (!sections.length) return [h('h1', { text: 'Results' }), h('p', { class: 'muted', text: 'Nothing found.' })];
    return [
      h('h1', { text: 'Results' }),
      sections.map((s) => {
        if (s.kind !== 'songs') return shelf(s);
        const tracks = s.items.filter((i) => i.type === 'song').map(toTrack);
        return h('section', { class: 'sec' }, h('h2', { text: s.title || 'Songs' }), trackList(tracks, { disposers }));
      }),
    ];
  });
  return () => disposers.forEach((off) => off());
}
