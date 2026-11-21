package dev.sudzekai.desklink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.Socket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

class AgentClient(private val context: Context, private val host: String, private val key: String, private val deviceId: String, private val bluetoothAddress: String) {
    private val serviceId = java.util.UUID.fromString("6b777071-7476-47df-8dda-6e0a526df8bf")

    companion object {
        private var discoveredHost: String? = null
    }

    suspend fun request(action: String, payload: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        require(key.length == 64) { "Введите ключ из Windows Agent" }
        val nonceBytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02X".format(it) }
        val keyBytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonceBytes))
        cipher.updateAAD(action.toByteArray(Charsets.UTF_8))
        val ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8)))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val signature = mac.doFinal("$deviceId\n$nonce\n$action\n$ciphertext".toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        val envelope = JSONObject().put("deviceId", deviceId).put("nonce", nonce).put("action", action).put("ciphertext", ciphertext).put("signature", signature)
        openTransport().use { transport ->
            transport.output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(envelope.toString())
                writer.newLine()
                writer.flush()
                val response = JSONObject(readLineLimited(transport.input))
                val responseNonce = response.getString("nonce").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
                decryptor.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, responseNonce))
                decryptor.updateAAD(nonce.toByteArray(Charsets.UTF_8))
                JSONObject(String(decryptor.doFinal(Base64.getDecoder().decode(response.getString("ciphertext"))), Charsets.UTF_8))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openTransport(): Transport {
        var lastError: Exception? = null
        for (candidate in listOf("127.0.0.1", host, discoveredHost).filterNotNull().distinct()) {
            try { return connectTcp(candidate) }
            catch (exception: Exception) { lastError = exception }
        }

        val found = discoverHost()
        if (found != null) {
            discoveredHost = found
            try { return connectTcp(found) }
            catch (exception: Exception) { lastError = exception }
        }

        if (bluetoothAddress.isBlank()) throw lastError ?: IllegalStateException("Agent не найден")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("Bluetooth недоступен")
        val socket = adapter.getRemoteDevice(bluetoothAddress).createRfcommSocketToServiceRecord(serviceId)
        socket.connect()
        return Transport(socket.inputStream, socket.outputStream, socket)
    }

    private fun connectTcp(address: String): Transport {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, 47652), 2000)
            socket.soTimeout = 30000
            return Transport(socket.getInputStream(), socket.getOutputStream(), socket)
        } catch (exception: Exception) {
            socket.close()
            throw exception
        }
    }

    private fun readLineLimited(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (bytes.size() <= 8 * 1024 * 1024) {
            val length = input.read(chunk)
            if (length < 0) throw IOException("Agent disconnected")
            var newline = -1
            for (index in 0 until length) {
                if (chunk[index] == 10.toByte()) { newline = index; break }
            }
            val count = if (newline < 0) length else newline
            if (bytes.size() + count > 8 * 1024 * 1024) throw IOException("Response too large")
            bytes.write(chunk, 0, count)
            if (newline >= 0) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
        }
        throw IOException("Response too large")
    }

    private fun discoverHost(): String? = try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1000
            val probe = "DESKLINK_DISCOVER".toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), 47653))
            val response = ByteArray(64)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            if (String(response, 0, packet.length, Charsets.US_ASCII) == "DESKLINK_AGENT") packet.address.hostAddress else null
        }
    } catch (_: Exception) { null }

    private class Transport(val input: InputStream, val output: OutputStream, private val resource: Closeable) : Closeable {
        override fun close() = resource.close()
    }
}
