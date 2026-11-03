package com.kynrek.spamreporter

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kynrek.spamreporter.Ui.add
import com.kynrek.spamreporter.Ui.card
import com.kynrek.spamreporter.Ui.dp
import com.kynrek.spamreporter.Ui.lp
import com.kynrek.spamreporter.Ui.screen
import com.kynrek.spamreporter.Ui.section
import com.kynrek.spamreporter.Ui.text

/** Collects the personal info both agencies require, and the FCC attestation (agreed explicitly by the user). */
class ProfileActivity : AppCompatActivity() {
    companion object {
        const val ATTEST = "I declare under penalty of perjury that (1) I am over 18 years old, (2) I am authorized to make decisions " +
            "regarding the telephone number listed below, and (3) the information I have provided today on this Federal Communications " +
            "Commission electronic form is, to the best of my knowledge, true and correct."
        const val ALSO = "I also confirm, for every sender this app files: I did not give the sender permission to contact me, did not make " +
            "an inquiry or application to them within the last 3 months, have no business or personal relationship with them, and I have " +
            "reviewed each sender's category and message text before filing. If any of this is not true for a sender, I will set that " +
            "sender to \"Don't file\" on the review screen."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val store = Store(this); val p = store.profile
        val CAPS = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        fun LinearLayout.field(hint: String, v: String, type: Int = CAPS, helper: String? = null): TextInputEditText {
            val et = TextInputEditText(context).apply { setText(v); inputType = type }
            addView(TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = hint; helper?.let { helperText = it }; addView(et) }, lp(bottom = dp(8)))
            return et
        }
        lateinit var first: TextInputEditText; lateinit var last: TextInputEditText; lateinit var a1: TextInputEditText
        lateinit var a2: TextInputEditText; lateinit var city: TextInputEditText; lateinit var state: TextInputEditText
        lateinit var zip: TextInputEditText; lateinit var email: TextInputEditText; lateinit var phone: TextInputEditText
        lateinit var dnc: TextInputEditText; lateinit var box: MaterialCheckBox

        screen("Your info", "Sent only to the FCC and FTC forms") {
            add(section("Name"))
            add(card { first = field("First name", p.first); last = field("Last name", p.last) })
            add(section("Mailing address"))
            add(card {
                a1 = field("Street address", p.address1); a2 = field("Apt / unit (optional)", p.address2)
                city = field("City", p.city)
                addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                    state = field("State", p.state, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
                    zip = field("ZIP", p.zip, InputType.TYPE_CLASS_NUMBER)
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; rightMargin = dp(8) }
                    (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1.4f }
                })
            })
            add(section("Contact"))
            add(card {
                email = field("Email", p.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "The FCC emails your ticket numbers here")
                phone = field("Phone that received the texts", p.phone, InputType.TYPE_CLASS_PHONE)
                dnc = field("Do Not Call registration date (optional)", p.dncRegisteredOn, InputType.TYPE_CLASS_DATETIME, "e.g. 2/29/2008 — from your donotcall.gov confirmation")
            })
            add(section("Attestation"))
            add(card {
                addView(text("FCC declaration", 15f, true))
                addView(text(ATTEST, 13f, alpha = 0.85f), lp(top = dp(6)))
                addView(text("Consent and relationship", 15f, true), lp(top = dp(12)))
                addView(text(ALSO, 13f, alpha = 0.85f), lp(top = dp(6)))
                box = MaterialCheckBox(context).apply { text = "I have read and agree. Apply this to the complaints this app files for me."; isChecked = p.attested }
                addView(box, lp(top = dp(8)))
                if (p.attested && p.attestedAt > 0) addView(text("Agreed " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.attestedAt)), 12f, alpha = 0.6f))
            })
            add(MaterialButton(context).apply {
                text = "Save"; setOnClickListener {
                    fun TextInputEditText.t() = text.toString().trim()
                    val np = Profile(first.t(), last.t(), a1.t(), a2.t(), city.t(), state.t(), zip.t(), email.t(), phone.t(), dnc.t(),
                        box.isChecked, if (box.isChecked) (if (p.attested) p.attestedAt else System.currentTimeMillis()) else 0)
                    if (!np.complete()) { Toast.makeText(context, "Fill in all required fields", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!Phone.isTenDigit(np.phone)) { Toast.makeText(context, "Enter a 10-digit US phone number", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    store.profile = np; finish()
                }
            }, dp(16))
        }
    }
}
