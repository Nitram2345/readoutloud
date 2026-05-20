package com.readoutloud

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.readoutloud.ui.theme.ReadOutLoudTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var ttsController: TtsController? = null
    private val isPlayingState = mutableStateOf(false)
    private val currentLinesState = mutableStateOf<List<TextLine>>(emptyList())
    private var savedSpeechRate = 1.0f
    private var savedVolume = 1.0f
    private var savedIsSpeaker = true

    private fun setupController(ctrl: TtsController) {
        ttsController = ctrl
        ctrl.onPlayStateChanged = { playing -> isPlayingState.value = playing }
        ctrl.onLinesChanged = { lines -> currentLinesState.value = lines }
        ctrl.setRate(savedSpeechRate)
        ctrl.setVolume(savedVolume)
        ctrl.setAudioOutput(savedIsSpeaker)
        Log.d("TTS", "Controller ready")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = binder as TtsService.TtsBinder
            val ctrl = svc.controller
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
    onSaveAndClose: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onAudioOutputChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var documentData by remember { mutableStateOf<DocumentData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var statusText by remember { mutableStateOf("PDF, DOCX oder EPUB öffnen") }
    var speechRate by remember { mutableStateOf(settings.speechRate) }
    var volume by remember { mutableStateOf(settings.volume) }
    val pdfListState = rememberLazyListState()
    var isSpeaker by remember { mutableStateOf(settings.isSpeaker) }

    var sleepMinutesLeft by remember { mutableStateOf(0) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var showToc by remember { mutableStateOf(false) }
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
            statusText = "Lade..."
            try {
                val oldUri = settings.lastUri
                val data = loadDocument(context, uri)
                documentData = data
                if (oldUri != uri) settings.savedLineIndex = -1
                settings.lastUri = uri
                statusText = when (data) {
                    is DocumentData.Pdf -> "${data.pages.size} Seiten, ${data.textLines.size} Textzeilen"
                    is DocumentData.Word -> "${data.textLines.size} Absätze"
                }
                val savedIdx = settings.savedLineIndex
                if (savedIdx >= 0 && savedIdx < data.textLines.size) {
                    onPrimeFrom(data.textLines.drop(savedIdx))
                }
            } catch (e: java.io.FileNotFoundException) {
                settings.lastUri = null
                statusText = "Datei nicht mehr vorhanden"
                Log.e("DOC", "File not found: $uri", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                statusText = "Abgebrochen"
                throw e
            } catch (e: Exception) {
                statusText = "FEHLER: ${e.javaClass.simpleName}: ${e.message}"
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            ) { Text("Öffnen") }

            RoundBtn(onClick = {
                isSpeaker = !isSpeaker
                settings.isSpeaker = isSpeaker
                onAudioOutputChange(isSpeaker)
            }) { Text(if (isSpeaker) "🔊" else "👂") }

            RoundBtn(
                onClick = { showToc = true },
                enabled = tocHeadings.isNotEmpty()
            ) { Text("≡", style = MaterialTheme.typography.titleMedium) }

            Spacer(Modifier.weight(1f))

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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundBtn(onClick = onSkipBackward, enabled = canControl) { Text("«30s", style = MaterialTheme.typography.labelSmall) }

            Button(
                onClick = {
                    if (isPlaying) { savePosition(); onPause() } else onResume()
                },
                enabled = canControl,
                contentPadding = btnPadding,
                modifier = Modifier.widthIn(min = 110.dp)
            ) { Text(if (isPlaying) "Pause" else "Laut Lesen") }

            RoundBtn(onClick = { if (sleepMinutesLeft > 0) cancelSleepTimer() else startSleepTimer() }) { Text(if (sleepMinutesLeft > 0) "${sleepMinutesLeft}m✕" else "30m", style = MaterialTheme.typography.labelSmall) }

            RoundBtn(onClick = onSkipForward, enabled = canControl) { Text("30s»", style = MaterialTheme.typography.labelSmall) }

            Spacer(Modifier.weight(1f))

            RoundBtn(onClick = {
                volume = (volume - 0.1f).coerceAtLeast(0.1f)
                    .let { Math.round(it * 10) / 10f }
                settings.volume = volume; onVolumeChange(volume)
            }) { Text("−", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

            Text("🔊%.0f%%".format(volume * 100), style = MaterialTheme.typography.bodyMedium)

            RoundBtn(onClick = {
                volume = (volume + 0.1f).coerceAtMost(1.5f)
                    .let { Math.round(it * 10) / 10f }
                settings.volume = volume; onVolumeChange(volume)
            }) { Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { loadJob?.cancel(); statusText = "Wird abgebrochen…" },
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .fillMaxWidth()
            ) { Text("Abbrechen") }
        }

        val allLines = documentData?.textLines
        val firstHighlight = currentHighlightLines.firstOrNull()
        val progressSuffix = if (firstHighlight != null && allLines != null && allLines.isNotEmpty()) {
            val idx = allLines.indexOf(firstHighlight)
            if (idx >= 0) {
                val pct = (idx.toFloat() / allLines.size * 100).toInt()
                val pageInfo = if (documentData is DocumentData.Pdf)
                    " · Seite ${firstHighlight.page + 1}/${(documentData as DocumentData.Pdf).pages.size}"
                else ""
                "  📍 $pct%$pageInfo"
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
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(36.dp)
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
