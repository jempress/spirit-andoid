package com.antony.spirit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class DiscoveredPc(val ip: String, val tcpPort: Int)

/**
 * Listens for "SPIRIT_HERE <port>" UDP broadcasts from the PC app. Exposes the most
 * recently heard PC via a StateFlow so the UI can show "found PC at X" and offer to
 * connect, or auto-connect once a PC is found and no manual override is set.
 */
class DiscoveryClient(private val scope: CoroutineScope) {

    companion object {
        const val BEACON_PORT = 6825
        const val BEACON_PREFIX = "SPIRIT_HERE"
    }

    private val _discovered = MutableStateFlow<DiscoveredPc?>(null)
    val discovered: StateFlow<DiscoveredPc?> = _discovered

    private var socket: DatagramSocket? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(BEACON_PORT))
                }
                socket = sock

                val buf = ByteArray(256)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet) // blocks until a beacon arrives
                    } catch (e: Exception) {
                        if (!isActive) break else continue
                    }

                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val parts = text.trim().split(" ")
                    if (parts.size == 2 && parts[0] == BEACON_PREFIX) {
                        val port = parts[1].toIntOrNull() ?: continue
                        val ip = packet.address.hostAddress ?: continue
                        _discovered.value = DiscoveredPc(ip, port)
                    }
                }
            } catch (e: Exception) {
                // Socket bind failure (e.g. port in use) — leave _discovered null,
                // UI falls back to manual entry.
            }
        }
    }

    fun stop() {
        socket?.close()
    }
}
