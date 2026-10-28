package com.nioro.print

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERM = 77
        const val REQ_IMAGE = 55
        const val ACTION_USB_PERMISSION = "com.nioro.print.USB_PERMISSION"
    }

    private lateinit var hub: PrinterHub
    private lateinit var s: PrinterSettings
    private lateinit var usbManager: UsbManager

    private lateinit var content: FrameLayout
    private val pages = ArrayList<View>()
    private val tabButtons = ArrayList<TextView>()
    private var currentTab = 0

    private lateinit var statusLabel: TextView
    private lateinit var btList: LinearLayout
    private lateinit var usbList: LinearLayout
    private lateinit var searchBtn: android.widget.Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val found = LinkedHashMap<String, FoundDevice>()
    private var pendingAfterPerm: (() -> Unit)? = null
    private var pendingUsb: UsbDevice? = null
    private var scanner: BluetoothLeScanner? = null
    private var searching = false
    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = PrinterHub.get(this)
        s = hub.settings
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.setBackgroundDrawable(Ui.bgGradient())
        buildUi()

        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_FOUND)
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        f.addAction(ACTION_USB_PERMISSION)
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }

        hub.onLog = { refreshLog() }
        hub.onState = { refreshStatus() }
        refreshStatus()
        refreshLog()
        refreshUsb()
        if (s.autoConnect && !hub.isConnected && !hub.busy) tryAutoConnect()
    }

    override fun onResume() {
        super.onResume()
        hub.onLog = { refreshLog() }
        hub.onState = { refreshStatus() }
        refreshStatus()
        refreshLog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshUsb()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
        hub.onLog = null
        hub.onState = null
    }

    // ------------------------------------------------------------------ interfaz base
    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        // Cabecera
        val header = Ui.card(this)
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        val title = Ui.label(this, "Nioro Print", 22f, Ui.TEXT, true)
        statusLabel = Ui.label(this, "Sin impresora conectada", 13f, Ui.MUTED)
        header.addView(title)
        header.addView(statusLabel)
        root.addView(header, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 14, 12, 14, 8))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pages.clear()
        pages.add(buildConnectPage())
        pages.add(buildPrintPage())
        pages.add(buildSettingsPage())
        pages.add(buildNamePage())
        pages.add(buildAdvancedPage())
        for (p in pages) {
            p.visibility = View.GONE
            content.addView(p, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Barra inferior
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = Ui.glass(this, 26, 0x22FFFFFF, 0x44FFFFFF)
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
        tabButtons.clear()
        val names = listOf("Conectar", "Imprimir", "Ajustes", "Nombre", "Avanzado")
        for ((i, n) in names.withIndex()) {
            val t = Ui.label(this, n, 12f, Ui.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11))
            t.setOnClickListener { selectTab(i) }
            tabButtons.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 14, 4, 14, 12))

        setContentView(root)
        selectTab(currentTab)
    }

    private fun selectTab(i: Int) {
        currentTab = i
        for ((idx, p) in pages.withIndex()) p.visibility = if (idx == i) View.VISIBLE else View.GONE
        for ((idx, t) in tabButtons.withIndex()) {
            if (idx == i) {
                t.setTextColor(Ui.TEXT)
                t.background = Ui.primaryBg(this)
            } else {
                t.setTextColor(Ui.MUTED)
                t.background = null
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun refreshStatus() {
        if (!::statusLabel.isInitialized) return
        when {
            hub.busy -> {
                statusLabel.text = "Trabajando…"
                statusLabel.setTextColor(Ui.WARN)
            }
            hub.isConnected -> {
                statusLabel.text = "Conectada: ${hub.currentLabel}"
                statusLabel.setTextColor(Ui.OK)
            }
            else -> {
                statusLabel.text = "Sin impresora conectada"
                statusLabel.setTextColor(Ui.MUTED)
            }
        }
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        logView.text = hub.logText()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ permisos
    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBtPerms(): Boolean =
        btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun withBtPermission(action: () -> Unit) {
        if (hasBtPerms()) {
            action()
        } else {
            pendingAfterPerm = action
            requestPermissions(btPerms(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM) {
            val ok = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
            val a = pendingAfterPerm
            pendingAfterPerm = null
            if (ok) a?.invoke() else toast("Sin permisos de Bluetooth no se puede buscar ni conectar")
        }
    }

    // ------------------------------------------------------------------ receptor de eventos
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) addBtDevice(d, false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null && found.containsKey(d.address)) addBtDevice(d, false)
                }
                ACTION_USB_PERMISSION -> {
                    val d = pendingUsb
                    pendingUsb = null
                    if (d != null && usbManager.hasPermission(d)) {
                        connectUsbDevice(d)
                    } else {
                        toast("Permiso USB denegado")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshUsb()
            }
        }
    }

    // ------------------------------------------------------------------ Bluetooth
    private fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    @Suppress("MissingPermission")
    private fun addBtDevice(d: BluetoothDevice, le: Boolean) {
        val name = try { d.name } catch (e: Exception) { null }
        if (name.isNullOrBlank()) return
        val kind = when (d.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> Kind.BT_CLASSIC
            BluetoothDevice.DEVICE_TYPE_LE -> Kind.BT_LE
            BluetoothDevice.DEVICE_TYPE_DUAL -> Kind.BT_DUAL
            else -> if (le) Kind.BT_LE else Kind.BT_CLASSIC
        }
        val bonded = d.bondState == BluetoothDevice.BOND_BONDED
        val entry = FoundDevice(name, d.address, kind, bt = d, bonded = bonded)
        val old = found[d.address]
        found[d.address] = entry
        if (old != entry) renderBtList()
    }

    @Suppress("MissingPermission")
    private fun startSearch() {
        withBtPermission {
            val ad = adapter()
            if (ad == null) {
                toast("Este equipo no tiene Bluetooth")
            } else if (!ad.isEnabled) {
                toast("Activa el Bluetooth y vuelve a buscar")
                try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { }
            } else {
                found.clear()
                for (d in ad.bondedDevices) addBtDevice(d, false)
                renderBtList()
                try { ad.cancelDiscovery() } catch (e: Exception) { }
                try { ad.startDiscovery() } catch (e: Exception) { hub.log("startDiscovery: ${e.message}") }
                try {
                    scanner = ad.bluetoothLeScanner
                    scanner?.startScan(scanCb)
                } catch (e: Exception) {
                    hub.log("scan BLE: ${e.message}")
                }
                searching = true
                searchBtn.text = "Buscando… (toca para detener)"
                ui.postDelayed({ stopSearch() }, 14000)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun stopSearch() {
        if (!searching) return
        searching = false
        try { adapter()?.cancelDiscovery() } catch (e: Exception) { }
        try { scanner?.stopScan(scanCb) } catch (e: Exception) { }
        scanner = null
        if (::searchBtn.isInitialized) searchBtn.text = "Buscar impresoras Bluetooth"
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addBtDevice(result.device, true)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) addBtDevice(r.device, true)
        }
    }

    private fun kindText(k: Kind): String = when (k) {
        Kind.BT_CLASSIC -> "Bluetooth clásico"
        Kind.BT_LE -> "Bluetooth BLE"
        Kind.BT_DUAL -> "Bluetooth dual"
        Kind.USB -> "USB"
        Kind.TCP -> "WiFi"
    }

    private fun renderBtList() {
        if (!::btList.isInitialized) return
        btList.removeAllViews()
        val items = found.values.sortedWith(compareBy({ !it.bonded }, { it.name.lowercase() }))
        if (items.isEmpty()) {
            btList.addView(Ui.label(this, "Aún no hay dispositivos. Enciende la impresora y toca Buscar.", 13f, Ui.MUTED))
            return
        }
        for (f in items) btList.addView(deviceRow(f))
    }

    private fun deviceRow(f: FoundDevice): View {
        val card = Ui.card(this)
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        val r = Ui.row(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val alias = Store.alias(this, f.address)
        col.addView(Ui.label(this, alias ?: f.name, 16f, Ui.TEXT, true))
        val sub = kindText(f.kind) + (if (f.bonded) " · emparejada" else "") + " · " + f.address +
            (if (alias != null) " · (${f.name})" else "")
        col.addView(Ui.label(this, sub, 12f, Ui.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = Ui.button(this, "Conectar", true) { connectFound(f) }
        r.addView(b)
        card.addView(r)
        val extra = Ui.row(this)
        extra.addView(Ui.button(this, "Renombrar") { renameDialog(f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (f.bt != null && !f.bonded) {
            extra.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
            extra.addView(Ui.button(this, "Emparejar (PIN 0000/1234)") { pair(f) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        }
        card.addView(extra, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0))
        card.layoutParams = Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8)
        return card
    }

    @Suppress("MissingPermission")
    private fun pair(f: FoundDevice) {
        withBtPermission {
            try {
                f.bt?.createBond()
                toast("Acepta el emparejamiento. PIN habitual: 0000 o 1234")
            } catch (e: Exception) {
                toast("No se pudo emparejar: ${e.message}")
            }
        }
    }

    private fun connectFound(f: FoundDevice) {
        if (f.kind == Kind.USB) {
            val u = f.usb
            if (u != null) usbConnect(u)
            return
        }
        val d = f.bt ?: return
        withBtPermission {
            stopSearch()
            toast("Conectando a ${f.name}…")
            hub.connectBt(d, f.kind) { err -> afterConnect(err) }
        }
    }

    private fun afterConnect(err: String?) {
        refreshStatus()
        if (err == null) {
            toast("Impresora conectada")
        } else {
            AlertDialog.Builder(this)
                .setTitle("No se pudo conectar")
                .setMessage(
                    err + "\n\nRevisa que la impresora esté encendida, cerca, con papel, " +
                        "emparejada (PIN 0000 o 1234) y que no esté conectada a otro teléfono."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun renameDialog(f: FoundDevice) {
        val e = Ui.edit(this, "Nuevo nombre", text = Store.alias(this, f.address) ?: f.name)
        AlertDialog.Builder(this)
            .setTitle("Renombrar impresora")
            .setMessage("El nombre se guarda en esta app. Déjalo vacío para volver al original.")
            .setView(e)
            .setPositiveButton("Guardar") { _, _ ->
                Store.setAlias(this, f.address, e.text.toString())
                renderBtList()
                refreshUsb()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ USB
    private fun refreshUsb() {
        if (!::usbList.isInitialized) return
        usbList.removeAllViews()
        val devs = usbManager.deviceList.values.toList()
        if (devs.isEmpty()) {
            usbList.addView(Ui.label(this, "No hay dispositivos USB. Conecta la impresora con cable OTG.", 13f, Ui.MUTED))
            return
        }
        for (d in devs) {
            val f = FoundDevice(
                d.productName ?: String.format("USB %04X:%04X", d.vendorId, d.productId),
                String.format("%04X:%04X", d.vendorId, d.productId), Kind.USB, usb = d
            )
            usbList.addView(deviceRow(f))
        }
    }

    private fun usbConnect(d: UsbDevice) {
        if (usbManager.hasPermission(d)) {
            connectUsbDevice(d)
        } else {
            pendingUsb = d
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(d, pi)
        }
    }

    private fun connectUsbDevice(d: UsbDevice) {
        toast("Conectando por USB…")
        hub.connectUsb(d) { err -> afterConnect(err) }
    }

    // ------------------------------------------------------------------ autoconexión
    private fun tryAutoConnect() {
        val k = Store.lastKind(this) ?: return
        try {
            when (k) {
                "TCP" -> hub.connectTcp(Store.lastA(this), Store.lastB(this).toIntOrNull() ?: 9100) { }
                "BT" -> {
                    if (!hasBtPerms()) return
                    val ad = adapter() ?: return
                    if (!ad.isEnabled) return
                    val d = ad.getRemoteDevice(Store.lastA(this))
                    val kind = try { Kind.valueOf(Store.lastB(this)) } catch (e: Exception) { Kind.BT_CLASSIC }
                    hub.connectBt(d, kind) { }
                }
                "USB" -> {
                    val vid = Store.lastA(this).toIntOrNull()
                    val pid = Store.lastB(this).toIntOrNull()
                    val d = usbManager.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
                    if (d != null && usbManager.hasPermission(d)) hub.connectUsb(d) { }
                }
            }
        } catch (e: Exception) {
            hub.log("Autoconexión: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ PÁGINA: CONECTAR
    private fun buildConnectPage(): View {
        val (sv, col) = Ui.page(this)

        val actions = Ui.card(this)
        actions.addView(Ui.label(this, "Conexión", 17f, Ui.TEXT, true))
        actions.addView(Ui.label(this,
            "Modo forzado: prueba todos los métodos (SPP seguro/inseguro, canales RFCOMM, UUID, BLE) con reintentos.",
            12f, Ui.MUTED))
        val r = Ui.row(this)
        r.addView(Ui.button(this, "Estado / info") {
            if (requireConnected()) hub.queryInfo { txt ->
                AlertDialog.Builder(this).setTitle("Impresora").setMessage(txt).setPositiveButton("OK", null).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
        r.addView(Ui.button(this, "Desconectar / cancelar") { hub.disconnect() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(r, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0))
        col.addView(actions, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))

        // Bluetooth
        val bt = Ui.card(this)
        bt.addView(Ui.label(this, "Bluetooth", 17f, Ui.TEXT, true))
        searchBtn = Ui.button(this, "Buscar impresoras Bluetooth", true) {
            if (searching) stopSearch() else startSearch()
        }
        bt.addView(searchBtn, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8))
        btList = LinearLayout(this)
        btList.orientation = LinearLayout.VERTICAL
        bt.addView(btList)
        col.addView(bt, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))
        renderBtList()

        // USB
        val usb = Ui.card(this)
        usb.addView(Ui.label(this, "USB (cable OTG)", 17f, Ui.TEXT, true))
        usb.addView(Ui.button(this, "Actualizar lista USB") { refreshUsb() },
            Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8))
        usbList = LinearLayout(this)
        usbList.orientation = LinearLayout.VERTICAL
        usb.addView(usbList)
        col.addView(usb, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))

        // WiFi
        val wifi = Ui.card(this)
        wifi.addView(Ui.label(this, "WiFi / red (puerto RAW 9100)", 17f, Ui.TEXT, true))
        val host = Ui.edit(this, "IP de la impresora, ej. 192.168.1.50",
            text = if (Store.lastKind(this) == "TCP") Store.lastA(this) else "")
        val port = Ui.edit(this, "Puerto", text = "9100")
        port.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        wifi.addView(host, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 6))
        wifi.addView(port, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6))
        wifi.addView(Ui.button(this, "Conectar por WiFi", true) {
            val h = host.text.toString().trim()
            val p = port.text.toString().trim().toIntOrNull() ?: 9100
            if (h.isEmpty()) toast("Escribe la IP de la impresora") else {
                toast("Conectando a $h:$p…")
                hub.connectTcp(h, p) { err -> afterConnect(err) }
            }
        })
        col.addView(wifi, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))
        return sv
    }

    private fun requireConnected(): Boolean {
        if (hub.isConnected) return true
        toast("Primero conecta una impresora")
        selectTab(0)
        return false
    }

    // ------------------------------------------------------------------ trabajos de impresión
    private fun runJob(okMsg: String, build: () -> ByteArray) {
        if (!requireConnected()) return
        Thread {
            val bytes: ByteArray? = try { build() } catch (e: Throwable) {
                runOnUiThread { toast("No se pudo preparar la impresión: ${e.message}") }
                null
            }
            if (bytes != null) {
                hub.send(bytes) { err ->
                    if (err == null) toast(okMsg) else AlertDialog.Builder(this)
                        .setTitle("Error al imprimir").setMessage(err).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ PÁGINA: IMPRIMIR
    private var printAlign = 0
    private var printBold = false

    private fun buildPrintPage(): View {
        val (sv, col) = Ui.page(this)

        val t = Ui.card(this)
        t.addView(Ui.label(this, "Texto", 17f, Ui.TEXT, true))
        val input = Ui.edit(this, "Escribe lo que quieres imprimir…", true)
        t.addView(input, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8))
        val alignSp = choiceSpinner(listOf("Izquierda", "Centro", "Derecha"), { printAlign }) { printAlign = it }
        t.addView(alignSp)
        t.addView(switchRow("Negrita", { printBold }) { printBold = it })
        t.addView(Ui.button(this, "Imprimir texto", true) {
            val txt = input.text.toString()
            if (txt.isBlank()) toast("Escribe algo primero") else {
                val a = printAlign
                val b = printBold
                runJob("Texto enviado") { EscPos.textJob(s, txt, a, b) }
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0))
        col.addView(t, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))

        val g = Ui.card(this)
        g.addView(Ui.label(this, "Imagen, QR y código de barras", 17f, Ui.TEXT, true))
        g.addView(Ui.button(this, "Elegir imagen de la galería") { pickImage() },
            Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8))
        val qr = Ui.edit(this, "Texto o enlace para el código QR")
        g.addView(qr)
        g.addView(Ui.button(this, "Imprimir QR") {
            val v = qr.text.toString().trim()
            if (v.isEmpty()) toast("Escribe el contenido del QR") else runJob("QR enviado") { EscPos.qrJob(s, v) }
        }, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 8))
        val bc = Ui.edit(this, "Texto para código de barras (Code 128)")
        g.addView(bc)
        g.addView(Ui.button(this, "Imprimir código de barras") {
            val v = bc.text.toString().trim()
            if (v.isEmpty()) toast("Escribe el contenido") else if (v.length > 24) toast("Máximo 24 caracteres") else {
                runJob("Código enviado") { EscPos.barcodeJob(s, v) }
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0))
        col.addView(g, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))

        val u = Ui.card(this)
        u.addView(Ui.label(this, "Herramientas", 17f, Ui.TEXT, true))
        u.addView(Ui.button(this, "Página de prueba", true) {
            runJob("Página de prueba enviada") { EscPos.testJob(s, hub.currentLabel) }
        }, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8))
        val r = Ui.row(this)
        r.addView(Ui.button(this, "Avanzar papel") { runJob("Papel avanzado") { EscPos.feedJob(s, 5) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
        r.addView(Ui.button(this, "Cortar") { runJob("Corte enviado") { EscPos.cutJob(s) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        u.addView(r)
        col.addView(u, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10))
        return sv
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        try {
            startActivityForResult(Intent.createChooser(i, "Elegir imagen"), REQ_IMAGE)
        } catch (e: Exception) {
            toast("No hay galería disponible")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runJob("Imagen enviada") {
                val bmp = decodeUri(uri, 2400) ?: throw IllegalStateException("No se pudo leer la imagen")
                EscPos.imageJob(s, bmp)
            }
        }
    }

    private fun decodeUri(uri: Uri, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    // ------------------------------------------------------------------ helpers de ajustes
    private fun spinnerAdapter(opts: List<String>): ArrayAdapter<String> {
        val ctx = this
        return object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, opts) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Ui.TEXT)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Ui.TEXT)
                v.setBackgroundColor(Color.parseColor("#14223F"))
                v.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
                return v
            }
        }
    }

    private fun choiceSpinner(opts: List<String>, get: () -> Int, set: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.background = Ui.glass(this, 14, 0x14FFFFFF, 0x33FFFFFF)
        sp.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        sp.adapter = spinnerAdapter(opts)
        sp.setSelection(get().coerceIn(0, opts.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != get()) set(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) { }
        }
        return sp
    }

    private fun switchRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val r = Ui.row(this)
        r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        r.addView(Ui.label(this, label, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        r.addView(sw)
        return r
    }

    private fun seekRow(label: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit,
                        fmt: (Int) -> String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val t = Ui.label(this, "$label: ${fmt(get())}", 14f)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = get().coerceIn(min, max) - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                t.text = "$label: ${fmt(p + min)}"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) { }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                set((bar?.progress ?: 0) + min)
            }
        })
        box.addView(t)
        box.addView(sb)
        box.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
        return box
    }

    private fun save() = Store.save(this, s)

    // ------------------------------------------------------------------ PÁGINA: AJUSTES
    private fun buildSettingsPage(): View {
        val (sv, col) = Ui.page(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val a = Ui.card(this)
        a.addView(Ui.label(this, "Papel e impresión", 17f, Ui.TEXT, true))
        a.addView(Ui.label(this, "Ancho de papel", 13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 8, 0, 4))
        val widths = listOf(384, 512, 576)
        a.addView(choiceSpinner(listOf("58 mm (384 puntos)", "72 mm (512 puntos)", "80 mm (576 puntos)"),
            { widths.indexOf(s.paperDots).coerceAtLeast(0) }) { s.paperDots = widths[it]; save() })
        a.addView(seekRow("Oscuridad", 0, 10, { s.density }, { s.density = it; save() }) { "$it/10" })
        a.addView(Ui.label(this, "Velocidad de envío", 13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 8, 0, 4))
        a.addView(choiceSpinner(listOf("Lenta (más segura)", "Normal", "Rápida", "Máxima"),
            { s.sendSpeed }) { s.sendSpeed = it; save() })
        a.addView(seekRow("Avance al terminar", 0, 10, { s.feedAfter }, { s.feedAfter = it; save() }) { "$it líneas" })
        a.addView(switchRow("Cortar papel al terminar", { s.cut }) { s.cut = it; save() })
        col.addView(a, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val b = Ui.card(this)
        b.addView(Ui.label(this, "Texto", 17f, Ui.TEXT, true))
        b.addView(Ui.label(this, "Codificación", 13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 8, 0, 4))
        b.addView(choiceSpinner(listOf("Windows-1252", "CP850 (recomendada)", "CP437", "ASCII sin acentos"),
            { s.encoding }) { s.encoding = it; save() })
        b.addView(Ui.label(this, "Tamaño de letra", 13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 8, 0, 4))
        b.addView(choiceSpinner(listOf("Normal (1x)", "Grande (2x)", "Muy grande (3x)", "Enorme (4x)"),
            { s.fontSize }) { s.fontSize = it; save() })
        b.addView(switchRow("Texto como imagen (acentos garantizados)", { s.textAsImage }) { s.textAsImage = it; save() })
        b.addView(switchRow("Suavizado de imágenes (dithering)", { s.dither }) { s.dither = it; save() })
        col.addView(b, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val c = Ui.card(this)
        c.addView(Ui.label(this, "Conexión", 17f, Ui.TEXT, true))
        c.addView(switchRow("Modo forzado (todos los métodos)", { s.force }) { s.force = it; save() })
        c.addView(seekRow("Reintentos", 1, 10, { s.retries }, { s.retries = it; save() }) { "$it" })
        c.addView(switchRow("Reconectar la última impresora al abrir", { s.autoConnect }) { s.autoConnect = it; save() })
        c.addView(switchRow("Enviar densidad/configuración (ESC 7) a la impresora", { s.applyOnConnect }) { s.applyOnConnect = it; save() })
        c.addView(switchRow("Comando extra de densidad (DC2 #)", { s.extraDensityCmd }) { s.extraDensityCmd = it; save() })
        c.addView(Ui.button(this, "Aplicar configuración a la impresora ahora", true) {
            runJob("Configuración aplicada") { EscPos.configJob(s) }
        }, Ui.lp(this, match, wrap, 0, 8, 0, 0))
        col.addView(c, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val d = Ui.card(this)
        d.addView(Ui.label(this, "Aplicación", 17f, Ui.TEXT, true))
        d.addView(Ui.label(this, "Versión ${Updater.currentVersionName(this)}", 13f, Ui.MUTED))
        d.addView(Ui.button(this, "Buscar actualización (GitHub)") { checkUpdate() },
            Ui.lp(this, match, wrap, 0, 8, 0, 8))
        d.addView(Ui.button(this, "Restablecer ajustes") {
            s.copyFrom(PrinterSettings())
            save()
            recreate()
        })
        col.addView(d, Ui.lp(this, match, wrap, 0, 0, 0, 10))
        return sv
    }

    private fun checkUpdate() {
        toast("Buscando actualización…")
        Updater.check(this) { r ->
            runOnUiThread {
                if (r.newer) {
                    AlertDialog.Builder(this)
                        .setTitle("Actualización disponible")
                        .setMessage(r.message + "\n\nSe descargará el APK; ábrelo para instalar.")
                        .setPositiveButton("Descargar") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } catch (e: Exception) { toast("No se pudo abrir el enlace") }
                        }
                        .setNegativeButton("Luego", null)
                        .show()
                } else {
                    toast(r.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------ PÁGINA: NOMBRE
    private var nameMethod = 0

    private fun validName(n: String): String? {
        val t = n.trim()
        return when {
            t.isEmpty() -> "Escribe el nombre"
            t.length > 20 -> "Máximo 20 caracteres"
            !t.all { it.code in 32..126 } -> "Solo letras, números y símbolos básicos (sin tildes)"
            else -> null
        }
    }

    private fun nameCommands(n: String, method: Int, custom: String): List<Pair<String, ByteArray>> {
        fun a(title: String, s: String) = Pair(title, s.toByteArray(Charsets.US_ASCII))
        val all = listOf(
            a("AT+NAME=…\\r\\n", "AT+NAME=$n\r\n"),
            a("AT+NAME=… (sin salto)", "AT+NAME=$n"),
            a("AT+BTNAME=…\\r\\n", "AT+BTNAME=$n\r\n"),
            a("AT+NAME… (pegado)", "AT+NAME$n\r\n"),
            a("AT+LENAME=… (BLE)", "AT+LENAME=$n\r\n"),
            a("AT+BNAME=…", "AT+BNAME=$n\r\n")
        )
        return when {
            method == 0 -> all
            method in 1..all.size -> listOf(all[method - 1])
            else -> {
                val txt = custom.replace("{NAME}", n).replace("\\r", "\r").replace("\\n", "\n")
                listOf(a("Personalizado", txt))
            }
        }
    }

    @Suppress("MissingPermission")
    private fun forgetBond() {
        val addr = if (Store.lastKind(this) == "BT") Store.lastA(this) else ""
        if (addr.isEmpty()) { toast("No hay una impresora Bluetooth reciente"); return }
        withBtPermission {
            try {
                val d = adapter()?.getRemoteDevice(addr)
                if (d == null) {
                    toast("Bluetooth no disponible")
                } else {
                    hub.disconnect()
                    val ok = d.javaClass.getMethod("removeBond").invoke(d) as? Boolean ?: false
                    toast(if (ok) "Emparejamiento olvidado. Apaga y enciende la impresora y busca de nuevo." else "No se pudo olvidar; hazlo en Ajustes > Bluetooth")
                    found.remove(addr)
                    renderBtList()
                }
            } catch (e: Exception) {
                toast("No se pudo olvidar: ${e.message}. Hazlo en Ajustes > Bluetooth")
            }
        }
    }

    private fun buildNamePage(): View {
        val (sv, col) = Ui.page(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val intro = Ui.card(this)
        intro.addView(Ui.label(this, "Nombre real de la impresora", 17f, Ui.TEXT, true))
        intro.addView(Ui.label(this,
            "Escribe el nombre Bluetooth directamente en el módulo de la impresora conectada, para que cualquier " +
                "equipo (como el Alco-Sensor) la vea con ese nombre, no solo este celular.\n\n" +
                "No existe un comando universal: cada fabricante usa uno distinto. Esta herramienta prueba los " +
                "métodos conocidos y muestra qué responde la impresora. Si responde «OK», funcionó. " +
                "Si todos dicen «sin respuesta», ese modelo no permite cambiar el nombre por software.",
            13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 6, 0, 0))
        col.addView(intro, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val n = Ui.card(this)
        n.addView(Ui.label(this, "1. Nombre nuevo", 15f, Ui.TEXT, true))
        val nameEdit = Ui.edit(this, "Nombre (máx. 20, sin tildes)", text = "MCP-7880")
        n.addView(nameEdit, Ui.lp(this, match, wrap, 0, 8, 0, 6))
        n.addView(Ui.label(this, "Método", 13f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 4, 0, 4))
        n.addView(choiceSpinner(
            listOf("Probar todos (recomendado)", "AT+NAME=… con salto", "AT+NAME=… sin salto", "AT+BTNAME=…",
                "AT+NAME… pegado", "AT+LENAME=… (BLE)", "AT+BNAME=…", "Personalizado"),
            { nameMethod }) { nameMethod = it })
        val custom = Ui.edit(this, "Personalizado, usa {NAME} y \\r \\n", text = "AT+NAME={NAME}\\r\\n")
        n.addView(custom, Ui.lp(this, match, wrap, 0, 8, 0, 6))
        n.addView(Ui.button(this, "Escribir nombre en la impresora", true) {
            val err = validName(nameEdit.text.toString())
            if (err != null) toast(err) else if (requireConnected()) {
                val nm = nameEdit.text.toString().trim()
                toast("Enviando…")
                hub.probe(nameCommands(nm, nameMethod, custom.text.toString())) { res ->
                    AlertDialog.Builder(this)
                        .setTitle("Respuesta de la impresora")
                        .setMessage(res + "\n\nSiguiente paso: apaga y enciende la impresora, olvida el emparejamiento y busca de nuevo para ver el nombre.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }, Ui.lp(this, match, wrap, 0, 8, 0, 0))
        col.addView(n, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val v = Ui.card(this)
        v.addView(Ui.label(this, "2. Comprobar", 15f, Ui.TEXT, true))
        v.addView(Ui.label(this, "Android guarda el nombre viejo en caché; hay que olvidar el emparejamiento.", 12f, Ui.MUTED))
        v.addView(Ui.button(this, "Consultar nombre actual (AT+NAME?)") {
            if (requireConnected()) hub.probe(listOf(
                Pair("AT+NAME?", "AT+NAME?\r\n".toByteArray()),
                Pair("AT+NAME", "AT+NAME\r\n".toByteArray()),
                Pair("AT+VERSION?", "AT+VERSION?\r\n".toByteArray())
            )) { res -> AlertDialog.Builder(this).setTitle("Respuesta").setMessage(res).setPositiveButton("OK", null).show() }
        }, Ui.lp(this, match, wrap, 0, 8, 0, 6))
        v.addView(Ui.button(this, "Olvidar emparejamiento de la impresora") { forgetBond() })
        col.addView(v, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val h = Ui.card(this)
        h.addView(Ui.label(this, "Importante", 15f, Ui.TEXT, true))
        h.addView(Ui.label(this,
            "• Cambiar el nombre no cambia el protocolo: el Alco-Sensor puede aceptar la impresora por nombre y aun así imprimir mal si no entiende el formato. Pruébalo con una impresión real.\n" +
                "• Algunas impresoras solo aceptan comandos AT en «modo AT» (por ejemplo, encenderlas con el botón FEED presionado). Si todo da «sin respuesta», prueba eso.\n" +
                "• Si no hay respuesta en ningún método, copia el registro (pestaña Avanzado) y dime el modelo exacto: se puede buscar su comando propio.",
            12f, Ui.MUTED), Ui.lp(this, match, wrap, 0, 6, 0, 0))
        col.addView(h, Ui.lp(this, match, wrap, 0, 0, 0, 10))
        return sv
    }

    // ------------------------------------------------------------------ PÁGINA: AVANZADO
    private fun parseHex(t: String): ByteArray? {
        val clean = t.replace("0x", "", ignoreCase = true).filter { it.isLetterOrDigit() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun buildAdvancedPage(): View {
        val (sv, col) = Ui.page(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val a = Ui.card(this)
        a.addView(Ui.label(this, "Comandos ESC/POS en hexadecimal", 17f, Ui.TEXT, true))
        val hex = Ui.edit(this, "Ej: 1B 40 1B 61 01")
        a.addView(hex, Ui.lp(this, match, wrap, 0, 8, 0, 6))
        a.addView(Ui.button(this, "Enviar bytes", true) {
            val bytes = parseHex(hex.text.toString())
            if (bytes == null) toast("Hex inválido (usa pares: 1B 40 …)") else {
                runJob("Bytes enviados (${bytes.size})") { bytes }
            }
        })
        a.addView(Ui.label(this, "Autotest de fábrica (prueba distintos modelos)", 13f, Ui.MUTED),
            Ui.lp(this, match, wrap, 0, 10, 0, 4))
        val r = Ui.row(this)
        r.addView(Ui.button(this, "Autotest 1") { runJob("Autotest enviado") { byteArrayOf(0x12, 0x54) } },
            LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
        r.addView(Ui.button(this, "Autotest 2") {
            runJob("Autotest enviado") { byteArrayOf(0x1D, 0x28, 0x41, 0x02, 0x00, 0x00, 0x02) }
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
        r.addView(Ui.button(this, "Reiniciar") { runJob("Reinicio enviado") { byteArrayOf(0x1B, 0x40) } },
            LinearLayout.LayoutParams(0, wrap, 1f))
        a.addView(r)
        col.addView(a, Ui.lp(this, match, wrap, 0, 0, 0, 10))

        val l = Ui.card(this)
        l.addView(Ui.label(this, "Registro", 17f, Ui.TEXT, true))
        logView = Ui.label(this, "", 11f, Ui.MUTED)
        logView.typeface = android.graphics.Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        logScroll = ScrollView(this)
        logScroll.addView(logView)
        l.addView(logScroll, Ui.lp(this, match, Ui.dp(this, 260), 0, 8, 0, 8))
        val lr = Ui.row(this)
        lr.addView(Ui.button(this, "Copiar") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Nioro Print", hub.logText()))
            toast("Registro copiado")
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        lr.addView(Ui.space(this, 1), LinearLayout.LayoutParams(Ui.dp(this, 8), 1))
        lr.addView(Ui.button(this, "Limpiar") { hub.clearLog() }, LinearLayout.LayoutParams(0, wrap, 1f))
        l.addView(lr)
        col.addView(l, Ui.lp(this, match, wrap, 0, 0, 0, 10))
        return sv
    }
}
