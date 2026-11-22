// voice-core.js - the pure part of click-to-speak: the energy end-pointer, resampling to 16 kHz, quiet
// padding, the 16-bit mono WAV writer, and the mapping from a /api/voice answer to a local command.
// No DOM, no network, so `node desktopApp/web-tests/voice.test.mjs` can test it. voice.js does the rest.
import { clamp } from './util.js';

export const TARGET_RATE = 16000;

/** Root-mean-square level of a Float32Array (0 for an empty one). */
export function rms(frame) {
  if (!frame || !frame.length) return 0;
  let sum = 0;
  for (let i = 0; i < frame.length; i++) sum += frame[i] * frame[i];
  return Math.sqrt(sum / frame.length);
}

/**
 * Decides when the user has finished speaking. Feed it frames in order with `push(frame, sampleRate)`.
 * It returns null while recording should go on, or a reason: 'silence' (speech, then `silenceMs` of
 * quiet), 'max' (`maxMs` reached after speech), 'nospeech' (nothing heard in `noSpeechMs`, or at the cap).
 * The first 300 ms set the noise floor; speech is a level above three times that floor.
 * With stopOnSilence false (hold to talk) only the cap ends it.
 */
export class EndPointer {
  constructor(o = {}) {
    this.silenceMs = o.silenceMs ?? 600;
    this.maxMs = o.maxMs ?? 8000;
    this.noSpeechMs = o.noSpeechMs ?? 5000;
    this.minSpeechMs = o.minSpeechMs ?? 120;
    this.calibrateMs = o.calibrateMs ?? 300;
    this.stopOnSilence = o.stopOnSilence !== false;
    this.elapsed = 0;
    this.floorSum = 0;
    this.floorMs = 0;
    this.speechMs = 0;
    this.silentMs = 0;
    this.threshold = 0.012;
    this.heard = false;
  }

  push(frame, rate) {
    const ms = (frame.length / rate) * 1000;
    const level = rms(frame);
    this.elapsed += ms;
    if (this.floorMs < this.calibrateMs) {
      this.floorSum += level * ms;
      this.floorMs += ms;
      this.threshold = clamp((this.floorSum / this.floorMs) * 3, 0.012, 0.08);
    }
    if (level > this.threshold) {
      this.speechMs += ms;
      this.silentMs = 0;
      if (this.speechMs >= this.minSpeechMs) this.heard = true;
    } else if (this.heard) {
      this.silentMs += ms;
    }
    if (this.elapsed >= this.maxMs) return this.heard ? 'max' : 'nospeech';
    if (!this.stopOnSilence) return null;
    if (this.heard && this.silentMs >= this.silenceMs) return 'silence';
    if (!this.heard && this.elapsed >= this.noSpeechMs) return 'nospeech';
    return null;
  }
}

/** Joins Float32Array blocks into one. */
export function concatFloat(chunks) {
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Float32Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}

/** Resamples mono floats. Down: box average over each output sample's span. Up (rare): linear. */
export function downsample(input, inRate, outRate = TARGET_RATE) {
  if (!(inRate > 0) || !(outRate > 0)) throw new RangeError('bad sample rate');
  if (inRate === outRate) return input;
  if (inRate < outRate) {
    const n = Math.floor((input.length * outRate) / inRate);
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const pos = (i * inRate) / outRate;
      const j = Math.floor(pos);
      const a = input[j] ?? 0;
      const b = input[Math.min(j + 1, input.length - 1)] ?? a;
      out[i] = a + (b - a) * (pos - j);
    }
    return out;
  }
  const ratio = inRate / outRate;
  const n = Math.floor(input.length / ratio);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const from = i * ratio;
    const to = from + ratio;
    let sum = 0;
    let weight = 0;
    for (let j = Math.floor(from); j < to && j < input.length; j++) {
      const w = Math.min(to, j + 1) - Math.max(from, j);
      if (w > 0) { sum += input[j] * w; weight += w; }
    }
    out[i] = weight ? sum / weight : 0;
  }
  return out;
}

/** Floats in [-1, 1] to 16-bit integers (values outside are clipped). */
export function toInt16(floats) {
  const out = new Int16Array(floats.length);
  for (let i = 0; i < floats.length; i++) {
    const s = Math.max(-1, Math.min(1, floats[i]));
    out[i] = Math.round(s < 0 ? s * 0x8000 : s * 0x7fff);
  }
  return out;
}

/** `ms` of near-silence (a few LSB of repeatable noise, never pure digital zero) before and after. */
export function padWithQuiet(samples, ms, rate = TARGET_RATE) {
  const pad = Math.round((ms * rate) / 1000);
  const out = new Int16Array(pad + samples.length + pad);
  let seed = 12345;
  const quiet = () => {
    seed = (Math.imul(seed, 1103515245) + 12345) >>> 0;
    return (seed % 5) - 2;
  };
  for (let i = 0; i < pad; i++) { out[i] = quiet(); out[pad + samples.length + i] = quiet(); }
  out.set(samples, pad);
  return out;
}

/** A 16-bit mono PCM WAV file (44-byte header) as a Uint8Array. */
export function encodeWav(samples, rate = TARGET_RATE) {
  const bytes = samples.length * 2;
  const buf = new ArrayBuffer(44 + bytes);
  const v = new DataView(buf);
  const tag = (at, s) => { for (let i = 0; i < 4; i++) v.setUint8(at + i, s.charCodeAt(i)); };
  tag(0, 'RIFF'); v.setUint32(4, 36 + bytes, true); tag(8, 'WAVE');
  tag(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  tag(36, 'data'); v.setUint32(40, bytes, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, samples[i], true);
  return new Uint8Array(buf);
}

/** Recorded blocks at `inRate` -> { bytes, ms }: 16 kHz mono WAV with 300 ms of quiet on both sides. */
export function buildWav(chunks, inRate, padMs = 300) {
  const mono = downsample(concatFloat(chunks), inRate, TARGET_RATE);
  const padded = padWithQuiet(toInt16(mono), padMs, TARGET_RATE);
  return { bytes: encodeWav(padded, TARGET_RATE), ms: Math.round((padded.length * 1000) / TARGET_RATE) };
}

// ---- the answer from POST /api/voice -> what the page does -------------------------------------------------
const ITEM_TYPES = new Set(['song', 'album', 'playlist', 'artist']);
const isItem = (x) => !!x && typeof x === 'object' && typeof x.id === 'string' && typeof x.title === 'string' && ITEM_TYPES.has(x.type);
const none = (text) => ({ do: 'none', text: typeof text === 'string' ? text : '' });

/**
 * Maps the contract's answer ({heard?, action, result, candidates?, item?, value?}) to a command:
 * item, addTo, addLast, choices, choose, chooseOther, control, volume, shuffle, like, whatsPlaying, none.
 */
export function planFor(ans) {
  if (!ans || typeof ans !== 'object' || typeof ans.action !== 'string') return none('');
  const text = typeof ans.result === 'string' ? ans.result : '';
  const item = isItem(ans.item) ? ans.item : null;
  switch (ans.action) {
    case 'play':
    case 'playPlaylist':
      return item ? { do: 'item', item, text } : none(text);
    case 'addToPlaylist':
      return item ? { do: 'addTo', item, text } : none(text);
    case 'addToLastPlaylist':
      return { do: 'addLast', text };
    case 'choices': {
      const items = Array.isArray(ans.candidates) ? ans.candidates.filter(isItem).slice(0, 3) : [];
      return items.length ? { do: 'choices', items, text } : none(text);
    }
    case 'choose': {
      const n = parseInt(ans.value, 10);
      return Number.isInteger(n) && n >= 1 ? { do: 'choose', n, text } : none(text);
    }
    case 'chooseOther':
      return { do: 'chooseOther', text };
    case 'pause':
    case 'resume':
    case 'next':
    case 'previous':
      return { do: 'control', name: ans.action, text };
    case 'volumeUp':
      return { do: 'volume', delta: 10, text };
    case 'volumeDown':
      return { do: 'volume', delta: -10, text };
    case 'setVolume': {
      const p = Number(ans.value);
      return Number.isFinite(p) ? { do: 'volume', percent: clamp(p, 0, 100), text } : none(text);
    }
    case 'shuffle':
      return { do: 'shuffle', on: ans.value === 'true' || ans.value === true ? true : ans.value === 'false' || ans.value === false ? false : null, text };
    case 'like':
      return { do: 'like', text };
    case 'whatsPlaying':
      return { do: 'whatsPlaying', text };
    default:
      return none(text);
  }
}

/** The candidate a spoken "number 2" or "the other one" means, or null. `list` is the last choices shown. */
export function pickCandidate(list, plan) {
  if (!Array.isArray(list) || !list.length) return null;
  if (plan.do === 'choose') return list[plan.n - 1] || null;
  if (plan.do === 'chooseOther') return list.length > 1 ? list[1] : null;
  return null;
}

/** Volume (0..1) after a plan: a percentage sets it, a delta moves it by that many points. */
export function volumeAfter(plan, current) {
  if (plan.percent !== undefined) return clamp(plan.percent / 100, 0, 1);
  return clamp(Math.round(current * 100 + (plan.delta || 0)) / 100, 0, 1);
}
