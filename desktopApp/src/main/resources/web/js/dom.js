// dom.js - tiny element helpers. Text always goes in through textContent / text nodes, never innerHTML,
// and there is no `style` attribute (the page's CSP blocks inline styles; use classes or el.style.setProperty).
import { artSrc } from './util.js';

export const $ = (selector, root = document) => root.querySelector(selector);

const PROPS = new Set(['value', 'checked', 'disabled', 'hidden', 'selected']);

function append(el, kids) {
  for (const kid of kids) {
    if (kid == null || kid === false) continue;
    if (Array.isArray(kid)) append(el, kid);
    else el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
}

/** h('button', { class: 'btn', onclick: fn, text: 'Play' }, child, ...) */
export function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value == null || value === false || key === 'style') continue;
      if (key === 'class') el.className = value;
      else if (key === 'text') el.textContent = value;
      else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value);
      else if (PROPS.has(key)) el[key] = value;
      else el.setAttribute(key, value === true ? '' : String(value));
    }
  }
  append(el, kids);
  return el;
}

export const clear = (el) => el.replaceChildren();

const SVG_NS = 'http://www.w3.org/2000/svg';

/** An icon from the sprite in index.html (`#i-<name>`). */
export function icon(name) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'ic');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', '#i-' + name);
  svg.append(use);
  return svg;
}

/** Swaps the icon inside a button (the button keeps its label and handlers). */
export function setIcon(button, name) {
  const use = button.querySelector('use');
  if (use) use.setAttribute('href', '#i-' + name);
}

/** A lazy cover image with explicit size, or a plain placeholder when there is no art. */
export function img(url, px, cls = 'art') {
  if (!url) return h('span', { class: cls + ' ph', 'aria-hidden': 'true' });
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  const el = h('img', { class: cls + ' lz', width: px, height: px, alt: '', loading: 'lazy', decoding: 'async' });
  const done = () => el.classList.add('ld');
  el.addEventListener('load', done);
  el.addEventListener('error', done);
  el.src = artSrc(url, Math.round(px * ratio));
  return el;
}
