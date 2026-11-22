// boot.js - classic (non-module) script, runs before first paint so the saved theme shows without a flash.
// It must stay tiny and self-contained; theme.js takes over afterwards.
(function () {
  var root = document.documentElement;
  var theme = null;
  var saver = null;
  try {
    theme = localStorage.getItem('muses.theme');
    saver = localStorage.getItem('muses.saver');
  } catch (e) { /* storage blocked */ }
  if (theme !== 'light' && theme !== 'dark' && theme !== 'amoled' && theme !== 'tint') {
    theme = window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  root.dataset.theme = theme;
  if (saver === '1') root.dataset.saver = 'on';
})();
