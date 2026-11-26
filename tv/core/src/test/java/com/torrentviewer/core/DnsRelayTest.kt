package com.torrentviewer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The DNS relay's packet plumbing: parsing a query and building a valid reply. */
class DnsRelayTest {
    /** A minimal IPv4/UDP DNS query: 20-byte IP header, 8-byte UDP header, then [payload]. */
    private fun query(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val b = ByteArray(total)
        b[0] = 0x45; b[2] = (total ushr 8).toByte(); b[3] = total.toByte(); b[8] = 64; b[9] = 17
        System.arraycopy(src, 0, b, 12, 4); System.arraycopy(dst, 0, b, 16, 4)
        b[20] = (srcPort ushr 8).toByte(); b[21] = srcPort.toByte()
        b[22] = (dstPort ushr 8).toByte(); b[23] = dstPort.toByte()
        b[24] = (udpLen ushr 8).toByte(); b[25] = udpLen.toByte()
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    private val client = byteArrayOf(10, 111, 122.toByte(), 1)
    private val server = byteArrayOf(10, 111, 122.toByte(), 2)

    @Test fun parsesQuery() {
        val pkt = query(client, server, 44321, 53, byteArrayOf(1, 2, 3, 4, 5))
        val env = parseIpv4Udp(pkt, pkt.size)
        assertNotNull(env); env!!
        assertArrayEquals(client, env.srcIp)
        assertArrayEquals(server, env.dstIp)
        assertEquals(44321, env.srcPort)
        assertEquals(53, env.dstPort)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), env.payload)
    }

    @Test fun rejectsNonUdpOrShort() {
        assertNull(parseIpv4Udp(ByteArray(10), 10))        // too short
        val tcp = query(client, server, 1, 53, byteArrayOf(0)); tcp[9] = 6
        assertNull(parseIpv4Udp(tcp, tcp.size))            // TCP, not UDP
    }

    @Test fun replyIsSwappedAndChecksumsValid() {
        val env = parseIpv4Udp(query(client, server, 40000, 53, byteArrayOf(9, 9)), 30)!!
        val reply = byteArrayOf(7, 7, 7, 7)
        val out = buildIpv4UdpReply(env, reply)
        val back = parseIpv4Udp(out, out.size)
        assertNotNull(back); back!!
        // The answer comes from the server back to the client, port 53 -> client port.
        assertArrayEquals(server, back.srcIp)
        assertArrayEquals(client, back.dstIp)
        assertEquals(53, back.srcPort)
        assertEquals(40000, back.dstPort)
        assertArrayEquals(reply, back.payload)
        // A correct IP header checksums to zero over the whole header; likewise the UDP sum with its pseudo-header.
        assertEquals(0, checksum16(out, 0, 20))
        assertEquals(0, udpChecksum(out, 12, 16, 20, out.size - 20))
    }
}
