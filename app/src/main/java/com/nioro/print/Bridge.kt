package com.nioro.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Puente Bluetooth: el celular se anuncia como una "impresora" serie (SPP) para que un
 * Alco-Sensor (u otro equipo) le mande sus datos de impresión, y los reenvía a la
 * impresora térmica conectada.
 */
@SuppressLint("MissingPermission")
class BridgeServer(private val hub: PrinterHub) {
    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val MODE_RAW = 0
        const val MODE_TEXT = 1
        const val MODE_CAPTURE = 2

        private fun escParams(c: Int): Int = when (c) {
            0x40, 0x32 -> 0       // ESC @ , ESC 2
            else -> 1
        }

        /** Extrae texto imprimible de un flujo ESC/POS o de texto plano. */
        fun extractText(d: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                if (b == 0x1B) {
                    val c = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0
                    i += 2 + escParams(c)
                    continue
                }
                if (b == 0x1D) {
                    val c = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0
                    if (c == 0x76 && i + 7 < d.size) { // GS v 0 m xL xH yL yH data
                        val xl = d[i + 4].toInt() and 0xFF
                        val xh = d[i + 5].toInt() and 0xFF
                        val yl = d[i + 6].toInt() and 0xFF
                        val yh = d[i + 7].toInt() and 0xFF
                        i += 8 + (xl + xh * 256) * (yl + yh * 256)
                    } else {
                        i += 3
                    }
                    continue
                }
                when {
                    b == 0x0A -> sb.append('\n')
                    b == 0x0D -> {
                        val next = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else -1
                        if (next != 0x0A) sb.append('\n')
                    }
                    b == 0x09 -> sb.append("    ")
                    b in 0x20..0x7E -> sb.append(b.toChar())
                    b >= 0xA0 -> sb.append(b.toChar())
                }
                i++
            }
            return sb.toString()
        }

        fun hexDump(d: ByteArray, limit: Int = 160): String {
            val n = minOf(d.size, limit)
            val hex = StringBuilder()
            val asc = StringBuilder()
            for (i in 0 until n) {
                val v = d[i].toInt() and 0xFF
                hex.append(String.format("%02X ", v))
                asc.append(if (v in 32..126) v.toChar() else '.')
            }
            val more = if (d.size > limit) " …(+${d.size - limit})" else ""
            return "${d.size} bytes\n$hex$more\n$asc"
        }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false
    @Volatile var status = "Detenido"
    @Volatile var mode = MODE_RAW
    @Volatile var lastData: ByteArray = ByteArray(0)
    @Volatile var received = 0
    var onChange: (() -> Unit)? = null

    private var server: BluetoothServerSocket? = null
    private var client: BluetoothSocket? = null
    private var thread: Thread? = null

    private fun setStatus(s: String) {
        status = s
        main.post { onChange?.invoke() }
    }

    fun start(): Boolean {
        if (running) return true
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return false
        running = true
        val t = Thread { loop(adapter) }
        t.isDaemon = true
        thread = t
        t.start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        try { client?.close() } catch (e: Exception) { }
        server = null
        client = null
        setStatus("Detenido")
    }

    private fun openServer(adapter: BluetoothAdapter): BluetoothServerSocket {
        return try {
            adapter.listenUsingInsecureRfcommWithServiceRecord("Nioro Print", SPP)
        } catch (e: Exception) {
            hub.log("Puente: escucha insegura falló (${e.message}); probando segura")
            adapter.listenUsingRfcommWithServiceRecord("Nioro Print", SPP)
        }
    }

    private fun loop(adapter: BluetoothAdapter) {
        hub.log("Puente iniciado")
        while (running) {
            var c: BluetoothSocket? = null
            try {
                val srv = openServer(adapter)
                server = srv
                setStatus("Esperando al Alco-Sensor… (visible como ${adapter.name})")
                c = srv.accept()
                try { srv.close() } catch (e: Exception) { }
                server = null
                client = c
                val who = try { c.remoteDevice.name ?: c.remoteDevice.address } catch (e: Exception) { "equipo" }
                setStatus("Conectado con: $who")
                hub.log("Puente: conectado con $who")
                handle(c)
                hub.log("Puente: $who se desconectó")
            } catch (e: Exception) {
                if (!running) break
                hub.log("Puente: ${e.message}")
                setStatus("Reintentando…")
                try { Thread.sleep(1500) } catch (x: InterruptedException) { }
            } finally {
                try { c?.close() } catch (e: Exception) { }
                client = null
            }
        }
        setStatus("Detenido")
        hub.log("Puente detenido")
    }

    private fun handle(c: BluetoothSocket) {
        val inp = c.inputStream
        val buf = ByteArray(2048)
        val acc = ByteArrayOutputStream()
        var lastRx = System.currentTimeMillis()
        try {
            while (running) {
                if (inp.available() > 0) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    acc.write(buf, 0, n)
                    lastRx = System.currentTimeMillis()
                    if (mode == MODE_RAW && acc.size() >= 1024) flush(acc)
                } else {
                    val idle = if (mode == MODE_RAW) 250L else 1500L
                    if (acc.size() > 0 && System.currentTimeMillis() - lastRx > idle) flush(acc)
                    Thread.sleep(25)
                }
            }
        } catch (e: IOException) {
            // desconexión normal
        }
        if (acc.size() > 0) flush(acc)
    }

    private fun flush(acc: ByteArrayOutputStream) {
        val data = acc.toByteArray()
        acc.reset()
        if (data.isEmpty()) return
        lastData = data
        received += data.size
        hub.log("Puente recibió " + hexDump(data))
        main.post { onChange?.invoke() }
        deliver(data)
    }

    /** Envía datos a la impresora según el modo. Se usa también para reimprimir. */
    fun deliver(data: ByteArray) {
        if (mode == MODE_CAPTURE) return
        if (!hub.isConnected) {
            hub.log("Puente: no hay impresora conectada; datos guardados (usa Reimprimir)")
            return
        }
        if (mode == MODE_TEXT) {
            val text = extractText(data).trimEnd()
            if (text.isNotBlank()) {
                hub.send(EscPos.textJob(hub.settings, text, 0, false)) { }
                return
            }
            hub.log("Puente: no se encontró texto; reenviando tal cual")
        }
        hub.send(data) { }
    }
}
