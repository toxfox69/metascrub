package com.energenai.metascrub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// Dark theme colors
private val DarkBg = Color(0xFF0D0D0D)
private val DarkSurface = Color(0xFF1A1A2E)
private val DarkCard = Color(0xFF16213E)
private val Accent = Color(0xFF00FF88)
private val AccentDim = Color(0xFF00CC6A)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF888888)
private val ErrorRed = Color(0xFFFF4444)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if launched via share intent
        val sharedUrl = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.extractUrl()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        setContent {
            MetaScrubApp(initialUrl = sharedUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

private fun String.extractUrl(): String {
    // Extract URL from shared text (might contain extra text around the URL)
    val urlPattern = Regex("https?://[^\\s]+")
    return urlPattern.find(this)?.value ?: this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaScrubApp(initialUrl: String? = null) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(initialUrl ?: "") }
    var maxDepth by remember { mutableIntStateOf(1) }
    var maxPages by remember { mutableIntStateOf(25) }
    var sameDomainOnly by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScrubResult?>(null) }
    var resultText by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("") }
    var progressScraped by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-scrub if launched via share intent
    LaunchedEffect(initialUrl) {
        if (initialUrl != null && initialUrl.startsWith("http")) {
            isLoading = true
            errorMessage = null
            try {
                val scrubber = SpiderScrubber(
                    maxDepth = maxDepth,
                    maxPages = maxPages,
                    sameDomainOnly = sameDomainOnly
                ) { scraped, queued, current ->
                    progressScraped = scraped
                    progressText = "Scrubbing ($scraped pages, $queued queued)...\n${current.take(60)}"
                }
                result = scrubber.scrub(initialUrl)
                resultText = result!!.toText()
            } catch (e: Exception) {
                errorMessage = e.message
            }
            isLoading = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = DarkSurface,
            primary = Accent,
            onPrimary = DarkBg,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            error = ErrorRed
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        "META",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Accent,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "SCRUB",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            Icons.Default.Settings,
                            "Settings",
                            tint = if (showSettings) Accent else TextSecondary
                        )
                    }
                }

                // URL Input
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL to scrub") },
                    placeholder = { Text("https://example.com/docs") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { focusManager.clearFocus() }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent,
                        focusedLabelColor = Accent
                    ),
                    trailingIcon = {
                        if (url.isNotBlank()) {
                            IconButton(onClick = { url = "" }) {
                                Icon(Icons.Default.Clear, "Clear", tint = TextSecondary)
                            }
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                // Settings panel
                if (showSettings) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Spider Settings", fontWeight = FontWeight.Bold, color = Accent, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))

                            // Max depth
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Depth: $maxDepth", color = TextPrimary, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = maxDepth.toFloat(),
                                    onValueChange = { maxDepth = it.toInt() },
                                    valueRange = 0f..5f,
                                    steps = 4,
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = AccentDim),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Max pages
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Pages: $maxPages", color = TextPrimary, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = maxPages.toFloat(),
                                    onValueChange = { maxPages = it.toInt() },
                                    valueRange = 5f..100f,
                                    steps = 18,
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = AccentDim),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Same domain only
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Same domain only", color = TextPrimary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = sameDomainOnly,
                                    onCheckedChange = { sameDomainOnly = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = AccentDim.copy(alpha = 0.4f))
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Scrub button
                Button(
                    onClick = {
                        if (url.isBlank()) return@Button
                        val targetUrl = if (!url.startsWith("http")) "https://$url" else url
                        focusManager.clearFocus()
                        isLoading = true
                        errorMessage = null
                        result = null
                        resultText = ""

                        scope.launch {
                            try {
                                val scrubber = SpiderScrubber(
                                    maxDepth = maxDepth,
                                    maxPages = maxPages,
                                    sameDomainOnly = sameDomainOnly
                                ) { scraped, queued, current ->
                                    progressScraped = scraped
                                    progressText = "Scrubbing ($scraped pages, $queued queued)...\n${current.take(60)}"
                                }
                                result = scrubber.scrub(targetUrl)
                                resultText = result!!.toText()
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Unknown error"
                            }
                            isLoading = false
                        }
                    },
                    enabled = url.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = DarkBg,
                        disabledContainerColor = AccentDim.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = DarkBg,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("SCRUBBING...", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    } else {
                        Icon(Icons.Default.Search, "Scrub")
                        Spacer(Modifier.width(8.dp))
                        Text("SCRUB", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    }
                }

                // Progress
                if (isLoading && progressText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            LinearProgressIndicator(
                                progress = { (progressScraped.toFloat() / maxPages).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent,
                                trackColor = DarkBg
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                progressText,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Error
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Error: $errorMessage",
                            color = ErrorRed,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Results
                if (result != null && resultText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))

                    // Stats bar
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatChip("Pages", "${result!!.pages.size}")
                            StatChip("Links", "${result!!.totalLinks}")
                            StatChip("Time", "${result!!.elapsedMs}ms")
                            StatChip("Size", "${resultText.length / 1024}KB")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Copy to clipboard
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("MetaScrub", resultText))
                                Toast.makeText(context, "Copied ${resultText.length / 1024}KB to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("COPY", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Share as text
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, resultText)
                                    putExtra(Intent.EXTRA_SUBJECT, "MetaScrub: ${result!!.rootUrl}")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share scrub"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, "Share", Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SHARE", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Share as .txt file attachment
                        OutlinedButton(
                            onClick = {
                                val file = saveScrubToFile(context, resultText, result!!.rootUrl)
                                if (file != null) {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "MetaScrub: ${result!!.rootUrl}")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Attach scrub file"))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, "File", Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("FILE", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Result preview
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.2f))
                    ) {
                        SelectionContainer {
                            Text(
                                text = resultText,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}

private fun saveScrubToFile(context: Context, text: String, url: String): File? {
    return try {
        val dir = File(context.cacheDir, "scrubs")
        dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val domain = try { java.net.URI(url).host?.replace("www.", "") ?: "scrub" } catch (_: Exception) { "scrub" }
        val file = File(dir, "metascrub_${domain}_$timestamp.txt")
        file.writeText(text)
        file
    } catch (_: Exception) {
        null
    }
}
