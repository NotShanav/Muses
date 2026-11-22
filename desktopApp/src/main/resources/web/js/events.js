// events.js - one Server-Sent Events connection to /api/events. It is open only while the tab is
// visible (the 20 s keep-alive would otherwise wake a hidden tab), and a failed connection backs off
// from 3 s to 60 s. Events arrive on `bus` as CustomEvents named after the message type.

export const bus = new EventTarget();

/** What the page knows about the account; filled by /api/ping and by 'signedIn' events. */
export const session = { signedIn: null, version: '' };

let source = null;
let wanted = false;
let wait = 3000;
let timer = 0;

function onMessage(e) {
  wait = 3000;
  let msg;
  try { msg = JSON.parse(e.data); } catch { return; }
  if (!msg || typeof msg.type !== 'string') return;
  if (msg.type === 'signedIn') {
    const changed = session.signedIn !== null && session.signedIn !== !!msg.signedIn;
    session.signedIn = !!msg.signedIn;
    bus.dispatchEvent(new CustomEvent(changed ? 'authchange' : 'authstate', { detail: msg }));
    return;
  }
  bus.dispatchEvent(new CustomEvent(msg.type, { detail: msg }));
}

function open() {
  if (source || !wanted || document.hidden) return;
  source = new EventSource('/api/events');
  source.onmessage = onMessage;
  source.onerror = () => {
    close();
    timer = setTimeout(open, wait);
    wait = Math.min(wait * 2, 60000);
  };
}

function close() {
  if (source) { source.close(); source = null; }
  clearTimeout(timer);
  timer = 0;
}

export function startEvents() {
  wanted = true;
  open();
}

document.addEventListener('visibilitychange', () => {
  if (document.hidden) close();
  else if (wanted) open();
});
