@file:OptIn(ExperimentalMaterial3Api::class)

package app.watchlink

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Watch.init(this)
        enableEdgeToEdge()
        val rq = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Watch.ensure() }
        rq.launch(Manifest.permission.BLUETOOTH_CONNECT)
        setContent { App() }
    }
}

val HUES = floatArrayOf(215f, 145f, 0f, 285f, 28f, 175f, 330f)

fun scheme(h: Float, d: Boolean): ColorScheme {
    fun c(s: Float, l: Float) = Color.hsl(h, s, l)
    val t = Color.hsl((h + 60) % 360, if (d) .6f else .45f, if (d) .78f else .40f)
    return if (!d) lightColorScheme(
        primary = c(.55f, .40f), onPrimary = Color.White, primaryContainer = c(.65f, .88f), onPrimaryContainer = c(.7f, .12f),
        secondary = c(.25f, .40f), onSecondary = Color.White, secondaryContainer = c(.35f, .88f), onSecondaryContainer = c(.4f, .12f),
        tertiary = t, background = c(.25f, .98f), onBackground = c(.2f, .1f), surface = c(.25f, .98f), onSurface = c(.2f, .1f),
        surfaceVariant = c(.3f, .92f), onSurfaceVariant = c(.2f, .3f), outline = c(.1f, .5f))
    else darkColorScheme(
        primary = c(.7f, .78f), onPrimary = c(.8f, .15f), primaryContainer = c(.5f, .28f), onPrimaryContainer = c(.7f, .9f),
        secondary = c(.3f, .75f), onSecondary = c(.4f, .15f), secondaryContainer = c(.25f, .28f), onSecondaryContainer = c(.4f, .9f),
        tertiary = t, background = c(.2f, .08f), onBackground = c(.1f, .9f), surface = c(.2f, .08f), onSurface = c(.1f, .9f),
        surfaceVariant = c(.2f, .18f), onSurfaceVariant = c(.1f, .8f), outline = c(.1f, .55f))
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val ctx = LocalContext.current
    val th = Watch.theme
    val cs = if (th < 0) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else scheme(HUES[th], dark)
    MaterialTheme(colorScheme = cs, content = content)
}

@Composable
fun App() = Theme {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sub by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(sub != 0) { sub = 0 }
    Scaffold(bottomBar = {
        if (sub == 0) NavigationBar {
            listOf("Home" to "⌂", "Features" to "☰", "Device" to "⚙").forEachIndexed { i, (n, ic) ->
                NavigationBarItem(tab == i, { tab = i }, { Text(ic, fontSize = 20.sp) }, label = { Text(n) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).statusBarsPadding()) {
            when {
                sub == 1 -> AlarmsPage { sub = 0 }
                sub == 2 -> AppsPage { sub = 0 }
                sub == 3 -> MusicPage { sub = 0 }
                tab == 0 -> Home()
                tab == 1 -> Features { sub = it }
                else -> Device()
            }
        }
    }
}

// ---------- building blocks ----------
@Composable
fun Pg(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

@Composable
fun Cd(mod: Modifier = Modifier, c: @Composable ColumnScope.() -> Unit) =
    Surface(mod.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = c)
    }

@Composable
fun Sub(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Hd(t: String, back: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    TextButton(back) { Text("‹ Back") }
    Text(t, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun Sw(t: String, sub: String?, v: Boolean, f: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { f(!v) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.bodyLarge); if (sub != null) Sub(sub) }
        Switch(v, f)
    }

@Composable
fun SwP(t: String, k: String, d: Boolean, sub: String? = null, after: () -> Unit = {}) {
    var v by remember { mutableStateOf(Watch.p.getBoolean(k, d)) }
    Sw(t, sub, v) { v = it; Watch.put(k, it); after() }
}

@Composable
fun Seg(o: List<String>, k: String, f: (Int) -> Unit) {
    var s by remember { mutableIntStateOf(Watch.p.getInt(k, 0)) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        o.forEachIndexed { i, t ->
            SegmentedButton(s == i, { s = i; Watch.put(k, i); f(i) }, SegmentedButtonDefaults.itemShape(i, o.size)) { Text(t, maxLines = 1) }
        }
    }
}

@Composable
fun Fld(label: String, k: String, d: String = "") {
    var v by remember { mutableStateOf(Watch.p.getString(k, d)!!) }
    OutlinedTextField(v, { v = it; Watch.put(k, it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
fun TimeDlg(min: Int, ok: (Int) -> Unit, no: () -> Unit) {
    val s = rememberTimePickerState(min / 60, min % 60, true)
    AlertDialog(onDismissRequest = no,
        confirmButton = { TextButton({ ok(s.hour * 60 + s.minute) }) { Text("OK") } },
        dismissButton = { TextButton(no) { Text("Cancel") } },
        text = { TimePicker(s) })
}

@Composable
fun Nav(t: String, s: String, f: () -> Unit) = Cd(Modifier.clip(RoundedCornerShape(28.dp)).clickable(onClick = f)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.titleMedium); Sub(s) }
        Text("›", style = MaterialTheme.typography.headlineMedium)
    }
}

fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

// ---------- page 1 ----------
@Composable
fun Home() = Pg {
    val w = Watch
    Text(if (w.connected) "Connected" + (if (w.battery >= 0) " · ${w.battery}%" else "") else w.status,
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Cd {
        Text("Weather", style = MaterialTheme.typography.titleMedium)
        if (w.wx.size >= 3) {
            Text("${w.wx[0]}°", style = MaterialTheme.typography.displayMedium)
            Text("High ${w.wx[1]}° · Low ${w.wx[2]}°")
        } else Sub("Set latitude and longitude on the Device tab")
        TextButton({ w.updateWeather() }) { Text("Update") }
    }
    Cd {
        Text("Steps today", style = MaterialTheme.typography.titleMedium)
        Text("${w.steps}", style = MaterialTheme.typography.displayMedium)
    }
    Cd {
        Text("Heart rate", style = MaterialTheme.typography.titleMedium)
        Text(if (w.hr > 0) "${w.hr} bpm" else "— bpm", style = MaterialTheme.typography.displayMedium)
        if (w.hrStat.isNotEmpty()) Sub("Today: " + w.hrStat)
        Button({ w.hrToggle() }, enabled = w.connected) { Text(if (w.hrOn) "Stop" else "Measure") }
    }
    Cd {
        Text("Blood pressure", style = MaterialTheme.typography.titleMedium)
        Text(if (w.bp.isEmpty()) "—/— mmHg" else w.bp, style = MaterialTheme.typography.displayMedium)
        Button({ w.bpStart() }, enabled = w.connected && !w.bpBusy) { Text(if (w.bpBusy) "Measuring…" else "Measure") }
    }
}

// ---------- page 2 ----------
@Composable
fun Features(go: (Int) -> Unit) = Pg {
    Cd {
        Text("Find watch", style = MaterialTheme.typography.titleMedium)
        Sub("Makes the watch vibrate")
        Button({ Watch.go(Watch.hex("ab00000001020701")) }, enabled = Watch.connected) { Text("Find") }
    }
    Cd {
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Sub("Screen-on time")
        Seg(listOf("5 s", "10 s", "15 s"), "sec") { Watch.go(Watch.a9()) }
        SwP("Raise hand to wake", "raise", true) { Watch.go(Watch.a9()) }
    }
    Cd {
        Text("Calls", style = MaterialTheme.typography.titleMedium)
        Sub("Manage how the watch handles incoming phone calls")
        SwP("Reject call with key", "rej", false, "Press the side button on the watch to reject an incoming call") { Watch.go(Watch.d7()) }
    }
    Cd {
        Text("Heart rate monitoring", style = MaterialTheme.typography.titleMedium)
        Sub("Choose how frequently the sensor measures your heart rate.")
        Seg(listOf("Real-time", "Static"), "hrm") { Watch.go(Watch.d6()) }
    }
    Cd {
        Text("Do not disturb", style = MaterialTheme.typography.titleMedium)
        var s by remember { mutableIntStateOf(Watch.p.getInt("dS", 1320)) }
        var e by remember { mutableIntStateOf(Watch.p.getInt("dE", 480)) }
        var ed by remember { mutableIntStateOf(0) }
        SwP("Quiet period", "don", false, "Raise-to-wake, vibration and notices are off in this period") { Watch.go(Watch.d7()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ ed = 1 }) { Text("From " + fmt(s)) }
            OutlinedButton({ ed = 2 }) { Text("To " + fmt(e)) }
        }
        SwP("Turn off band vibration", "dvib", false) { Watch.go(Watch.d7()) }
        SwP("Turn off message reminders", "dmsg", false) { Watch.go(Watch.d7()) }
        if (ed != 0) TimeDlg(if (ed == 1) s else e, { m ->
            if (ed == 1) { s = m; Watch.put("dS", m) } else { e = m; Watch.put("dE", m) }
            ed = 0; Watch.go(Watch.d7())
        }, { ed = 0 })
    }
    Cd {
        Text("Language", style = MaterialTheme.typography.titleMedium)
        Sub("Sending the menu text takes a few seconds")
        Seg(listOf("English", "Deutsch", "Indonesia"), "lang") { Watch.setLang(it) }
    }
    Nav("Alarms", "Three alarms with days") { go(1) }
    Nav("Notifications", "Choose apps") { go(2) }
    Nav("Music", "Configure track updates") { go(3) }
}

@Composable
fun AlarmsPage(back: () -> Unit) = Pg {
    Hd("Alarms", back)
    for (i in 0..2) {
        var a by remember { mutableStateOf(Watch.al(i)) }
        var ed by remember { mutableStateOf(false) }
        fun save(n: List<Int>) { a = n; Watch.setAl(i, n) }
        Cd {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d:%02d".format(a[0], a[1]), style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.weight(1f).clickable { ed = true })
                Switch(a[3] == 1, { on -> save(a.toMutableList().also { l -> l[3] = if (on) 1 else 0 }) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                "SMTWTFS".forEachIndexed { d, ch ->
                    val bit = 1 shl d
                    FilterChip((a[2] and bit) != 0, { save(a.toMutableList().also { l -> l[2] = l[2] xor bit }) }, { Text("$ch") })
                }
            }
        }
        if (ed) TimeDlg(a[0] * 60 + a[1], { m -> ed = false; save(a.toMutableList().also { l -> l[0] = m / 60; l[1] = m % 60 }) }, { ed = false })
    }
}

@Composable
fun AppsPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loadedApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
            apps = loadedApps
        }
    }
    var sel by remember { mutableStateOf(Watch.p.getStringSet("apps", emptySet())!!.toSet()) }
    var q by remember { mutableStateOf("") }
    val on = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Hd("Notifications", back)
        if (!on) Cd {
            Text("Notification access is off", style = MaterialTheme.typography.titleMedium)
            Button({ ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open settings") }
        }
        OutlinedTextField(q, { q = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(apps.filter { it.second.contains(q, true) }, key = { it.first }) { (pkg, name) ->
                Row(Modifier.fillMaxWidth().clickable {
                    val n = if (pkg in sel) sel - pkg else sel + pkg
                    sel = n; Watch.p.edit().putStringSet("apps", n).apply()
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pkg in sel, null)
                    Spacer(Modifier.width(12.dp))
                    Text(name)
                }
            }
        }
    }
}

@Composable
fun MusicPage(back: () -> Unit) {
    val ctx = LocalContext.current
    val on = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hd("Music / Now Playing", back)
        if (!on) Cd {
            Text("Notification access is off", style = MaterialTheme.typography.titleMedium)
            Sub("Notification access is required to read active music playback.")
            Button({ ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open settings") }
        }
        Cd {
            Text("Music Track Synchronization", style = MaterialTheme.typography.titleMedium)
            Sub("Sends song title and artist to your watch whenever music is playing. This update is completely silent and does not vibrate the watch.")
            SwP("Enable Now Playing", "np", true)
        }
        Cd {
            Text("Automatic Detection", style = MaterialTheme.typography.titleMedium)
            Sub("Android manages music players via media sessions. Any playing music player will be detected automatically without needing to select it from an app list.")
        }
    }
}

// ---------- page 3 ----------
@Composable
fun Device() = Pg {
    val w = Watch
    var confirm by remember { mutableStateOf(false) }
    Cd {
        Text("Watch", style = MaterialTheme.typography.headlineSmall)
        Text(if (w.connected) "Connected" else w.status, color = MaterialTheme.colorScheme.primary)
        Sub("Battery: " + (if (w.battery >= 0) "${w.battery}%" else "—") + (if (w.fw.isNotEmpty()) " · Firmware ${w.fw}" else ""))
        Fld("Watch address", "addr", "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ w.reconnect() }) { Text("Reconnect") }
            OutlinedButton({ w.disconnect() }) { Text("Disconnect") }
        }
        FilledTonalButton({ w.go(w.time()) }, enabled = w.connected) { Text("Sync time") }
    }
    Cd {
        Text("Send message", style = MaterialTheme.typography.titleMedium)
        var t by remember { mutableIntStateOf(4) }
        var m by remember { mutableStateOf("") }
        var buzz by remember { mutableStateOf(true) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Call" to 0, "QQ" to 1, "WeChat" to 2, "News" to 4).forEach { (n, v) ->
                FilterChip(t == v, { t = v }, { Text(n) })
            }
        }
        OutlinedTextField(m, { m = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
        Sw("Vibrate", null, buzz) { buzz = it }
        Button({ w.msg(t, m, buzz) }, enabled = w.connected && m.isNotBlank()) { Text("Send") }
    }
    Cd {
        Text("Weather location", style = MaterialTheme.typography.titleMedium)
        Fld("Latitude", "lat")
        Fld("Longitude", "lon")
        Fld("Weather icon code", "cond", "3")
        Button({ w.updateWeather() }, enabled = w.connected) { Text("Update weather") }
    }
    Cd {
        Text("Color", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(w.theme < 0, { w.theme = -1; w.put("theme", -1) }, { Text("System") })
            HUES.forEachIndexed { i, h ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.hsl(h, .55f, .5f))
                    .border(if (w.theme == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { w.theme = i; w.put("theme", i) })
            }
        }
    }
    OutlinedButton({ confirm = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        enabled = w.connected) { Text("Factory reset watch") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Reset the watch?") }, text = { Text("This erases the data on the watch.") },
        confirmButton = { TextButton({ confirm = false; w.reset() }) { Text("Reset") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
