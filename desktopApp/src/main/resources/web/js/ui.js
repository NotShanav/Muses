// ui.js - shared pieces: toast, error box, cards and shelves, track lists, the small menu dialog,
// and renderAsync (loading -> content or error with "Try again").
import { h, icon, img } from './dom.js';
import { player } from './player.js';
import { api, ApiError } from './api.js';
import { toTrack, itemHref, fmtTime } from './util.js';

// ---- messages -------------------------------------------------------------------------------------
export function describeError(err) {
  if (err instanceof ApiError) {
    if (err.code === 'signed_out') return 'Sign in to YouTube Music inside the Muses desktop app first.';
    if (err.status === 403) return 'This browser session is not allowed. Reload the page.';
    if (err.status === 429) return 'Muses is busy. Try again in a moment.';
    return err.message;
  }
  if (err instanceof TypeError) return 'Cannot reach Muses on your computer. Is the app running?';
  return 'Something went wrong.';
}

let toastTimer = 0;

export function toast(message, opts = {}) {
  const box = document.getElementById('toast');
  const hide = () => box.classList.remove('show');
  box.replaceChildren(h('span', { text: message }));
  if (opts.action) {
    box.append(h('button', {
      class: 'btn small', type: 'button', text: opts.action,
      onclick: () => { hide(); if (opts.onAction) opts.onAction(); },
    }));
  }
  box.classList.add('show');
  clearTimeout(toastTimer);
  if (!opts.sticky) toastTimer = setTimeout(hide, 3500);
}

export function errorBox(err, retry) {
  return h('div', { class: 'msg', role: 'alert' },
    h('p', { text: describeError(err) }),
    retry ? h('button', { class: 'btn', type: 'button', text: 'Try again', onclick: retry }) : null);
}

/** Loading text, then build(data) (a node or an array), or the error box. Aborted loads do nothing. */
export async function renderAsync(ctx, title, fetcher, build, silent = false) {
  const { main } = ctx;
  if (!silent) {
    main.replaceChildren(h('h1', { text: title }), h('div', { class: 'skel', role: 'status', 'aria-label': 'Loading' }, Array.from({ length: 8 }, () => h('i'))));
  }
  try {
    const data = await fetcher();
    if (ctx.signal.aborted) return;
    main.replaceChildren(...[].concat(build(data)));
  } catch (e) {
    if (ctx.signal.aborted || (e && e.name === 'AbortError')) return;
    main.replaceChildren(h('h1', { text: title }), errorBox(e, () => renderAsync(ctx, title, fetcher, build)));
  }
}

// ---- cards and shelves ----------------------------------------------------------------------------
export function card(item, onPlay) {
  const parts = [
    img(item.art, 148, 'art'),
    h('span', { class: 't', text: item.title || '' }),
    h('span', { class: 's', text: item.subtitle || '' }),
  ];
  if (item.type === 'song') return h('button', { class: 'card', type: 'button', onclick: onPlay }, parts);
  return h('a', { class: 'card' + (item.type === 'artist' ? ' round' : ''), href: itemHref(item) }, parts);
}

let shelfSeq = 0;

/** One horizontal shelf (CSS scroll-snap). Songs in it play as a queue from the one clicked. */
export function shelf(s) {
  const songs = s.items.filter((i) => i.type === 'song');
  const tracks = songs.map(toTrack);
  const id = 'sh-' + (++shelfSeq);
  const cards = s.items.map((item) => card(item, () => player.playList(tracks, songs.indexOf(item))));
  return h('section', { class: 'sec' },
    h('h2', { id, text: s.title || '' }),
    h('div', { class: 'shelf', role: 'group', 'aria-labelledby': id, tabindex: '0' }, cards));
}

// ---- dialog menu ----------------------------------------------------------------------------------
export function menuDialog(title, actions) {
  const dlg = h('dialog', { class: 'menu', 'aria-label': title },
    h('div', { class: 'inner' },
      h('h2', { text: title }),
      h('div', { class: 'list' }, actions.map((a) => h('button', {
        class: 'btn wide', type: 'button', text: a.label,
        onclick: () => { dlg.close(); a.run(); },
      }))),
      h('button', { class: 'btn wide ghost', type: 'button', text: 'Cancel', onclick: () => dlg.close() })));
  dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close(); }); // the backdrop
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  return dlg;
}

export async function choosePlaylist(videoId) {
  try {
    const lib = await api.library();
    const lists = lib.playlists || [];
    if (!lists.length) { toast('You have no playlists to add to.'); return; }
    menuDialog('Add to playlist', lists.map((p) => ({
      label: p.title,
      run: async () => {
        try { await api.add(p.id, videoId); toast('Added to ' + p.title); } catch (e) { toast(describeError(e)); }
      },
    })));
  } catch (e) {
    toast(describeError(e));
  }
}

// ---- track list -----------------------------------------------------------------------------------
function row(t, i) {
  return h('li', { class: 'row', 'data-i': i, 'data-v': t.videoId },
    h('button', { class: 'row-main', type: 'button', 'data-act': 'play' },
      img(t.art, 44, 'art'),
      h('span', { class: 'rt' },
        h('span', { class: 't', text: t.title || '' }),
        h('span', { class: 's', text: t.artists || '' })),
      t.durationMs ? h('span', { class: 'dur', text: fmtTime(t.durationMs / 1000) }) : null),
    h('button', {
      class: 'icon-btn', type: 'button', 'data-act': 'more',
      'aria-label': 'More actions for ' + (t.title || 'song'),
    }, icon('more')));
}

function trackMenu(t, opts) {
  const likedList = !!opts.likedList;
  menuDialog(t.title || 'Song', [
    { label: 'Play next', run: () => { player.playNext(t); toast('Playing next'); } },
    { label: 'Add to queue', run: () => { player.enqueue(t); toast('Added to queue'); } },
    { label: 'Add to playlist…', run: () => choosePlaylist(t.videoId) },
    {
      label: likedList ? 'Remove from liked music' : 'Like',
      run: async () => {
        try { await api.like(t.videoId, !likedList); toast(likedList ? 'Removed from liked music' : 'Liked'); }
        catch (e) { toast(describeError(e)); }
      },
    },
  ]);
}

/**
 * A list of Tracks; one click handler for all rows. `opts.disposers` receives the cleanup for the
 * "now playing" highlight, `opts.likedList` is true on Liked music (then the menu offers "remove").
 */
export function trackList(tracks, opts = {}) {
  const ul = h('ul', { class: 'tracks' }, tracks.map(row));
  ul.addEventListener('click', (e) => {
    const li = e.target.closest('li[data-i]');
    if (!li) return;
    const t = tracks[Number(li.dataset.i)];
    if (!t) return;
    const btn = e.target.closest('button[data-act]');
    if (btn && btn.dataset.act === 'more') trackMenu(t, opts);
    else player.playList(tracks, Number(li.dataset.i));
  });
  const mark = () => {
    const old = ul.querySelector('.cur');
    if (old) old.classList.remove('cur');
    const cur = player.current;
    if (!cur) return;
    const li = ul.querySelector('[data-v="' + CSS.escape(cur.videoId) + '"]');
    if (li) li.classList.add('cur');
  };
  mark();
  if (opts.disposers) opts.disposers.push(player.on('track', mark));
  return ul;
}
