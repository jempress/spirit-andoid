package com.antony.spirit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private lateinit var discovery: DiscoveryClient
    private lateinit var connection: SpiritConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        discovery = DiscoveryClient(lifecycleScope)
        connection = SpiritConnection(lifecycleScope)
        discovery.start()

        setContent {
            MaterialTheme {
                SpiritApp(discovery, connection)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
        connection.disconnect()
    }
}

@Composable
fun SpiritApp(discovery: DiscoveryClient, connection: SpiritConnection) {
    val discovered by discovery.discovered.collectAsState()
    val connState by connection.state.collectAsState()
    var manualIp by remember { mutableStateOf("") }
    var autoConnectAttempted by remember { mutableStateOf(false) }

    // Auto-connect the moment we discover a PC, but only once per discovery
    // (avoids re-triggering connect() on every beacon if already connected/connecting).
    LaunchedEffect(discovered) {
        val pc = discovered
        if (pc != null && !autoConnectAttempted && connState == ConnectionState.DISCONNECTED) {
            autoConnectAttempted = true
            connection.connect(pc.ip, pc.tcpPort)
        }
    }

    if (connState == ConnectionState.CONNECTED) {
        // Full-screen trackpad once connected — this is the actual product surface.
        Box(modifier = Modifier.fillMaxSize()) {
            GestureSurface(connection, modifier = Modifier.fillMaxSize())
            Text(
                text = "Spirit connected",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    } else {
        ConnectScreen(
            discovered = discovered,
            state = connState,
            manualIp = manualIp,
            onManualIpChange = { manualIp = it },
            onConnectClicked = {
                val target = discovered ?: manualIp.takeIf { it.isNotBlank() }?.let {
                    DiscoveredPc(it, 6824)
                }
                target?.let { connection.connect(it.ip, it.tcpPort) }
            }
        )
    }
}

@Composable
private fun ConnectScreen(
    discovered: DiscoveredPc?,
    state: ConnectionState,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    onConnectClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Spirit", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        val statusText = when (state) {
            ConnectionState.DISCONNECTED -> if (discovered != null)
                "Found PC at ${discovered.ip} — tap Connect" else "Searching for PC on WiFi..."
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.FAILED -> "Connection failed — check PC is running and on same network"
            ConnectionState.CONNECTED -> "" // handled by caller, unreachable here
        }
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        if (discovered == null) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualIpChange,
                label = { Text("PC IP address (manual fallback)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = onConnectClicked,
            enabled = state != ConnectionState.CONNECTING,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect")
        }
    }
}
