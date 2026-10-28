package com.nioro.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface Transport {
    val label: String
    val connected: Boolean
    fun connect()
    fun write(data: ByteArray)
    fun read(timeoutMs: Int, max: Int): ByteArray
    fun close()
}

private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Lectura genérica con espera por inactividad. */
private fun readStream(inp: java.io.InputStream, timeoutMs: Int, max: Int): ByteArray {
    val end = System.currentTimeMillis() + timeoutMs
    val buf = ByteArrayOutputStream()
    while (System.currentTimeMillis() < end && buf.size() < max) {
        val av = inp.available()
        if (av > 0) {
            val tmp = ByteArray(minOf(av, max - buf.size()))
            val n = inp.read(tmp)
            if (n > 0) buf.write(tmp, 0, n)
            Thread.sleep(40)
            if (inp.available() == 0 && buf.size() > 0) break
        } else {
            Thread.sleep(20)
        }
    }
    return buf.toByteArray()
}

// ---------------------------------------------------------------------------
// Bluetooth clásico (SPP) con todos los métodos de conexión posibles
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class BtClassicTransport(
    private val device: BluetoothDevice,
    private val force: Boolean,
    private val retries: Int,
    private val log: (String) -> Unit
) : Transport {
    private var socket: BluetoothSocket? = null
    @Volatile private var up = false
    @Volatile private var cancelled = false

    override val label: String
        get() = "Bluetooth " + (safeName() ?: device.address)

    override val connected: Boolean
        get() = up && (socket?.isConnected == true)

    private fun safeName(): String? = try { device.name } catch (e: Exception) { null }

    private fun reflect(method: String, ch: Int): BluetoothSocket {
        val m = device.javaClass.getMethod(method, Int::class.javaPrimitiveType)
        return m.invoke(device, ch) as BluetoothSocket
    }

    private fun strategies(): List<Pair<String, () -> BluetoothSocket>> {
        val list = ArrayList<Pair<String, () -> BluetoothSocket>>()
        list.add(Pair("SPP seguro", { device.createRfcommSocketToServiceRecord(SPP) }))
        list.add(Pair("SPP inseguro", { device.createInsecureRfcommSocketToServiceRecord(SPP) }))
        if (force) {
            for (ch in 1..3) {
                list.add(Pair("canal $ch (reflexión)", { reflect("createRfcommSocket", ch) }))
            }
            for (ch in 1..2) {
                list.add(Pair("canal $ch inseguro (reflexión)", { reflect("createInsecureRfcommSocket", ch) }))
            }
            val extra: List<UUID> = try {
                device.uuids?.map { it.uuid } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            for (u in extra.distinct()) {
                if (u != SPP) {
                    list.add(Pair("UUID $u", { device.createInsecureRfcommSocketToServiceRecord(u) }))
                }
            }
        }
        return list
    }

    override fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IOException("Este equipo no tiene Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth apagado: actívalo e intenta de nuevo")
        var lastErr: Exception? = null
        cancelled = false
        val rounds = if (force) retries.coerceAtLeast(1) else 1
        for (round in 1..rounds) {
            try { adapter.cancelDiscovery() } catch (e: Exception) { }
            for ((name, factory) in strategies()) {
                if (cancelled) throw IOException("Conexión cancelada")
                var s: BluetoothSocket? = null
                try {
                    log("BT ronda $round/$rounds: $name")
                    s = factory()
                    s.connect()
                    socket = s
                    up = true
                    log("BT conectado usando: $name")
                    return
                } catch (e: Exception) {
                    lastErr = e
                    log("   falló: ${e.message}")
                    try { s?.close() } catch (x: Exception) { }
                    Thread.sleep(300)
                }
            }
            if (cancelled) throw IOException("Conexión cancelada")
            if (round < rounds) Thread.sleep(700)
        }
        throw IOException("No se pudo conectar por Bluetooth: ${lastErr?.message ?: "sin respuesta"}")
    }

    override fun write(data: ByteArray) {
        val o = socket?.outputStream ?: throw IOException("Sin conexión")
        o.write(data)
        o.flush()
    }

    override fun read(timeoutMs: Int, max: Int): ByteArray {
        val i = socket?.inputStream ?: return ByteArray(0)
        return readStream(i, timeoutMs, max)
    }

    override fun close() {
        up = false
        cancelled = true
        try { socket?.close() } catch (e: Exception) { }
        socket = null
    }
}

// ---------------------------------------------------------------------------
// Bluetooth Low Energy (GATT)
// ---------------------------------------------------------------------------
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val retries: Int,
    private val log: (String) -> Unit
) : Transport {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var up = false
    @Volatile private var connOk = false
    @Volatile private var cancelled = false
    @Volatile private var connLatch = CountDownLatch(1)
    @Volatile private var svcLatch = CountDownLatch(1)
    @Volatile private var mtuLatch = CountDownLatch(1)
    private val writeSem = Semaphore(0)

    private fun safeName(): String? = try { device.name } catch (e: Exception) { null }

    override val label: String
        get() = "BLE " + (safeName() ?: device.address)

    override val connected: Boolean
        get() = up

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connOk = true
                connLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                up = false
                connOk = false
                connLatch.countDown()
                svcLatch.countDown()
                mtuLatch.countDown()
                writeSem.release()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            mtuLatch.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            svcLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeSem.release()
        }
    }

    private fun pickWritable(g: BluetoothGatt): BluetoothGattCharacteristic? {
        val preferred = listOf("0000ff02", "49535343-8841", "0000ffe1", "00002af1", "0000fff2", "bef8d6c9")
        val all = ArrayList<BluetoothGattCharacteristic>()
        for (svc in g.services) {
            val su = svc.uuid.toString().lowercase()
            if (su.startsWith("00001800") || su.startsWith("00001801")) continue
            for (c in svc.characteristics) {
                val p = c.properties
                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ) {
                    all.add(c)
                    log("   BLE característica escribible: ${c.uuid}")
                }
            }
        }
        for (pref in preferred) {
            val hit = all.firstOrNull { it.uuid.toString().lowercase().startsWith(pref) }
            if (hit != null) return hit
        }
        return all.firstOrNull()
    }

    override fun connect() {
        val rounds = retries.coerceAtLeast(1).coerceAtMost(4)
        var lastErr = "sin respuesta"
        for (round in 1..rounds) {
            log("BLE ronda $round/$rounds")
            close()
            cancelled = false
            connLatch = CountDownLatch(1)
            svcLatch = CountDownLatch(1)
            mtuLatch = CountDownLatch(1)
            connOk = false
            val g = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(ctx, false, callback)
            }
            gatt = g
            if (!connLatch.await(10, TimeUnit.SECONDS) || !connOk) {
                if (cancelled) throw IOException("Conexión cancelada")
                lastErr = "no conectó (GATT)"
                try { g.close() } catch (e: Exception) { }
                gatt = null
                Thread.sleep(400)
                continue
            }
            try { g.requestMtu(247) } catch (e: Exception) { }
            mtuLatch.await(2, TimeUnit.SECONDS)
            g.discoverServices()
            if (!svcLatch.await(10, TimeUnit.SECONDS)) {
                lastErr = "no se descubrieron servicios"
                close()
                continue
            }
            val c = pickWritable(g)
            if (c == null) {
                lastErr = "no hay característica de escritura"
                close()
                continue
            }
            writeChar = c
            up = true
            log("BLE listo, MTU $mtu, característica ${c.uuid}")
            return
        }
        throw IOException("No se pudo conectar por BLE: $lastErr")
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Sin conexión BLE")
        val c = writeChar ?: throw IOException("Sin característica BLE")
        val chunk = (mtu - 3).coerceIn(20, 512)
        val noResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        c.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            if (!up) throw IOException("Conexión BLE perdida")
            val end = minOf(i + chunk, data.size)
            writeSem.drainPermits()
            c.value = data.copyOfRange(i, end)
            var ok = g.writeCharacteristic(c)
            var tries = 0
            while (!ok && tries < 40) {
                Thread.sleep(15)
                ok = g.writeCharacteristic(c)
                tries++
            }
            if (!ok) throw IOException("BLE rechazó la escritura")
            if (!writeSem.tryAcquire(3, TimeUnit.SECONDS)) throw IOException("BLE sin respuesta")
            if (noResp) Thread.sleep(3)
            i = end
        }
    }

    override fun read(timeoutMs: Int, max: Int): ByteArray = ByteArray(0)

    override fun close() {
        up = false
        cancelled = true
        try { gatt?.disconnect() } catch (e: Exception) { }
        try { gatt?.close() } catch (e: Exception) { }
        gatt = null
        writeChar = null
    }
}

// ---------------------------------------------------------------------------
// USB (host)
// ---------------------------------------------------------------------------
class UsbTransport(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val log: (String) -> Unit
) : Transport {
    private var conn: UsbDeviceConnection? = null
    private var outEp: UsbEndpoint? = null
    private var inEp: UsbEndpoint? = null
    private var iface: android.hardware.usb.UsbInterface? = null
    @Volatile private var up = false

    override val label: String
        get() = "USB " + String.format("%04X:%04X", device.vendorId, device.productId)

    override val connected: Boolean
        get() = up

    override fun connect() {
        if (!usb.hasPermission(device)) throw IOException("Sin permiso USB")
        val c = usb.openDevice(device) ?: throw IOException("No se pudo abrir el dispositivo USB")
        val ifaces = ArrayList<android.hardware.usb.UsbInterface>()
        for (i in 0 until device.interfaceCount) ifaces.add(device.getInterface(i))
        ifaces.sortBy { if (it.interfaceClass == 7) 0 else 1 }
        for (itf in ifaces) {
            var o: UsbEndpoint? = null
            var n: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) o = ep else n = ep
                }
            }
            if (o == null) continue
            log("USB interfaz ${itf.id} clase ${itf.interfaceClass}")
            var claimed = c.claimInterface(itf, true)
            if (!claimed && device.configurationCount > 0) {
                log("   reintentando con setConfiguration")
                c.setConfiguration(device.getConfiguration(0))
                claimed = c.claimInterface(itf, true)
            }
            if (claimed) {
                conn = c
                iface = itf
                outEp = o
                inEp = n
                up = true
                log("USB listo")
                return
            }
        }
        c.close()
        throw IOException("No se encontró un canal USB de impresión")
    }

    override fun write(data: ByteArray) {
        val c = conn ?: throw IOException("Sin conexión USB")
        val ep = outEp ?: throw IOException("Sin endpoint USB")
        var off = 0
        while (off < data.size) {
            val len = minOf(4096, data.size - off)
            var n = c.bulkTransfer(ep, data, off, len, 5000)
            var tries = 0
            while (n < 0 && tries < 3) {
                Thread.sleep(50)
                n = c.bulkTransfer(ep, data, off, len, 5000)
                tries++
            }
            if (n < 0) throw IOException("Falló la transferencia USB")
            off += n
        }
    }

    override fun read(timeoutMs: Int, max: Int): ByteArray {
        val c = conn ?: return ByteArray(0)
        val ep = inEp ?: return ByteArray(0)
        val buf = ByteArray(64)
        val n = c.bulkTransfer(ep, buf, buf.size, timeoutMs)
        return if (n > 0) buf.copyOf(minOf(n, max)) else ByteArray(0)
    }

    override fun close() {
        up = false
        try { iface?.let { conn?.releaseInterface(it) } } catch (e: Exception) { }
        try { conn?.close() } catch (e: Exception) { }
        conn = null
    }
}

// ---------------------------------------------------------------------------
// WiFi / red (RAW 9100)
// ---------------------------------------------------------------------------
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val retries: Int,
    private val log: (String) -> Unit
) : Transport {
    private var sock: Socket? = null
    @Volatile private var cancelled = false

    override val label: String
        get() = "WiFi $host:$port"

    override val connected: Boolean
        get() = sock?.let { it.isConnected && !it.isClosed } == true

    override fun connect() {
        var last: Exception? = null
        cancelled = false
        for (i in 1..retries.coerceAtLeast(1)) {
            if (cancelled) throw IOException("Conexión cancelada")
            try {
                log("WiFi intento $i: $host:$port")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.keepAlive = true
                sock = s
                return
            } catch (e: Exception) {
                last = e
                log("   falló: ${e.message}")
                Thread.sleep(500)
            }
        }
        throw IOException("No se pudo conectar a $host:$port (${last?.message})")
    }

    override fun write(data: ByteArray) {
        val o = sock?.getOutputStream() ?: throw IOException("Sin conexión")
        o.write(data)
        o.flush()
    }

    override fun read(timeoutMs: Int, max: Int): ByteArray {
        val i = sock?.getInputStream() ?: return ByteArray(0)
        return readStream(i, timeoutMs, max)
    }

    override fun close() {
        cancelled = true
        try { sock?.close() } catch (e: Exception) { }
        sock = null
    }
}
