package com.example.udprelay

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** SOCKS5 sunucusu: TCP CONNECT + UDP ASSOCIATE (QUIC / WhatsApp UDP dahil). */
class Socks5Server(
    private val port: Int,
    private val user: String,
    private val pass: String
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val dnsCache = ConcurrentHashMap<String, Pair<InetAddress, Long>>()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))   // 0.0.0.0: tüm arayüzler (hotspot dahil)
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun resolve(host: String): InetAddress {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (now - it.second < 300_000) return it.first }
        val a = InetAddress.getByName(host)
        dnsCache[host] = a to now
        return a
    }

    private fun handle(c: Socket) {
        try {
            c.use {
                c.tcpNoDelay = true
                val inp = DataInputStream(BufferedInputStream(c.getInputStream()))
                val out = c.getOutputStream()

                // --- El sıkışma + isteğe bağlı kullanıcı/şifre ---
                if (inp.readUnsignedByte() != 5) return
                val methods = ByteArray(inp.readUnsignedByte())
                inp.readFully(methods)
                if (user.isNotEmpty()) {
                    if (!methods.contains(2.toByte())) { out.write(byteArrayOf(5, -1)); return }
                    out.write(byteArrayOf(5, 2)); out.flush()
                    inp.readUnsignedByte()
                    val u = ByteArray(inp.readUnsignedByte()); inp.readFully(u)
                    val p = ByteArray(inp.readUnsignedByte()); inp.readFully(p)
                    if (String(u) != user || String(p) != pass) { out.write(byteArrayOf(1, 1)); return }
                    out.write(byteArrayOf(1, 0))
                } else {
                    out.write(byteArrayOf(5, 0))
                }
                out.flush()

                // --- İstek ---
                inp.readUnsignedByte()                  // VER
                val cmd = inp.readUnsignedByte()
                inp.readUnsignedByte()                  // RSV
                val atyp = inp.readUnsignedByte()
                val host: String = when (atyp) {
                    1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                    4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                    3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.let { String(it, Charsets.US_ASCII) }
                    else -> { reply(out, 8, ByteArray(4), 0); return }
                }
                val dport = inp.readUnsignedShort()

                when (cmd) {
                    1 -> tcpConnect(c, inp, out, host, dport)
                    3 -> udpAssociate(c, inp, out)
                    else -> reply(out, 7, ByteArray(4), 0)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun reply(o: OutputStream, rep: Int, addr: ByteArray, port: Int) {
        val atyp = if (addr.size == 4) 1 else 4
        o.write(byteArrayOf(5, rep.toByte(), 0, atyp.toByte()) + addr +
                byteArrayOf((port shr 8).toByte(), port.toByte()))
        o.flush()
    }

    // ---------------- TCP ----------------
    private fun tcpConnect(c: Socket, inp: DataInputStream, out: OutputStream, host: String, port: Int) {
        val r = Socket()
        try {
            r.connect(InetSocketAddress(resolve(host), port), 10_000)
        } catch (e: Exception) {
            reply(out, 5, ByteArray(4), 0); r.close(); return
        }
        r.tcpNoDelay = true
        reply(out, 0, ByteArray(4), 0)
        val t = Thread {
            try { r.getInputStream().copyTo(out) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
            try { r.close() } catch (_: Exception) {}
        }
        t.start()
        try { inp.copyTo(r.getOutputStream()) } catch (_: Exception) {}
        try { r.close() } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
    }

    // ---------------- UDP ----------------
    private fun udpAssociate(c: Socket, inp: DataInputStream, out: OutputStream) {
        val relay = DatagramSocket(0)
        try {
            reply(out, 0, c.localAddress.address, relay.localPort)

            // TCP kontrol bağlantısı kapanınca UDP ilişkisi de biter
            Thread {
                try { while (inp.read() != -1) { } } catch (_: Exception) {}
                relay.close()
            }.start()

            val clientIp = c.inetAddress
            var clientAddr: InetSocketAddress? = null
            val buf = ByteArray(65_535)

            while (!relay.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { relay.receive(p) } catch (e: Exception) { break }

                try {
                    val fromClient = p.address == clientIp &&
                            (clientAddr == null || p.port == clientAddr.port)
                    if (fromClient) {
                        if (clientAddr == null) clientAddr = InetSocketAddress(p.address, p.port)
                        if (p.length < 10 || buf[2].toInt() != 0) continue   // FRAG desteklenmiyor
                        var off: Int
                        val dst: InetAddress
                        when (buf[3].toInt()) {
                            1 -> { dst = InetAddress.getByAddress(buf.copyOfRange(4, 8)); off = 8 }
                            4 -> { dst = InetAddress.getByAddress(buf.copyOfRange(4, 20)); off = 20 }
                            3 -> {
                                val l = buf[4].toInt() and 0xff
                                dst = resolve(String(buf, 5, l, Charsets.US_ASCII)); off = 5 + l
                            }
                            else -> continue
                        }
                        if (off + 2 > p.length) continue
                        val dport = ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)
                        off += 2
                        relay.send(DatagramPacket(buf, off, p.length - off, InetSocketAddress(dst, dport)))
                    } else if (clientAddr != null) {
                        val ab = p.address.address
                        val atyp = if (p.address is Inet6Address) 4 else 1
                        val hdr = byteArrayOf(0, 0, 0, atyp.toByte()) + ab +
                                byteArrayOf((p.port shr 8).toByte(), p.port.toByte())
                        val pkt = ByteArray(hdr.size + p.length)
                        System.arraycopy(hdr, 0, pkt, 0, hdr.size)
                        System.arraycopy(buf, 0, pkt, hdr.size, p.length)
                        relay.send(DatagramPacket(pkt, pkt.size, clientAddr))
                    }
                } catch (_: Exception) {
                }
            }
        } finally {
            relay.close()
        }
    }
}
