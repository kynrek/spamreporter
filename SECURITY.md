# Security & data handling

## What data the app handles
- **Your profile:** name, mailing address, email, phone, DNC registration date, and attestation time.
- **Messages from senders you marked as spam:** sender, time, and text.
- **Filing results:** agency, status, and confirmation number.

## Where it goes
| Destination | What is sent | Why |
|---|---|---|
| `consumercomplaints.fcc.gov` | Profile + complaint text for a sender | FCC complaint |
| `reportfraud.ftc.gov` | Profile + complaint text for a commercial sender | FTC report |
| `www2.donotcall.gov` | Your phone number + date of the text | donotcall.gov's official hand-off link into ReportFraud |
| `static.zdassets.com`, `cdn.jsdelivr.net`, `cdnjs.cloudflare.com` | **GET requests only** (scripts/styles the FCC form needs) | No form data |
| `challenges.cloudflare.com` | Cloudflare bot check in front of the FCC site | No form data |

Every other request is blocked by `NetPolicy` inside the WebView before it leaves the device:

- `googletagmanager.com`
- `dap.digitalgov.gov`
- Azure Monitor / Application Insights
- AudioEye
- Google Fonts

Those trackers are embedded in the government pages themselves, and blocking them stops third-party scripts from seeing what is typed into the forms.

## At rest
- **Encryption:** all app data is in app-private storage, encrypted with AES-256-GCM. The key is generated in the Android Keystore,
  can't be exported, is hardware-backed where available, and is destroyed on uninstall or "Delete all my data".
- **No backups:** `allowBackup=false`, and the data-extraction rules exclude everything from cloud backup and device transfer.
- **Exported reports:** these are written to `Downloads/SpamReporter` only when you tap Export, and are not encrypted because they're meant for you to keep.
  They contain confirmation numbers and phone numbers, not your address.

## Permissions
- **`READ_SMS`:** read the SMS/MMS inbox.
- **`INTERNET`:** reach the agency sites above.
- **`POST_NOTIFICATIONS`:** show status notifications.
- **Accessibility service (optional, user-enabled):** limited to `com.google.android.apps.messaging`. It only acts after you start an import.
- **Notification listener (optional, user-enabled):** reads Google Messages notifications and Gmail notifications containing an FCC ticket number.
  All other notifications are ignored.

## Reporting a vulnerability
Open a private security advisory on the GitHub repository.
