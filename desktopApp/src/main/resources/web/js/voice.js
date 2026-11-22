// voice.js - click-to-speak. The microphone starts BEFORE "Listening" is shown (it is shown on the first
// audio frame), recording stops on 600 ms of silence, after 8 s, or on a second click (or on release in
// "hold to talk"), then 16 kHz mono 16-bit WAV with 300 ms of quiet on both sides goes to POST /api/voice.
// The answer is carried out in this tab (the PC plays nothing). Audio, text and candidates live in memory
// only for the request and the panel, and are dropped afterwards. Needs localhost or HTTPS.
import { api, postRaw, ApiError } from './api.js';
import { player } from './player.js';
import { h, icon } from './dom.js';
import { toTrack } from './util.js';
import { describeError } from './ui.js';
import { EndPointer, buildWav, planFor, pickCandidate, volumeAfter } from './voice-core.js';

const K_HOLD = 'muses.voice.hold';
const K_CUE = 'muses.voice.cue';
const CUE_MS = 90;
const PANEL_MS = 6000;       // a result without choices stays this long
const CHOICES_MS = 30000;    // choices stay this long, then they and the remembered candidates go
const LAST_PLAYLIST_MS = 10 * 60 * 1000;

let phase = 'idle'; // 'idle' | 'starting' | 'listening' | 'sending'
let session = null;
let candidates = []; // the last choices shown (for "number 2" / "the other one")
let lastPlaylist = null; // { id, title, at }: for "add it to the same playlist", this tab only
let panel = null;
let panelTimer = 0;
let candTimer = 0;
const buttons = new Set();

// ---- settings (preferences only; never content) --------------------------------------------------------
function read(key) { try { return localStorage.getItem(key); } catch { return null; } }
function write(key, on) { try { if (on) localStorage.setItem(key, '1'); else localStorage.removeItem(key); } catch { /* blocked */ } }

export const voiceSettings = {
  get hold() { return read(K_HOLD) === '1'; },
  set hold(on) { write(K_HOLD, on); },
  get cue() { return read(K_CUE) === '1'; },
  set cue(on) { write(K_CUE, on); },
};

export function unsupportedReason() {
  if (!window.isSecureContext) {
    return 'The microphone only works on localhost or HTTPS. Open http://localhost:' + (location.port || '8765')
      + ' on this PC. Voice over the network needs HTTPS, which Muses Web does not have yet.';
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return 'This browser cannot use the microphone.';
  if (!window.AudioContext && !window.webkitAudioContext) return 'This browser cannot record audio.';
  return null;
}

// ---- the small result panel ------------------------------------------------------------------------------
function ensurePanel() {
  if (panel) return panel;
  panel = h('div', { id: 'voice-panel', class: 'voice-panel', role: 'status', 'aria-live': 'polite', hidden: true });
  document.body.append(panel);
  return panel;
}

/** Remembers the last choices (for "number 2" / "the other one") for 30 s, then forgets them. */
function setCandidates(list) {
  candidates = list;
  clearTimeout(candTimer);
  candTimer = list.length ? setTimeout(() => { candidates = []; candTimer = 0; }, CHOICES_MS) : 0;
}

/** Empties and hides the panel. The remembered choices stay until their own 30 s are over. */
function hidePanel() {
  clearTimeout(panelTimer);
  panelTimer = 0;
  if (panel) { panel.replaceChildren(); panel.hidden = true; }
}

/** The Close button: everything goes, including the remembered choices. */
function dismiss() {
  hidePanel();
  setCandidates([]);
}

/** Shows a line, optionally what was heard and up to 3 choices. Without choices it hides itself later. */
function say(text, opts = {}) {
  const box = ensurePanel();
  clearTimeout(panelTimer);
  const kids = [h('p', { class: 'vp-line', text })];
  if (opts.heard) kids.push(h('p', { class: 'vp-heard muted', text: 'Heard: \u201C' + opts.heard + '\u201D' }));
  if (opts.choices && opts.choices.length) {
    kids.push(h('ul', { class: 'vp-choices' }, opts.choices.map((item) => h('li', {},
      h('button', { class: 'btn wide', type: 'button', onclick: () => choose(item) },
        h('span', { class: 'vp-t', text: item.title }),
        item.subtitle ? h('span', { class: 'vp-s muted', text: item.subtitle }) : null)))));
  }
  if (!opts.busy) kids.push(h('button', { class: 'btn small ghost', type: 'button', text: 'Close', onclick: dismiss }));
  box.replaceChildren(...kids);
  box.hidden = false;
  if (!opts.busy) panelTimer = setTimeout(hidePanel, opts.choices && opts.choices.length ? CHOICES_MS : PANEL_MS);
}

// ---- buttons ---------------------------------------------------------------------------------------------
function sync() {
  const on = phase === 'listening' || phase === 'starting';
  const label = phase === 'listening' ? 'Stop listening' : phase === 'sending' ? 'Cancel voice command' : phase === 'starting' ? 'Cancel' : 'Speak a command';
  for (const b of buttons) {
    b.setAttribute('aria-pressed', String(on));
    b.setAttribute('aria-label', label);
    b.setAttribute('title', label);
    b.classList.toggle('live', on || phase === 'sending');
  }
}

function setPhase(p) { phase = p; sync(); }

/** Click-to-speak on a button; in "hold to talk" a pointer press starts and release stops. Returns a detach function. */
export function attach(button) {
  const holding = () => voiceSettings.hold;
  const onClick = (e) => { if (holding() && e.detail !== 0) return; toggle(); };
  const onDown = (e) => {
    if (!holding() || (e.pointerType === 'mouse' && e.button !== 0)) return;
    try { button.setPointerCapture(e.pointerId); } catch { /* capture is a nicety */ }
    start(true);
  };
  const onUp = () => { if (holding()) release(); };
  const onMenu = (e) => { if (holding()) e.preventDefault(); }; // no context menu on a long touch
  button.addEventListener('click', onClick);
  button.addEventListener('pointerdown', onDown);
  button.addEventListener('pointerup', onUp);
  button.addEventListener('pointercancel', onUp);
  button.addEventListener('contextmenu', onMenu);
  buttons.add(button);
  sync();
  return () => {
    button.removeEventListener('click', onClick);
    button.removeEventListener('pointerdown', onDown);
    button.removeEventListener('pointerup', onUp);
    button.removeEventListener('pointercancel', onUp);
    button.removeEventListener('contextmenu', onMenu);
    buttons.delete(button);
  };
}

// ---- capture -----------------------------------------------------------------------------------------------
function teardown(s) {
  try {
    if (s.node && s.node.port) s.node.port.onmessage = null;
    if (s.node && 'onaudioprocess' in s.node) s.node.onaudioprocess = null;
    if (s.src) s.src.disconnect();
    if (s.node) s.node.disconnect();
    if (s.mute) s.mute.disconnect();
  } catch { /* already disconnected */ }
  if (s.stream) s.stream.getTracks().forEach((t) => t.stop()); // the browser's recording dot goes off now
  if (s.ctx && s.ctx.state !== 'closed') s.ctx.close().catch(() => {});
  s.stream = null;
  s.ctx = null;
}

async function wire(s) {
  const ctx = s.ctx;
  s.src = ctx.createMediaStreamSource(s.stream);
  s.mute = ctx.createGain(); // keeps the graph pulled without playing the microphone out loud
  s.mute.gain.value = 0;
  s.mute.connect(ctx.destination);
  if (ctx.audioWorklet && typeof AudioWorkletNode === 'function') {
    try {
      await ctx.audioWorklet.addModule('/js/voice-worklet.js');
      if (s.cancelled) return;
      s.node = new AudioWorkletNode(ctx, 'muses-capture', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
      s.node.port.onmessage = (e) => { if (e.data instanceof Float32Array) onFrame(s, e.data); };
      s.src.connect(s.node);
      s.node.connect(s.mute);
      return;
    } catch { /* worklet blocked or missing: use the older node below */ }
  }
  if (typeof ctx.createScriptProcessor !== 'function') throw new Error('norecord');
  s.node = ctx.createScriptProcessor(4096, 1, 1);
  s.node.onaudioprocess = (e) => onFrame(s, new Float32Array(e.inputBuffer.getChannelData(0)));
  s.src.connect(s.node);
  s.node.connect(s.mute);
}

function playCue(ctx) {
  try {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 880;
    gain.gain.value = 0.04;
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + CUE_MS / 1000);
  } catch { /* no cue */ }
}

function onFrame(s, frame) {
  if (s.done || s.cancelled || !s.ctx) return;
  const now = performance.now();
  if (!s.listening) {
    // First audio has arrived: only now does the page say "Listening" (and only then may a cue sound).
    s.listening = true;
    setPhase('listening');
    say('Listening\u2026', { busy: true });
    if (voiceSettings.cue) { s.mutedUntil = now + CUE_MS + 60; playCue(s.ctx); }
  }
  if (now < s.mutedUntil) return; // the cue is not recorded
  s.chunks.push(frame);
  const verdict = s.pointer.push(frame, s.ctx.sampleRate);
  if (verdict) finish(s, verdict);
  else if (s.stopAsked) finish(s, 'manual');
}

async function start(hold) {
  if (phase !== 'idle') return;
  const why = unsupportedReason();
  if (why) { say(why); return; }
  hidePanel();
  const s = {
    hold: !!hold, cancelled: false, done: false, listening: false, stopAsked: false, mutedUntil: 0,
    stream: null, ctx: null, src: null, node: null, mute: null, ctl: null, chunks: [],
    pointer: new EndPointer({ stopOnSilence: !hold }),
  };
  session = s;
  setPhase('starting');
  say('Starting the microphone\u2026', { busy: true });
  try {
    s.stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    if (s.cancelled) { teardown(s); return; }
    const Ctx = window.AudioContext || window.webkitAudioContext;
    s.ctx = new Ctx();
    if (s.ctx.state === 'suspended') await s.ctx.resume();
    await wire(s);
    if (s.cancelled) { teardown(s); return; }
    if (s.stopAsked && !s.listening) finish(s, 'manual');
  } catch (e) {
    teardown(s);
    fail(s, e);
  }
}

/** A second click, or release in hold mode. */
function release() {
  const s = session;
  if (!s || s.done || s.cancelled) return;
  if (phase === 'listening') finish(s, 'manual');
  else if (phase === 'starting') s.stopAsked = true;
}

function cancel(message) {
  const s = session;
  if (!s || s.cancelled) return; // also works while the request is in flight (s.done is already true then)
  s.cancelled = true;
  s.done = true;
  if (s.ctl) s.ctl.abort();
  teardown(s);
  s.chunks = [];
  session = null;
  setPhase('idle');
  if (message) say(message); else hidePanel();
}

function toggle() {
  if (phase === 'idle') start(false);
  else if (phase === 'listening') release();
  else cancel(); // starting or sending: a click cancels
}

async function finish(s, reason) {
  if (s.done) return;
  s.done = true;
  const rate = s.ctx ? s.ctx.sampleRate : 48000;
  const chunks = s.chunks;
  s.chunks = [];
  teardown(s); // microphone and audio graph are released before anything is sent
  const heard = s.pointer.heard;
  if (reason === 'nospeech' || !heard) {
    session = null;
    setPhase('idle');
    say('I did not hear anything. Try again, a little closer to the microphone.');
    return;
  }
  setPhase('sending');
  say('Thinking\u2026', { busy: true });
  let wav = buildWav(chunks, rate);
  s.ctl = new AbortController();
  try {
    const ans = await postRaw('/api/voice', wav.bytes, 'audio/wav', { signal: s.ctl.signal });
    wav = null; // dropped: nothing about the recording is kept
    if (s.cancelled) return;
    session = null;
    setPhase('idle');
    await handle(ans);
  } catch (e) {
    wav = null;
    if (s.cancelled || (e && e.name === 'AbortError')) return;
    fail(s, e);
  }
}

function messageFor(e) {
  const name = e && e.name;
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'The microphone is blocked. Allow it for this site in the browser settings, then try again.';
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'No microphone was found.';
  if (name === 'NotReadableError' || name === 'AbortError') return 'The microphone is busy in another app.';
  if (e && e.message === 'norecord') return 'This browser cannot record audio.';
  if (e instanceof ApiError) {
    switch (e.code) {
      case 'program_missing':
      case 'model_missing': return 'Voice recognition is not installed in the desktop app yet. Set it up there: Settings, Advanced, Experimental, Voice commands.';
      case 'timeout': return 'Recognition took too long. Try again.';
      case 'busy': return 'Muses is still handling another voice request.';
      case 'too_short': return 'That was too short. Try again.';
      case 'too_long': return 'That was too long. Keep it under 8 seconds.';
      case 'bad_audio': return 'The recording could not be read. Try again.';
      default: break;
    }
  }
  return describeError(e);
}

function fail(s, e) {
  if (session === s) session = null;
  setPhase('idle');
  say(messageFor(e));
}

// ---- carrying out the answer in this tab -----------------------------------------------------------------
async function playItem(item) {
  if (item.type === 'song') { player.playNow(toTrack(item)); return 'Playing ' + item.title; }
  const res = await api.playlist(item.id);
  const tracks = Array.isArray(res.items) ? res.items : [];
  if (!tracks.length) return 'There is nothing to play in ' + item.title + '.';
  player.playList(tracks, 0);
  lastPlaylist = { id: item.id, title: item.title, at: Date.now() };
  return 'Playing ' + item.title;
}

async function addCurrent(id, title) {
  const t = player.current;
  if (!t) return 'Nothing is playing to add.';
  await api.add(id, t.videoId);
  lastPlaylist = { id, title, at: Date.now() };
  return 'Added to ' + title;
}

function nowPlaying() {
  const t = player.current;
  if (!t) return 'Nothing is playing.';
  return (t.title || 'A song') + (t.artists ? ' by ' + t.artists : '');
}

async function execute(plan) {
  switch (plan.do) {
    case 'item': return playItem(plan.item);
    case 'addTo': return addCurrent(plan.item.id, plan.item.title);
    case 'addLast': {
      if (!lastPlaylist || Date.now() - lastPlaylist.at > LAST_PLAYLIST_MS) return 'Say which playlist first, for example "add this to" and its name.';
      return addCurrent(lastPlaylist.id, lastPlaylist.title);
    }
    case 'control':
      if (plan.name === 'pause') player.pause();
      else if (plan.name === 'resume') player.play();
      else if (plan.name === 'next') player.next();
      else player.prev();
      return plan.text || 'Done';
    case 'volume': player.setVolume(volumeAfter(plan, player.volume)); return 'Volume ' + Math.round(player.volume * 100) + '%';
    case 'shuffle':
      if (plan.on === null || plan.on !== player.shuffle) player.toggleShuffle();
      return player.shuffle ? 'Shuffle on' : 'Shuffle off';
    case 'like':
      if (!player.current) return 'Nothing is playing to like.';
      await player.like(true);
      return 'Liked';
    case 'whatsPlaying': return nowPlaying();
    default: return plan.text || 'I did not understand that.';
  }
}

async function choose(item) {
  setCandidates([]);
  try { say(await playItem(item)); } catch (e) { say(messageFor(e)); }
}

async function handle(ans) {
  const heard = ans && typeof ans.heard === 'string' ? ans.heard : '';
  const plan = planFor(ans);
  try {
    if (plan.do === 'choices') {
      setCandidates(plan.items);
      say(plan.text || 'Did you mean\u2026?', { heard, choices: plan.items });
      return;
    }
    if (plan.do === 'choose' || plan.do === 'chooseOther') {
      const item = pickCandidate(candidates, plan);
      if (!item) { say('There is nothing to choose from. Ask again.', { heard }); return; }
      setCandidates([]);
      say(await playItem(item), { heard });
      return;
    }
    say(await execute(plan), { heard });
  } catch (e) {
    say(messageFor(e), { heard });
  }
}

// ---- start-up ---------------------------------------------------------------------------------------------
/** Adds the microphone button to the top bar, before the account dot. */
export function initVoice() {
  const bar = document.getElementById('top-actions');
  if (!bar) return;
  const btn = h('button', { id: 'mic', class: 'icon-btn mic', type: 'button', 'aria-label': 'Speak a command', 'aria-pressed': 'false' }, icon('mic'));
  bar.insertBefore(btn, document.getElementById('avatar'));
  attach(btn);
  // A hidden tab must not keep a microphone open: drop the recording without sending it.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && (phase === 'listening' || phase === 'starting')) cancel();
  });
}

export const voice = { attach, toggle, get phase() { return phase; }, cancel };
