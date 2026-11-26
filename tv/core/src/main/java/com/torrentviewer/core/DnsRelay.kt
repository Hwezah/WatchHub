package com.torrentviewer.core

/**
 * The bits of a captured IPv4/UDP packet the DNS relay needs: who it's from and to, and the DNS message inside. Android
 * only lets an app hold one VPN slot, so system-wide ad blocking without the WireGuard tunnel runs as a tiny DNS-only
 * VPN: it captures the phone's DNS queries, sends them on to an ad-filtering resolver, and writes the answers back.
 * These functions are the packet plumbing for that, kept pure (no Android) so they can be unit-tested.
 */
data class UdpEnvelope(
    val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray,
)

/** Reads an IPv4 UDP packet from [pkt]'s first [len] bytes; null when it isn't IPv4/UDP or is too short to be whole. */
fun parseIpv4Udp(pkt: ByteArray, len: Int): UdpEnvelope? {
    if (len < 28) return null
    if ((pkt[0].toInt() ushr 4 and 0xF) != 4) return null
    val ihl = (pkt[0].toInt() and 0xF) * 4
    if (ihl < 20 || len < ihl + 8) return null
    if ((pkt[9].toInt() and 0xFF) != 17) return null
    val udpLen = ((pkt[ihl + 4].toInt() and 0xFF) shl 8) or (pkt[ihl + 5].toInt() and 0xFF)
    val payStart = ihl + 8
    val payEnd = minOf(len, ihl + udpLen)
    if (udpLen < 8 || payEnd < payStart) return null
    return UdpEnvelope(
        srcIp = pkt.copyOfRange(12, 16),
        dstIp = pkt.copyOfRange(16, 20),
        srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF),
        dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF),
        payload = pkt.copyOfRange(payStart, payEnd),
    )
}

/**
 * Wraps [reply] as the answer to [req]: a full IPv4 UDP packet from the request's destination back to its source (the
 * addresses and ports swapped), with correct IP and UDP checksums, ready to write to the tunnel.
 */
fun buildIpv4UdpReply(req: UdpEnvelope, reply: ByteArray): ByteArray {
    val udpLen = 8 + reply.size
    val total = 20 + udpLen
    val b = ByteArray(total)
    b[0] = 0x45                                   // IPv4, 20-byte header
    b[2] = (total ushr 8).toByte(); b[3] = total.toByte()
    b[6] = 0x40                                   // don't fragment
    b[8] = 64                                     // TTL
    b[9] = 17                                     // UDP
    System.arraycopy(req.dstIp, 0, b, 12, 4)      // from the server…
    System.arraycopy(req.srcIp, 0, b, 16, 4)      // …back to the client
    val ipck = checksum16(b, 0, 20)
    b[10] = (ipck ushr 8).toByte(); b[11] = ipck.toByte()
    val u = 20
    b[u] = (req.dstPort ushr 8).toByte(); b[u + 1] = req.dstPort.toByte()       // src port = server (53)
    b[u + 2] = (req.srcPort ushr 8).toByte(); b[u + 3] = req.srcPort.toByte()   // dst port = client
    b[u + 4] = (udpLen ushr 8).toByte(); b[u + 5] = udpLen.toByte()
    System.arraycopy(reply, 0, b, u + 8, reply.size)
    val uck = udpChecksum(b, 12, 16, u, udpLen)
    // A computed zero is sent as 0xFFFF, since 0 means "no checksum" in UDP.
    val uc = if (uck == 0) 0xFFFF else uck
    b[u + 6] = (uc ushr 8).toByte(); b[u + 7] = uc.toByte()
    return b
}

/** The 16-bit one's-complement checksum of [count] bytes from [off] (IP header, or part of the UDP sum). */
fun checksum16(bytes: ByteArray, off: Int, count: Int): Int {
    var sum = 0L
    var i = off
    val end = off + count
    while (i + 1 < end) { sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF); i += 2 }
    if (i < end) sum += (bytes[i].toInt() and 0xFF) shl 8
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum.inv() and 0xFFFF).toInt()
}

/** The UDP checksum over the IPv4 pseudo-header (src/dst at [ipSrc]/[ipDst]) plus the UDP header and payload at [udpOff]. */
fun udpChecksum(b: ByteArray, ipSrc: Int, ipDst: Int, udpOff: Int, udpLen: Int): Int {
    var sum = 0L
    for (off in intArrayOf(ipSrc, ipDst)) {
        sum += ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        sum += ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }
    sum += 17                                     // protocol
    sum += udpLen
    var i = udpOff
    val end = udpOff + udpLen
    while (i + 1 < end) { sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
    if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum.inv() and 0xFFFF).toInt()
}
