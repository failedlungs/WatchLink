package app.watchlink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URL
import java.util.*
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
object Watch {
    lateinit var p: SharedPreferences
    private lateinit var ctx: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var gatt: BluetoothGatt? = null
    private var cmd: BluetoothGattCharacteristic? = null
    private var ack: CompletableDeferred<Unit>? = null
    private var ackOp = -1
    private var synced = false
    private var off = false

    var connected by mutableStateOf(false)
    var status by mutableStateOf("Disconnected")
    var battery by mutableIntStateOf(-1)
    var fw by mutableStateOf("")
    var steps by mutableIntStateOf(0)
    var hr by mutableIntStateOf(0)
    var hrOn by mutableStateOf(false)
    var hrStat by mutableStateOf("")
    var bp by mutableStateOf("")
    var bpBusy by mutableStateOf(false)
    var wx by mutableStateOf(listOf<Int>())
    var theme by mutableIntStateOf(-1)

    private val L = listOf(
        "Steps|km|kcal|bpm|Training|Skipping|Swimming|Message|Function|Dial|Info|Reset|Off|hang up|Version|Find me|Cycling|Table tennis|Badminton|Tennis|Status|Heart|Bp|Weather|Running|Stopwatch|Bright|Function|Find|Finish?|Yes|No|Blood oxygen",
        "Schritte|km|kcal|bpm|Sport|Seilhüpfen|Schwimmen|Nachrichten|Mehr|Wählen|Info|Rücksetzung|Auschalten|lange drücken|Version|Finde mich|Radfahren|Tischtennis|Badminton|Tennis|Zustand m|herzfrequenz|blutdruck|Wetter|Laufen|stoppuhr|helligkeit|Funktion|Handy suchen|Ende Sie die aktuelle Bewegung am?|ist|Nein|Blutsauerstoff",
        "Langkah|Km|Kalori|Bpm|Latihan|Lompat tali|Berenang|Pesan|Fungsi|Ganti antarmuka utama|Informasi produk|Atur Ulang|Matikan|Tekan lama dan tolak|Versi|Cari ponsel|Bersepeda|Bola tenis meja|Bulu tangkis|Tenis|keadaan|Detak jantung|Tekanan darah|Cuaca|Menjalankan|Stopwatch|Kecerahan|Fitur lainnya|Mencari|Akhiri latihan saat ini?|ya|Tidak|Oksigen darah"
    )
    private val LANG_CODE = intArrayOf(2, 5, 0x13)

    private fun u(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        p = ctx.getSharedPreferences("w", 0)
        theme = p.getInt("theme", -1)
        loadWx(); refreshSteps(); hrRec(0)
    }

    fun put(k: String, v: Any) {
        val e = p.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is String -> e.putString(k, v) }
        e.apply()
    }

    // ---------- packets ----------
    fun time(): ByteArray {
        val c = Calendar.getInstance(); val y = c[Calendar.YEAR]
        return b(0xA3, y shr 8, y and 255, c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH], c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], c[Calendar.SECOND])
    }
    fun a9() = hex("a900aa003c" + "%02x".format(5 + 5 * p.getInt("sec", 0)) + "00002710" +
            (if (p.getBoolean("raise", true)) "01" else "00") + "ff00140100020100")
    fun d7(): ByteArray {
        val f = (if (p.getBoolean("rej", false)) 8 else 0) or (if (p.getBoolean("dvib", false)) 2 else 0) or
                (if (p.getBoolean("dmsg", false)) 4 else 0)
        val s = p.getInt("dS", 1320); val e = p.getInt("dE", 480)
        return b(0xD7, f, s / 60, s % 60, e / 60, e % 60, if (p.getBoolean("don", false)) 1 else 0)
    }
    fun d6() = b(0xD6, if (p.getInt("hrm", 0) == 0) 2 else 1)
    fun al(i: Int): List<Int> = p.getString("al$i", "12,0,62,0")!!.split(",").map { it.toInt() }
    fun alarm(i: Int): ByteArray {
        val a = al(i)
        return b(0xAB, a[2], a[0], a[1]) + (if (a[3] == 1) b(2, 0x0A, 2, 0) else b(0, 0, 0, 0)) + b(i + 1)
    }
    fun setAl(i: Int, a: List<Int>) { put("al$i", a.joinToString(",")); go(alarm(i)) }

    private fun langPkts(i: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        L[i].split("|").forEachIndexed { k, s ->
            val d = s.toByteArray(Charsets.UTF_16BE)
            out += b(0xAF, 0, LANG_CODE[i], k + 1, d.size) + d.copyOfRange(0, minOf(15, d.size))
            var o = 15; var q = 1
            while (o < d.size) { out += b(0xAF, q) + d.copyOfRange(o, minOf(o + 18, d.size)); o += 18; q++ }
            out += b(0xAF, 0xFD)
        }
        return out
    }
    fun setLang(i: Int) { put("lang", i); scope.launch { seq(listOf(hex("afaa")) + langPkts(i), false) } }

    fun msg(type: Int, text: String, buzz: Boolean = true) {
        scope.launch {
            val truncatedText = text.take(77)
            val d = truncatedText.toByteArray(Charsets.UTF_16BE)
            val l = mutableListOf(b(0xC5, 0, type, d.size) + d.copyOfRange(0, minOf(16, d.size)))
            var o = 16; var q = 1
            while (o < d.size) { l += b(0xC5, q) + d.copyOfRange(o, minOf(o + 18, d.size)); o += 18; q++ }
            l += b(0xC5, 0xFD)
            seq(l)
            if (buzz) {
                delay(200)
                send(hex("ab00000001010000"))
            }
        }
    }

    // ---------- send ----------
    private suspend fun sendRaw(x: ByteArray, wait: Boolean = true): Boolean {
        val g = gatt ?: return false
        val c = cmd ?: return false
        val w = CompletableDeferred<Unit>(); ack = w; ackOp = x[0].toInt() and 255
        if (g.writeCharacteristic(c, x, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS) return false
        if (!wait) return true
        return try { withTimeout(3000) { w.await() }; delay(50); true } catch (e: Exception) { false }
    }
    suspend fun send(x: ByteArray) = lock.withLock { sendRaw(x) }
    suspend fun seq(l: List<ByteArray>, strict: Boolean = true): Boolean = lock.withLock {
        for (x in l) if (!sendRaw(x) && strict) return@withLock false
        true
    }
    fun go(x: ByteArray) { scope.launch { send(x) } }
    fun reset() { scope.launch { lock.withLock { sendRaw(hex("ad"), false) } } }

    private suspend fun sync() {
        for (x in listOf(time(), hex("a1"), a9(), hex("a2"))) send(x)
        for (i in 0..2) send(alarm(i))
        send(d6()); send(d7()); pushWx()
        updateWeather()
    }

    // ---------- live features ----------
    fun hrToggle() {
        if (hrOn) { hrOn = false; go(hex("e500")) } else { hrOn = true; hr = 0; go(hex("e511")) }
    }
    fun bpStart() {
        bpBusy = true; bp = "Measuring…"; go(hex("c711"))
        scope.launch { delay(70000); if (bpBusy) { bpBusy = false; bp = "No result" } }
    }

    private fun onData(v: ByteArray) {
        val op = v[0].toInt() and 255
        if (ackOp == op) ack?.complete(Unit)
        fun at(i: Int) = v[i].toInt() and 255
        when (op) {
            0x1c -> if (!synced) { synced = true; scope.launch { sync() } }
            0xa2 -> if (v.size >= 2) battery = at(1)
            0xa1 -> if (v.size > 2) fw = String(v, 1, v.size - 1, Charsets.US_ASCII)
            0xb1 -> if (v.size >= 8) {
                val key = "st%04d%02d%02d_%02d".format((at(1) shl 8) or at(2), at(3), at(4), at(5))
                val n = (at(6) shl 8) or at(7)
                if (n > p.getInt(key, 0)) put(key, n)
                refreshSteps()
            }
            0xe5 -> if (v.size >= 4) {
                if (at(2) == 0 && at(3) > 0) { hr = at(3); hrRec(at(3)) }
                if (at(1) == 0) hrOn = false
            } else if (at(1) == 0) hrOn = false
            0xc7 -> if (v.size >= 5) {
                if (at(2) == 0) { bp = "${at(3)}/${at(4)}"; bpBusy = false }
                else if (at(2) == 0xff) { bp = "No result"; bpBusy = false }
            }
        }
    }

    private fun refreshSteps() {
        val c = Calendar.getInstance()
        val pre = "st%04d%02d%02d_".format(c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH])
        steps = (0..23).sumOf { p.getInt(pre + "%02d".format(it), 0) }
    }

    private fun hrRec(v: Int) {
        val c = Calendar.getInstance(); val d = c[Calendar.YEAR] * 1000 + c[Calendar.DAY_OF_YEAR]
        val s = p.getString("hrd", "")!!.split(",").map { it.toIntOrNull() ?: 0 }
        val t = if (s.size == 5 && s[0] == d) s else listOf(d, 999, 0, 0, 0)
        val r = if (v > 0) listOf(d, minOf(t[1], v), maxOf(t[2], v), t[3] + v, t[4] + 1) else t
        if (v > 0) put("hrd", r.joinToString(","))
        if (r[4] > 0) hrStat = "min ${r[1]} · avg ${r[3] / r[4]} · max ${r[2]}"
    }

    // ---------- weather ----------
    private fun loadWx() {
        wx = p.getString("wx", "")!!.split(",").mapNotNull { it.toIntOrNull() }
    }
    private suspend fun pushWx() {
        val w = wx; if (w.size < 15) return
        val c = p.getString("cond", "3")?.toIntOrNull() ?: 3
        fun d(i: Int) = b(c, 0, w[1 + 2 * i], w[2 + 2 * i])
        seq(listOf(
            b(0xCB, 1, c, 0, w[0], w[1], w[2]) + hex("000600190000000000000000"),
            b(0xCB, 2) + d(1) + d(2) + d(3) + d(4),
            b(0xCB, 3) + d(5) + d(6)
        ))
    }
    fun updateWeather() {
        val la = p.getString("lat", "")!!.trim(); val lo = p.getString("lon", "")!!.trim()
        if (la.isEmpty() || lo.isEmpty()) { pushNow(); return }
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                
                val j = JSONObject(response)
                val mx = j.getJSONObject("daily").getJSONArray("temperature_2m_max")
                val mn = j.getJSONObject("daily").getJSONArray("temperature_2m_min")
                val now = j.getJSONObject("current").getDouble("temperature_2m").roundToInt()
                val all = listOf(now) + (0..6).flatMap { listOf(mx.getDouble(it).roundToInt(), mn.getDouble(it).roundToInt()) }
                put("wx", all.joinToString(",")); loadWx()
            } catch (e: Exception) { }
            pushWx()
        }
    }
    private fun pushNow() { scope.launch { pushWx() } }

    // ---------- connection ----------
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) { status = "Discovering…"; g.discoverServices() }
            else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; cmd = null; hrOn = false; bpBusy = false; synced = false
                ack?.cancel()
                g.close(); gatt = null
                if (off) status = "Disconnected" else { status = "Waiting for watch…"; open(true) }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            val ch = g.services.firstNotNullOfOrNull { it.getCharacteristic(u("33f1")) }
            val n = g.services.firstNotNullOfOrNull { it.getCharacteristic(u("33f2")) }
            if (ch == null || n == null) { status = "Watch service not found"; return }
            cmd = ch
            g.setCharacteristicNotification(n, true)
            g.writeDescriptor(n.getDescriptor(u("2902")), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) {
            connected = true; status = "Connected"
            scope.launch { delay(2000); if (!synced) { synced = true; sync() } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
            if (v.isNotEmpty()) onData(v)
        }
    }

    private fun open(auto: Boolean) {
        val addr = p.getString("addr", "")!!.trim().uppercase()
        if (addr.isEmpty()) {
            status = "Please enter Watch MAC Address"
            return
        }
        try {
            val bm = ctx.getSystemService(BluetoothManager::class.java)
            val d = bm.adapter.getRemoteDevice(addr)
            gatt = d.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE)
            if (!auto) status = "Connecting…"
        } catch (e: Exception) { status = "Error: ${e.message}" }
    }
    fun ensure() { if (gatt == null) { off = false; open(false) } }
    fun reconnect() {
        off = false; gatt?.let { it.disconnect(); it.close() }; gatt = null
        connected = false; cmd = null; synced = false; open(false)
    }
    fun disconnect() { off = true; if (gatt == null) status = "Disconnected" else gatt?.disconnect() }
}
