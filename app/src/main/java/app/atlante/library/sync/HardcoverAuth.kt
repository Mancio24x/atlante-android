package app.atlante.library.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import org.json.JSONObject

/**
 * Connection to Hardcover. Two ways, the same for Sync and the saga engine:
 * - OAuth ("Collega con Hardcover"), what Hardcover asks of apps used by other people: the user approves Atlante
 *   on hardcover.app and comes back; no key to create or paste. Authorization code with PKCE (public client, no
 *   secret), redirect to app.atlante.library:/oauth/hardcover. Access tokens last a week and are renewed with the
 *   refresh token, which changes at every renewal.
 * - A personal key (hc_pat_…) pasted by the user, for whoever prefers it.
 * Tokens stay in the app's private preferences on this phone.
 */
object HardcoverAuth {
    /** Public id of the "Atlante Library" app registered on Hardcover (not a secret). Empty: OAuth not offered yet. */
    const val CLIENT_ID = ""
    const val REDIRECT = "app.atlante.library:/oauth/hardcover"
    const val SCOPES = "read:catalog read:me read:library write:library offline_access"
    private const val AUTHORIZE = "https://hardcover.app/oauth2/authorize"
    private const val TOKEN = "https://api.hardcover.app/oauth2/token"
    private const val REVOKE = "https://api.hardcover.app/oauth2/revoke"
    private const val ISSUER = "https://api.hardcover.app"

    private const val PREFS = "MainActivity"
    const val PAT = "hardcoverToken"
    private const val ACCESS = "hardcoverAccess"; private const val REFRESH = "hardcoverRefresh"; private const val EXPIRES = "hardcoverExpires"
    private const val STATE = "hardcoverOauthState"; private const val VERIFIER = "hardcoverOauthVerifier"

    @JvmStatic val oauthAvailable get() = CLIENT_ID.isNotEmpty()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Connected through OAuth, or with a personal key. */
    @JvmStatic fun connected(c: Context) = prefs(c).getString(REFRESH, "")!!.isNotEmpty() || prefs(c).getString(PAT, "")!!.isNotEmpty()
    @JvmStatic fun usesOauth(c: Context) = prefs(c).getString(REFRESH, "")!!.isNotEmpty()

    /** Step 1: opens Hardcover's consent page; the answer comes back to MainActivity through [REDIRECT]. */
    @JvmStatic fun start(c: Context) {
        val random = SecureRandom()
        fun token(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        val verifier = token(32); val state = token(16)
        prefs(c).edit().putString(VERIFIER, verifier).putString(STATE, state).apply()
        val url = AUTHORIZE + "?response_type=code&client_id=" + enc(CLIENT_ID) + "&redirect_uri=" + enc(REDIRECT) + "&scope=" + enc(SCOPES) +
            "&state=" + enc(state) + "&code_challenge=" + enc(challenge(verifier)) + "&code_challenge_method=S256"
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun challenge(verifier: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    @JvmStatic fun isCallback(uri: Uri?) = uri != null && uri.scheme == "app.atlante.library" && (uri.path ?: "").endsWith("/oauth/hardcover")

    /** Step 2 (off the main thread): checks state and issuer, trades the code for the tokens. */
    @JvmStatic fun finish(c: Context, uri: Uri) {
        val p = prefs(c)
        uri.getQueryParameter("error")?.let { throw IOException(if (it == "access_denied") "Collegamento annullato su Hardcover." else "Hardcover: " + (uri.getQueryParameter("error_description") ?: it)) }
        val state = p.getString(STATE, null)
        if (state == null || state != uri.getQueryParameter("state")) throw IOException("Risposta di Hardcover non riconosciuta: riprova il collegamento.")
        uri.getQueryParameter("iss")?.let { if (it != ISSUER) throw IOException("Risposta da un server diverso da Hardcover: collegamento annullato.") }
        val code = uri.getQueryParameter("code") ?: throw IOException("Hardcover non ha restituito il codice di accesso.")
        val verifier = p.getString(VERIFIER, "")!!
        p.edit().remove(STATE).remove(VERIFIER).apply()
        save(c, post(TOKEN, "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=${enc(CLIENT_ID)}&code_verifier=${enc(verifier)}"))
    }

    /** The token for the next request: the OAuth access token (renewed when it is about to expire) or the personal key. */
    @JvmStatic @Synchronized fun token(c: Context): String {
        val p = prefs(c)
        val refresh = p.getString(REFRESH, "")!!
        if (refresh.isEmpty()) return p.getString(PAT, "")!!.trim()
        if (System.currentTimeMillis() < p.getLong(EXPIRES, 0) - 5 * 60_000L) return p.getString(ACCESS, "")!!
        return renew(c)
    }

    /** After a 401 with OAuth: renews once; returns null when there is nothing to renew. */
    @JvmStatic @Synchronized fun renewAfterRejection(c: Context): String? = if (usesOauth(c)) renew(c) else null

    private fun renew(c: Context): String {
        val refresh = prefs(c).getString(REFRESH, "")!!
        val answer = try { post(TOKEN, "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(CLIENT_ID)}") }
        catch (e: IOException) {
            if (e.message?.contains("invalid_grant") == true) { forget(c); throw IOException("Il collegamento con Hardcover è scaduto: collegati di nuovo dalle impostazioni.") }
            throw e
        }
        save(c, answer)
        return prefs(c).getString(ACCESS, "")!!
    }

    private fun save(c: Context, json: JSONObject) {
        val access = json.optString("access_token"); val refresh = json.optString("refresh_token")
        if (access.isEmpty()) throw IOException("Hardcover non ha concesso l’accesso.")
        prefs(c).edit().putString(ACCESS, access).putString(REFRESH, refresh.ifEmpty { prefs(c).getString(REFRESH, "") })
            .putLong(EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 7 * 86_400L) * 1000).remove(PAT).apply()
    }

    /** Disconnects: the tokens are revoked on Hardcover (so the app leaves its list of authorized apps), then forgotten. */
    @JvmStatic fun disconnect(c: Context) {
        val refresh = prefs(c).getString(REFRESH, "")!!
        if (refresh.isNotEmpty()) runCatching { post(REVOKE, "token=${enc(refresh)}&client_id=${enc(CLIENT_ID)}") }
        forget(c)
    }

    private fun forget(c: Context) = prefs(c).edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(PAT)
        .remove("hardcoverUser").remove("hardcoverLast").apply()

    private fun post(url: String, form: String): JSONObject {
        val headers = HashMap<String, String>(); headers["Content-Type"] = "application/x-www-form-urlencoded"
        val r = SyncCore.http("POST", url, form, headers, null, null, 1024 * 1024)
        val body = runCatching { JSONObject(r.body ?: "") }.getOrDefault(JSONObject())
        if (r.status !in 200..299) throw IOException("Hardcover: " + body.optString("error_description", body.optString("error", "codice ${r.status}")) + " (" + body.optString("error") + ")")
        return body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
