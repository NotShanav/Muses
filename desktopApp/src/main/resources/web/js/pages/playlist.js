// playlist.js - /api/playlist/{id}: also used for albums, artists and Liked music (id LM).
import { api } from '../api.js';
import { h, icon } from '../dom.js';
import { renderAsync, trackList } from '../ui.js';
import { player } from '../player.js';

export function render(ctx) {
  const disposers = [];
  if (!ctx.id) {
    ctx.main.replaceChildren(h('h1', { text: 'Playlist' }), h('p', { class: 'muted', text: 'No playlist chosen.' }));
    return;
  }
  renderAsync(ctx, 'Playlist', () => api.playlist(ctx.id, ctx.signal), (data) => {
    const tracks = (data.items || []).filter((t) => t && t.videoId);
    const count = tracks.length + (tracks.length === 1 ? ' song' : ' songs');
    const meta = [data.owner, count, data.editable ? 'You can edit this' : ''].filter(Boolean).join(' · ');
    const none = tracks.length === 0;
    return [
      h('div', { class: 'phead' },
        h('div', {}, h('h1', { text: data.title || 'Playlist' }), h('p', { class: 'muted', text: meta })),
        h('div', { class: 'actions' },
          h('button', {
            class: 'btn primary', type: 'button', disabled: none,
            onclick: () => player.playList(tracks, 0, { shuffle: false }),
          }, icon('play'), 'Play'),
          h('button', {
            class: 'btn', type: 'button', disabled: none,
            onclick: () => player.playList(tracks, Math.floor(Math.random() * tracks.length), { shuffle: true }),
          }, icon('shuffle'), 'Shuffle'))),
      none
        ? h('p', { class: 'muted', text: 'This list has no playable songs.' })
        : trackList(tracks, { disposers, likedList: ctx.id === 'LM' }),
    ];
  });
  return () => disposers.forEach((off) => off());
}
