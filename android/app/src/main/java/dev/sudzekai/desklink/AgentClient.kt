package dev.sudzekai.desklink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AgentClient(private val host: String, private val key: String) {
    suspend fun request(action: String, payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        require(key.length == 64) { "Введите ключ из Windows Agent" }
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        val body = payload.toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal("$nonce\n$action\n$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        val envelope = JSONObject().put("nonce", nonce).put("action", action).put("payload", payload).put("signature", signature)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 47652), 5000)
            socket.soTimeout = 30000
            socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(envelope.toString())
                writer.newLine()
                writer.flush()
                JSONObject(socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: error("Agent disconnected"))
            }
        }
    }
}
