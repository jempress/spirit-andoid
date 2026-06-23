package com.antony.spirit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/**
 * Owns the TCP socket to the PC. Sending is funneled through a Channel consumed by
 * a single coroutine so gesture callbacks (which may fire rapidly off the UI thread
 * via pointerInput) never touch the socket directly — avoids any need for manual
 * locking around the OutputStream.
 */
class SpiritConnection(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    // Capacity large enough to absorb a burst of fast touch-move events without
    // blocking the gesture-detection coroutine; UNLIMITED is fine since frames are tiny.
    private val outbound = Channel<String>(capacity = Channel.UNLIMITED)

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    fun connect(ip: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            _state.value = ConnectionState.CONNECTING
            try {
                val sock = Socket().apply {
                    tcpNoDelay = true // matches PC side — these are tiny, latency-sensitive frames
                    connect(InetSocketAddress(ip, port), 3000)
                }
                socket = sock
                writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII))
                _state.value = ConnectionState.CONNECTED

                // Drain the outbound channel for the lifetime of this connection.
                for (frame in outbound) {
                    if (!isActive) break
                    try {
                        writer?.write(frame)
                        writer?.write("\n")
                        writer?.flush()
                    } catch (e: Exception) {
                        _state.value = ConnectionState.FAILED
                        break
                    }
                }
            } catch (e: Exception) {
                _state.value = ConnectionState.FAILED
            }
        }
    }

    /** Non-blocking; safe to call from gesture callbacks on the UI thread. */
    fun send(frame: String) {
        if (_state.value == ConnectionState.CONNECTED) {
            outbound.trySend(frame)
        }
    }

    fun disconnect() {
        socket?.close()
        socket = null
        writer = null
        _state.value = ConnectionState.DISCONNECTED
    }
}
