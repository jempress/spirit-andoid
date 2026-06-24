package com.antony.spirit

/**
 * Wire protocol: newline-delimited ASCII frames over TCP. Must stay in sync with
 * Protocol.cs on the PC side. Deliberately simple text framing — debuggable with
 * netcat, no serialization library needed for six opcodes.
 */
object Protocol {
    fun move(dx: Float, dy: Float) = "MOVE %.2f %.2f".format(dx, dy)
    fun clickLeft() = "CLICK_L"
    fun clickRight() = "CLICK_R"
    fun dragStart() = "DRAG_START"
    fun dragMove(dx: Float, dy: Float) = "DRAG_MOVE %.2f %.2f".format(dx, dy)
    fun dragEnd() = "DRAG_END"
    fun scroll(dy: Float) = "SCROLL %.2f".format(dy)
    fun hscroll(dx: Float) = "HSCROLL %.2f".format(dx)
    fun zoom(delta: Float) = "ZOOM %.2f".format(delta)
    fun ping() = "PING"
}
