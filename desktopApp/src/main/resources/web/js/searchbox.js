// searchbox.js - the search field in the top bar with typeahead from /api/suggest. Text typed here
// is sent only to the server's suggest/search endpoints and is never stored by the page.
import { api } from './api.js';
import { h } from './dom.js';
import { debounce } from './util.js';

let input;
let list;
let active = -1;
let ctl = null;

function options() { return Array.from(list.children); }

function close() {
  if (ctl) { ctl.abort(); ctl = null; }
  list.hidden = true;
  list.replaceChildren();
  active = -1;
  input.setAttribute('aria-expanded', 'false');
  input.removeAttribute('aria-activedescendant');
}

function submit(text) {
  const q = text.trim();
  close();
  if (!q) return;
  location.hash = '#/search?q=' + encodeURIComponent(q);
}

function highlight(i) {
  const items = options();
  items.forEach((li, n) => li.setAttribute('aria-selected', n === i ? 'true' : 'false'));
  active = i;
  if (i >= 0) input.setAttribute('aria-activedescendant', items[i].id);
  else input.removeAttribute('aria-activedescendant');
}

function show(suggestions) {
  list.replaceChildren(...suggestions.map((text, n) => h('li', {
    id: 'sg-' + n, role: 'option', 'aria-selected': 'false', text,
  })));
  list.hidden = suggestions.length === 0;
  input.setAttribute('aria-expanded', suggestions.length ? 'true' : 'false');
  active = -1;
}

const lookup = debounce(async () => {
  const q = input.value.trim();
  if (q.length < 2) { close(); return; }
  if (ctl) ctl.abort();
  ctl = new AbortController();
  try {
    const found = await api.suggest(q, ctl.signal);
    if (Array.isArray(found)) show(found.filter((s) => typeof s === 'string').slice(0, 8));
  } catch { /* typeahead is optional: no message */ }
}, 180);

export function setQuery(q) { if (input) input.value = q || ''; }
export function focusSearch() { if (input) input.focus(); }

export function initSearch() {
  const form = document.getElementById('searchform');
  input = document.getElementById('q');
  list = document.getElementById('sugg');
  form.addEventListener('submit', (e) => { e.preventDefault(); submit(input.value); });
  input.addEventListener('input', lookup);
  input.addEventListener('keydown', (e) => {
    const n = options().length;
    if (e.key === 'ArrowDown' && n) { e.preventDefault(); highlight((active + 1) % n); }
    else if (e.key === 'ArrowUp' && n) { e.preventDefault(); highlight((active - 1 + n) % n); }
    else if (e.key === 'Escape') close();
    else if (e.key === 'Enter' && active >= 0) {
      e.preventDefault();
      const text = options()[active].textContent;
      input.value = text;
      submit(text);
    }
  });
  list.addEventListener('mousedown', (e) => e.preventDefault()); // keep focus in the field
  list.addEventListener('click', (e) => {
    const li = e.target.closest('li');
    if (!li) return;
    input.value = li.textContent;
    submit(li.textContent);
  });
  form.addEventListener('focusout', (e) => { if (!form.contains(e.relatedTarget)) close(); });
}
