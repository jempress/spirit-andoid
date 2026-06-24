package com.antony.spirit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tunable feel parameters — sensitivity and thresholds. Adjust these first if the
 * trackpad feels too twitchy or too sluggish; avoid touching the detection logic
 * below unless you're changing actual gesture behavior.
 */
private object GestureTuning {
    const val MOVE_SENSITIVITY = 1.6f       // multiplier on raw touch delta -> cursor px
    const val SCROLL_SENSITIVITY = 0.5f
    const val ZOOM_SENSITIVITY = 0.05f       // pinch distance delta -> wheel notches
    const val ZOOM_THRESHOLD_PX = 0.6f       // min per-frame distance change to count as pinch (was too high before, causing slow pinches to be misread as scroll)
    const val TAP_MAX_DURATION_MS = 200L
    const val TAP_MAX_MOVEMENT_PX = 12f
    const val LONG_PRESS_MS = 350L
    const val EDGE_ZONE_FRACTION = 0.08f     // last 8% of width/height counts as an edge-scroll zone
}

private enum class EdgeZone { NONE, RIGHT_VSCROLL, BOTTOM_HSCROLL }

/**
 * Full-surface touchpad. One finger = move/tap/drag, two fingers = scroll/zoom/right-click,
 * disambiguated by tracking pointer count and movement over time within awaitPointerEventScope.
 */
@Composable
fun GestureSurface(connection: SpiritConnection, modifier: Modifier = Modifier) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .pointerInput(Unit) {
                awaitEachGesture(connection, haptics)
            }
    ) {
        // Intentionally blank — this is a touch surface, not a visual canvas.
        // Drawing here later (e.g. a subtle ripple on tap) is a nice v2 addition.
    }
}

/**
 * The actual multi-touch state machine. Runs once per "gesture session" (from first
 * finger down to all fingers up), using Compose's low-level pointer input API.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitEachGesture(
    connection: SpiritConnection,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    while (true) {
        awaitPointerEventScope {
            // --- Wait for the first finger down, starting a new gesture session ---
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            var pointerCount = 1
            var prevPointerCount = 0   // 0 = "no frame processed yet", forces first real frame to re-baseline
            var lastSingle = firstDown.position
            val downTime = System.currentTimeMillis()
            var totalMovement = 0f
            var isDragging = false      // true once DRAG_START has been sent
            var twoFingerStart: Offset? = null
            var lastTwoFingerDistance = 0f
            var lastTwoFingerMidY = 0f
            var sawTwoFingers = false

            // Bottom-right corner could match both zones; prioritize bottom (horizontal)
            // since it's the more specific/intentional gesture in that overlap.
            val edgeZone = when {
                firstDown.position.y > size.height * (1f - GestureTuning.EDGE_ZONE_FRACTION) ->
                    EdgeZone.BOTTOM_HSCROLL
                firstDown.position.x > size.width * (1f - GestureTuning.EDGE_ZONE_FRACTION) ->
                    EdgeZone.RIGHT_VSCROLL
                else -> EdgeZone.NONE
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                pointerCount = pressed.size

                if (pointerCount == 0) {
                    // All fingers lifted -> gesture session ends. Decide tap vs drag-end.
                    val duration = System.currentTimeMillis() - downTime
                    when {
                        isDragging -> connection.send(Protocol.dragEnd())
                        sawTwoFingers -> {
                            // Quick two-finger tap with minimal movement = right click
                            if (duration < GestureTuning.TAP_MAX_DURATION_MS &&
                                totalMovement < GestureTuning.TAP_MAX_MOVEMENT_PX
                            ) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                connection.send(Protocol.clickRight())
                            }
                        }
                        duration < GestureTuning.TAP_MAX_DURATION_MS &&
                            totalMovement < GestureTuning.TAP_MAX_MOVEMENT_PX &&
                            edgeZone == EdgeZone.NONE -> {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            connection.send(Protocol.clickLeft())
                        }
                        // else: a slow single-finger move that never crossed the long-press
                        // threshold into a drag — already sent as MOVE deltas, nothing more to do.
                    }
                    break
                }

                // Re-baseline on any pointer-count change (finger added or removed)
                // instead of computing a delta this frame — comparing positions across
                // a transition compares different fingers' coordinates and produces a
                // large bogus jump. This is the root cause of both the cursor-jump and
                // the unreliable two-finger right-click (bogus movement broke the tap
                // threshold check).
                if (pointerCount != prevPointerCount) {
                    prevPointerCount = pointerCount
                    if (pointerCount == 1) {
                        lastSingle = pressed.first().position
                    } else if (pointerCount == 2) {
                        val (a, b) = pressed.take(2)
                        twoFingerStart = a.position
                        lastTwoFingerDistance = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                        lastTwoFingerMidY = (a.position.y + b.position.y) / 2f
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (pointerCount == 1) {
                    val p = pressed.first()
                    val delta = p.position - lastSingle
                    lastSingle = p.position
                    totalMovement += hypot(delta.x, delta.y)

                    when (edgeZone) {
                        EdgeZone.RIGHT_VSCROLL -> {
                            if (delta.y != 0f) {
                                connection.send(Protocol.scroll(-delta.y * GestureTuning.SCROLL_SENSITIVITY))
                            }
                        }
                        EdgeZone.BOTTOM_HSCROLL -> {
                            if (delta.x != 0f) {
                                connection.send(Protocol.hscroll(delta.x * GestureTuning.SCROLL_SENSITIVITY))
                            }
                        }
                        EdgeZone.NONE -> {
                            val heldLongEnough =
                                System.currentTimeMillis() - downTime > GestureTuning.LONG_PRESS_MS
                            val movedEnough = totalMovement > GestureTuning.TAP_MAX_MOVEMENT_PX

                            if (!isDragging && heldLongEnough && !movedEnough) {
                                // Held still past the long-press threshold -> start a drag.
                                isDragging = true
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                connection.send(Protocol.dragStart())
                            }

                            if (delta.x != 0f || delta.y != 0f) {
                                val dx = delta.x * GestureTuning.MOVE_SENSITIVITY
                                val dy = delta.y * GestureTuning.MOVE_SENSITIVITY
                                if (isDragging) {
                                    connection.send(Protocol.dragMove(dx, dy))
                                } else {
                                    connection.send(Protocol.move(dx, dy))
                                }
                            }
                        }
                    }
                } else if (pointerCount == 2) {
                    sawTwoFingers = true
                    val (a, b) = pressed.take(2)
                    val dist = hypot((a.position.x - b.position.x), (a.position.y - b.position.y))
                    val midY = (a.position.y + b.position.y) / 2f

                    if (twoFingerStart == null) {
                        // Just transitioned from 1 -> 2 fingers; seed baselines, no event yet.
                        twoFingerStart = a.position
                        lastTwoFingerDistance = dist
                        lastTwoFingerMidY = midY
                    } else {
                        val distDelta = dist - lastTwoFingerDistance
                        val midYDelta = midY - lastTwoFingerMidY

                        // Pinch is "distance between fingers changing"; scroll is "pair
                        // translating together with distance roughly constant." Comparing
                        // distDelta against the threshold first (rather than against midYDelta)
                        // makes slow, deliberate pinches register reliably instead of losing
                        // to scroll on noisy per-frame comparisons.
                        if (abs(distDelta) > GestureTuning.ZOOM_THRESHOLD_PX) {
                            connection.send(Protocol.zoom(distDelta * GestureTuning.ZOOM_SENSITIVITY))
                        } else if (abs(midYDelta) > 1f) {
                            connection.send(Protocol.scroll(-midYDelta * GestureTuning.SCROLL_SENSITIVITY))
                        }

                        totalMovement += abs(midYDelta)
                        lastTwoFingerDistance = dist
                        lastTwoFingerMidY = midY
                    }
                }
                // 3+ fingers: currently ignored (no v1 gesture maps to it).

                event.changes.forEach { it.consume() }
            }
        }
    }
}
