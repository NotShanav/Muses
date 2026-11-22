// theme.js - Light, Dark, AMOLED, Album tint, and the battery saver. The choice lives in localStorage
// (keys muses.theme / muses.saver). boot.js applies it before first paint; this module changes it.
import { tintGradient } from './util.js';

const K_THEME = 'muses.theme';
const K_SAVER = 'muses.saver';
export const THEMES = [
  ['system', 'Follow the system'],
  ['light', 'Light'],
  ['dark', 'Dark'],
  ['amoled', 'AMOLED black'],
  ['tint', 'Album tint'],
];

const root = document.documentElement;

function read(key) { try { return localStorage.getItem(key); } catch { return null; } }
function write(key, value) {
  try {
    if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value);
  } catch { /* storage blocked: the choice lasts until reload */ }
}

export function getTheme() {
  const t = read(K_THEME);
  return t && t !== 'system' && THEMES.some((x) => x[0] === t) ? t : 'system';
}

export const saverOn = () => read(K_SAVER) === '1';

const resolve = (t) => (t === 'system' ? (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark') : t);

function apply() {
  root.dataset.theme = resolve(getTheme());
  if (saverOn()) root.dataset.saver = 'on'; else delete root.dataset.saver;
  const meta = document.querySelector('meta[name="theme-color"]');
  const bg = getComputedStyle(root).getPropertyValue('--bg').trim();
  if (meta && bg) meta.setAttribute('content', bg);
}

// --- Album tint: a static gradient on one of two fixed layers; changing it cross-fades opacity only.
let palette = null;
let shown = '';
let front = 0;

function renderTint() {
  const layers = document.querySelectorAll('#tint i');
  if (layers.length < 2) return;
  const gradient = getTheme() === 'tint' ? tintGradient(palette) : null;
  if (!gradient) {
    shown = '';
    layers.forEach((l) => l.classList.remove('on'));
    return;
  }
  if (gradient === shown) return;
  shown = gradient;
  const next = layers[front ^ 1];
  next.style.setProperty('background', gradient);
  next.classList.add('on');
  layers[front].classList.remove('on');
  front ^= 1;
}

/** The three hex colours of /api/track `palette`, or null. Used only by the "Album tint" theme. */
export function setTint(colors) {
  palette = colors || null;
  renderTint();
}

export function setTheme(name) {
  write(K_THEME, name === 'system' ? null : name);
  apply();
  renderTint();
}

export function setSaver(on) {
  write(K_SAVER, on ? '1' : null);
  apply();
}

export function initTheme() {
  apply();
  matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
    if (getTheme() === 'system') apply();
  });
}
