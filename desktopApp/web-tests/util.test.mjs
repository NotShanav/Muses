// Optional, not part of the Gradle build: `node desktopApp/web-tests/util.test.mjs` (Node 22+).
// Tests the pure helpers of the web UI. No browser, no network, no DOM.
import assert from 'node:assert/strict';
import {
  fmtTime, clamp, parseHash, artSrc, findLyricIndex, shuffled, isHex, tintGradient, toTrack, itemHref,
} from '../src/main/resources/web/js/util.js';

let n = 0;
const test = (name, fn) => { fn(); n++; console.log('ok -', name); };

test('fmtTime', () => {
  assert.equal(fmtTime(0), '0:00');
  assert.equal(fmtTime(83.9), '1:23');
  assert.equal(fmtTime(3725), '1:02:05');
  assert.equal(fmtTime(-1), '0:00');
  assert.equal(fmtTime(NaN), '0:00');
  assert.equal(fmtTime(Infinity), '0:00');
});

test('clamp', () => {
  assert.equal(clamp(5, 0, 3), 3);
  assert.equal(clamp(-5, 0, 3), 0);
  assert.equal(clamp(2, 0, 3), 2);
});

test('parseHash', () => {
  assert.deepEqual(parseHash(''), { name: 'home', id: null, query: {} });
  assert.deepEqual(parseHash('#'), { name: 'home', id: null, query: {} });
  assert.deepEqual(parseHash('#/now'), { name: 'now', id: null, query: {} });
  assert.deepEqual(parseHash('#/search?q=a%26b%20c'), { name: 'search', id: null, query: { q: 'a&b c' } });
  assert.deepEqual(parseHash('#/playlist/PL%20x_-1'), { name: 'playlist', id: 'PL x_-1', query: {} });
  assert.equal(parseHash('#/playlist/%E0%A4%A').id, null);
  assert.equal(parseHash('#/search?q=1&q=2').query.q, '1');
});

test('artSrc resizes google covers only', () => {
  const g = '/api/art?u=' + encodeURIComponent('https://lh3.googleusercontent.com/abc=w544-h544-l90-rj');
  const out = artSrc(g, 148 * 2);
  const u = new URLSearchParams(out.slice('/api/art?'.length)).get('u');
  assert.equal(u, 'https://lh3.googleusercontent.com/abc=w320-h320-l90-rj');
  const s = '/api/art?u=' + encodeURIComponent('https://yt3.ggpht.com/xyz=s900-c-k');
  assert.equal(new URLSearchParams(artSrc(s, 96).slice('/api/art?'.length)).get('u'), 'https://yt3.ggpht.com/xyz=s96-c-k');
  const yt = '/api/art?u=' + encodeURIComponent('https://i.ytimg.com/vi/abc/hq720.jpg?sqp=x');
  assert.equal(artSrc(yt, 96), yt);
  assert.equal(artSrc('https://example.com/x.jpg', 96), 'https://example.com/x.jpg');
  assert.equal(artSrc(null, 96), '');
  assert.equal(artSrc(g, 0), g);
});

test('findLyricIndex', () => {
  const lines = [{ ms: 1000 }, { ms: 2000 }, { ms: 2000 }, { ms: 5000 }];
  assert.equal(findLyricIndex(lines, 0), -1);
  assert.equal(findLyricIndex(lines, 1000), 0);
  assert.equal(findLyricIndex(lines, 1999), 0);
  assert.equal(findLyricIndex(lines, 2000), 2);
  assert.equal(findLyricIndex(lines, 99999), 3);
  assert.equal(findLyricIndex([], 5), -1);
});

test('shuffled is a permutation and does not touch the input', () => {
  const input = [1, 2, 3, 4, 5, 6];
  let seed = 7;
  const rand = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const out = shuffled(input, rand);
  assert.deepEqual(input, [1, 2, 3, 4, 5, 6]);
  assert.deepEqual([...out].sort(), input);
});

test('colours and tint', () => {
  assert.ok(isHex('#a1B2c3'));
  assert.ok(!isHex('#abc'));
  assert.ok(!isHex('red; background:url(x)'));
  assert.equal(tintGradient(['#000000']), null);
  assert.equal(tintGradient(null), null);
  const three = tintGradient(['#112233', '#445566', '#778899']);
  assert.ok(three.includes('#112233, #445566 55%, #778899'));
  const two = tintGradient(['#112233', 'bad', '#778899']);
  assert.ok(two.includes('#112233, #778899'));
  assert.ok(!tintGradient(['#112233', 'url(x)', 'x']));
});

test('toTrack and itemHref', () => {
  const t = toTrack({ id: 'abc', type: 'song', title: 'T', subtitle: 'A', art: '/api/art?u=x' });
  assert.deepEqual(t, { videoId: 'abc', title: 'T', artists: 'A', durationMs: 0, art: '/api/art?u=x' });
  assert.equal(toTrack({ id: 'a', videoId: 'b' }).videoId, 'b');
  assert.equal(itemHref({ id: 'MPREb_x y' }), '#/playlist/MPREb_x%20y');
});

console.log(n + ' groups passed');
