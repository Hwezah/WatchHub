package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [readTombstone]: Android's native crash record read for the thread that crashed. */
class TombstoneTest {
    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return out.toByteArray() }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }
    private fun num(no: Int, v: Long) = varint((no.toLong() shl 3) or 0) + varint(v)
    private fun msg(no: Int, body: ByteArray) = varint((no.toLong() shl 3) or 2) + varint(body.size.toLong()) + body
    private fun str(no: Int, s: String) = msg(no, s.toByteArray())

    private fun frame(file: String, fn: String, off: Long) = msg(4, num(1, 0x1234) + str(4, fn) + num(5, off) + str(6, file))
    private fun thread(id: Long, name: String, vararg frames: ByteArray) =
        msg(16, num(1, id) + msg(2, num(1, id) + str(2, name) + frames.fold(ByteArray(0)) { a, b -> a + b }))

    @Test
    fun namesTheCrashedThreadAndItsFrames() {
        val bytes = num(5, 100) + num(6, 222) + str(2, "samsung/dm1q") +
            msg(10, num(1, 11) + str(2, "SIGSEGV") + num(3, 1) + str(4, "SEGV_MAPERR") + num(9, 0)) +
            msg(15, str(1, "null pointer dereference")) +
            // A bystander first, as the map may list it.
            thread(111, "Profile Saver", frame("/apex/libc.so", "syscall", 28)) +
            thread(222, "DefaultDispatch", frame("/data/app/x/lib/arm64/libholes.so", "holes_scan", 40), frame("/apex/libart.so", "art_quick_invoke_stub", 8))
        val lines = readTombstone(bytes)!!
        assertEquals("SIGSEGV · SEGV_MAPERR · at 0x0", lines[0])
        assertEquals("null pointer dereference", lines[1])
        assertEquals("Crashed in thread \"DefaultDispatch\" (222):", lines[2])
        assertEquals("#00 libholes.so  holes_scan+40", lines[3])
        assertEquals("#01 libart.so  art_quick_invoke_stub+8", lines[4])
        assertTrue(lines.last().startsWith("Other threads: Profile Saver"))
    }

    @Test
    fun notATombstoneIsNull() {
        assertNull(readTombstone("plain text, not a protobuf at all".toByteArray()))
        assertNull(readTombstone(ByteArray(0)))
    }
}
