// settings.js - theme, battery saver, playback hint, voice options, desktop window, account, about.
// Everything here is a preference kept in localStorage (never names, searches or recordings).
import { h } from '../dom.js';
import { THEMES, getTheme, setTheme, saverOn, setSaver } from '../theme.js';
import { session } from '../events.js';
import { player } from '../player.js';
import { voiceSettings, unsupportedReason } from '../voice.js';

function checkbox(label, checked, onchange, hint) {
  return [
    h('label', { class: 'opt' },
      h('input', { type: 'checkbox', checked, onchange: (e) => onchange(e.target.checked) }),
      h('span', { text: label })),
    hint ? h('p', { class: 'hint', text: hint }) : null,
  ];
}

export function render({ main }) {
  const themes = h('fieldset', { class: 'set' },
    h('legend', { text: 'Theme' }),
    THEMES.map(([id, label]) => h('label', { class: 'opt' },
      h('input', { type: 'radio', name: 'theme', value: id, checked: getTheme() === id, onchange: () => setTheme(id) }),
      h('span', { text: label }))));

  const saver = h('fieldset', { class: 'set' },
    h('legend', { text: 'Battery saver' }),
    checkbox('Turn off blur and all transitions', saverOn(), setSaver,
      'The top bar and mini-player become solid, and nothing animates. Playing music costs about the same either way; this saves the drawing work.'));

  const playback = h('fieldset', { class: 'set' },
    h('legend', { text: 'Playback' }),
    checkbox('Prefer lower bitrate (AAC)', player.quality === 'low', (on) => player.setQuality(on ? 'low' : 'auto'),
      'Only a hint for now: it is sent with each stream request, but the desktop app does not act on it yet. Its own audio quality setting decides.'),
    h('p', { class: 'hint', text: 'Music plays in this tab with the browser\u2019s own decoder. The desktop equaliser, spatial sound and crossfade are not used here.' }));

  const why = unsupportedReason();
  const voice = h('fieldset', { class: 'set' },
    h('legend', { text: 'Voice' }),
    h('p', { class: 'hint', text: why || 'The microphone button is in the top bar and on Now playing. Click once, speak, and it stops by itself after a short silence (or click again). Nothing is stored.' }),
    checkbox('Hold to talk', voiceSettings.hold, (on) => { voiceSettings.hold = on; },
      'Press and hold the microphone button while you speak, release to send. The keyboard still works with a single press.'),
    checkbox('Short beep when listening starts', voiceSettings.cue, (on) => { voiceSettings.cue = on; },
      'The beep is played after the microphone is already on and is cut out of the recording.'));

  const desktop = h('section', { class: 'sec' },
    h('h2', { text: 'Desktop window' }),
    h('p', { text: 'To bring back the Muses desktop window, use the Muses icon in the system tray and choose \u201CShow the desktop window\u201D.' }),
    h('p', { class: 'hint', text: 'A button for this in the page needs a small addition to the desktop app and is not there yet.' }));

  const account = h('p', {
    text: session.signedIn === null ? 'Checking the account\u2026' : session.signedIn
      ? 'Signed in to YouTube Music (inside the Muses desktop app).'
      : 'Not signed in. Sign in inside the Muses desktop app.',
  });

  const about = h('details', { class: 'about' },
    h('summary', { text: 'About and licences' }),
    h('p', { text: 'Muses Web is the browser client of Muses, a modified version of BitChord. It is free software under the GNU General Public License v3 or later, and its source is available with the desktop app (see LICENSE and NOTICE.md).' }),
    h('p', { text: 'This page uses no framework, no CDN and no web fonts. The icon shapes follow Google\u2019s Material Icons (Apache License 2.0). Licences of the desktop app\u2019s libraries are listed in the desktop app under Settings, Licenses.' }),
    h('p', { class: 'muted', text: 'Muses Web is a personal server on your own computer. It is not a public website.' }));

  main.replaceChildren(
    h('h1', { text: 'Settings' }),
    themes,
    saver,
    playback,
    voice,
    desktop,
    h('section', { class: 'sec' }, h('h2', { text: 'Account' }), account),
    h('section', { class: 'sec' }, h('h2', { text: 'About this mode' }),
      h('p', { text: 'Music plays in this browser tab. The desktop window can stay closed.' }),
      session.version ? h('p', { class: 'muted mono', text: 'Muses ' + session.version }) : null),
    about);
}
