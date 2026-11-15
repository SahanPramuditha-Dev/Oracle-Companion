package dev.oracle.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URL
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Connection(
    val host: String, val port: Int, val pin: String, val pc: String, val token: String = "",
    val remoteHost: String? = null,
) {
    companion object {
        private fun ipv4(host: String): List<Int>? {
            val parts = host.split('.').map { it.toIntOrNull() ?: return null }
            return parts.takeIf { it.size == 4 && it.all { part -> part in 0..255 } && it.joinToString(".") == host }
        }

        fun isRemoteAddress(host: String): Boolean {
            val parts = ipv4(host) ?: return false
            return parts[0] == 100 && parts[1] in 64..127
        }

        fun parse(value: JSONObject): Connection {
            require(value.getInt("v") == 1) { "Unsupported pairing code." }
            val host = value.getString("host")
            val parts = requireNotNull(ipv4(host)) { "Invalid PC address." }
            require(parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168) || host == "127.0.0.1" ||
                isRemoteAddress(host)) { "Use a local or Tailscale PC address." }
            val remote = value.optString("remoteHost").takeIf { it.isNotEmpty() }
            require(remote == null || isRemoteAddress(remote)) { "Invalid Tailscale address." }
            val port = value.getInt("port")
            val pin = value.getString("pin")
            require(port in 1..65535 && pin.matches(Regex("[a-f0-9]{64}"))) { "Invalid desktop identity." }
            return Connection(host, port, pin, value.getString("pc"), value.optString("token"), remote)
        }
    }

    fun json() = JSONObject().put("v", 1).put("host", host).put("port", port)
        .put("pin", pin).put("pc", pc).put("token", token).put("remoteHost", remoteHost)
}

class ApiFailure(val status: Int, message: String) : Exception(message)

class CompanionApi(private val target: Connection, context: Context? = null) {
    internal val actionReceipts = context?.let {
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("${target.pin}:${target.token}".toByteArray())
        ActionReceipts(it.getSharedPreferences("pc_action_receipts", Context.MODE_PRIVATE),
            Base64.encodeToString(identity, Base64.NO_WRAP))
    }
    private val socketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw CertificateException("Unexpected client certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing PC certificate")
                leaf.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                val expected = target.pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("PC identity changed")
            }
        }), null)
    }.socketFactory

    @Volatile private var activeStream: HttpsURLConnection? = null
    @Volatile private var activeHost = target.host
    private var nextLocalProbe = 0L
    private val recheckLocalRoute = AtomicBoolean()

    val isRemote: Boolean get() = Connection.isRemoteAddress(activeHost)

    fun stopStream() { activeStream?.disconnect() }

    fun invalidateRoute() { recheckLocalRoute.set(true) }

    fun call(path: String, data: JSONObject? = null, onDelta: ((String) -> Unit)? = null): JSONObject {
        val alternate = target.remoteHost?.takeUnless { it == target.host }
        if (alternate == null || path != "status") {
            val label = data?.let { pcReceiptLabel(path, it) }
            val receipts = actionReceipts
            if (receipts == null || label == null) return request(activeHost, path, data, onDelta)
            val receipt = receipts.begin(label)
            try {
                val reply = request(activeHost, path, data, onDelta)
                val result = reply.optJSONObject("result") ?: reply
                val status = when {
                    reply.opt("ok") == false || result.opt("ok") == false ||
                        reply.opt("success") == false || result.opt("success") == false ||
                        result.optString("status") in setOf("failed", "error") -> "Failed"
                    data?.optString("action") in setOf("power.restart", "power.shutdown", "power.sleep",
                        "power.hibernate", "restart", "shutdown", "sleep", "hibernate") -> "Accepted · outcome unverified"
                    result.optString("status") in setOf("pending", "queued", "running", "accepted") -> "Accepted · outcome unverified"
                    else -> "PC replied"
                }
                receipts.finish(receipt.id, status, receivedReply = true)
                return reply
            } catch (failure: Exception) {
                val rejected = failure is ApiFailure && failure.status in 400..499 && failure.status != 408
                receipts.finish(receipt.id, if (rejected) "Rejected" else "Unable to confirm",
                    receivedReply = failure is ApiFailure)
                throw failure
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val forceLocal = recheckLocalRoute.getAndSet(false)
        val first = if (!forceLocal && activeHost == alternate && now < nextLocalProbe) alternate else target.host
        val second = if (first == alternate) target.host else alternate
        val result = try {
            request(first, path, data, onDelta).also { activeHost = first }
        } catch (failure: IOException) {
            request(second, path, data, onDelta).also { activeHost = second }
        }
        if (first == target.host) nextLocalProbe = now + 30_000
        return result
    }

    fun pair(data: JSONObject): JSONObject {
        target.remoteHost?.takeUnless { it == target.host }?.let { remote ->
            activeHost = try {
                request(target.host, "status", null, null)
                target.host
            } catch (_: ApiFailure) {
                // The pinned gateway requires pairing to return status.
                target.host
            } catch (_: IOException) {
                remote
            }
        }
        return request(activeHost, "pair", data, null)
    }

    private fun request(host: String, path: String, data: JSONObject?, onDelta: ((String) -> Unit)?): JSONObject {
        val url = URL("https", host, target.port, "/companion/v1/$path")
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        if (onDelta != null) activeStream = connection
        try {
            connection.sslSocketFactory = socketFactory
            // The pinned certificate authenticates the PC independently of its current IP.
            connection.setHostnameVerifier { requestedHost, _ -> requestedHost == host }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = when {
                path in setOf("vision", "vision/product") -> 100_000
                path in setOf("vision/people", "vision/read") -> 35_000
                path == "vision/detect" -> 12_000
                path == "chat" && data != null -> 90_000
                else -> 7500
            }
            if (target.token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer ${target.token}")
            if (data != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (onDelta != null && status in 200..299 &&
                connection.contentType?.startsWith("application/x-ndjson") == true) {
                connection.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: throw java.io.EOFException("Reply interrupted")
                        val event = JSONObject(line)
                        event.optJSONObject("history")?.let { return it }
                        if (event.has("error")) throw ApiFailure(502, event.getString("error"))
                        event.optString("delta").takeIf { it.isNotEmpty() }?.let(onDelta)
                    }
                }
            }
            val response = stream?.bufferedReader()?.use { JSONObject(it.readText()) } ?: JSONObject()
            if (status !in 200..299) throw ApiFailure(status, response.optString("message", "Request failed"))
            return response
        } finally {
            if (activeStream === connection) activeStream = null
            connection.disconnect()
        }
    }
}

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("oracle_pairing", Context.MODE_PRIVATE)
    private val alias = "oracle-companion-pairing"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun write(connection: Connection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(connection.json().toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("identity", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()) {
            "Could not store pairing."
        }
    }

    fun read(): Connection? = runCatching {
        val stored = prefs.getString("identity", null) ?: return null
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        Connection.parse(JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)))
    }.getOrNull()

    fun lastConnectedAt(): Long = prefs.getLong("last_connection", 0L)

    fun recordConnection(time: Long) {
        prefs.edit().putLong("last_connection", time).apply()
    }

    fun clear() { prefs.edit().remove("identity").remove("last_connection").apply() }
}

