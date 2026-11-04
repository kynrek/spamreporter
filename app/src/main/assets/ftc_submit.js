(async () => {
  const want = window.__P.senderDigits;
  if (document.querySelector('#rdphone').value.replace(/\D/g, '') !== want) return JSON.stringify({ ok: false, error: 'phone mismatch' });
  const b = [...document.querySelectorAll('button')].find(b => b.offsetParent !== null && b.innerText.trim() == 'Submit');
  b.scrollIntoView({ block: 'center' }); if (window.__P.watch) { b.style.outline = '4px solid #d7263d'; await new Promise(r => setTimeout(r, 1200)) }
  b.click(); for (let i = 0; i < 300; i++) { await new Promise(r => setTimeout(r, 50)); const m = document.body.innerText.match(/Report Number\s*(\d+)/); if (m) return JSON.stringify({ ok: true, report: m[1] }) }
  return JSON.stringify({ ok: false, error: 'no report number', path: location.pathname });
})().then(r => AndroidBridge.result(r));
