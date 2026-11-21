package dev.sudzekai.desklink

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
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
import java.io.File

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
    private var clipboardText by mutableStateOf("")
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
    private var fileStatus by mutableStateOf("")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refreshPairedDevices()
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            uris.forEachIndexed { index, uri ->
                try {
                    val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                    } ?: "file"
                    val temporary = File.createTempFile("desklink-", ".upload", cacheDir)
                    var uploadId = ""
                    try {
                        contentResolver.openInputStream(uri)?.use { source -> temporary.outputStream().use { source.copyTo(it) } }
                            ?: error("Не удалось открыть файл")
                        val connection = client()
                        val started = connection.request("file.start", JSONObject().put("name", name).put("size", temporary.length()))
                        if (!started.optBoolean("ok")) error(started.optString("error"))
                        uploadId = started.getString("data")
                        val buffer = ByteArray(512 * 1024)
                        var sent = 0L
                        temporary.inputStream().use { source ->
                            while (true) {
                                val length = source.read(buffer)
                                if (length < 0) break
                                val chunk = Base64.getEncoder().encodeToString(buffer.copyOf(length))
                                val result = connection.request("file.chunk", JSONObject().put("id", uploadId).put("data", chunk))
                                if (!result.optBoolean("ok")) error(result.optString("error"))
                                sent += length
                                fileStatus = "${index + 1}/${uris.size}: ${(sent * 100 / temporary.length().coerceAtLeast(1))}%"
                            }
                        }
                        val finished = connection.request("file.finish", JSONObject().put("id", uploadId))
                        if (!finished.optBoolean("ok")) error(finished.optString("error"))
                        fileStatus = "Передано ${index + 1} из ${uris.size}"
                    } catch (exception: Exception) {
                        if (uploadId.isNotBlank()) try { client().request("file.cancel", JSONObject().put("id", uploadId)) } catch (_: Exception) { }
                        throw exception
                    } finally { temporary.delete() }
                } catch (exception: Exception) {
                    fileStatus = "Ошибка файла: ${exception.message}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
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
        val pages = listOf("Мониторинг", "Мультимедиа", "Команды", "Файлы и буфер", "Настройки")
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
            if (ambient) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().clickable { ambient = false }, contentAlignment = Alignment.Center) {
                    Box(Modifier.offset(x = (shift * 2).dp, y = (shift * 2).dp)) { Monitoring(metrics, true) }
                }
            } else {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    Text(pages[pager.currentPage], Modifier.padding(start = 16.dp, top = 6.dp), color = Color.LightGray, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                        when (page) {
                            0 -> Monitoring(metrics, false)
                            1 -> MediaPage(isLandscape)
                            2 -> CommandsPage(commands)
                            3 -> FilesPage(isLandscape)
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

    @Composable
    private fun Monitoring(data: JSONObject, compact: Boolean) {
        val groups = listOf(
            listOf(Metric("CPU", "cpuLoad", "%", 100.0), Metric("CPU", "cpuTemperature", "°C", 100.0), Metric("CPU", "cpuClock", "MHz", 5000.0)),
            listOf(Metric("GPU", "gpuLoad", "%", 100.0), Metric("GPU", "gpuTemperature", "°C", 100.0), Metric("VRAM", "gpuMemory", "MB", 16000.0)),
            listOf(Metric("RAM", "ramLoad", "%", 100.0), Metric("RAM", "ramUsed", "GB", data.optDouble("ramTotal", 32.0))),
            listOf(Metric("DISK", "diskLoad", "%", 100.0), Metric("READ", "diskRead", "MB/s", 100_000_000.0), Metric("WRITE", "diskWrite", "MB/s", 100_000_000.0))
        )
        if (LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { metric -> MetricCard(data, metric, compact) }
                    }
                }
            }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(if (compact) 150.dp else 125.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups.flatten()) { metric -> MetricCard(data, metric, compact) }
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
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(if (compact) Color.Black else MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize().padding(12.dp), color = color, trackColor = Color.DarkGray)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(metric.title, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                Text(shown, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White)
            }
        }
    }

    @Composable
    private fun CommandsPage(items: List<RemoteCommand>) {
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { command ->
                Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).clickable {
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
        val artwork: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                if (image != null) Image(image, "Обложка", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("♫", style = MaterialTheme.typography.displayLarge, color = Color.Gray)
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(artist, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { mediaAction("previous") }) { Text("⏮") }
                    Button(onClick = { mediaAction("toggle") }) { Text(if (media.optBoolean("playing")) "⏸" else "▶") }
                    Button(onClick = { mediaAction("next") }) { Text("⏭") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { mediaAction("mute") }) { Text(if (media.optBoolean("muted")) "🔇" else "🔊") }
                    Slider(value = volume.coerceIn(0f, 1f), onValueChange = { volume = it }, onValueChangeFinished = { mediaAction("volume", volume) }, modifier = Modifier.weight(1f))
                }
            }
        }
        if (isLandscape) Row(Modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.weight(1f)) { artwork() }
            Box(Modifier.weight(1f)) { controls() }
        } else Column(Modifier.fillMaxSize().padding(12.dp)) {
            artwork()
            controls()
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

    @Composable
    private fun FilesPage(isLandscape: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipboardBlock: @Composable () -> Unit = {
            Column(Modifier.padding(12.dp)) {
                Text("Буфер обмена")
                OutlinedTextField(value = clipboardText, onValueChange = { clipboardText = it }, modifier = Modifier.fillMaxWidth().height(130.dp))
                Row {
                    Button(onClick = { clipboardText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: "" }) { Text("Из телефона") }
                    Button(onClick = { scope.launch { try { client().request("clipboard.set", JSONObject().put("text", clipboardText)); status = "Передано" } catch (e: Exception) { status = e.message ?: "Ошибка" } } }) { Text("Передать") }
                    Button(onClick = { scope.launch { try { clipboardText = client().request("clipboard.get").optString("data"); clipboard.setPrimaryClip(ClipData.newPlainText("DeskLink", clipboardText)) } catch (e: Exception) { status = e.message ?: "Ошибка" } } }) { Text("Получить") }
                }
            }
        }
        val filesBlock: @Composable () -> Unit = {
            Column(Modifier.padding(12.dp)) {
                Text("Файлы")
                Button(onClick = { pickFiles.launch(arrayOf("image/*", "video/*")) }) { Text("Фото/видео") }
                Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Файлы") }
                Text(fileStatus)
            }
        }
        if (isLandscape) Row { Box(Modifier.weight(1f)) { clipboardBlock() }; Box(Modifier.weight(1f)) { filesBlock() } }
        else Column { clipboardBlock(); filesBlock() }
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
