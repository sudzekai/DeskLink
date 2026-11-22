package dev.sudzekai.desklink

import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("desklink", MODE_PRIVATE) }
    private var lastTouch by mutableLongStateOf(System.currentTimeMillis())
    private var status by mutableStateOf("Не подключено")
    private var lastConnectedAt by mutableLongStateOf(0L)
    private var metrics by mutableStateOf(JSONObject())
    private var media by mutableStateOf(JSONObject())
    private var commands by mutableStateOf(listOf<RemoteCommand>())
    private var host by mutableStateOf("127.0.0.1")
    private var key by mutableStateOf("")
    private var deviceId by mutableStateOf("primary")
    private var bluetoothAddress by mutableStateOf("")
    private var pairedDevices by mutableStateOf(listOf<Pair<String, String>>())
    private var ambientEnabled by mutableStateOf(true)
    private var ambient by mutableStateOf(false)
    private var idleMinutes by mutableIntStateOf(5)
    private var ambientBrightness by mutableFloatStateOf(0.01f)
    private var telemetrySeconds by mutableIntStateOf(15)
    private var preferredHz by mutableFloatStateOf(10f)
    private var pixelShift by mutableStateOf(true)
    private var keepScreen by mutableStateOf(true)
    private var shift by mutableIntStateOf(0)
    private lateinit var iconCodes: JSONObject
    private var cpuWarning by mutableIntStateOf(70)
    private var cpuCritical by mutableIntStateOf(85)
    private var gpuWarning by mutableIntStateOf(75)
    private var gpuCritical by mutableIntStateOf(90)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refreshPairedDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        hideSystemBars()
        host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1"
        key = preferences.getString("key", "") ?: ""
        deviceId = preferences.getString("deviceId", "primary") ?: "primary"
        bluetoothAddress = preferences.getString("bluetooth", "") ?: ""
        ambientEnabled = preferences.getBoolean("ambient", true)
        idleMinutes = preferences.getInt("idleMinutes", 5)
        ambientBrightness = preferences.getFloat("brightness", 0.01f)
        telemetrySeconds = preferences.getInt("telemetrySeconds", 15)
        preferredHz = preferences.getFloat("preferredHz", 10f)
        pixelShift = preferences.getBoolean("pixelShift", true)
        keepScreen = preferences.getBoolean("keepScreen", true)
        iconCodes = JSONObject(assets.open("bootstrap-icons.json").bufferedReader().use { it.readText() })
        cpuWarning = preferences.getInt("cpuWarning", 70)
        cpuCritical = preferences.getInt("cpuCritical", 85)
        gpuWarning = preferences.getInt("gpuWarning", 75)
        gpuCritical = preferences.getInt("gpuCritical", 90)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            refreshPairedDevices()
        setContent { DeskLinkScreen() }
    }

    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouch = System.currentTimeMillis()
            if (ambient) ambient = false
        }
        return super.dispatchTouchEvent(event)
    }

    @android.annotation.SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun refreshPairedDevices() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        pairedDevices = adapter.bondedDevices.map { it.name.orEmpty() to it.address }
    }

    private fun client() = AgentClient(this, host.trim(), key.trim(), deviceId.trim(), bluetoothAddress)

    private suspend fun refresh() {
        try {
            val response = client().request("telemetry")
            if (!response.optBoolean("ok")) error(response.optString("error"))
            metrics = response.getJSONObject("data")
            status = "Подключено"
            lastConnectedAt = System.currentTimeMillis()
            if (!ambient) {
                val mediaResponse = client().request("media")
                if (mediaResponse.optBoolean("ok")) media = mediaResponse.getJSONObject("data")
                val commandResponse = client().request("commands")
                if (commandResponse.optBoolean("ok")) {
                    val array = commandResponse.getJSONArray("data")
                    commands = (0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)
                        RemoteCommand(item.getString("Id"), item.getString("Name"), item.optString("Icon"))
                    }
                }
            }
        } catch (exception: Exception) {
            status = if (lastConnectedAt == 0L) "Поиск компьютера" else if (System.currentTimeMillis() - lastConnectedAt < 60_000) "Переподключение" else "ПК недоступен"
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DeskLinkScreen() {
        val pages = listOf("Мониторинг", "Мультимедиа", "Команды", "Настройки")
        val pager = rememberPagerState(pageCount = { pages.size })
        val coroutineScope = rememberCoroutineScope()
        val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        LaunchedEffect(Unit) {
            while (true) {
                ambient = ambientEnabled && System.currentTimeMillis() - lastTouch >= idleMinutes * 60_000L
                refresh()
                delay(if (ambient) telemetrySeconds * 1000L else 3_000)
            }
        }
        LaunchedEffect(ambient, pixelShift) {
            while (ambient && pixelShift) {
                shift = (shift + 1) % 5
                delay(60_000)
            }
        }
        DisposableEffect(ambient, ambientBrightness, preferredHz, keepScreen) {
            val attributes = window.attributes
            attributes.screenBrightness = if (ambient) ambientBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            val modes = display?.supportedModes.orEmpty()
            val chosen = modes.minByOrNull { kotlin.math.abs(it.refreshRate - preferredHz) }
            attributes.preferredDisplayModeId = if (ambient) chosen?.modeId ?: 0 else 0
            attributes.preferredRefreshRate = if (ambient) chosen?.refreshRate ?: 0f else 0f
            window.attributes = attributes
            if (keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { }
        }
        MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F1116), surface = Color(0xFF202630), primary = Color(0xFF82B8FF))) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (ambient) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().clickable { ambient = false }, contentAlignment = Alignment.Center) {
                    Box(Modifier.offset(x = (shift * 2).dp, y = (shift * 2).dp)) { Monitoring(metrics, true) }
                }
            } else {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    AnimatedContent(targetState = pager.currentPage, transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        slideInHorizontally(tween(220)) { it * direction } togetherWith slideOutHorizontally(tween(220)) { -it * direction }
                    }, label = "sectionTitle") { page ->
                        Text(pages[page], Modifier.padding(start = 16.dp, top = 6.dp), color = Color.LightGray, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                        when (page) {
                            0 -> Monitoring(metrics, false)
                            1 -> MediaPage(isLandscape)
                            2 -> CommandsPage(commands)
                            else -> SettingsPage()
                        }
                    }
                    if (!isLandscape) {
                        Text(status, Modifier.padding(horizontal = 16.dp), color = Color.Gray, maxLines = 1)
                        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 0.dp) {
                            pages.forEachIndexed { index, title -> Tab(selected = pager.currentPage == index, onClick = { coroutineScope.launch { pager.animateScrollToPage(index) } }, text = { Text(title) }) }
                        }
                    }
                }
            }
            }
        }
    }

    @Composable
    private fun Monitoring(data: JSONObject, compact: Boolean) {
        val allGroups = listOf(
            listOf(Metric("CPU", "cpuLoad", "%", 100.0), Metric("CPU", "cpuTemperature", "°C", 100.0), Metric("CPU", "cpuClock", "MHz", 5000.0)),
            listOf(Metric("GPU", "gpuLoad", "%", 100.0), Metric("GPU", "gpuTemperature", "°C", 100.0), Metric("VRAM", "gpuMemory", "MB", 16000.0)),
            listOf(Metric("RAM", "ramLoad", "%", 100.0), Metric("RAM", "ramUsed", "GB", data.optDouble("ramTotal", 32.0))),
            listOf(Metric("DISK", "diskLoad", "%", 100.0), Metric("READ", "diskRead", "MB/s", 100_000_000.0), Metric("WRITE", "diskWrite", "MB/s", 100_000_000.0))
        )
        val available = allGroups.flatten().filter { !data.isNull(it.key) && data.optDouble(it.key).isFinite() }
        if (available.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ожидание показателей ПК", color = Color.Gray) }
            return
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val columns = if (maxWidth > maxHeight) 4 else 2
            val rows = (available.size + columns - 1) / columns
            val cardHeight = ((maxHeight - 8.dp * (rows - 1)) / rows).coerceAtMost(110.dp).coerceAtLeast(48.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                available.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { metric ->
                            Box(Modifier.weight(1f).height(cardHeight)) { MetricCard(data, metric, compact) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricCard(data: JSONObject, metric: Metric, compact: Boolean) {
        val value = if (data.isNull(metric.key)) null else data.optDouble(metric.key).takeUnless { it.isNaN() }
        val progress = ((value ?: 0.0) / metric.scale).toFloat().coerceIn(0f, 1f)
        val temperature = metric.unit == "°C"
        val warning = if (metric.key == "gpuTemperature") gpuWarning else cpuWarning
        val critical = if (metric.key == "gpuTemperature") gpuCritical else cpuCritical
        val color = if (temperature) when { value == null -> Color.Gray; value >= critical -> Color.Red; value >= warning -> Color.Yellow; else -> Color.Green } else MaterialTheme.colorScheme.primary
        val shown = when {
            value == null -> "Н/Д"
            metric.unit == "MB/s" -> "%.1f MB/s".format(value / 1_000_000.0)
            metric.key == "ramUsed" && !data.isNull("ramTotal") -> "%.1f/%.1f GB".format(value, data.optDouble("ramTotal"))
            metric.unit == "GB" -> "%.1f GB".format(value)
            else -> "${value.toInt()}${metric.unit}"
        }
        Column(Modifier.fillMaxSize().background(if (compact) Color.Black else MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(metric.title + if (temperature) " · Температура" else "", color = Color(0xFF9AA8BA), fontSize = 11.sp, maxLines = 1)
            Text(shown, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = color, maxLines = 1)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = color, trackColor = Color(0xFF303B4A))
        }
    }

    @Composable
    private fun CommandsPage(items: List<RemoteCommand>) {
        LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { command ->
                Box(Modifier.width(140.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).clickable {
                    scope.launch {
                        status = "Выполняется: ${command.name}"
                        try {
                            val result = client().request("run", JSONObject().put("id", command.id))
                            status = if (result.optBoolean("ok")) "Выполнено: ${command.name}" else result.optString("error")
                        } catch (exception: Exception) { status = exception.message ?: "Ошибка" }
                    }
                }, contentAlignment = Alignment.Center) {
                    val code = iconCodes.optInt(command.icon, 0)
                    if (code > 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String(Character.toChars(code)), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 36.sp, color = Color.White)
                        Text(command.name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                    } else Text(command.name, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp), color = Color.White)
                }
            }
        }
    }

    @Composable
    private fun MediaPage(isLandscape: Boolean) {
        val title = media.optString("title").ifBlank { "Ничего не воспроизводится" }
        val artist = media.optString("artist")
        val image = remember(media.optString("artwork")) {
            try {
                val bytes = Base64.getDecoder().decode(media.optString("artwork"))
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
        var volume by remember(media.optDouble("volume")) { mutableFloatStateOf(media.optDouble("volume", 0.5).toFloat()) }
        val duration = media.optDouble("duration", 0.0).toFloat()
        val start = media.optDouble("start", 0.0).toFloat()
        var seeking by remember { mutableStateOf(false) }
        var position by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(media) { if (!seeking) position = media.optDouble("position", 0.0).toFloat() }
        val artwork: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                if (image != null) Image(image, "Обложка", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(iconGlyph("music-note"), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 64.sp, color = Color.Gray)
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(artist, color = Color.Gray, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (duration > start) {
                    Slider(value = position.coerceIn(start, duration), onValueChange = { seeking = true; position = it },
                        onValueChangeFinished = { mediaAction("seek", position); seeking = false },
                        valueRange = start..duration, enabled = media.optBoolean("canSeek"), modifier = Modifier.fillMaxWidth().height(32.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position - start), fontSize = 11.sp, color = Color.Gray)
                        Text(formatTime(duration - start), fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { mediaAction("previous") }) { Text(iconGlyph("skip-backward-fill"), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 24.sp) }
                    Button(onClick = { mediaAction("toggle") }) { Text(iconGlyph(if (media.optBoolean("playing")) "pause-fill" else "play-fill"), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 24.sp) }
                    Button(onClick = { mediaAction("next") }) { Text(iconGlyph("skip-forward-fill"), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 24.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { mediaAction("mute") }) { Text(iconGlyph(if (media.optBoolean("muted")) "volume-mute-fill" else "volume-up-fill"), fontFamily = FontFamily(Font(R.font.bootstrap_icons)), fontSize = 24.sp) }
                    Slider(value = volume.coerceIn(0f, 1f), onValueChange = { volume = it }, onValueChangeFinished = { mediaAction("volume", volume) }, modifier = Modifier.weight(1f))
                }
            }
        }
        if (isLandscape) Row(Modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.weight(0.42f).fillMaxHeight()) { artwork() }
            Box(Modifier.weight(0.58f).fillMaxHeight()) { controls() }
        } else Column(Modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { artwork() }
            Box(Modifier.height(280.dp)) { controls() }
        }
    }

    private fun mediaAction(operation: String, level: Float? = null) {
        scope.launch {
            try {
                val payload = JSONObject().put("operation", operation)
                if (level != null) payload.put("level", level)
                val response = client().request("media.control", payload)
                if (!response.optBoolean("ok")) status = response.optString("error")
                refresh()
            } catch (exception: Exception) { status = exception.message ?: "Ошибка" }
        }
    }

    private fun formatTime(seconds: Float): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun iconGlyph(name: String): String {
        val code = iconCodes.optInt(name, 0)
        return if (code > 0) String(Character.toChars(code)) else "?"
    }

    @Composable
    private fun SettingsPage() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Адрес Windows Agent")
            OutlinedTextField(host, { host = it; preferences.edit().putString("host", it).apply() }, modifier = Modifier.fillMaxWidth())
            Text("Ключ подключения")
            OutlinedTextField(key, { key = it; preferences.edit().putString("key", it).apply() }, modifier = Modifier.fillMaxWidth())
            Text("Идентификатор доверенного устройства")
            OutlinedTextField(deviceId, { deviceId = it; preferences.edit().putString("deviceId", it).apply() }, modifier = Modifier.fillMaxWidth())
            Text("Сопряжённый ПК для Bluetooth")
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                else refreshPairedDevices()
            }) { Text("Выбрать Bluetooth-устройство") }
            pairedDevices.forEach { (name, address) ->
                Row(Modifier.fillMaxWidth().clickable { bluetoothAddress = address; preferences.edit().putString("bluetooth", address).apply() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = bluetoothAddress == address, onClick = { bluetoothAddress = address; preferences.edit().putString("bluetooth", address).apply() })
                    Text("$name · $address")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ambientEnabled, { ambientEnabled = it; preferences.edit().putBoolean("ambient", it).apply() })
                Text("Ambient Mode")
            }
            Text("Бездействие: $idleMinutes мин")
            Slider(idleMinutes.toFloat(), { idleMinutes = it.toInt().coerceIn(1, 30); preferences.edit().putInt("idleMinutes", idleMinutes).apply() }, valueRange = 1f..30f)
            Text("Яркость: ${(ambientBrightness * 100).toInt()}%")
            Slider(ambientBrightness, { ambientBrightness = it; preferences.edit().putFloat("brightness", it).apply() }, valueRange = 0.01f..0.3f)
            Text("Телеметрия в Ambient Mode: $telemetrySeconds с")
            Slider(telemetrySeconds.toFloat(), { telemetrySeconds = it.toInt().coerceIn(10, 15); preferences.edit().putInt("telemetrySeconds", telemetrySeconds).apply() }, valueRange = 10f..15f)
            Text("Предпочтительная частота дисплея: ${preferredHz.toInt()} Гц")
            Slider(preferredHz, { preferredHz = it; preferences.edit().putFloat("preferredHz", it).apply() }, valueRange = 5f..120f)
            Text("CPU: жёлтый от $cpuWarning°C, красный от $cpuCritical°C")
            Slider(cpuWarning.toFloat(), { cpuWarning = it.toInt().coerceAtMost(cpuCritical - 1); preferences.edit().putInt("cpuWarning", cpuWarning).apply() }, valueRange = 40f..100f)
            Slider(cpuCritical.toFloat(), { cpuCritical = it.toInt().coerceAtLeast(cpuWarning + 1); preferences.edit().putInt("cpuCritical", cpuCritical).apply() }, valueRange = 40f..110f)
            Text("GPU: жёлтый от $gpuWarning°C, красный от $gpuCritical°C")
            Slider(gpuWarning.toFloat(), { gpuWarning = it.toInt().coerceAtMost(gpuCritical - 1); preferences.edit().putInt("gpuWarning", gpuWarning).apply() }, valueRange = 40f..100f)
            Slider(gpuCritical.toFloat(), { gpuCritical = it.toInt().coerceAtLeast(gpuWarning + 1); preferences.edit().putInt("gpuCritical", gpuCritical).apply() }, valueRange = 40f..110f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pixelShift, { pixelShift = it; preferences.edit().putBoolean("pixelShift", it).apply() })
                Text("Периодическое смещение")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(keepScreen, { keepScreen = it; preferences.edit().putBoolean("keepScreen", it).apply() })
                Text("Держать экран включённым")
            }
            Text(status)
        }
    }
}

data class RemoteCommand(val id: String, val name: String, val icon: String)
data class Metric(val title: String, val key: String, val unit: String, val scale: Double)
