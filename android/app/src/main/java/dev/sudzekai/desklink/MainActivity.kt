package dev.sudzekai.desklink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("desklink", MODE_PRIVATE) }
    private var lastTouch by mutableLongStateOf(System.currentTimeMillis())
    private var status by mutableStateOf("Не подключено")
    private var metrics by mutableStateOf(JSONObject())
    private var commands by mutableStateOf(listOf<RemoteCommand>())
    private var host by mutableStateOf("127.0.0.1")
    private var key by mutableStateOf("")
    private var clipboardText by mutableStateOf("")
    private var ambientEnabled by mutableStateOf(true)
    private var ambient by mutableStateOf(false)
    private var fileStatus by mutableStateOf("")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            uris.forEachIndexed { index, uri ->
                try {
                    val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                    } ?: "file"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось открыть файл")
                    require(bytes.size <= 5_000_000) { "Файл больше 5 МБ" }
                    val result = client().request("file", JSONObject().put("name", name).put("data", Base64.getEncoder().encodeToString(bytes)))
                    if (!result.optBoolean("ok")) error(result.optString("error"))
                    fileStatus = "Передано ${index + 1} из ${uris.size}"
                } catch (exception: Exception) {
                    fileStatus = "Ошибка файла: ${exception.message}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = preferences.getString("host", "127.0.0.1") ?: "127.0.0.1"
        key = preferences.getString("key", "") ?: ""
        ambientEnabled = preferences.getBoolean("ambient", true)
        setContent { DeskLinkScreen() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouch = System.currentTimeMillis()
            if (ambient) ambient = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun client() = AgentClient(host.trim(), key.trim())

    private suspend fun refresh() {
        try {
            val response = client().request("telemetry")
            if (!response.optBoolean("ok")) error(response.optString("error"))
            metrics = response.getJSONObject("data")
            status = "Подключено"
            val commandResponse = client().request("commands")
            if (commandResponse.optBoolean("ok")) {
                val array = commandResponse.getJSONArray("data")
                commands = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    RemoteCommand(item.getString("Id"), item.getString("Name"), item.optString("Icon"))
                }
            }
        } catch (exception: Exception) {
            status = exception.message ?: "Нет соединения"
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
                ambient = ambientEnabled && System.currentTimeMillis() - lastTouch >= 300_000
                refresh()
                delay(if (ambient) 15_000 else 3_000)
            }
        }
        DisposableEffect(ambient) {
            val attributes = window.attributes
            attributes.screenBrightness = if (ambient) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attributes
            onDispose { }
        }
        MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F1116), surface = Color(0xFF202630), primary = Color(0xFF82B8FF))) {
            if (ambient) {
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { ambient = false }, contentAlignment = Alignment.Center) {
                    Monitoring(metrics, true)
                }
            } else {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Text(pages[pager.currentPage], Modifier.padding(start = 16.dp, top = 10.dp), color = Color.LightGray, fontWeight = FontWeight.SemiBold)
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                        when (page) {
                            0 -> Monitoring(metrics, false)
                            1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Мультимедиа пока недоступно") }
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
        val entries = listOf(
            Triple("CPU", "cpuLoad", "%"), Triple("CPU", "cpuTemperature", "°C"),
            Triple("GPU", "gpuLoad", "%"), Triple("GPU", "gpuTemperature", "°C"),
            Triple("RAM", "ramLoad", "%"), Triple("DISK", "diskLoad", "%")
        )
        LazyVerticalGrid(columns = GridCells.Adaptive(if (compact) 150.dp else 125.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries) { (title, keyName, unit) ->
                val value = if (data.isNull(keyName)) null else data.optDouble(keyName).takeUnless { it.isNaN() }
                val progress = ((value ?: 0.0) / if (unit == "°C") 100.0 else 100.0).toFloat().coerceIn(0f, 1f)
                val color = if (unit == "°C") when { value == null -> Color.Gray; value >= 85 -> Color.Red; value >= 70 -> Color.Yellow; else -> Color.Green } else MaterialTheme.colorScheme.primary
                Box(Modifier.aspectRatio(1f).background(if (compact) Color.Black else MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize().padding(12.dp), color = color, trackColor = Color.DarkGray)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        Text(if (value == null) "Н/Д" else "${value.toInt()}$unit", fontWeight = FontWeight.Bold)
                    }
                }
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
                }, contentAlignment = Alignment.Center) { Text(command.name, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
            }
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
        Column(Modifier.padding(16.dp)) {
            Text("Адрес Windows Agent")
            OutlinedTextField(host, { host = it; preferences.edit().putString("host", it).apply() }, modifier = Modifier.fillMaxWidth())
            Text("Ключ подключения")
            OutlinedTextField(key, { key = it; preferences.edit().putString("key", it).apply() }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ambientEnabled, { ambientEnabled = it; preferences.edit().putBoolean("ambient", it).apply() })
                Text("Ambient Mode через 5 минут")
            }
            Text(status)
        }
    }
}

data class RemoteCommand(val id: String, val name: String, val icon: String)
