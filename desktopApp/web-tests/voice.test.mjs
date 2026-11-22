// Optional: `node desktopApp/web-tests/voice.test.mjs` (Node 22+). Tests js/voice-core.js (end-pointer,
// resampling, quiet padding, WAV header, answer -> command) and the pure parts of js/mediasession.js.
// No browser, no microphone, no network.
import assert from 'node:assert/strict';

globalThis.Audio = class extends EventTarget { constructor() { super(); this.paused = true; this.volume = 1; } };
globalThis.document = Object.assign(new EventTarget(), { hidden: false });

const core = await import('../src/main/resources/web/js/voice-core.js');
const ms = await import('../src/main/resources/web/js/mediasession.js');
const { EndPointer, downsample, toInt16, padWithQuiet, encodeWav, buildWav, planFor, pickCandidate, volumeAfter, rms } = core;

let n = 0;
const test = (name, fn) => { fn(); n++; console.log('ok -', name); };

const RATE = 16000;
const frameLen = 800; // 50 ms
const noise = () => Float32Array.from({ length: frameLen }, (_, i) => 0.003 * Math.sin(i * 1.7));
const speech = () => Float32Array.from({ length: frameLen }, (_, i) => 0.2 * Math.sin(i * 0.3));
function run(ep, plan) { // plan: [[frames, kind], ...] -> { reason, at } (at = frames fed)
  let fed = 0;
  for (const [count, kind] of plan) {
    for (let i = 0; i < count; i++) {
      fed++;
      const r = ep.push(kind === 's' ? speech() : noise(), RATE);
      if (r) return { reason: r, at: fed };
    }
  }
  return { reason: null, at: fed };
}

test('rms of silence, a constant and an empty frame', () => {
  assert.equal(rms(new Float32Array(0)), 0);
  assert.equal(rms(new Float32Array(10)), 0);
  assert.ok(Math.abs(rms(Float32Array.from({ length: 10 }, () => 0.5)) - 0.5) < 1e-9);
});

test('end-pointer stops 600 ms after speech ends', () => {
  const r = run(new EndPointer(), [[6, 'n'], [20, 's'], [40, 'n']]);
  assert.equal(r.reason, 'silence');
  assert.equal(r.at, 6 + 20 + 12); // 12 frames of 50 ms = 600 ms
});

test('end-pointer waits through a short pause inside a sentence', () => {
  const r = run(new EndPointer(), [[6, 'n'], [10, 's'], [8, 'n'], [10, 's'], [8, 'n']]);
  assert.equal(r.reason, null);
});

test('end-pointer gives up when nothing is said for 5 s', () => {
  const r = run(new EndPointer(), [[200, 'n']]);
  assert.equal(r.reason, 'nospeech');
  assert.equal(r.at, 100);
});

test('a 50 ms click is not speech', () => {
  const ep = new EndPointer();
  run(ep, [[6, 'n'], [1, 's'], [20, 'n']]);
  assert.equal(ep.heard, false);
});

test('end-pointer caps at 8 s of continuous speech', () => {
  const r = run(new EndPointer(), [[6, 'n'], [300, 's']]);
  assert.equal(r.reason, 'max');
  assert.equal(r.at, 160);
});

test('hold to talk ignores silence and ends only at the cap', () => {
  const r = run(new EndPointer({ stopOnSilence: false }), [[6, 'n'], [10, 's'], [100, 'n']]);
  assert.equal(r.reason, null);
  const cap = run(new EndPointer({ stopOnSilence: false }), [[6, 'n'], [10, 's'], [200, 'n']]);
  assert.equal(cap.reason, 'max');
});

test('a noisy room raises the threshold (steady noise is not speech)', () => {
  const loud = () => Float32Array.from({ length: frameLen }, (_, i) => 0.03 * Math.sin(i * 2.1));
  const ep = new EndPointer();
  let reason = null;
  for (let i = 0; i < 80 && !reason; i++) reason = ep.push(loud(), RATE);
  assert.equal(ep.heard, false);
});

test('downsample 48 kHz to 16 kHz keeps length ratio and a low tone', () => {
  const inRate = 48000;
  const input = Float32Array.from({ length: 4800 }, (_, i) => 0.5 * Math.sin((2 * Math.PI * 440 * i) / inRate));
  const out = downsample(input, inRate);
  assert.equal(out.length, 1600);
  let peak = 0;
  for (const v of out) peak = Math.max(peak, Math.abs(v));
  assert.ok(peak > 0.45 && peak <= 0.5);
});

test('downsample 44.1 kHz gives floor(length / ratio) and 16 kHz is returned as is', () => {
  const out = downsample(new Float32Array(44100), 44100);
  assert.equal(out.length, 16000);
  const same = new Float32Array(5);
  assert.equal(downsample(same, 16000), same);
  assert.throws(() => downsample(same, 0));
  assert.equal(downsample(new Float32Array(8000), 8000).length, 16000); // 8 kHz upsampled
});

test('toInt16 clips and scales', () => {
  const out = toInt16(Float32Array.from([0, 1, -1, 2, -2, 0.5]));
  assert.deepEqual([...out], [0, 32767, -32768, 32767, -32768, 16384]);
});

test('300 ms of quiet on both sides, never pure zero, never loud', () => {
  const body = Int16Array.from([1000, -1000, 1000]);
  const out = padWithQuiet(body, 300, RATE);
  assert.equal(out.length, 4800 + 3 + 4800);
  assert.deepEqual([...out.slice(4800, 4803)], [1000, -1000, 1000]);
  const lead = [...out.slice(0, 4800)];
  const trail = [...out.slice(4803)];
  assert.ok(lead.every((v) => Math.abs(v) <= 2) && trail.every((v) => Math.abs(v) <= 2));
  assert.ok(new Set(lead).size > 1);
});

test('encodeWav writes a 16 kHz mono 16-bit PCM header the server accepts', () => {
  const wav = encodeWav(Int16Array.from([1, -2, 3]), RATE);
  const v = new DataView(wav.buffer);
  const tag = (at) => String.fromCharCode(...wav.slice(at, at + 4));
  assert.equal(wav.length, 44 + 6);
  assert.equal(tag(0), 'RIFF');
  assert.equal(v.getUint32(4, true), 36 + 6);
  assert.equal(tag(8), 'WAVE');
  assert.equal(tag(12), 'fmt ');
  assert.equal(v.getUint16(20, true), 1);
  assert.equal(v.getUint16(22, true), 1);
  assert.equal(v.getUint32(24, true), 16000);
  assert.equal(v.getUint32(28, true), 32000);
  assert.equal(v.getUint16(34, true), 16);
  assert.equal(tag(36), 'data');
  assert.equal(v.getUint32(40, true), 6);
  assert.equal(v.getInt16(44, true), 1);
  assert.equal(v.getInt16(46, true), -2);
  assert.equal(v.getInt16(48, true), 3);
});

test('buildWav: 1 s at 48 kHz becomes 1.6 s of WAV (1 s + 2 x 300 ms)', () => {
  const chunks = [new Float32Array(24000), new Float32Array(24000)];
  const { bytes, ms: len } = buildWav(chunks, 48000);
  assert.equal(len, 1600);
  assert.equal(bytes.length, 44 + 1600 * 16 * 2);
});

test('the 8 s cap plus padding stays under the server limits (0.2 s to 15 s, 1 MB)', () => {
  const { bytes, ms: len } = buildWav([new Float32Array(8 * 48000)], 48000);
  assert.ok(len < 15000 && len > 200);
  assert.ok(bytes.length < 1024 * 1024);
});

test('planFor maps every action of the contract', () => {
  const song = { id: 'abcdefghijk', type: 'song', title: 'Song', subtitle: 'A' };
  const list = { id: 'PL1', type: 'playlist', title: 'Mix', subtitle: '' };
  assert.deepEqual(planFor({ action: 'play', result: 'r', item: song }), { do: 'item', item: song, text: 'r' });
  assert.equal(planFor({ action: 'playPlaylist', item: list }).do, 'item');
  assert.equal(planFor({ action: 'play' }).do, 'none');
  assert.equal(planFor({ action: 'addToPlaylist', item: list }).do, 'addTo');
  assert.equal(planFor({ action: 'addToLastPlaylist' }).do, 'addLast');
  const four = [song, song, song, song];
  assert.equal(planFor({ action: 'choices', candidates: four }).items.length, 3);
  assert.equal(planFor({ action: 'choices', candidates: [{ bad: 1 }] }).do, 'none');
  assert.equal(planFor({ action: 'choose', value: '2' }).n, 2);
  assert.equal(planFor({ action: 'choose', value: 'two' }).do, 'none');
  assert.equal(planFor({ action: 'chooseOther' }).do, 'chooseOther');
  for (const a of ['pause', 'resume', 'next', 'previous']) assert.deepEqual(planFor({ action: a }), { do: 'control', name: a, text: '' });
  assert.equal(planFor({ action: 'volumeUp' }).delta, 10);
  assert.equal(planFor({ action: 'volumeDown' }).delta, -10);
  assert.equal(planFor({ action: 'setVolume', value: '35' }).percent, 35);
  assert.equal(planFor({ action: 'setVolume', value: '500' }).percent, 100);
  assert.equal(planFor({ action: 'setVolume', value: 'loud' }).do, 'none');
  assert.equal(planFor({ action: 'shuffle', value: 'true' }).on, true);
  assert.equal(planFor({ action: 'shuffle', value: 'false' }).on, false);
  assert.equal(planFor({ action: 'shuffle' }).on, null);
  assert.equal(planFor({ action: 'like' }).do, 'like');
  assert.equal(planFor({ action: 'whatsPlaying' }).do, 'whatsPlaying');
  assert.equal(planFor({ action: 'none', result: 'no' }).text, 'no');
  assert.equal(planFor({ action: 'somethingNew' }).do, 'none');
  assert.equal(planFor(null).do, 'none');
  assert.equal(planFor('x').do, 'none');
});

test('pickCandidate: number n and "the other one"', () => {
  const list = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  assert.equal(pickCandidate(list, { do: 'choose', n: 3 }).id, 'c');
  assert.equal(pickCandidate(list, { do: 'choose', n: 4 }), null);
  assert.equal(pickCandidate(list, { do: 'chooseOther' }).id, 'b');
  assert.equal(pickCandidate([list[0]], { do: 'chooseOther' }), null);
  assert.equal(pickCandidate([], { do: 'choose', n: 1 }), null);
});

test('volumeAfter: delta and percent, clamped', () => {
  assert.equal(volumeAfter({ delta: 10 }, 0.5), 0.6);
  assert.equal(volumeAfter({ delta: 10 }, 0.95), 1);
  assert.equal(volumeAfter({ delta: -10 }, 0.05), 0);
  assert.equal(volumeAfter({ percent: 35 }, 1), 0.35);
});

test('mediasession metaFor prefers details and builds three artwork sizes', () => {
  const track = { title: 'T', artists: 'A, B', art: '/api/art?u=https%3A%2F%2Flh3.googleusercontent.com%2Fx%3Dw544-h544-l90-rj' };
  const plain = ms.metaFor(track, null);
  assert.equal(plain.title, 'T');
  assert.equal(plain.artist, 'A, B');
  assert.equal(plain.artwork.length, 3);
  assert.equal(plain.artwork[2].sizes, '512x512');
  assert.ok(plain.artwork[2].src.includes('w512-h512'));
  const full = ms.metaFor(track, { title: 'Real', artists: ['X', 'Y'], album: 'Alb', art: { small: '/s', large: '/l' } });
  assert.deepEqual([full.title, full.artist, full.album], ['Real', 'X, Y', 'Alb']);
  assert.equal(full.artwork[0].src, '/l');
  const empty = ms.metaFor(null, null);
  assert.deepEqual(empty, { title: '', artist: '', album: '', artwork: [] });
});

test('mediasession positionFor never returns an invalid state', () => {
  assert.equal(ms.positionFor(NaN, 1, 1), null);
  assert.equal(ms.positionFor(0, 0, 1), null);
  assert.equal(ms.positionFor(Infinity, 0, 1), null);
  assert.deepEqual(ms.positionFor(100, 150, 1), { duration: 100, position: 100, playbackRate: 1 });
  assert.deepEqual(ms.positionFor(100, -5, 0), { duration: 100, position: 0, playbackRate: 1 });
  assert.deepEqual(ms.positionFor(100, NaN, 1.5), { duration: 100, position: 0, playbackRate: 1.5 });
});

console.log(n + ' groups passed');
process.exit(0);
