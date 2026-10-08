package com.vkturnproxy.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "HttpProxyServer"

object HttpProxyServer {
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    var port: Int = 8080
        private set

    fun start(listenPort: Int = 8080): Boolean {
        if (_isRunningFlow.value) return true
        port = listenPort

        return try {
            val sSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = sSocket
            _isRunningFlow.value = true
            Log.i(TAG, "LAN HTTP Proxy server started on 0.0.0.0:$port")

            serverJob = scope.launch {
                while (isActive && !sSocket.isClosed) {
                    try {
                        val client = sSocket.accept()
                        clientSockets.add(client)
                        launch {
                            try {
                                handleClient(client)
                            } catch (e: Exception) {
                                // Silent on client disconnect
                            } finally {
                                clientSockets.remove(client)
                                runCatching { client.close() }
                            }
                        }
                    } catch (e: SocketException) {
                        break // ServerSocket closed
                    } catch (e: Exception) {
                        Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LAN HTTP Proxy: ${e.message}", e)
            _isRunningFlow.value = false
            false
        }
    }

    fun stop() {
        if (!_isRunningFlow.value) return
        _isRunningFlow.value = false
        Log.i(TAG, "Stopping LAN HTTP Proxy server")

        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        clientSockets.forEach { sock ->
            try {
                sock.close()
            } catch (_: Exception) {}
        }
        clientSockets.clear()
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()

        // Read the first line of HTTP request
        val firstLine = readLine(clientIn) ?: return@withContext
        val parts = firstLine.split(" ")
        if (parts.size < 2) return@withContext

        val method = parts[0].uppercase()
        val target = parts[1]

        if (method == "CONNECT") {
            // HTTPS Tunnel
            val hostPort = target.split(":")
            val host = hostPort[0]
            val remotePort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

            // Consume remaining headers up to empty line
            while (true) {
                val line = readLine(clientIn) ?: break
                if (line.isEmpty()) break
            }

            try {
                // Outgoing socket automatically routes through active VpnService (tun0)
                val remote = Socket(host, remotePort)
                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()

                // Reply HTTP 200 Connection Established
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                // Bidirectional pipe
                val c2r = launch { pipe(clientIn, remoteOut) }
                val r2c = launch { pipe(remoteIn, clientOut) }
                c2r.join()
                r2c.join()
                remote.close()
            } catch (e: Exception) {
                try {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    clientOut.flush()
                } catch (_: Exception) {}
            }
        } else {
            // Plain HTTP Proxy
            var host = ""
            var remotePort = 80
            val headers = StringBuilder()
            headers.append(firstLine).append("\r\n")

            while (true) {
                val line = readLine(clientIn) ?: break
                headers.append(line).append("\r\n")
                if (line.isEmpty()) break
                if (line.startsWith("Host:", ignoreCase = true)) {
                    val h = line.substring(5).trim()
                    val hp = h.split(":")
                    host = hp[0]
                    remotePort = hp.getOrNull(1)?.toIntOrNull() ?: 80
                }
            }

            if (host.isNotEmpty()) {
                try {
                    val remote = Socket(host, remotePort)
                    remote.getOutputStream().write(headers.toString().toByteArray())
                    remote.getOutputStream().flush()

                    val c2r = launch { pipe(clientIn, remote.getOutputStream()) }
                    val r2c = launch { pipe(remote.getInputStream(), clientOut) }
                    c2r.join()
                    r2c.join()
                    remote.close()
                } catch (e: Exception) {
                    try {
                        clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        clientOut.flush()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isNotEmpty()) sb.toString() else null
            }
            if (b == '\n'.code) {
                return sb.toString().trimEnd('\r')
            }
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString() // Safety guard
        }
    }

    private suspend fun pipe(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16384)
        try {
            while (isActive) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {}
    }
}
