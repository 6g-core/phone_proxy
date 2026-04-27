package com.example.phoneproxy.service

import android.util.Log
import com.example.phoneproxy.model.ProxyRule
import com.example.phoneproxy.model.udpMappings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ProxyRuntimeStatus(
    val isRunning: Boolean = false,
    val enabledRuleCount: Int = 0,
    val message: String = "Stopped",
)

object PhoneProxyRuntime {
    private val _status = MutableStateFlow(ProxyRuntimeStatus())
    val status: StateFlow<ProxyRuntimeStatus> = _status

    internal fun update(status: ProxyRuntimeStatus) {
        _status.value = status
    }
}

class PhoneProxyEngine(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val runners = mutableListOf<RuleRuntime>()

    suspend fun start(rules: List<ProxyRule>) {
        stop()

        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty()) {
            PhoneProxyRuntime.update(
                ProxyRuntimeStatus(
                    isRunning = false,
                    enabledRuleCount = 0,
                    message = "No enabled rules.",
                )
            )
            return
        }

        try {
            enabledRules.forEach { rule ->
                val runtime = RuleRuntime(rule, scope)
                runtime.start()
                runners.add(runtime)
            }
            PhoneProxyRuntime.update(
                ProxyRuntimeStatus(
                    isRunning = true,
                    enabledRuleCount = enabledRules.size,
                    message = "Running ${enabledRules.size} rule(s).",
                )
            )
        } catch (exc: Exception) {
            stop()
            throw exc
        }
    }

    suspend fun stop() {
        runners.reversed().forEach { it.stop() }
        runners.clear()
        PhoneProxyRuntime.update(
            ProxyRuntimeStatus(
                isRunning = false,
                enabledRuleCount = 0,
                message = "Stopped",
            )
        )
    }
}

private class RuleRuntime(
    private val rule: ProxyRule,
    private val scope: CoroutineScope,
) {
    private val tcpRunner = TcpRuleRunner(rule, scope)
    private val udpRunners = rule.udpMappings().map { mapping ->
        UdpRuleRunner(
            ruleName = rule.name,
            listenIp = rule.listenIp,
            listenPort = mapping.listenPort,
            targetIp = rule.targetIp,
            targetPort = mapping.targetPort,
            scope = scope,
        )
    }

    suspend fun start() {
        tcpRunner.start()
        val startedUdpRunners = mutableListOf<UdpRuleRunner>()
        try {
            udpRunners.forEach {
                it.start()
                startedUdpRunners += it
            }
        } catch (exc: Exception) {
            startedUdpRunners.asReversed().forEach { it.stop() }
            tcpRunner.stop()
            throw exc
        }
    }

    suspend fun stop() {
        udpRunners.asReversed().forEach { it.stop() }
        tcpRunner.stop()
    }
}

private class TcpRuleRunner(
    private val rule: ProxyRule,
    private val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connectionJobs = ConcurrentHashMap.newKeySet<Job>()
    private val connectionIds = AtomicInteger(1)

    suspend fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(resolveListenAddress(rule.listenIp), rule.listenPort))
        serverSocket = socket
        acceptJob = scope.launch {
            Log.i(TAG, "[${rule.name}] tcp listening on ${rule.listenIp}:${rule.listenPort}")
            while (true) {
                val client = try {
                    runInterruptible { socket.accept() }
                } catch (exc: Exception) {
                    if (exc is CancellationException || exc is SocketException) {
                        break
                    }
                    Log.w(TAG, "[${rule.name}] tcp accept failed: ${exc.message}", exc)
                    break
                }

                val job = scope.launch {
                    handleClient(client, connectionIds.getAndIncrement())
                }
                connectionJobs += job
                job.invokeOnCompletion { connectionJobs.remove(job) }
            }
        }
    }

    suspend fun stop() {
        acceptJob?.cancel()
        serverSocket?.close()
        acceptJob?.join()
        acceptJob = null
        serverSocket = null

        connectionJobs.toList().forEach { job ->
            job.cancel()
        }
        connectionJobs.toList().forEach { job ->
            try {
                job.join()
            } catch (_: CancellationException) {
            }
        }
        connectionJobs.clear()
    }

    private suspend fun handleClient(client: Socket, connectionId: Int) = supervisorScope {
        val clientPeer = client.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "[${rule.name}] tcp conn=$connectionId accepted $clientPeer")

        val upstream = Socket()
        try {
            runInterruptible {
                upstream.connect(InetSocketAddress(rule.targetIp, rule.targetPort), TCP_CONNECT_TIMEOUT_MS)
            }
            upstream.tcpNoDelay = true
            client.tcpNoDelay = true
            Log.i(
                TAG,
                "[${rule.name}] tcp conn=$connectionId connected to ${rule.targetIp}:${rule.targetPort}"
            )

            val uplink = launch {
                pumpSocket(
                    input = client.getInputStream(),
                    output = upstream.getOutputStream(),
                    direction = "uplink",
                    ruleName = rule.name,
                    connectionId = connectionId,
                    onCompleted = { shutdownOutputQuietly(upstream) },
                )
            }
            val downlink = launch {
                pumpSocket(
                    input = upstream.getInputStream(),
                    output = client.getOutputStream(),
                    direction = "downlink",
                    ruleName = rule.name,
                    connectionId = connectionId,
                    onCompleted = { shutdownOutputQuietly(client) },
                )
            }

            uplink.join()
            downlink.join()
        } catch (exc: Exception) {
            Log.w(TAG, "[${rule.name}] tcp conn=$connectionId failed: ${exc.message}", exc)
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
            Log.i(TAG, "[${rule.name}] tcp conn=$connectionId closed")
        }
    }

    companion object {
        private const val TAG = "PhoneProxyTcp"
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
    }
}

private class UdpRuleRunner(
    private val ruleName: String,
    private val listenIp: String,
    private val listenPort: Int,
    private val targetIp: String,
    private val targetPort: Int,
    private val scope: CoroutineScope,
) {
    private var listenerSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var reaperJob: Job? = null
    private val sessions = ConcurrentHashMap<String, UdpSession>()
    private val sessionIds = AtomicInteger(1)
    private val sendLock = Any()

    suspend fun start() {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(resolveListenAddress(listenIp), listenPort))
        listenerSocket = socket

        listenerJob = scope.launch {
            Log.i(TAG, "[$ruleName] udp listening on $listenIp:$listenPort")
            val buffer = ByteArray(MAX_DATAGRAM_SIZE)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    runInterruptible { socket.receive(packet) }
                } catch (exc: Exception) {
                    if (exc is CancellationException || exc is SocketException) {
                        break
                    }
                    Log.w(TAG, "[$ruleName] udp receive failed: ${exc.message}", exc)
                    break
                }

                val payload = packet.data.copyOf(packet.length)
                val clientAddress = InetSocketAddress(packet.address, packet.port)
                val session = sessions.computeIfAbsent(clientKey(clientAddress)) {
                    createSession(clientAddress)
                }
                session.touch()
                try {
                    runInterruptible {
                        session.upstreamSocket.send(DatagramPacket(payload, payload.size))
                    }
                } catch (exc: Exception) {
                    Log.w(
                        TAG,
                        "[$ruleName] udp session=${session.id} send failed: ${exc.message}",
                        exc
                    )
                    closeSession(clientKey(clientAddress), "send_failed")
                }
            }
        }

        reaperJob = scope.launch {
            while (true) {
                delay(UDP_IDLE_TIMEOUT_MS / 2)
                val now = System.currentTimeMillis()
                sessions.entries.forEach { entry ->
                    if (now - entry.value.lastActivityMs.get() > UDP_IDLE_TIMEOUT_MS) {
                        closeSession(entry.key, "idle_timeout")
                    }
                }
            }
        }
    }

    suspend fun stop() {
        reaperJob?.cancel()
        reaperJob?.join()
        reaperJob = null

        listenerJob?.cancel()
        listenerSocket?.close()
        listenerJob?.join()
        listenerJob = null
        listenerSocket = null

        sessions.keys.toList().forEach { closeSession(it, "shutdown") }
    }

    private fun createSession(clientAddress: InetSocketAddress): UdpSession {
        val upstreamSocket = DatagramSocket()
        upstreamSocket.connect(InetSocketAddress(targetIp, targetPort))
        val session = UdpSession(
            id = sessionIds.getAndIncrement(),
            clientAddress = clientAddress,
            upstreamSocket = upstreamSocket,
        )
        session.receiveJob = scope.launch {
            val buffer = ByteArray(MAX_DATAGRAM_SIZE)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    runInterruptible { upstreamSocket.receive(packet) }
                } catch (exc: Exception) {
                    if (exc is CancellationException || exc is SocketException || session.closed.get()) {
                        break
                    }
                    Log.w(
                        TAG,
                        "[$ruleName] udp session=${session.id} upstream receive failed: ${exc.message}",
                        exc
                    )
                    break
                }

                val payload = packet.data.copyOf(packet.length)
                session.touch()
                val listener = listenerSocket ?: break
                try {
                    synchronized(sendLock) {
                        listener.send(
                            DatagramPacket(
                                payload,
                                payload.size,
                                session.clientAddress.address,
                                session.clientAddress.port,
                            )
                        )
                    }
                } catch (exc: Exception) {
                    Log.w(
                        TAG,
                        "[$ruleName] udp session=${session.id} downstream send failed: ${exc.message}",
                        exc
                    )
                    break
                }
            }
            closeSession(clientKey(clientAddress), "upstream_closed")
        }

        Log.i(
            TAG,
            "[$ruleName] udp session=${session.id} " +
                "${clientAddress.address.hostAddress}:${clientAddress.port} -> $targetIp:$targetPort"
        )
        return session
    }

    private fun closeSession(key: String, reason: String) {
        val session = sessions.remove(key) ?: return
        if (!session.closed.compareAndSet(false, true)) {
            return
        }
        session.upstreamSocket.close()
        session.receiveJob?.cancel()
        Log.i(TAG, "[$ruleName] udp session=${session.id} closed reason=$reason")
    }

    companion object {
        private const val TAG = "PhoneProxyUdp"
        private const val MAX_DATAGRAM_SIZE = 65_507
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L
    }
}

private data class UdpSession(
    val id: Int,
    val clientAddress: InetSocketAddress,
    val upstreamSocket: DatagramSocket,
    val closed: AtomicBoolean = AtomicBoolean(false),
    val lastActivityMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    var receiveJob: Job? = null,
) {
    fun touch() {
        lastActivityMs.set(System.currentTimeMillis())
    }
}

private suspend fun copySocket(
    input: InputStream,
    output: OutputStream,
    direction: String,
    ruleName: String,
    connectionId: Int,
): Long {
    return runInterruptible {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            output.flush()
            total += read
        }
        Log.i("PhoneProxyTcp", "[$ruleName] tcp conn=$connectionId $direction bytes=$total")
        total
    }
}

private suspend fun pumpSocket(
    input: InputStream,
    output: OutputStream,
    direction: String,
    ruleName: String,
    connectionId: Int,
    onCompleted: () -> Unit,
): Long {
    return try {
        copySocket(
            input = input,
            output = output,
            direction = direction,
            ruleName = ruleName,
            connectionId = connectionId,
        )
    } catch (exc: Exception) {
        if (exc is CancellationException) {
            throw exc
        }
        Log.i(
            "PhoneProxyTcp",
            "[$ruleName] tcp conn=$connectionId $direction closed with ${exc.javaClass.simpleName}: ${exc.message}"
        )
        0L
    } finally {
        onCompleted()
    }
}

private fun resolveListenAddress(ip: String): InetAddress {
    return if (ip == "0.0.0.0") {
        InetAddress.getByName("0.0.0.0")
    } else {
        InetAddress.getByName(ip)
    }
}

private fun closeQuietly(socket: Socket) {
    try {
        socket.close()
    } catch (_: Exception) {
    }
}

private fun shutdownOutputQuietly(socket: Socket) {
    try {
        socket.shutdownOutput()
    } catch (_: Exception) {
    }
}

private fun clientKey(address: InetSocketAddress): String {
    return "${address.address.hostAddress}:${address.port}"
}
