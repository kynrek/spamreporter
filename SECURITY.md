# Security & data handling

## What data the app handles
- **Your profile:** name, mailing address, email, phone, DNC registration date, and attestation time.
- **Messages from senders you marked as spam:** sender, time, and text.
- **Filing results:** agency, status, and confirmation number.

## Where it goes
| Destination | What is sent | Why |
|---|---|---|
| `consumercomplaints.fcc.gov` | Profile + complaint text for a sender | FCC complaint |
| `fcctest.zendesk.com` | FCC session hand-off only (the FCC complaint center's own Zendesk account, despite the name) | FCC sign-in / session check |
| `reportfraud.ftc.gov` | Profile + complaint text for a commercial sender | FTC report |
| `www2.donotcall.gov` | Your phone number + date of the text | donotcall.gov's official hand-off link into ReportFraud |
| `static.zdassets.com`, `cdn.jsdelivr.net`, `cdnjs.cloudflare.com` | **GET requests only** (scripts/styles the FCC form needs) | No form data |
| `challenges.cloudflare.com` | Cloudflare bot check in front of the FCC site | No form data |
| `spamreporter.kynrek.com` | **Only if you turn on Settings → Community (off by default).** Sharing: spam sender number, time, category, and (toggle, default on) the text. Your receiving number, name and contact phone are each a separate toggle, off by default, with a confirmation. Downloading: fetches reported numbers. | Optional community spam list |

Every other request is blocked by `NetPolicy` inside the WebView before it leaves the device:

- `googletagmanager.com`
- `dap.digitalgov.gov`
- Azure Monitor / Application Insights
- AudioEye
- Google Fonts

Those trackers are embedded in the government pages themselves, and blocking them stops third-party scripts from seeing what is typed into the forms.

## Community spam list (optional, off by default)
- **Off by default.** Nothing is contacted until you turn on "Share spam with the community" or "Use the community spam list".
- **Signed requests:** every request carries an HMAC with the app key (kept out of this repo, in `secrets.properties`) and an
  ECDSA P-256 signature from a per-install key made in the Android Keystore. The server stores only `sha256(public key)`,
  so it can tell whether two uploads came from the same install, but not who made them. "Reset app ID" makes a new key.
  The app key ships inside the APK, so it only keeps out casual abuse. The real protection is the server side:
  per-install and per-IP rate limits, replay protection (timestamp ± 5 min plus single-use nonces), input validation,
  and admin blocking of installs, IPs and sender numbers.
- **Server storage:** raw IPs are never stored. A salted hash is kept for abuse tracing, and access logging is off.
- **Downloaded numbers are only hints:** they show as a "Community" tag and are never marked or reported automatically.
- **Transport:** HTTPS only, system CAs only (`network_security_config.xml`).

## Call blocking (optional)
- Only works if you make Spam Reporter the phone's call screening ("Caller ID & spam") app. Android then asks it about each
  incoming call. It checks the number against your spam list on the phone and answers block or allow. Nothing is sent anywhere.
- Contacts, known numbers and anything marked not spam are never blocked. Each number can be un-blocked individually.
- Texts can't be blocked by a third-party app. "Block texts" copies the number and opens the phone's own Blocked numbers screen.

## At rest
- **Encryption:** all app data is in app-private storage, encrypted with AES-256-GCM. The key is generated in the Android Keystore,
  can't be exported, is hardware-backed where available, and is destroyed on uninstall or "Delete all my data".
- **No backups:** `allowBackup=false`, and the data-extraction rules exclude everything from cloud backup and device transfer.
- **Exported reports:** these are written to `Downloads/SpamReporter` only when you tap Export, and are not encrypted because they're meant for you to keep.
  They contain confirmation numbers and phone numbers, not your address.

## Optional FCC account sign-in
- **Your password:** you type it on the FCC's own sign-in page, shown in a WebView. That screen injects no JavaScript and exposes no app interface, and the app never reads form fields, so it cannot see your password.
- **The session:**
  - **Storage:** the FCC's session cookie lives only in the app's private WebView cookie store. That store is excluded from backups and device transfer.
  - **Use:** the app never reads, exports or transmits the cookie. Only the WebView sends it, and only to `consumercomplaints.fcc.gov`.
  - **Lifetime:** the FCC ends the session about 8 hours after sign-in. The app records only the sign-in time, so it can show when the session will end.
- **Sign out:** this loads the FCC's logout page to end the session on the FCC's side, then deletes the local cookies and site storage. "Delete all my data" also clears the session.
- **Risk:** while you're signed in, anyone with access to your unlocked phone and this app could file complaints or see your complaint history under your FCC account. Sign out when you're done if that matters to you.

## Permissions
- **`READ_SMS`:** read the SMS/MMS inbox.
- **`INTERNET`:** reach the agency sites above.
- **`POST_NOTIFICATIONS`:** show status notifications.
- **Accessibility service (optional, user-enabled):** limited to `com.google.android.apps.messaging`. It only acts after you start an import.
- **Notification listener (optional, user-enabled):** reads Google Messages notifications and Gmail notifications containing an FCC ticket number.
  All other notifications are ignored.

## Reporting a vulnerability
Open a private security advisory on the GitHub repository.
