// Re-focus the FTC sender phone field right before the app types into it (another lane may have had focus meanwhile).
AndroidBridge.result((() => { const ph = document.querySelector('#rdphone'); if (ph) { ph.scrollIntoView({ block: 'center' }); ph.focus() }
  return JSON.stringify({ ok: !!ph && document.activeElement === ph }) })());
