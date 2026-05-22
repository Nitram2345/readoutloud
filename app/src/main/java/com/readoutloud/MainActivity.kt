package com.readoutloud

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readoutloud.ui.theme.ReadOutLoudTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var ttsController: TtsController? = null
    private val isPlayingState = mutableStateOf(false)
    private val currentLinesState = mutableStateOf<List<TextLine>>(emptyList())
    private var savedSpeechRate = 1.0f
    private var savedVolume = 1.0f
    private var savedIsSpeaker = true
    private var savedLanguage = "de"
    private val languageState = mutableStateOf("de")

    private fun changeLanguage(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        languageState.value = lang
        recreate()
    }

    private fun setupController(ctrl: TtsController) {
        ttsController = ctrl
        ctrl.onPlayStateChanged = { playing -> isPlayingState.value = playing }
        ctrl.onLinesChanged = { lines -> currentLinesState.value = lines }
        ctrl.setRate(savedSpeechRate)
        ctrl.setVolume(savedVolume)
        ctrl.setAudioOutput(savedIsSpeaker)
        ctrl.setLanguage(savedLanguage)
        Log.d("TTS", "Controller ready")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = binder as TtsService.TtsBinder
            val ctrl = svc.getController()
            if (ctrl != null) {
                setupController(ctrl)
            } else {
                svc.onControllerReady = { setupController(it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            ttsController = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PDFBoxResourceLoader.init(applicationContext)
        val settings0 = AppSettings(this)
        savedSpeechRate = settings0.speechRate
        savedVolume = settings0.volume
        savedIsSpeaker = settings0.isSpeaker
        savedLanguage = settings0.language
        // Set locale without recreate() during startup
        val locale = Locale(savedLanguage)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val intent = Intent(this, TtsService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        setContent {
            ReadOutLoudTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PdfViewerScreen(
                        modifier = Modifier.padding(innerPadding),
                        isPlaying = isPlayingState.value,
                        currentHighlightLines = currentLinesState.value,
                        onStartFrom = { lines -> ttsController?.startFrom(lines) },
                        onPrimeFrom = { lines -> ttsController?.primeFrom(lines) },
                        onPause = { ttsController?.pause() },
                        onResume = { ttsController?.resume() },
                        canResume = { ttsController?.canResume() == true },
                        onRateChange = { rate -> ttsController?.setRate(rate) },
                        onSkipForward = { ttsController?.skipForward(30) },
                        onSkipBackward = { ttsController?.skipBackward(30) },
                        onVolumeChange = { v -> ttsController?.setVolume(v) },
                        onAudioOutputChange = { s -> ttsController?.setAudioOutput(s) },
                        onLanguageChange = { lang -> changeLanguage(lang) },
                        currentLanguage = languageState.value,
                        onSaveAndClose = {
                            stopService(Intent(this, TtsService::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    currentHighlightLines: List<TextLine>,
    onStartFrom: (List<TextLine>) -> Unit,
    onPrimeFrom: (List<TextLine>) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    canResume: () -> Boolean,
    onRateChange: (Float) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onAudioOutputChange: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    currentLanguage: String,
    onSaveAndClose: () -> Unit,
) {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var documentData by remember { mutableStateOf<DocumentData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var statusText by remember { mutableStateOf(resources.getString(R.string.status_idle)) }
    var speechRate by remember { mutableStateOf(settings.speechRate) }
    var volume by remember { mutableStateOf(settings.volume) }
    val pdfListState = rememberLazyListState()
    var isSpeaker by remember { mutableStateOf(settings.isSpeaker) }

    var sleepMinutesLeft by remember { mutableStateOf(0) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(settings.language) }
    val tocHeadings = remember(documentData) {
        documentData?.textLines?.mapIndexedNotNull { idx, line ->
            if (headingLevel(line.style) > 0) idx to line else null
        } ?: emptyList()
    }

    fun startSleepTimer() {
        sleepJob?.cancel()
        sleepMinutesLeft = 30
        sleepJob = scope.launch {
            while (sleepMinutesLeft > 0) {
                kotlinx.coroutines.delay(60_000)
                sleepMinutesLeft--
            }
            onPause()
            sleepJob = null
        }
    }
    fun cancelSleepTimer() { sleepJob?.cancel(); sleepJob = null; sleepMinutesLeft = 0 }

    LaunchedEffect(currentHighlightLines) {
        val targetPage = currentHighlightLines.firstOrNull()?.page ?: return@LaunchedEffect
        if (documentData is DocumentData.Pdf) pdfListState.animateScrollToItem(targetPage)
    }

    fun loadUri(uri: Uri) {
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            statusText = resources.getString(R.string.status_loading)
            try {
                val oldUri = settings.lastUri
                val data = loadDocument(context, uri)
                documentData = data
                if (oldUri != uri) settings.savedLineIndex = -1
                settings.lastUri = uri
                statusText = when (data) {
                    is DocumentData.Pdf -> resources.getString(R.string.status_pages, data.pages.size, data.textLines.size)
                    is DocumentData.Word -> resources.getString(R.string.status_paragraphs, data.textLines.size)
                }
                val savedIdx = settings.savedLineIndex
                if (savedIdx >= 0 && savedIdx < data.textLines.size) {
                    onPrimeFrom(data.textLines.drop(savedIdx))
                }
            } catch (e: java.io.FileNotFoundException) {
                settings.lastUri = null
                statusText = resources.getString(R.string.status_file_not_found)
                Log.e("DOC", "File not found: $uri", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                statusText = resources.getString(R.string.status_cancelled)
                throw e
            } catch (e: Exception) {
                statusText = resources.getString(R.string.status_error, "${e.javaClass.simpleName}: ${e.message}")
                Log.e("DOC", "Load failed", e)
            } finally {
                isLoading = false
                loadJob = null
            }
        }
    }

    LaunchedEffect(Unit) {
        settings.lastUri?.let { loadUri(it) }
    }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadUri(uri)
        }
    }

    fun savePosition() {
        val firstLine = currentHighlightLines.firstOrNull() ?: return
        val idx = documentData?.textLines?.indexOf(firstLine) ?: return
        if (idx >= 0) settings.savedLineIndex = idx
    }

    val btnPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val canControl = isPlaying || canResume()

    Column(modifier = modifier.fillMaxSize()) {
        // Row 1: ← | Öffnen | spacer | − 1.0× +
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundBtn(onClick = {
                if (isPlaying) { savePosition(); onPause() }
                onSaveAndClose()
            }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "⬅",
                        modifier = Modifier.offset(y = (-5).dp),
                        style = MaterialTheme.typography.titleLarge.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            }

            Button(
                onClick = {
                    docPicker.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/epub+zip",
                            "application/vnd.oasis.opendocument.text"
                        )
                    )
                },
                enabled = !isLoading,
                contentPadding = btnPadding
            ) { Text(stringResource(R.string.open)) }

            RoundBtn(onClick = { showLangDialog = true }) { Text(when (language) { "de" -> "🇩🇪" "en" -> "🇬🇧" "nl" -> "🇳🇱" "it" -> "🇮🇹" "fr" -> "🇫🇷" "pt" -> "🇵🇹" "sv" -> "🇸🇪" else -> "🇩🇪" }, style = MaterialTheme.typography.titleMedium) }

            RoundBtn(onClick = { showHelp = true }) { Text("?", style = MaterialTheme.typography.titleMedium) }

            Spacer(Modifier.weight(1f))

            RoundBtn(
                onClick = { showToc = true },
                enabled = tocHeadings.isNotEmpty()
            ) { Text("≡", style = MaterialTheme.typography.titleMedium) }

            RoundBtn(onClick = {
                speechRate = (speechRate - 0.1f).coerceAtLeast(1.0f)
                    .let { Math.round(it * 10) / 10f }
                settings.speechRate = speechRate; onRateChange(speechRate)
            }) { Text("−", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

            Text("🕛%.1f×".format(speechRate), style = MaterialTheme.typography.bodyMedium)

            RoundBtn(onClick = {
                speechRate = (speechRate + 0.1f).coerceAtMost(3.0f)
                    .let { Math.round(it * 10) / 10f }
                settings.speechRate = speechRate; onRateChange(speechRate)
            }) { Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }

        // Row 2: «30s | Laut Lesen | 30s» | spacer | − 🔊100% +
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundBtn(onClick = onSkipBackward, enabled = canControl) { Text(stringResource(R.string.skip_back), style = MaterialTheme.typography.labelSmall) }

            Button(
                onClick = {
                    if (isPlaying) { savePosition(); onPause() } else onResume()
                },
                enabled = canControl,
                contentPadding = btnPadding,
                modifier = Modifier.widthIn(min = 110.dp)
            ) { Text(stringResource(if (isPlaying) R.string.pause else R.string.listen)) }

            RoundBtn(onClick = { if (sleepMinutesLeft > 0) cancelSleepTimer() else startSleepTimer() }) { Text(stringResource(if (sleepMinutesLeft > 0) R.string.sleep_timer_active else R.string.sleep_timer, sleepMinutesLeft), style = MaterialTheme.typography.labelSmall) }

            RoundBtn(onClick = onSkipForward, enabled = canControl) { Text(stringResource(R.string.skip_forward), style = MaterialTheme.typography.labelSmall) }

            Spacer(Modifier.weight(1f))

            RoundBtn(onClick = {
                isSpeaker = !isSpeaker
                settings.isSpeaker = isSpeaker
                onAudioOutputChange(isSpeaker)
            }) { Text(if (isSpeaker) "🔊" else "👂", style = MaterialTheme.typography.labelSmall) }

            RoundBtn(onClick = {
                volume = (volume - 0.1f).coerceAtLeast(0.1f)
                    .let { Math.round(it * 10) / 10f }
                settings.volume = volume; onVolumeChange(volume)
            }) { Text("−", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }

            Text(stringResource(R.string.volume, volume * 100), style = MaterialTheme.typography.bodySmall)

            RoundBtn(onClick = {
                volume = (volume + 0.1f).coerceAtMost(1.5f)
                    .let { Math.round(it * 10) / 10f }
                settings.volume = volume; onVolumeChange(volume)
            }) { Text("+", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { loadJob?.cancel(); statusText = resources.getString(R.string.status_cancelled) },
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .fillMaxWidth()
            ) { Text(stringResource(R.string.status_cancelled)) }
        }

        val allLines = documentData?.textLines
        val firstHighlight = currentHighlightLines.firstOrNull()
        val progressSuffix = if (firstHighlight != null && allLines != null && allLines.isNotEmpty()) {
            val idx = allLines.indexOf(firstHighlight)
            if (idx >= 0) {
                val pct = (idx.toFloat() / allLines.size * 100).toInt()
                val pageInfo = if (documentData is DocumentData.Pdf)
                    " · ${resources.getString(R.string.page_indicator, firstHighlight.page + 1, (documentData as DocumentData.Pdf).pages.size)}"
                else ""
                "  📍 ${resources.getString(R.string.progress, pct)}$pageInfo"
            } else ""
        } else ""
        Text(
            text = statusText + progressSuffix,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall
        )

        val data = documentData
        if (data != null) {
            when (data) {
                is DocumentData.Pdf -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = pdfListState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(data.pages) { pageIndex, bitmap ->
                                PdfPageView(
                                    bitmap = bitmap,
                                    pageLines = data.textLines.filter { it.page == pageIndex },
                                    highlightLines = currentHighlightLines.filter { it.page == pageIndex },
                                    onTap = { xFraction, yFraction ->
                                        val lines = buildSpeechFromTap(pageIndex, xFraction, yFraction, data.textLines)
                                        if (lines.isNotEmpty()) onStartFrom(lines)
                                    }
                                )
                                HorizontalDivider(thickness = 2.dp)
                            }
                        }
                        if (data.pages.size > 1) {
                            Text(
                                text = "${pdfListState.firstVisibleItemIndex + 1} / ${data.pages.size}",
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                is DocumentData.Word -> {
                    WordDocView(
                        textLines = data.textLines,
                        highlightLines = currentHighlightLines,
                        onStartFrom = onStartFrom
                    )
                }
            }
        }

        if (showToc) {
            ModalBottomSheet(onDismissRequest = { showToc = false }) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    itemsIndexed(tocHeadings) { _, (idx, line) ->
                        val level = headingLevel(line.style)
                        Text(
                            text = line.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showToc = false
                                    onStartFrom(documentData!!.textLines.drop(idx))
                                }
                                .padding(
                                    start = (8 + (level - 1) * 16).dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp
                                ),
                            style = when (level) {
                                1    -> MaterialTheme.typography.titleMedium
                                2    -> MaterialTheme.typography.bodyLarge
                                else -> MaterialTheme.typography.bodyMedium
                            },
                            fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.Normal
                        )
                        if (level == 1) HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }

        if (showHelp) {
            ModalBottomSheet(onDismissRequest = { showHelp = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.help_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(stringResource(R.string.help_controls_top), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HelpItem("⬅", stringResource(R.string.help_back))
                    HelpItem(stringResource(R.string.open), stringResource(R.string.help_open))
                    HelpItem("≡", stringResource(R.string.help_toc))
                    HelpItem("?", stringResource(R.string.help_help))
                    HelpItem("🔊/👂", stringResource(R.string.help_audio))
                    HelpItem("− / +", stringResource(R.string.help_speed))

                    Text(stringResource(R.string.help_controls_bottom), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    HelpItem(stringResource(R.string.skip_back), stringResource(R.string.help_skip_back))
                    HelpItem(stringResource(R.string.listen), stringResource(R.string.help_listen))
                    HelpItem(stringResource(R.string.sleep_timer), stringResource(R.string.help_timer))
                    HelpItem(stringResource(R.string.skip_forward), stringResource(R.string.help_skip_forward))
                    HelpItem("− / +", stringResource(R.string.help_volume))

                    Text(stringResource(R.string.help_tap_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.help_tap), style = MaterialTheme.typography.bodyMedium)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(stringResource(R.string.legal_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HelpItem(stringResource(R.string.privacy_policy), stringResource(R.string.privacy_description), link = stringResource(R.string.privacy_url))

                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { showHelp = false }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.help_close)) }
                }
            }
        }

        if (showLangDialog) {
            AlertDialog(
                onDismissRequest = { showLangDialog = false },
                title = { Text(stringResource(R.string.select_language)) },
                text = {
                    Column {
                        LanguageOption("de", stringResource(R.string.lang_de)) {
                            language = "de"; settings.language = "de"; onLanguageChange("de"); showLangDialog = false
                        }
                        LanguageOption("en", stringResource(R.string.lang_en)) {
                            language = "en"; settings.language = "en"; onLanguageChange("en"); showLangDialog = false
                        }
                        LanguageOption("nl", stringResource(R.string.lang_nl)) {
                            language = "nl"; settings.language = "nl"; onLanguageChange("nl"); showLangDialog = false
                        }
                        LanguageOption("it", stringResource(R.string.lang_it)) {
                            language = "it"; settings.language = "it"; onLanguageChange("it"); showLangDialog = false
                        }
                        LanguageOption("fr", stringResource(R.string.lang_fr)) {
                            language = "fr"; settings.language = "fr"; onLanguageChange("fr"); showLangDialog = false
                        }
                        LanguageOption("pt", stringResource(R.string.lang_pt)) {
                            language = "pt"; settings.language = "pt"; onLanguageChange("pt"); showLangDialog = false
                        }
                        LanguageOption("sv", stringResource(R.string.lang_sv)) {
                            language = "sv"; settings.language = "sv"; onLanguageChange("sv"); showLangDialog = false
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showLangDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
            )
        }
    }
}

@Composable
fun LanguageOption(code: String, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun HelpItem(icon: String, text: String, link: String? = null) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(icon, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = if (link != null) Modifier.clickable {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            context.startActivity(Intent.createChooser(intent, "Open"))
        } else Modifier)
    }
}

private fun headingLevel(styleId: String): Int {
    val s = styleId.lowercase().replace(" ", "")
    return when {
        s == "title"                                          -> 1
        s.matches(Regex("(heading|berschrift|h)1"))          -> 1
        s.matches(Regex("(heading|berschrift|h)2"))          -> 2
        s.matches(Regex("(heading|berschrift|h)3"))          -> 3
        s.matches(Regex("(heading|berschrift|h)[456]"))      -> 4
        s.contains("heading") || s.contains("berschrift")   -> 2
        else                                                  -> 0
    }
}

@Composable
fun WordDocView(
    textLines: List<TextLine>,
    highlightLines: List<TextLine>,
    onStartFrom: (List<TextLine>) -> Unit
) {
    val listState = rememberLazyListState()
    val highlightIndices = remember(highlightLines) {
        highlightLines.map { it.y.toInt() }.toSet()
    }

    LaunchedEffect(highlightLines) {
        val targetIdx = highlightLines.firstOrNull()?.y?.toInt() ?: return@LaunchedEffect
        listState.animateScrollToItem(targetIdx)
    }

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        itemsIndexed(textLines) { idx, line ->
            val isHighlighted = idx in highlightIndices
            val level = headingLevel(line.style)
            val textStyle = when (level) {
                1    -> MaterialTheme.typography.titleLarge
                2    -> MaterialTheme.typography.titleMedium
                3    -> MaterialTheme.typography.titleSmall
                4    -> MaterialTheme.typography.bodyLarge
                else -> MaterialTheme.typography.bodyMedium
            }
            val topPadding = when (level) {
                1    -> 18.dp
                2    -> 14.dp
                3, 4 -> 8.dp
                else -> 3.dp
            }
            Text(
                text = line.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPadding)
                    .background(if (isHighlighted) highlightColor else Color.Transparent)
                    .clickable { onStartFrom(textLines.drop(idx)) }
                    .padding(vertical = 3.dp),
                style = textStyle,
                fontWeight = if (level > 0) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun RoundBtn(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.CircleShape,
        contentPadding = PaddingValues(2.dp),
        modifier = Modifier.size(33.dp)
    ) { content() }
}

@Composable
fun PdfPageView(
    bitmap: Bitmap,
    pageLines: List<TextLine>,
    highlightLines: List<TextLine>,
    onTap: (xFraction: Float, yFraction: Float) -> Unit
) {
    val isBottomOrigin = remember(pageLines) {
        pageLines.size >= 2 && pageLines.first().y > pageLines.last().y
    }
    val currentOnTap by rememberUpdatedState(onTap)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

    Image(
        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnTap(
                        offset.x / size.width.toFloat(),
                        offset.y / size.height.toFloat()
                    )
                }
            }
            .drawWithContent {
                drawContent()
                val first = highlightLines.firstOrNull() ?: return@drawWithContent
                val scaleX = size.width / first.pageWidth
                val scaleY = size.height / first.pageHeight
                for (line in highlightLines) {
                    val left = line.x * scaleX
                    val right = (line.x + line.width) * scaleX
                    val (top, bottom) = if (isBottomOrigin) {
                        val b = (first.pageHeight - line.y) * scaleY
                        Pair(b - line.height * scaleY, b)
                    } else {
                        Pair(line.y * scaleY, (line.y + line.height) * scaleY)
                    }
                    drawRect(
                        color = highlightColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top)
                    )
                }
            }
    )
}
