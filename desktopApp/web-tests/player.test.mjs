// Optional: `node desktopApp/web-tests/player.test.mjs` (Node 22+). Runs js/player.js against a tiny
// fake <audio>, document and fetch, to check the queue, shuffle and repeat logic. No browser.
import assert from 'node:assert/strict';

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)); },
  removeItem: (k) => { store.delete(k); },
};
class FakeAudio extends EventTarget {
  constructor() {
    super();
    this.src = ''; this.paused = true; this.ended = false; this.currentTime = 0; this.duration = NaN; this.error = null;
    this.volume = 1; this.muted = false; this.preload = '';
    FakeAudio.all.push(this);
  }
  removeAttribute(name) { if (name === 'src') this.src = ''; }
  load() { this.loads = (this.loads || 0) + 1; }
  play() { this.paused = false; this.ended = false; this.dispatchEvent(new Event('play')); return Promise.resolve(); }
  pause() { if (!this.paused) { this.paused = true; this.dispatchEvent(new Event('pause')); } }
}
FakeAudio.all = [];
globalThis.Audio = FakeAudio;
globalThis.document = Object.assign(new EventTarget(), { hidden: false });
const fetched = [];
globalThis.fetch = async (url) => { fetched.push(String(url)); return { ok: true, status: 200, json: async () => ({ title: 'x', liked: false, artists: [] }) }; };

const { player } = await import('../src/main/resources/web/js/player.js');
const t = (id) => ({ videoId: id, title: id, artists: '', durationMs: 0, art: null });
const ids = () => player.queue.map((x) => x.videoId);
let n = 0;
const test = (name, fn) => { fn(); n++; console.log('ok -', name); };
const atest = async (name, fn) => { await fn(); n++; console.log('ok -', name); };
const tick = () => new Promise((r) => setTimeout(r, 0));
const fire = (type) => player.audio.dispatchEvent(new Event(type));

test('playList starts at the chosen track and streams it', () => {
  player.playList([t('a'), t('b'), t('c')], 1, { shuffle: false });
  assert.equal(player.current.videoId, 'b');
  assert.equal(player.audio.src, '/api/stream/b');
  assert.ok(player.playing);
});

test('next stops at the end unless repeat all', () => {
  player.next();
  assert.equal(player.current.videoId, 'c');
  player.next();
  assert.equal(player.current.videoId, 'c');
  player.cycleRepeat();
  assert.equal(player.repeat, 'all');
  player.next();
  assert.equal(player.current.videoId, 'a');
});

test('prev restarts when past 3 s, else goes back', () => {
  player.audio.currentTime = 10;
  player.prev();
  assert.equal(player.current.videoId, 'a');
  assert.equal(player.audio.currentTime, 0);
  player.jumpTo(2);
  player.audio.currentTime = 1;
  player.prev();
  assert.equal(player.current.videoId, 'b');
});

test('repeat one restarts the same track when it ends', () => {
  player.cycleRepeat();
  assert.equal(player.repeat, 'one');
  player.audio.currentTime = 99;
  player.audio.dispatchEvent(new Event('ended'));
  assert.equal(player.current.videoId, 'b');
  assert.equal(player.audio.currentTime, 0);
  player.cycleRepeat();
  assert.equal(player.repeat, 'off');
});

test('shuffle keeps the current track first and off restores the order', () => {
  player.playList([t('a'), t('b'), t('c'), t('d')], 2, { shuffle: false });
  player.toggleShuffle();
  assert.equal(player.index, 0);
  assert.equal(player.current.videoId, 'c');
  assert.deepEqual([...ids()].sort(), ['a', 'b', 'c', 'd']);
  player.toggleShuffle();
  assert.deepEqual(ids(), ['a', 'b', 'c', 'd']);
  assert.equal(player.current.videoId, 'c');
  assert.equal(player.index, 2);
});

test('playNext and enqueue put tracks where expected', () => {
  player.playList([t('a'), t('b'), t('c')], 0, { shuffle: false });
  player.playNext(t('x'));
  player.enqueue(t('y'));
  assert.deepEqual(ids(), ['a', 'x', 'b', 'c', 'y']);
  assert.equal(player.current.videoId, 'a');
});

test('ended at the end of the queue stays on the last track, paused', () => {
  player.playList([t('a'), t('b')], 1, { shuffle: false });
  player.audio.pause();
  player.audio.dispatchEvent(new Event('ended'));
  assert.equal(player.current.videoId, 'b');
  assert.ok(!player.playing);
});

test('a media error retries once, then skips; at most three skips in a row', () => {
  player.playList([t('a'), t('b'), t('c'), t('d'), t('e'), t('f')], 0, { shuffle: false });
  player.audio.error = { code: 4 };
  fire('error');
  assert.equal(player.current.videoId, 'a'); // first error: retry, no skip
  fire('error');
  assert.equal(player.current.videoId, 'b'); // second error: skip
  for (let i = 0; i < 12; i++) fire('error');
  assert.equal(player.current.videoId, 'd');
  player.audio.error = null;
});

test('the retry keeps the position', () => {
  player.playList([t('a'), t('b')], 0, { shuffle: false });
  player.audio.currentTime = 42;
  player.audio.error = { code: 4 };
  fire('error');
  assert.equal(player.current.videoId, 'a');
  player.audio.currentTime = 0;
  fire('loadedmetadata');
  assert.equal(player.audio.currentTime, 42);
  player.audio.error = null;
});

test('tracks without a videoId are ignored', () => {
  player.playList([{ title: 'no id' }, t('z')], 0, { shuffle: false });
  assert.equal(player.current.videoId, 'z');
  assert.equal(player.queue.length, 1);
});

test('volume is clamped, saved, and unmutes', () => {
  player.setVolume(0.3);
  assert.equal(player.audio.volume, 0.3);
  assert.equal(localStorage.getItem('muses.vol'), '0.30');
  player.setVolume(5);
  assert.equal(player.audio.volume, 1);
  player.toggleMute();
  assert.equal(player.muted, true);
  player.setVolume(0.5);
  assert.equal(player.muted, false);
  player.setVolume('x');
  assert.equal(player.audio.volume, 0.5);
});

test('quality hint adds ?q=low only when set', () => {
  player.setQuality('low');
  player.playList([t('q1')], 0);
  assert.equal(player.audio.src, '/api/stream/q1?q=low');
  player.setQuality('auto');
  player.playList([t('q2')], 0);
  assert.equal(player.audio.src, '/api/stream/q2');
});

await atest('the next track is warmed up once, 20 s before the end, on the second Audio', async () => {
  const pre = FakeAudio.all[1];
  player.playList([t('a'), t('b'), t('c')], 0, { shuffle: false });
  player.audio.duration = 100;
  player.audio.currentTime = 50;
  fire('timeupdate');
  await tick();
  assert.equal(pre.src, '');
  player.audio.currentTime = 85;
  fire('timeupdate');
  await tick(); await tick();
  assert.equal(pre.src, '/api/stream/b');
  assert.equal(pre.preload, 'auto');
  const before = fetched.length;
  fire('timeupdate');
  await tick();
  assert.equal(fetched.length, before); // once only
  player.next();
  assert.equal(pre.src, ''); // released when the track changes
});

test('no warm-up on repeat one or at the end of the queue', () => {
  const pre = FakeAudio.all[1];
  player.playList([t('a'), t('b')], 1, { shuffle: false });
  player.audio.duration = 100;
  player.audio.currentTime = 90;
  fire('timeupdate');
  assert.equal(pre.src, '');
});

test('resume stores ids and position only', () => {
  player.playList([t('aaaaa'), t('bbbbb'), t('ccccc')], 1, { shuffle: false });
  player.audio.currentTime = 33.7;
  player.pause();
  const raw = localStorage.getItem('muses.resume');
  const o = JSON.parse(raw);
  assert.deepEqual(o.ids, ['aaaaa', 'bbbbb', 'ccccc']);
  assert.equal(o.i, 1);
  assert.equal(o.pos, 33);
  assert.ok(!/title|artist|"x"/.test(raw));
});

test('parseResume rejects bad data', () => {
  const ok = JSON.stringify({ v: 1, ids: ['abcde', 'fghij'], i: 1, pos: 5, s: 1, r: 'all' });
  assert.deepEqual(player.parseResume(ok), { ids: ['abcde', 'fghij'], i: 1, pos: 5, s: true, r: 'all' });
  assert.equal(player.parseResume(null), null);
  assert.equal(player.parseResume('{nope'), null);
  assert.equal(player.parseResume(JSON.stringify({ v: 2, ids: ['abcde'] })), null);
  assert.equal(player.parseResume(JSON.stringify({ v: 1, ids: ['a b'], i: 0, pos: 0 })), null);
  assert.equal(player.parseResume(JSON.stringify({ v: 1, ids: [], i: 0, pos: 0 })), null);
  const odd = player.parseResume(JSON.stringify({ v: 1, ids: ['abcde'], i: 9, pos: -3, r: 'zzz' }));
  assert.equal(odd.i, 0);
  assert.equal(odd.pos, 0);
  assert.equal(odd.r, 'off');
});

await atest('a fresh page restores the queue paused at the saved position', async () => {
  const fresh = (await import('../src/main/resources/web/js/player.js?fresh=1')).player;
  assert.equal(fresh.restore(), true);
  assert.equal(fresh.current.videoId, 'bbbbb');
  assert.equal(fresh.audio.src, '/api/stream/bbbbb');
  assert.equal(fresh.audio.paused, true);
  assert.equal(fresh.audio.preload, 'metadata');
  fresh.audio.dispatchEvent(new Event('loadedmetadata'));
  assert.equal(fresh.audio.currentTime, 33);
  await tick(); await tick();
  assert.equal(fresh.current.title, 'x'); // named from /api/track, not from storage
  assert.equal(fresh.restore(), false); // only into an empty player
});

console.log(n + ' groups passed');
process.exit(0);
