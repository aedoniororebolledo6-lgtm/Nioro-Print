package com.nioro.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class Kind { BT_CLASSIC, BT_LE, BT_DUAL, USB, TCP }

data class FoundDevice(
    val name: String,
    val address: String,
    val kind: Kind,
    val bt: BluetoothDevice? = null,
    val usb: UsbDevice? = null,
    val bonded: Boolean = false
)

/** Gestor central: conexión, envío por bloques, reconexión automática y registro. */
class PrinterHub private constructor(private val appCtx: Context) {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile var transport: Transport? = null
    @Volatile private var factory: (() -> Transport)? = null
    @Volatile var busy = false

    var settings: PrinterSettings = Store.load(appCtx)
    var onLog: (() -> Unit)? = null
    var onState: (() -> Unit)? = null

    val isConnected: Boolean
        get() = transport?.connected == true

    val currentLabel: String
        get() = transport?.label ?: ""

    fun logText(): String = synchronized(logBuf) { logBuf.toString() }

    fun clearLog() {
        synchronized(logBuf) { logBuf.setLength(0) }
        main.post { onLog?.invoke() }
    }

    fun log(msg: String) {
        synchronized(logBuf) {
            logBuf.append(fmt.format(Date())).append("  ").append(msg).append('\n')
            if (logBuf.length > 24000) logBuf.delete(0, logBuf.length - 18000)
        }
        main.post { onLog?.invoke() }
    }

    private fun post(cb: (String?) -> Unit, err: String?) {
        main.post { cb(err) }
    }

    // ----------------------------------------------------------------- conexión
    private fun establish(kindKey: String, a: String, b: String, name: String,
                          make: () -> Transport, cb: (String?) -> Unit) {
        busy = true
        main.post { onState?.invoke() }
        exec.execute {
            var err: String? = null
            try {
                try { transport?.close() } catch (e: Exception) { }
                transport = null
                val t = make()
                t.connect()
                transport = t
                factory = make
                Store.saveLast(appCtx, kindKey, a, b, name)
                log("Conectado: ${t.label}")
                if (settings.applyOnConnect) {
                    try {
                        writeChunked(t, EscPos.configJob(settings))
                        log("Configuración aplicada a la impresora")
                    } catch (e: Exception) {
                        log("No se pudo aplicar configuración: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                log("ERROR: $err")
            }
            busy = false
            main.post {
                onState?.invoke()
                cb(err)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBt(d: BluetoothDevice, kind: Kind, cb: (String?) -> Unit) {
        val s = settings
        val name = try { d.name ?: d.address } catch (e: Exception) { d.address }
        val make: () -> Transport = {
            val classic = BtClassicTransport(d, s.force, s.retries, ::log)
            val ble = BleTransport(appCtx, d, if (s.force) 2 else 1, ::log)
            CompositeBt(kind, s.force, classic, ble, ::log)
        }
        establish("BT", d.address, kind.name, name, make, cb)
    }

    fun connectUsb(d: UsbDevice, cb: (String?) -> Unit) {
        val usb = appCtx.getSystemService(Context.USB_SERVICE) as UsbManager
        val name = d.productName ?: String.format("%04X:%04X", d.vendorId, d.productId)
        establish("USB", d.vendorId.toString(), d.productId.toString(), name,
            { UsbTransport(usb, d, ::log) }, cb)
    }

    fun connectTcp(host: String, port: Int, cb: (String?) -> Unit) {
        val s = settings
        establish("TCP", host, port.toString(), "$host:$port",
            { TcpTransport(host, port, if (s.force) s.retries else 1, ::log) }, cb)
    }

    fun disconnect() {
        exec.execute {
            try { transport?.close() } catch (e: Exception) { }
            transport = null
            factory = null
            log("Desconectado")
            main.post { onState?.invoke() }
        }
    }

    // ----------------------------------------------------------------- envío
    private fun writeChunked(t: Transport, data: ByteArray) {
        val chunk = settings.chunkSize
        val delay = settings.chunkDelayMs
        var i = 0
        while (i < data.size) {
            val end = minOf(i + chunk, data.size)
            t.write(data.copyOfRange(i, end))
            i = end
            if (delay > 0 && i < data.size) Thread.sleep(delay)
        }
    }

    /** Envía un trabajo; si la conexión cayó, reconecta una vez y reintenta. */
    fun send(job: ByteArray, cb: (String?) -> Unit) {
        busy = true
        main.post { onState?.invoke() }
        exec.execute {
            var err: String? = null
            try {
                val t = transport
                if (t == null || !t.connected) throw IllegalStateException("No hay impresora conectada")
                log("Enviando ${job.size} bytes…")
                try {
                    writeChunked(t, job)
                } catch (e: Exception) {
                    log("Fallo al enviar (${e.message}); reconectando…")
                    val f = factory ?: throw e
                    try { t.close() } catch (x: Exception) { }
                    val nt = f()
                    nt.connect()
                    transport = nt
                    writeChunked(nt, job)
                }
                log("Trabajo enviado")
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                log("ERROR: $err")
                if (transport?.connected != true) transport = null
            }
            busy = false
            main.post {
                onState?.invoke()
                cb(err)
            }
        }
    }

    /** Consulta de estado en tiempo real (DLE EOT) e identificación (GS I). */
    fun queryInfo(cb: (String) -> Unit) {
        exec.execute {
            val sb = StringBuilder()
            try {
                val t = transport
                if (t == null || !t.connected) {
                    sb.append("No hay impresora conectada")
                } else {
                    sb.append(t.label).append('\n')
                    val st = ByteArray(1)
                    fun ask(cmd: ByteArray, wait: Int, max: Int): ByteArray {
                        t.write(cmd)
                        return t.read(wait, max)
                    }
                    val r1 = ask(byteArrayOf(0x10, 0x04, 0x01), 700, 1)
                    if (r1.isEmpty()) {
                        sb.append("Estado: la impresora no responde consultas (normal en algunos modelos o en BLE)\n")
                    } else {
                        st[0] = r1[0]
                        val v = r1[0].toInt() and 0xFF
                        sb.append(if ((v and 0x08) != 0) "Estado: fuera de línea\n" else "Estado: en línea\n")
                        val r2 = ask(byteArrayOf(0x10, 0x04, 0x02), 600, 1)
                        if (r2.isNotEmpty()) {
                            val o = r2[0].toInt() and 0xFF
                            if ((o and 0x04) != 0) sb.append("Tapa abierta\n")
                            if ((o and 0x20) != 0) sb.append("Sin papel (detiene impresión)\n")
                            if ((o and 0x40) != 0) sb.append("Error en la impresora\n")
                        }
                        val r4 = ask(byteArrayOf(0x10, 0x04, 0x04), 600, 1)
                        if (r4.isNotEmpty()) {
                            val p = r4[0].toInt() and 0xFF
                            if ((p and 0x60) != 0) sb.append("Papel agotado\n")
                            else if ((p and 0x0C) != 0) sb.append("Papel casi agotado\n")
                            else sb.append("Papel OK\n")
                        }
                        for ((n, title) in listOf(0x42 to "Fabricante", 0x43 to "Modelo", 0x41 to "Firmware")) {
                            val r = ask(byteArrayOf(0x1D, 0x49, n.toByte()), 600, 40)
                            val txt = r.filter { (it.toInt() and 0xFF) in 32..126 }
                                .map { (it.toInt() and 0xFF).toChar() }.joinToString("").trim('_', ' ')
                            if (txt.isNotEmpty()) sb.append("$title: $txt\n")
                        }
                    }
                }
            } catch (e: Exception) {
                sb.append("Error consultando: ").append(e.message)
            }
            val result = sb.toString().trim()
            log("Info:\n$result")
            main.post { cb(result) }
        }
    }

    companion object {
        @Volatile private var inst: PrinterHub? = null
        fun get(ctx: Context): PrinterHub {
            val i = inst
            if (i != null) return i
            synchronized(this) {
                val n = inst ?: PrinterHub(ctx.applicationContext)
                inst = n
                return n
            }
        }
    }
}

/** Para dispositivos Bluetooth: prueba clásico y luego BLE (modo forzado). */
class CompositeBt(
    private val kind: Kind,
    private val force: Boolean,
    private val classic: BtClassicTransport,
    private val ble: BleTransport,
    private val log: (String) -> Unit
) : Transport {
    private var active: Transport? = null

    override val label: String
        get() = active?.label ?: classic.label

    override val connected: Boolean
        get() = active?.connected == true

    override fun connect() {
        val order = ArrayList<Transport>()
        when (kind) {
            Kind.BT_LE -> {
                order.add(ble)
                if (force) order.add(classic)
            }
            else -> {
                order.add(classic)
                if (force || kind == Kind.BT_DUAL) order.add(ble)
            }
        }
        var last: Exception? = null
        for (t in order) {
            try {
                t.connect()
                active = t
                return
            } catch (e: Exception) {
                last = e
                log("${t.label}: ${e.message}")
            }
        }
        throw java.io.IOException(last?.message ?: "No se pudo conectar")
    }

    override fun write(data: ByteArray) {
        (active ?: throw java.io.IOException("Sin conexión")).write(data)
    }

    override fun read(timeoutMs: Int, max: Int): ByteArray = active?.read(timeoutMs, max) ?: ByteArray(0)

    override fun close() {
        classic.close()
        ble.close()
        active = null
    }
}
