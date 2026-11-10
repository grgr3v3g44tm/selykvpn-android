package xyz.selykvpn.app.data

import android.os.Build
import xyz.selykvpn.app.BuildConfig
import xyz.selykvpn.app.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

class Http {
    val userAgent = "SelykVPN/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.RELEASE}"

    val direct: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /**
     * The app itself is excluded from the VPN, so while connected its requests are sent through
     * the local authenticated HTTP inbound of the running Xray core instead.
     */
    fun tunnel(): OkHttpClient? {
        val proxy = VpnController.appProxy.value ?: return null
        return direct.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(proxy.user, proxy.password))
                    .build()
            }
            .build()
    }

    /** Runs [block] through the tunnel when connected and falls back to a direct connection. */
    suspend fun <T> withClient(block: suspend (OkHttpClient) -> T): T {
        tunnel()?.let { client ->
            try {
                return block(client)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to the direct attempt
            }
        }
        return block(direct)
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
