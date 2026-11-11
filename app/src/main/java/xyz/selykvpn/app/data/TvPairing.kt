package xyz.selykvpn.app.data

import xyz.selykvpn.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A pairing code of a TV: [url] (tgapp.selykvpn.xyz/tv/<code>) is shown as a QR code. */
@Serializable
data class TvSession(val code: String, val token: String, val url: String, val expiresIn: Int = 900)

@Serializable
private data class TvWaitRequest(val code: String, val token: String)

@Serializable
private data class TvWaitResponse(val status: String? = null, val key: String? = null)

@Serializable
private data class TvSendRequest(val code: String, val key: String)

/**
 * Adding a key to a TV from a phone through tgapp.selykvpn.xyz (server/src/index.mjs, "TV pairing"):
 * the TV gets a code and waits; the phone sends its key for that code (the app, or the web page
 * the QR code opens).
 */
class TvPairing(private val http: Http) {

    /** The code ran out or the server forgot it (restart): the TV asks for a new one. */
    class Expired : Exception()

    enum class SendResult { SENT, NO_CODE, FOREIGN_KEY, TOO_MANY, FAILED }

    suspend fun start(): TvSession {
        val (code, body) = post("/api/v1/tv/session", "{}")
        if (code != 200) throw IOException("HTTP $code")
        return AppJson.decodeFromString(TvSession.serializer(), body)
    }

    /** Waits up to ~10 s on the server: the key once a phone has sent it, null if not yet. */
    suspend fun awaitKey(session: TvSession): String? {
        val (code, body) = post("/api/v1/tv/wait", AppJson.encodeToString(TvWaitRequest.serializer(), TvWaitRequest(session.code, session.token)))
        if (code == 404) throw Expired()
        if (code != 200) throw IOException("HTTP $code")
        val answer = AppJson.decodeFromString(TvWaitResponse.serializer(), body)
        return answer.key?.takeIf { answer.status == "ready" && it.isNotBlank() }
    }

    suspend fun send(code: String, key: String): SendResult = try {
        val (status, _) = post("/api/v1/tv/send", AppJson.encodeToString(TvSendRequest.serializer(), TvSendRequest(code, key)))
        when (status) {
            200 -> SendResult.SENT
            404 -> SendResult.NO_CODE
            400 -> SendResult.FOREIGN_KEY
            429 -> SendResult.TOO_MANY
            else -> SendResult.FAILED
        }
    } catch (e: IOException) {
        SendResult.FAILED
    }

    private suspend fun post(path: String, json: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val body = json.toRequestBody("application/json".toMediaType())
        http.withClient { client ->
            val request = Request.Builder().url("${BuildConfig.API_BASE}$path").post(body).build()
            client.newCall(request).await().use { it.code to it.body.string() }
        }
    }

    companion object {
        private val LINK = Regex(
            "^(?:" + Regex.escape(BuildConfig.API_BASE) + "/tv/|selykvpn://tv/)([A-Za-z0-9]{3}-?[A-Za-z0-9]{3})/?$",
            RegexOption.IGNORE_CASE,
        )

        /** The TV's QR code "https://tgapp.selykvpn.xyz/tv/ABC123" (or "selykvpn://tv/ABC123") -> "ABC123". */
        fun codeFromLink(text: String?): String? =
            LINK.find(text.orEmpty().trim())?.groupValues?.get(1)?.replace("-", "")?.uppercase()

        /** "ABC123" -> "ABC 123": easier to read off the screen. */
        fun display(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
    }
}
