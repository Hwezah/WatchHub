package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.torrentviewer.core.AD_FILTER_DNS
import com.torrentviewer.core.buildIpv4UdpReply
import com.torrentviewer.core.parseIpv4Udp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * System-wide ad blocking without the WireGuard tunnel (the owner's ask). Android gives an app one VPN slot, so this
 * can't run alongside the WireGuard VPN — [Vpn] hands the slot over: ad blocking rides the tunnel's DNS while the VPN
 * is on, and this tiny DNS-only VPN takes over when it's off.
 */
object AdShield {
    private val _on = MutableStateFlow(false)
    /** Whether the DNS-only shield is up, for Settings. */
    val on: StateFlow<Boolean> = _on
    internal fun mark(v: Boolean) { _on.value = v }

    /** Whether the phone has let this app be a VPN yet (the same consent WireGuard uses). */
    fun ready(context: Context): Boolean = VpnService.prepare(context) == null

    fun start(context: Context) {
        if (!ready(context)) return
        runCatching { context.startService(Intent(context, AdShieldService::class.java).setAction(AdShieldService.START)) }
    }

    fun stop(context: Context) {
        runCatching { context.startService(Intent(context, AdShieldService::class.java).setAction(AdShieldService.STOP)) }
    }
}

/**
 * A DNS-only VPN: it routes just the phone's DNS traffic ([TUN_DNS] is the only route), relays each query to an
 * ad-filtering resolver over a protected socket (so the name resolves — or doesn't, if it's an ad host — the same way
 * everywhere), and writes the answer back. Every other packet is left untouched.
 */
class AdShieldService : VpnService() {
    @Volatile private var running = false
    private var pfd: ParcelFileDescriptor? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            else -> bringUp()
        }
        return START_STICKY
    }

    private fun bringUp() {
        if (running) return
        val fd = runCatching {
            Builder()
                .setSession("WatchHub ad blocking")
                .addAddress(TUN_SELF, 24)
                .addDnsServer(TUN_DNS)
                .addRoute(TUN_DNS, 32)
                .setBlocking(true)
                .establish()
        }.getOrNull() ?: return
        pfd = fd
        running = true
        pool = Executors.newFixedThreadPool(8)
        AdShield.mark(true)
        Thread { pump(fd) }.apply { isDaemon = true }.start()
    }

    /** Reads DNS queries off the tunnel and hands each to the pool, which relays it and writes the reply back. */
    private fun pump(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val writeLock = Any()
        val upstream = runCatching { InetAddress.getByName(AD_FILTER_DNS[0]) }.getOrNull()
        val buf = ByteArray(32_767)
        while (running && upstream != null) {
            val n = runCatching { input.read(buf) }.getOrNull() ?: break
            if (n <= 0) continue
            val packet = buf.copyOf(n)   // the buffer is reused by the next read, so hand the worker its own copy
            pool?.execute {
                val env = parseIpv4Udp(packet, packet.size) ?: return@execute
                if (env.dstPort != 53) return@execute
                // A protected socket bypasses the tunnel, so the relay can't loop back into itself.
                runCatching {
                    DatagramSocket().use { sock ->
                        protect(sock)
                        sock.soTimeout = 5_000
                        sock.send(DatagramPacket(env.payload, env.payload.size, upstream, 53))
                        val rb = ByteArray(32_767)
                        val rp = DatagramPacket(rb, rb.size)
                        sock.receive(rp)
                        val out = buildIpv4UdpReply(env, rb.copyOf(rp.length))
                        synchronized(writeLock) { output.write(out) }
                    }
                }
            }
        }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun teardown() {
        running = false
        AdShield.mark(false)
        runCatching { pool?.shutdownNow() }
        pool = null
        runCatching { pfd?.close() }
        pfd = null
    }

    // Another VPN (WireGuard) took the slot, or the system tore us down.
    override fun onRevoke() { teardown(); stopSelf() }
    override fun onDestroy() { teardown(); super.onDestroy() }

    companion object {
        const val START = "com.torrentviewer.mobile.ADSHIELD_START"
        const val STOP = "com.torrentviewer.mobile.ADSHIELD_STOP"
        private const val TUN_SELF = "10.111.222.1"
        private const val TUN_DNS = "10.111.222.2"
    }
}
