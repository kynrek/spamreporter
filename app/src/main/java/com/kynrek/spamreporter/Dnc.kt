package com.kynrek.spamreporter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kynrek.spamreporter.Ui.dp

/**
 * Setup step: the National Do Not Call Registry (run by the FTC). The app only opens the registry's own pages in the
 * phone's browser — it sends nothing there itself. Being on the list makes telemarketing texts Do Not Call violations,
 * which strengthens every report; the date (optional) is added to FCC complaints.
 *
 * The step is shown until the user says they're registered (or a registration date is in their profile); after opening
 * Register / Check, coming back to the app asks whether they finished. Settings → "Do Not Call Registry" reopens it.
 */
object Dnc {
    const val REGISTER = "https://www.donotcall.gov/register"
    const val VERIFY = "https://www.donotcall.gov/verify"

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Done = the user said they're signed up / verified, or entered the registration date. Hides the setup step. */
    fun done(c: Context) = if (p(c).contains("dnc_confirmed")) p(c).getBoolean("dnc_confirmed", false)
        else Store(c).profile.dncRegisteredOn.isNotBlank()   // never answered: a saved date counts
    private fun setDone(c: Context, on: Boolean) = p(c).edit().putBoolean("dnc_confirmed", on).apply()
    fun wipe(c: Context) = p(c).edit().remove("dnc_confirmed").remove("dnc_pending").apply()

    private fun open(a: Activity, url: String, what: String) {
        if (runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }.isSuccess)
            p(a).edit().putString("dnc_pending", what).apply()   // ask how it went when they come back
    }

    fun ask(a: Activity, changed: () -> Unit) {
        val prof = Store(a).profile
        val num = prof.phoneDashed().takeIf { prof.phoneDigits().length == 10 }
        MaterialAlertDialogBuilder(a)
            .setTitle("National Do Not Call Registry")
            .setMessage("Put ${num ?: "your number"} on the free Do Not Call list before reporting. Once it's on the list, " +
                "telemarketing texts break federal Do Not Call rules, which makes your reports stronger.\n\n" +
                "Sign up or Check opens the official donotcall.gov site in your browser (they email you a link to confirm, or the date " +
                "you registered). This app doesn't send anything there.")
            .setPositiveButton("Sign up") { _, _ -> open(a, REGISTER, "register") }
            .setNegativeButton("Check") { _, _ -> open(a, VERIFY, "verify") }
            .setNeutralButton("Already signed up") { _, _ -> confirmed(a, changed) }
            .show()
    }

    /** Called when the home screen comes back: if Sign up / Check was opened, ask whether it's done. */
    fun followUp(a: Activity, changed: () -> Unit) {
        val what = p(a).getString("dnc_pending", null) ?: return
        p(a).edit().remove("dnc_pending").apply()
        if (done(a)) return
        MaterialAlertDialogBuilder(a)
            .setTitle("Do Not Call Registry")
            .setMessage(if (what == "register") "Did you sign up your number? (Click the link in the email from donotcall.gov to finish — " +
                    "it can take up to 31 days to take full effect.)"
                else "Is your number on the Do Not Call list?")
            .setPositiveButton(if (what == "register") "Yes, I signed up" else "Yes, it's on the list") { _, _ -> confirmed(a, changed) }
            .setNegativeButton("Not yet", null)
            .show()
    }

    /** Signed up / verified: hide the step, and offer to note the registration date (optional) for FCC complaints. */
    private fun confirmed(a: Activity, changed: () -> Unit) {
        setDone(a, true); changed()
        val store = Store(a)
        val input = EditText(a).apply { hint = "e.g. 2/29/2008"; inputType = InputType.TYPE_CLASS_DATETIME; setText(store.profile.dncRegisteredOn) }
        MaterialAlertDialogBuilder(a)
            .setTitle("Registration date (optional)")
            .setMessage("If you know when your number was registered (it's in the email from donotcall.gov), it's added to your FCC " +
                "complaints. You can also add it later under Your info.")
            .setView(LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> store.profile = store.profile.copy(dncRegisteredOn = input.text.toString().trim()); changed() }
            .setNegativeButton("Skip", null)
            .show()
    }

    /** Settings: bring the step back. */
    fun reset(c: Context) = setDone(c, false)
}
