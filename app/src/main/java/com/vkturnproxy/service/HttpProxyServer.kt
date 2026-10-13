package com.vkturnproxy.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "HttpProxyServer"

object HttpProxyServer {
    private var serverJob: Job? = null
    private var httpServerSocket: ServerSocket? = null
    private var socksServerSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    var httpPort: Int = 8080
        private set

    var socksPort: Int = 1080
        private set

    fun start(listenHttpPort: Int = 8080, listenSocksPort: Int = 1080): Boolean {
        if (_isRunningFlow.value) return true
        httpPort = listenHttpPort
        socksPort = listenSocksPort

        val vpnService = VkTurnVpnService.instance

        return try {
            // 1. Bind HTTP Proxy socket (0.0.0.0:8080)
            val httpSock = ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
            httpServerSocket = httpSock

            // 2. Bind SOCKS5 Proxy socket (0.0.0.0:1080) if distinct
            val socksSock = if (socksPort != httpPort) {
                try {
                    val s = ServerSocket(socksPort, 50, InetAddress.getByName("0.0.0.0"))
                    socksServerSocket = s
                    s
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot bind SOCKS5 port $socksPort: ${e.message}")
                    null
                }
            } else null

            _isRunningFlow.value = true
            Log.i(TAG, "Hybrid LAN Proxy started: HTTP=0.0.0.0:$httpPort, SOCKS5=0.0.0.0:$socksPort")

            serverJob = scope.launch {
                // Launch listener for HTTP port
                launch { listenLoop(httpSock) }
                // Launch listener for SOCKS5 port if opened
                if (socksSock != null) {
                    launch { listenLoop(socksSock) }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LAN Proxy: ${e.message}", e)
            stop()
            false
        }
    }

    private suspend fun listenLoop(serverSocket: ServerSocket) = withContext(Dispatchers.IO) {
        val vpnService = VkTurnVpnService.instance
        while (isActive && !serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                // CRITICAL: Protect the local hotspot client socket so return packets go back over Wi-Fi, NOT tun0!
                vpnService?.protect(client)
                client.tcpNoDelay = true
                client.keepAlive = true
                client.soTimeout = 15000 // Handshake timeout; cleared to 0 once connection is piped
                clientSockets.add(client)

                scope.launch {
                    try {
                        handleClient(client)
                    } catch (_: Exception) {
                    } finally {
                        clientSockets.remove(client)
                        runCatching { client.close() }
                    }
                }
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Accept error on ${serverSocket.localPort}: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!_isRunningFlow.value) return
        _isRunningFlow.value = false
        Log.i(TAG, "Stopping LAN Proxy server")

        serverJob?.cancel()
        serverJob = null

        try {
            httpServerSocket?.close()
        } catch (_: Exception) {}
        httpServerSocket = null

        try {
            socksServerSocket?.close()
        } catch (_: Exception) {}
        socksServerSocket = null

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

        val firstByteInt = clientIn.read()
        if (firstByteInt == -1) return@withContext

        // Protocol sniffing:
        // Byte 0x05 -> SOCKS5 handshake (Telegram & SOCKS5 clients)
        // Otherwise -> HTTP / HTTPS CONNECT request
        if (firstByteInt == 0x05) {
            handleSocks5(client, clientIn, clientOut)
        } else {
            handleHttp(client, clientIn, clientOut, firstByteInt.toByte())
        }
    }

    /* ─── SOCKS5 Implementation (RFC 1928) ─── */
    private suspend fun handleSocks5(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream
    ) = withContext(Dispatchers.IO) {
        // We already consumed VER (0x05)
        // 1. Read NMETHODS
        val nMethods = clientIn.read()
        if (nMethods <= 0) return@withContext
        val methods = ByteArray(nMethods)
        readFully(clientIn, methods)

        // Handshake response: 0x05 (VER), 0x00 (NO AUTH REQUIRED)
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        // 2. Read Request: VER(1), CMD(1), RSV(1), ATYP(1)
        val ver = clientIn.read()
        val cmd = clientIn.read()
        val rsv = clientIn.read()
        val atyp = clientIn.read()

        if (ver != 0x05 || cmd != 0x01) {
            // Only CMD=0x01 (CONNECT) is supported
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            return@withContext
        }

        val targetHost: String
        when (atyp) {
            0x01 -> { // IPv4 (4 bytes)
                val ipBytes = ByteArray(4)
                readFully(clientIn, ipBytes)
                targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: return@withContext
            }
            0x03 -> { // Domain name (1 byte length + domain)
                val domainLen = clientIn.read()
                if (domainLen <= 0) return@withContext
                val domainBytes = ByteArray(domainLen)
                readFully(clientIn, domainBytes)
                targetHost = String(domainBytes, Charsets.US_ASCII)
            }
            0x04 -> { // IPv6 (16 bytes)
                val ipBytes = ByteArray(16)
                readFully(clientIn, ipBytes)
                targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: return@withContext
            }
            else -> {
                clientOut.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                return@withContext
            }
        }

        // Port (2 bytes big-endian)
        val p1 = clientIn.read()
        val p2 = clientIn.read()
        if (p1 == -1 || p2 == -1) return@withContext
        val targetPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

        // 3. Connect to destination (outgoing socket routes through active tun0 VPN)
        val remote = try {
            val targetIp = resolveIPv4(targetHost)
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(targetIp, targetPort), 10000)
            sock
        } catch (e: Exception) {
            // Reply 0x04 (Host unreachable)
            try {
                clientOut.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } catch (_: Exception) {}
            return@withContext
        }

        // 4. Reply SUCCESS: VER=0x05, REP=0x00, RSV=0x00, ATYP=0x01, BND.ADDR=0.0.0.0, BND.PORT=0
        try {
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
        } catch (e: Exception) {
            runCatching { remote.close() }
            return@withContext
        }

        // 5. Bidirectional data pipe (clear timeouts for active persistent streaming)
        client.soTimeout = 0
        remote.soTimeout = 0
        pipeBidirectional(client, remote)
    }

    /* ─── HTTP / HTTPS CONNECT Implementation ─── */
    private suspend fun handleHttp(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        firstByte: Byte
    ) = withContext(Dispatchers.IO) {
        val remainingFirstLine = readLine(clientIn) ?: return@withContext
        val firstLine = "${firstByte.toInt().toChar()}$remainingFirstLine"
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

            val remote = try {
                val targetIp = resolveIPv4(host)
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(InetSocketAddress(targetIp, remotePort), 10000)
                sock
            } catch (e: Exception) {
                try {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    clientOut.flush()
                } catch (_: Exception) {}
                return@withContext
            }

            // Reply HTTP 200 Connection Established
            try {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()
            } catch (e: Exception) {
                runCatching { remote.close() }
                return@withContext
            }

            client.soTimeout = 0
            remote.soTimeout = 0
            pipeBidirectional(client, remote)
        } else {
            // Plain HTTP Proxy
            var host = ""
            var remotePort = 80
            val headers = StringBuilder()
            val cleanFirstLine = rewriteHttpUri(firstLine)
            headers.append(cleanFirstLine).append("\r\n")

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
                val remote = try {
                    val targetIp = resolveIPv4(host)
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.connect(InetSocketAddress(targetIp, remotePort), 10000)
                    sock
                } catch (e: Exception) {
                    try {
                        clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        clientOut.flush()
                    } catch (_: Exception) {}
                    return@withContext
                }

                try {
                    remote.getOutputStream().write(headers.toString().toByteArray())
                    remote.getOutputStream().flush()
                } catch (e: Exception) {
                    runCatching { remote.close() }
                    return@withContext
                }

                client.soTimeout = 0
                remote.soTimeout = 0
                pipeBidirectional(client, remote)
            }
        }
    }

    private fun rewriteHttpUri(firstLine: String): String {
        val parts = firstLine.split(" ")
        if (parts.size < 3) return firstLine
        val uri = parts[1]
        if (uri.startsWith("http://", ignoreCase = true)) {
            val withoutScheme = uri.substring(7)
            val slashIdx = withoutScheme.indexOf('/')
            val path = if (slashIdx != -1) withoutScheme.substring(slashIdx) else "/"
            return "${parts[0]} $path ${parts[2]}"
        }
        return firstLine
    }

    private fun skipDnsName(dis: java.io.DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                dis.readUnsignedByte() // skip pointer second byte
                break // pointer terminates the name
            }
            dis.skipBytes(len)
        }
    }

    private fun resolveViaVpnDns(host: String, dnsServerIp: String = "10.77.77.1"): InetAddress? {
        var socket: java.net.DatagramSocket? = null
        try {
            socket = java.net.DatagramSocket()
            socket.soTimeout = 800 // Fast 800ms timeout
            val txId = (Math.random() * 65535).toInt()
            val baos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(baos)
            dos.writeShort(txId)
            dos.writeShort(0x0100) // standard query, recursion desired
            dos.writeShort(1)      // 1 question
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)

            for (part in host.split(".")) {
                if (part.isEmpty()) continue
                val bytes = part.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(1) // QTYPE = A
            dos.writeShort(1) // QCLASS = IN
            dos.flush()

            val queryData = baos.toByteArray()
            val packet = java.net.DatagramPacket(queryData, queryData.size, InetAddress.getByName(dnsServerIp), 53)
            socket.send(packet)

            val respBuf = ByteArray(512)
            val respPacket = java.net.DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)

            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(respBuf, 0, respPacket.length))
            val rTxId = dis.readUnsignedShort()
            if (rTxId != txId) return null
            dis.readUnsignedShort() // flags
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            dis.readUnsignedShort()
            dis.readUnsignedShort()

            for (i in 0 until qdCount) {
                skipDnsName(dis)
                dis.skipBytes(4)
            }

            for (i in 0 until anCount) {
                skipDnsName(dis)
                val aType = dis.readUnsignedShort()
                dis.skipBytes(2)
                dis.skipBytes(4)
                val rdLen = dis.readUnsignedShort()
                if (aType == 1 && rdLen == 4) {
                    val ipBytes = ByteArray(4)
                    dis.readFully(ipBytes)
                    return InetAddress.getByAddress(ipBytes)
                } else {
                    dis.skipBytes(rdLen)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
        return null
    }

    private fun resolveIPv4(host: String): InetAddress {
        if (host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
            return InetAddress.getByName(host)
        }
        return try {
            val addrs = InetAddress.getAllByName(host)
            addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        } catch (_: Exception) {
            val vpnDnsResult = resolveViaVpnDns(host, "10.77.77.1")
                ?: resolveViaVpnDns(host, "1.1.1.1")
            vpnDnsResult ?: InetAddress.getByName(host)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException("Unexpected EOF while reading ${buffer.size} bytes")
            offset += read
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
            if (sb.length > 8192) return sb.toString()
        }
    }

    /* ─── Non-Deadlocking Bidirectional Pipe ─── */
    private suspend fun pipeBidirectional(client: Socket, remote: Socket) = withContext(Dispatchers.IO) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        coroutineScope {
            val jobClientToRemote = launch {
                val buffer = ByteArray(32768)
                try {
                    while (isActive) {
                        val n = clientIn.read(buffer)
                        if (n == -1) break
                        remoteOut.write(buffer, 0, n)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { remote.shutdownOutput() }
                }
            }

            val jobRemoteToClient = launch {
                val buffer = ByteArray(32768)
                try {
                    while (isActive) {
                        val n = remoteIn.read(buffer)
                        if (n == -1) break
                        clientOut.write(buffer, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { client.shutdownOutput() }
                }
            }

            try {
                jobClientToRemote.join()
                jobRemoteToClient.join()
            } finally {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
        }
    }
}
