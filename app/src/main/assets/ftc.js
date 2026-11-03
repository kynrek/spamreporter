// ReportFraud.ftc.gov: step 1 = assistant category (window.__STEP=='assistant'), step 2 = main form.
(async () => {
const P = window.__P, U = P.profile;
const sl = ms => new Promise(r => setTimeout(r, ms));
try {
  if (window.__STEP == 'assistant') {
    for (let i = 0; i < 40 && !document.body.innerText.includes('common problems'); i++) await sl(250);
    const ans = async txt => { const els = [...document.querySelectorAll('label, [role=radio], .radio, li, div')]
      .filter(e => e.offsetParent !== null && e.innerText && e.innerText.trim().split('\n')[0].trim() === txt);
      const el = els[els.length - 1]; if (!el) throw 'NF ' + txt; el.click(); await sl(1000) };
    await ans(P.ftcCat); if (P.ftcSub) await ans(P.ftcSub);
    const b = [...document.querySelectorAll('button')].find(b => b.offsetParent !== null && /^Continue/.test(b.innerText.trim()) && !b.disabled);
    b.click(); return JSON.stringify({ ok: true, step: 'assistant' });
  }
  for (let i = 0; i < 40 && !document.getElementById('yes-or-no-money-no'); i++) await sl(250);
  const setv = (sel, v) => { const el = document.querySelector(sel); if (!el) throw 'missing ' + sel;
    const pr = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : el.tagName == 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(pr, 'value').set.call(el, v); ['input', 'change', 'blur'].forEach(e => el.dispatchEvent(new Event(e, { bubbles: true }))); return el.value };
  const radio = async id => { const r = document.getElementById(id); if (!r) throw 'noradio ' + id; r.click(); await sl(400) };
  await radio('yes-or-no-money-no'); await radio('yes-or-no-asked-money-no');
  const mc = document.getElementById('mobile-check-one'); if (mc && !mc.checked) { mc.click(); await sl(300) }
  const sel = document.querySelector('#rdcontact'); if (sel && !sel.value) { const o = [...sel.options].find(o => /text/i.test(o.text)); if (o) setv('#rdcontact', o.value) }
  setv('#rddatetwo', P.firstDate); if (P.company) setv('#rcname', P.company);
  await radio('yes-or-no-contact-no'); await sl(300);
  const ta = [...document.querySelectorAll('textarea')].find(t => t.offsetParent !== null);
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(ta, P.ftcDesc); ['input', 'change', 'blur'].forEach(e => ta.dispatchEvent(new Event(e, { bubbles: true })));
  await radio('yes-or-no-report-other-yes'); await sl(500);
  setv('#rayfirstName', U.first); setv('#raylastName', U.last); setv('#reportAboutYouAddress', U.address1);
  if (U.address2) setv('#rayaddresstwo', U.address2); setv('#raycity', U.city);
  const st = document.querySelector('#raystate'); setv('#raystate', [...st.options].find(o => o.text.trim() == U.stateName).value);
  setv('#USZipCode', U.zip); setv('#rayphone', U.phone);
  const pt = document.querySelector('#rayphoneType'); const mo = [...pt.options].find(o => /cell|mobile/i.test(o.text)); if (mo) setv('#rayphoneType', mo.value);
  setv('#rayemail', U.email); await radio('yes-or-no-small-business-no'); await sl(400);
  // the sender phone field is masked: focus it and let the app type the digits with real key events
  const ph = document.querySelector('#rdphone'); ph.scrollIntoView({ block: 'center' }); ph.focus();
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(ph, ''); ph.dispatchEvent(new Event('input', { bubbles: true }));
  return JSON.stringify({ ok: true, step: 'form', focus: document.activeElement.id });
} catch (e) { return JSON.stringify({ ok: false, error: String(e) }) }
})().then(r => AndroidBridge.result(r));
