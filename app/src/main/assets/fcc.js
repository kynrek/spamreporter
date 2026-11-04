// Fills the FCC "Phone Complaint" (Unwanted Calls/Texts) form. Input: window.__P (job + profile).
// Returns JSON with field checks and the Submit button's rect; the app then taps it with a real touch
// (the FCC site ignores synthetic JS clicks).
(async () => {
const P = window.__P, U = P.profile;
const sl = ms => new Promise(r => setTimeout(r, ms));
// wait for a condition instead of fixed pauses: polls every 30 ms
const until = async (fn, ms = 4000) => { const t0 = Date.now(); let v; while (!(v = fn())) { if (Date.now() - t0 > ms) return null; await sl(30) } return v };
const vis = e => e && e.offsetParent !== null;
await until(() => document.querySelector('[role=combobox]'), 20000);
const show = async (el, ms = 450) => { if (!el || !P.watch) return; el.scrollIntoView({ block: 'center', behavior: 'smooth' }); el.style.outline = '3px solid #f5b400'; el.style.outlineOffset = '2px'; await sl(ms); setTimeout(() => { el.style.outline = '' }, 1500) };  // watch mode: bring each field into view as it's filled
const lab = c => { const id = (c.getAttribute('aria-labelledby') || '').split(' ')[0]; return ((document.getElementById(id) || {}).innerText || c.getAttribute('aria-label') || '').trim() };
const combos = () => [...document.querySelectorAll('[role=combobox]')].filter(c => c.offsetParent !== null);
const has = l => combos().some(c => lab(c).startsWith(l));
const match = t => o => { const x = o.innerText.trim(); return x == t || (t.endsWith('*') && x.startsWith(t.slice(0, -1))) };
const pick = async (l, t) => {
  const c = await until(() => combos().find(c => lab(c).startsWith(l)), 3000); if (!c) throw 'nocombo ' + l;
  await show(c); c.click();
  const o = await until(() => [...document.querySelectorAll('[role=option]')].filter(vis).find(match(t)), 3000);
  if (!o) throw 'noopt ' + l + '=' + t; o.click();
  // done when the list has closed and the combobox shows the choice
  await until(() => ![...document.querySelectorAll('[role=option]')].some(vis) && (c.innerText || '').includes(t.replace(/\*$/, '').slice(0, 12)), 1500);
  await sl(40) };
const inp = l => [...document.querySelectorAll('form input:not([type=hidden]),form textarea')].find(i => { if (i.offsetParent === null) return false;
  const f = i.closest('[data-garden-id="forms.field"],.form-field') || i.parentElement; return ((f.querySelector('label') || {}).innerText || '').startsWith(l) });
const set = async (l, v) => { const el = inp(l); if (!el) throw 'noinput ' + l; await show(el); const pr = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  if (el.maxLength > 0 && v.length > el.maxLength) v = v.slice(0, el.maxLength - 1) + '…';  // respect the field's own limit
  Object.getOwnPropertyDescriptor(pr, 'value').set.call(el, v); ['input', 'change', 'blur'].forEach(e => el.dispatchEvent(new Event(e, { bubbles: true }))) };
try {
  await pick('Phone Issues', 'Unwanted Calls/Texts (including do not call and spoofing)');
  await pick('Unwanted Calls/Texts Sub', 'All Other Unwanted Calls/Texts');
  await pick('Did the call/message that you are reporting advert', P.adv);
  if (P.adv == 'Yes') await pick('Type of Property', P.cat);
  if (P.cat == 'Other') {
    // choosing "Other" reveals a required free-text "Type of Property, Goods, or Services" field
    const el = await until(() => [...document.querySelectorAll('form input:not([type=hidden]),form textarea')].find(i => i.offsetParent &&
      i.getAttribute('role') != 'combobox' &&
      (((i.closest('[data-garden-id="forms.field"],.form-field') || i.parentElement).querySelector('label') || {}).innerText || '').startsWith('Type of Property')), 3000);
    if (!el) throw 'noinput other'; await show(el);
    const pr = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(pr, 'value').set.call(el, P.catOther || 'Unsolicited marketing');
    ['input', 'change', 'blur'].forEach(e => el.dispatchEvent(new Event(e, { bubbles: true })));
  }
  await pick('Did the caller claim to be from', 'Uncertain');
  await pick('Your Phone Method', 'Wireless (cell phone/other mobile device)');
  await pick('Your Phone Type/Location', 'Residential/Personal');
  await pick('Please indicate if the telephone number subject', P.onDnc ? 'Yes' : 'Uncertain');
  await pick('Type of Call or Message', 'Text Message');
  for (const l of ['Have you or anyone else in your household done any', 'Have you or anyone else in your household made any',
                   'Do you or anyone in your household have a personal', 'Have you or anyone else in your household or business given'])
    if (has(l)) await pick(l, 'No');
  await pick('Did you receive Caller ID', 'Yes');
  await pick("Was the caller's business name", P.named);
  if (has('Was the business name provided at the')) await pick('Was the business name provided at the', P.start || 'No');
  await pick('State', U.stateName);
  await pick('Filing on Behalf', 'No');
  if (inp('Your email address')) await set('Your email address', U.email);   // absent when signed in to an FCC account
  await set('Subject', P.subject);
  await set('Telephone Number Where the Unwanted', U.phone);
  await set('Date of Your Issue', P.date); if (P.time) await set('Time of Your Issue', P.time);
  await set('Caller ID Number', P.sender);
  if (inp('Provide the advertiser')) await set('Provide the advertiser', P.sender);
  if (inp('Please provide the name of the advertiser')) await set('Please provide the name of the advertiser', (P.org || '').slice(0, 100));
  await set('Additional Information', P.add);
  await set('Your First Name', U.first); await set('Your Last Name', U.last); await set('Address 1', U.address1);
  if (U.address2) await set('Address 2', U.address2); await set('City', U.city); await set('Zip Code', U.zip); await set('Phone (where', U.phone);
  const ed = document.querySelector('.ck-editor__editable'); await show(ed);
  ed.ckeditorInstance.setData(P.desc.split('\n').map(l => '<p>' + (l.replace(/&/g, '&amp;').replace(/</g, '&lt;') || '&nbsp;') + '</p>').join(''));  // one paragraph per line
  const att = inp('Attest'); await show(att); if (!att.checked) att.click(); await until(() => att.checked, 1000);
  const empty = combos().filter(c => (c.innerText || '').trim().startsWith('-')).map(c => lab(c).slice(0, 40));
  const b = document.querySelector('form button[type=submit]'); b.scrollIntoView({ block: 'center' }); if (P.watch) { b.style.outline = '4px solid #d7263d'; await sl(1200) } else await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));  // layout settled after scroll
  const r = b.getBoundingClientRect();
  const ok = !!inp('Date of Your Issue').value && document.querySelector('[name="request[description]"]').value.length > 20 && att.checked && empty.length == 0;
  return JSON.stringify({ ok, empty, x: r.left + r.width / 2, y: r.top + r.height / 2, dpr: window.devicePixelRatio });
} catch (e) { return JSON.stringify({ ok: false, error: String(e) }) }
})().then(r => AndroidBridge.result(r));
