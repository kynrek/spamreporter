// Fills the FCC "Phone Complaint" (Unwanted Calls/Texts) form. Input: window.__P (job + profile).
// Returns JSON with field checks and the Submit button's rect; the app then taps it with a real touch
// (the FCC site ignores synthetic JS clicks).
(async () => {
const P = window.__P, U = P.profile;
const sl = ms => new Promise(r => setTimeout(r, ms));
for (let i = 0; i < 60 && !document.querySelector('[role=combobox]'); i++) await sl(250);
const lab = c => { const id = (c.getAttribute('aria-labelledby') || '').split(' ')[0]; return ((document.getElementById(id) || {}).innerText || c.getAttribute('aria-label') || '').trim() };
const combos = () => [...document.querySelectorAll('[role=combobox]')].filter(c => c.offsetParent !== null);
const has = l => combos().some(c => lab(c).startsWith(l));
const pick = async (l, t) => { const c = combos().find(c => lab(c).startsWith(l)); if (!c) throw 'nocombo ' + l; c.click(); await sl(450);
  const o = [...document.querySelectorAll('[role=option]')].find(o => { const x = o.innerText.trim(); return x == t || (t.endsWith('*') && x.startsWith(t.slice(0, -1))) });
  if (!o) throw 'noopt ' + l + '=' + t; o.click(); await sl(600) };
const inp = l => [...document.querySelectorAll('form input:not([type=hidden]),form textarea')].find(i => { if (i.offsetParent === null) return false;
  const f = i.closest('[data-garden-id="forms.field"],.form-field') || i.parentElement; return ((f.querySelector('label') || {}).innerText || '').startsWith(l) });
const set = (l, v) => { const el = inp(l); if (!el) throw 'noinput ' + l; const pr = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(pr, 'value').set.call(el, v); ['input', 'change', 'blur'].forEach(e => el.dispatchEvent(new Event(e, { bubbles: true }))) };
try {
  await pick('Phone Issues', 'Unwanted Calls/Texts (including do not call and spoofing)');
  await pick('Unwanted Calls/Texts Sub', 'All Other Unwanted Calls/Texts');
  await pick('Did the call/message that you are reporting advert', P.adv);
  if (P.adv == 'Yes') await pick('Type of Property', P.cat);
  if (P.cat == 'Other') {
    // choosing "Other" reveals a required free-text "Type of Property, Goods, or Services" field
    await sl(800);
    const el = [...document.querySelectorAll('form input:not([type=hidden]),form textarea')].find(i => i.offsetParent &&
      i.getAttribute('role') != 'combobox' &&
      (((i.closest('[data-garden-id="forms.field"],.form-field') || i.parentElement).querySelector('label') || {}).innerText || '').startsWith('Type of Property'));
    if (!el) throw 'noinput other';
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
  set('Your email address', U.email); set('Subject', P.subject);
  set('Telephone Number Where the Unwanted', U.phone);
  set('Date of Your Issue', P.date); if (P.time) set('Time of Your Issue', P.time);
  set('Caller ID Number', P.sender);
  if (inp('Provide the advertiser')) set('Provide the advertiser', P.sender);
  if (inp('Please provide the name of the advertiser')) set('Please provide the name of the advertiser', (P.org || '').slice(0, 100));
  set('Additional Information', P.add);
  set('Your First Name', U.first); set('Your Last Name', U.last); set('Address 1', U.address1);
  if (U.address2) set('Address 2', U.address2); set('City', U.city); set('Zip Code', U.zip); set('Phone (where', U.phone);
  const ed = document.querySelector('.ck-editor__editable');
  ed.ckeditorInstance.setData('<p>' + P.desc.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</p>'); await sl(300);
  const att = inp('Attest'); if (!att.checked) att.click(); await sl(300);
  const empty = combos().filter(c => (c.innerText || '').trim().startsWith('-')).map(c => lab(c).slice(0, 40));
  const b = document.querySelector('form button[type=submit]'); b.scrollIntoView({ block: 'center' }); await sl(400);
  const r = b.getBoundingClientRect();
  const ok = !!inp('Date of Your Issue').value && document.querySelector('[name="request[description]"]').value.length > 20 && att.checked && empty.length == 0;
  return JSON.stringify({ ok, empty, x: r.left + r.width / 2, y: r.top + r.height / 2, dpr: window.devicePixelRatio });
} catch (e) { return JSON.stringify({ ok: false, error: String(e) }) }
})().then(r => AndroidBridge.result(r));
