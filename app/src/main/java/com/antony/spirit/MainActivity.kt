package com.antony.spirit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay

// Brand palette pulled from the app icon — dark navy background, glowing light-blue accent.
private val SpiritNavy = Color(0xFF0E1A2E)
private val SpiritNavyDeep = Color(0xFF081120)
private val SpiritAccent = Color(0xFF7EC8E3)

class MainActivity : ComponentActivity() {

    private lateinit var discovery: DiscoveryClient
    private lateinit var connection: SpiritConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The splash screen API dismisses as soon as the first frame is drawn, which
        // with Compose can happen almost instantly — too fast to actually register as
        // a splash. Holding it on screen for a fixed minimum duration makes the brand
        // moment actually visible instead of flashing past unnoticed.
        var minDurationElapsed = false
        splashScreen.setKeepOnScreenCondition { !minDurationElapsed }
        lifecycleScope.launchWhenCreated {
            delay(600)
            minDurationElapsed = true
        }

        discovery = DiscoveryClient(lifecycleScope, applicationContext)
        connection = SpiritConnection(lifecycleScope)
        discovery.start()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = SpiritAccent,
                    background = SpiritNavy,
                    surface = SpiritNavy
                )
            ) {
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // Connect screen reads naturally in portrait; the trackpad surface is wider-than-tall
    // like a real touchpad, so it switches to landscape only once actually connected.
    LaunchedEffect(connState) {
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = if (connState == ConnectionState.CONNECTED) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

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
            CornerMarkers()
            Text(
                text = "Spirit connected",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = Color.White.copy(alpha = 0.35f),
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
            },
            onUsbConnectClicked = {
                // Requires `adb reverse tcp:6824 tcp:6824` run once on the PC with the
                // phone plugged in — that tunnels the phone's localhost:6824 through to
                // the PC's localhost:6824 where Spirit's TCP server is listening.
                connection.connect("127.0.0.1", 6824)
            }
        )
    }
}

@Composable
private fun CornerMarkers() {
    val color = Color.White.copy(alpha = 0.25f)
    val size = 18.dp
    val thickness = 2.dp

    // Four corners, each a small L-bracket. Purely decorative/orientational —
    // no pointerInput here, so touches pass through to the GestureSurface beneath.
    Box(Modifier.fillMaxSize()) {
        listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)
            .forEach { corner ->
                Box(
                    modifier = Modifier
                        .align(corner)
                        .padding(12.dp)
                        .size(size)
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = thickness.toPx()
                        val len = this.size.minDimension
                        // Two short perpendicular lines forming an L, oriented per corner.
                        val isTop = corner == Alignment.TopStart || corner == Alignment.TopEnd
                        val isStart = corner == Alignment.TopStart || corner == Alignment.BottomStart
                        val yEdge = if (isTop) 0f else this.size.height
                        val xEdge = if (isStart) 0f else this.size.width
                        drawLine(color, Offset(xEdge, yEdge), Offset(xEdge, yEdge + (if (isTop) len else -len)), strokePx)
                        drawLine(color, Offset(xEdge, yEdge), Offset(xEdge + (if (isStart) len else -len), yEdge), strokePx)
                    }
                }
            }
    }
}

@Composable
private fun ConnectScreen(
    discovered: DiscoveredPc?,
    state: ConnectionState,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    onConnectClicked: () -> Unit,
    onUsbConnectClicked: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(SpiritNavy, SpiritNavyDeep)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.spirit_splash_icon),
                contentDescription = "Spirit logo",
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(20.dp))

            Text(
                "Spirit",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            val statusText = when (state) {
                ConnectionState.DISCONNECTED -> if (discovered != null)
                    "Found PC at ${discovered.ip}" else "Searching for PC on WiFi..."
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.FAILED -> "Connection failed — check PC is running and on the same network"
                ConnectionState.CONNECTED -> "" // handled by caller, unreachable here
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = SpiritAccent.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(28.dp))

            if (discovered == null) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = onManualIpChange,
                    label = { Text("PC IP address (manual fallback)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpiritAccent,
                        unfocusedBorderColor = SpiritAccent.copy(alpha = 0.4f),
                        focusedLabelColor = SpiritAccent,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick = onConnectClicked,
                enabled = state != ConnectionState.CONNECTING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpiritAccent,
                    contentColor = SpiritNavyDeep
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Connect", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "— or —",
                color = Color.White.copy(alpha = 0.3f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onUsbConnectClicked,
                enabled = state != ConnectionState.CONNECTING,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpiritAccent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect via USB")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Run \"adb reverse tcp:6824 tcp:6824\" once on the PC with the phone plugged in, then tap above.",
                color = Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
