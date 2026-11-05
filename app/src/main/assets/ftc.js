// ReportFraud.ftc.gov: step 1 = assistant category (window.__STEP=='assistant'), step 2 = main form.
(async () => {
const P = window.__P, U = P.profile;
const sl = ms => new Promise(r => setTimeout(r, ms));
const until = async (fn, ms = 4000) => { const t0 = Date.now(); let v; while (!(v = fn())) { if (Date.now() - t0 > ms) return null; await sl(30) } return v };
const show = async (el, ms = 450) => { if (!el || !P.watch) return; el.scrollIntoView({ block: 'center', behavior: 'smooth' }); el.style.outline = '3px solid #f5b400'; el.style.outlineOffset = '2px'; await sl(ms); setTimeout(() => { el.style.outline = '' }, 1500) };  // watch mode: bring each field into view as it's filled
try {
  if (window.__STEP == 'assistant') {
    await until(() => document.body.innerText.includes('common problems'), 20000);
    const find = txt => { const els = [...document.querySelectorAll('label, [role=radio], .radio, li, div')]
      .filter(e => e.offsetParent !== null && e.innerText && e.innerText.trim().split('\n')[0].trim() === txt); return els[els.length - 1] };
    const ans = async txt => { const el = await until(() => find(txt), 4000); if (!el) throw 'NF ' + txt; await show(el); el.click(); await sl(150) };
    const cont = async () => { const b = await until(() => [...document.querySelectorAll('button')].find(b => b.offsetParent !== null && /^Continue/.test(b.innerText.trim()) && !b.disabled), 4000);
      if (!b) throw 'no continue'; b.click() };
    await ans(P.ftcCat);
    // a sub-question (e.g. "What type of health issue was it?") is a second assistant screen, shown after Continue
    if (P.ftcSub) { await cont(); await until(() => !document.body.innerText.includes('common problems'), 6000); await ans(P.ftcSub) }
    await cont(); return JSON.stringify({ ok: true, step: 'assistant' });
  }
  await until(() => document.getElementById('yes-or-no-money-no'), 20000);
  const setv = async (sel, v) => { const el = document.querySelector(sel); if (!el) throw 'missing ' + sel; await show(el);
    const pr = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : el.tagName == 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(pr, 'value').set.call(el, v); ['input', 'change', 'blur'].forEach(e => el.dispatchEvent(new Event(e, { bubbles: true }))); return el.value };
  const radio = async id => { const r = document.getElementById(id); if (!r) throw 'noradio ' + id; await show(r.closest('label,div') || r); r.click(); await sl(60) };
  await radio('yes-or-no-money-no'); await radio('yes-or-no-asked-money-no');
  const mc = document.getElementById('mobile-check-one'); if (mc && !mc.checked) { mc.click(); await sl(60) }
  const sel = document.querySelector('#rdcontact'); if (sel && !sel.value) { const o = [...sel.options].find(o => /text/i.test(o.text)); if (o) await setv('#rdcontact', o.value) }
  await setv('#rddatetwo', P.firstDate); if (P.company) await setv('#rcname', P.company);
  await radio('yes-or-no-contact-no');
  const ta = [...document.querySelectorAll('textarea')].find(t => t.offsetParent !== null); await show(ta);
  const fd = ta.maxLength > 0 && P.ftcDesc.length > ta.maxLength ? P.ftcDesc.slice(0, ta.maxLength) : P.ftcDesc;  // respect the field's limit
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(ta, fd); ['input', 'change', 'blur'].forEach(e => ta.dispatchEvent(new Event(e, { bubbles: true })));
  // "Are you filing this report for someone else?" -> No (Yes would add a second person's details section)
  await radio('yes-or-no-report-other-no'); await until(() => document.querySelector('#rayfirstName'), 3000);
  await setv('#rayfirstName', U.first); await setv('#raylastName', U.last);
  const ctry = document.querySelector('#raycountry');   // the State list only appears once the country is USA
  if (ctry) { await setv('#raycountry', [...ctry.options].find(o => o.text.trim() == 'USA').value); if (!await until(() => document.querySelector('#raystate'), 3000)) throw 'no state list' }
  await setv('#reportAboutYouAddress', U.address1);
  if (U.address2) await setv('#rayaddresstwo', U.address2); await setv('#raycity', U.city);
  const st = document.querySelector('#raystate'); await setv('#raystate', [...st.options].find(o => o.text.trim() == U.stateName).value);
  await setv('#USZipCode', U.zip); await setv('#rayphone', U.phone);
  const pt = document.querySelector('#rayphoneType'); const mo = [...pt.options].find(o => /cell|mobile/i.test(o.text)); if (mo) await setv('#rayphoneType', mo.value);
  await setv('#rayemail', U.email); await radio('yes-or-no-small-business-no'); await until(() => document.querySelector('#rdphone'), 3000);
  // the sender phone field is masked: focus it and let the app type the digits with real key events
  const ph = document.querySelector('#rdphone'); ph.scrollIntoView({ block: 'center' }); ph.focus();
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(ph, ''); ph.dispatchEvent(new Event('input', { bubbles: true }));
  return JSON.stringify({ ok: true, step: 'form', focus: document.activeElement.id });
} catch (e) { return JSON.stringify({ ok: false, error: String(e) }) }
})().then(r => AndroidBridge.result(r));
