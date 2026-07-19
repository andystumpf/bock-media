/**
 * Spotify-style keyboard shortcuts.
 */
(function (root) {
  'use strict';

  function typing() {
    const el = document.activeElement;
    if (!el) return false;
    const tag = el.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
  }

  function init() {
    root.addEventListener('keydown', (e) => {
      if (typing()) return;
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key.toLowerCase() === 'k') return; // handled by ShellLayout
      if (e.code === 'Space') {
        e.preventDefault();
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) root.WebPlayback.toggle();
        else document.getElementById('np-mini-play')?.click();
      } else if (e.key === 'ArrowLeft' && !e.shiftKey && !mod) {
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) { e.preventDefault(); root.WebPlayback.seekRatio(Math.max(0, (root.WebPlayback.getState().positionSec - 5) / (root.WebPlayback.getState().durationSec || 1))); }
      } else if (e.key === 'ArrowRight' && !e.shiftKey && !mod) {
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) { e.preventDefault(); root.WebPlayback.seekRatio(Math.min(1, (root.WebPlayback.getState().positionSec + 5) / (root.WebPlayback.getState().durationSec || 1))); }
      } else if (e.key === 'ArrowUp') {
        const vol = document.getElementById('np-bar-volume');
        if (vol) { e.preventDefault(); vol.value = Math.min(100, parseInt(vol.value, 10) + 5); vol.dispatchEvent(new Event('input')); }
      } else if (e.key === 'ArrowDown') {
        const vol = document.getElementById('np-bar-volume');
        if (vol) { e.preventDefault(); vol.value = Math.max(0, parseInt(vol.value, 10) - 5); vol.dispatchEvent(new Event('input')); }
      } else if (e.shiftKey && e.key === 'ArrowLeft') {
        e.preventDefault();
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) root.WebPlayback.prev();
      } else if (e.shiftKey && e.key === 'ArrowRight') {
        e.preventDefault();
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) root.WebPlayback.next();
      } else if (e.key.toLowerCase() === 'l') {
        const st = typeof root.WebPlayback !== 'undefined' ? root.WebPlayback.getState() : null;
        if (st?.current?.path) root.toggleTrackLike?.(st.current.path, st.current.title, st.current.artist);
      } else if (e.key.toLowerCase() === 's') {
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) {
          const st = root.WebPlayback.getState();
          root.WebPlayback.setShuffle(!st.shuffle);
        }
      } else if (e.key.toLowerCase() === 'r') {
        if (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active) root.WebPlayback.cycleRepeat?.();
      } else if (e.key.toLowerCase() === 'f') {
        root.location.hash = 'nowplaying';
      }
    });
  }

  root.Shortcuts = { init };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
