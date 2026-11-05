package com.kynrek.spamreporter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/**
 * Optional community spam list (spamreporter.kynrek.com). Everything here is OFF by default.
 *
 * - Sharing (opt-in) uploads the sender number, date/time and, if allowed, the text of messages you marked as spam.
 *   Your own number, name and phone are only included if you turn each one on (each asks for confirmation).
 * - Downloading (opt-in) fetches numbers other people reported; they're shown as a "Community" tag on
 *   "Choose spam senders" as a hint only — nothing is ever marked or filed automatically.
 * - Requests are signed with the app's key (proves it's this app) and a per-install key made on this phone in the
 *   Android Keystore. The server only sees a hash of that key: it can tell two uploads came from the same install,
 *   but not who you are. "Reset community ID" makes a new key.
 * - Plain HTTPS to this one host only, system CAs only (network_security_config.xml). Nothing else is contacted.
 */
object Community {
    const val HOST = "spamreporter.kynrek.com"
    private const val BASE = "https://$HOST"
    private const val ALIAS = "spamreporter-community-v1"

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun available() = BuildConfig.COMMUNITY_KEY.isNotBlank()

    fun shareOn(c: Context) = p(c).getBoolean("community_share", false)
    fun downloadOn(c: Context) = p(c).getBoolean("community_download", false)
    fun shareBody(c: Context) = p(c).getBoolean("community_share_body", true)
    fun shareTo(c: Context) = p(c).getBoolean("community_share_to", false)
    fun shareName(c: Context) = p(c).getBoolean("community_share_name", false)
    fun sharePhone(c: Context) = p(c).getBoolean("community_share_phone", false)
    fun set(c: Context, key: String, on: Boolean) {
        p(c).edit().putBoolean(key, on).apply()
        if (key == "community_download" && !on) { list(c).delete(); p(c).edit().remove("community_since").apply() }
    }
    fun lastSync(c: Context) = p(c).getLong("community_last", 0)

    // ---- signing ----
    private fun keyPair(): Pair<PrivateKey, ByteArray> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        return (ks.getKey(ALIAS, null) as PrivateKey) to ks.getCertificate(ALIAS).publicKey.encoded
    }
    fun resetId() = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    /** Short, non-identifying install ID (same as the server's), for display. */
    fun installId() = runCatching { sha(keyPair().second).take(12) }.getOrDefault("—")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun call(method: String, path: String, body: ByteArray = ByteArray(0)): JSONObject {
        val (priv, pub) = keyPair()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        val canon = listOf(method, path.substringBefore('?'), ts, nonce, sha(body)).joinToString("\n").toByteArray()
        val appSig = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.decode(BuildConfig.COMMUNITY_KEY, Base64.DEFAULT), "HmacSHA256")) }.doFinal(canon)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(canon) }.sign()
        val con = URL(BASE + path).openConnection() as HttpsURLConnection
        try {
            con.requestMethod = method; con.connectTimeout = 15000; con.readTimeout = 30000; con.useCaches = false
            mapOf("X-Pubkey" to b64(pub), "X-Ts" to ts, "X-Nonce" to nonce, "X-App-Sig" to b64(appSig), "X-Sig" to b64(sig),
                "Content-Type" to "application/json", "User-Agent" to "SpamReporter/${BuildConfig.VERSION_NAME}").forEach(con::setRequestProperty)
            if (method == "POST") { con.doOutput = true; con.outputStream.use { it.write(body) } }
            val code = con.responseCode
            val text = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code >= 400) throw RuntimeException(o.optString("error", "server error $code"))
            return o
        } finally { con.disconnect() }
    }

    // ---- sharing ----
    private fun sentFile(c: Context) = File(c.filesDir, "community_sent.json")
    private fun sent(c: Context): MutableSet<String> = runCatching { JSONArray(sentFile(c).readText()).let { a -> (0 until a.length()).map(a::getString) } }
        .getOrDefault(emptyList()).toMutableSet()

    /** Messages that would be shared: from senders you marked spam (and still count as spam), not yet uploaded. */
    fun pending(c: Context): List<Pair<Msg, Category?>> {
        val s = Store(c); val notSpam = s.notSpam(); val done = sent(c)
        val spam = s.spamSenders().map(Phone::digits).toSet() - notSpam
        val marks = s.spamMarks(); val remember = s.rememberSpam(); val cats = s.categories().mapKeys { Phone.digits(it.key) }
        val all = s.messages(); val bySender = all.groupBy { Phone.digits(it.sender) }
        return all.filter { m -> val d = Phone.digits(m.sender)
            d in spam && m.key() !in done && cats[d] != Category.IGNORE && (remember || m.ts <= (marks[d] ?: Long.MAX_VALUE))
        }.groupBy { Phone.digits(it.sender) }.flatMap { (d, ms) ->   // same category the filing uses: yours, else the classifier's
            val cat = cats[d] ?: Classifier.classify(bySender[d].orEmpty().map { it.body })
            ms.map { it to cat } }
    }

    private fun upload(c: Context): Int {
        val prof = Store(c).profile
        val todo = pending(c); if (todo.isEmpty()) return 0
        val done = sent(c); var n = 0
        for (chunk in todo.chunked(200)) {
            val reports = JSONArray()
            for ((m, cat) in chunk) reports.put(JSONObject().put("from", Phone.digits(m.sender)).put("sent_at", m.ts)
                .put("category", cat?.name?.lowercase() ?: "unknown")
                .apply { if (shareBody(c)) put("body", m.body.take(2000)) }
                .apply { if (shareTo(c) && prof.phoneDigits().length == 10) put("to", prof.phoneDigits()) })
            val sub = JSONObject().apply {
                if (shareName(c) && prof.first.isNotBlank()) put("name", "${prof.first} ${prof.last}".trim())
                if (sharePhone(c) && prof.phoneDigits().length == 10) put("phone", prof.phoneDigits())
            }
            val body = JSONObject().put("reports", reports).apply { if (sub.length() > 0) put("submitter", sub) }.toString().toByteArray()
            call("POST", "/v1/reports", body)
            chunk.forEach { done += it.first.key() }; n += chunk.size
            sentFile(c).writeText(JSONArray(done.toList()).toString())
        }
        return n
    }

    // ---- downloading ----
    data class Info(val reports: Int, val installs: Int, val lastSeen: Long, val category: String?, val sample: String?)
    private fun list(c: Context) = File(c.filesDir, "community_numbers.json")

    fun numbers(c: Context): Map<String, Info> {
        if (!downloadOn(c)) return emptyMap()
        val o = runCatching { JSONObject(list(c).readText()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let {
            Info(it.optInt("reports"), it.optInt("installs"), it.optLong("last_seen"), it.optString("category").ifBlank { null },
                it.optString("sample").ifBlank { null }) } }
    }

    private fun download(c: Context): Int {
        val since = p(c).getLong("community_since", 0)
        val r = call("GET", "/v1/numbers?since=$since")
        val o = runCatching { JSONObject(list(c).readText()) }.getOrDefault(JSONObject())
        val a = r.getJSONArray("numbers")
        for (i in 0 until a.length()) a.getJSONObject(i).let { o.put(it.getString("number"), it) }
        r.optJSONArray("suppressed")?.let { s -> for (i in 0 until s.length()) o.remove(s.getString(i)) }
        list(c).writeText(o.toString())
        p(c).edit().putLong("community_since", r.optLong("now", System.currentTimeMillis()) - 60_000).apply()
        return a.length()
    }

    /** "Delete all my data": local community files and the install key. */
    fun wipe(c: Context) { sentFile(c).delete(); list(c).delete(); resetId()
        p(c).edit().remove("community_since").remove("community_last").apply() }

    /** Upload + download as enabled. Returns a short summary, or throws with the server's message. */
    fun syncNow(c: Context): String {
        if (!available()) return "Not available in this build"
        val parts = mutableListOf<String>()
        if (shareOn(c)) parts += "shared ${upload(c)}"
        if (downloadOn(c)) parts += "${download(c)} updated numbers"
        p(c).edit().putLong("community_last", System.currentTimeMillis()).apply()
        return parts.joinToString(" · ").ifBlank { "Community sharing is off" }
    }

    /** Background sync, at most every 30 minutes, only when the user turned sharing or downloading on. */
    fun syncInBackground(c: Context) {
        val app = c.applicationContext
        if (!available() || !(shareOn(app) || downloadOn(app)) || System.currentTimeMillis() - lastSync(app) < 30 * 60_000) return
        p(app).edit().putLong("community_last", System.currentTimeMillis()).apply()
        Thread { runCatching { syncNow(app) } }.start()
    }
}
