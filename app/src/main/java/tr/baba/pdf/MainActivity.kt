package tr.baba.pdf

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        val initial: Uri? = intent?.data
        setContent {
            val scheme = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFB9AAF0), background = Color(0xFF25232E), surface = Color(0xFF2F2C3B))
            } else {
                lightColorScheme(primary = Color(0xFF7C6FD6), background = Color(0xFFF8F5FC), surface = Color(0xFFEEE8F8))
            }
            MaterialTheme(colorScheme = scheme) { App(initial) }
        }
    }
}

private val INVERT = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(initialUri: Uri?) {
    val ctx = LocalContext.current
    val doc = remember { DocState(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var invert by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var hits by remember { mutableStateOf<List<PdfOps.Hit>?>(null) }
    var pendingWrite by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { doc.open(uri) }
    }
    val mergeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
        if (list.isNotEmpty()) scope.launch { doc.merge(list) }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) pendingWrite?.invoke(uri)
    }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) pendingWrite?.invoke(uri)
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) pendingWrite?.invoke(uri)
    }

    LaunchedEffect(Unit) { initialUri?.let { doc.open(it) } }
    LaunchedEffect(doc.message) {
        doc.message?.let {
            snack.showSnackbar(it)
            doc.message = null
        }
    }

    val base = doc.name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "belge" }
    val hasDoc = doc.file != null

    val renderer = remember(doc.file, doc.version) { doc.file?.let { PageRenderer(it) } }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (hasDoc) doc.name else "Baba PDF", maxLines = 1) },
                actions = {
                    if (hasDoc) TextButton(onClick = { dialog = "search" }) { Text("Ara") }
                    TextButton(onClick = { menu = true }) { Text("Menü") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("PDF aç") { menu = false; openLauncher.launch(arrayOf("application/pdf")) }
                        MenuItem("PDF birleştir") { menu = false; mergeLauncher.launch(arrayOf("application/pdf")) }
                        if (hasDoc) {
                            MenuItem("Kaydet (PDF)") {
                                menu = false
                                pendingWrite = { u -> scope.launch { doc.exportPdf(u) } }
                                savePdf.launch("$base.pdf")
                            }
                            MenuItem("Sayfaları çıkar (böl)") { menu = false; dialog = "extract" }
                            MenuItem("Sayfaları sil") { menu = false; dialog = "delete" }
                            MenuItem("Sağa döndür 90°") { menu = false; dialog = "rotR" }
                            MenuItem("Sola döndür 90°") { menu = false; dialog = "rotL" }
                            MenuItem("180° döndür") { menu = false; dialog = "rot180" }
                            MenuItem("Küçült") { menu = false; dialog = "compress" }
                            MenuItem("Şifreli kopya kaydet") { menu = false; dialog = "encrypt" }
                            MenuItem("Metni dışa aktar (.txt)") {
                                menu = false
                                pendingWrite = { u -> scope.launch { doc.exportText(u) } }
                                saveTxt.launch("$base.txt")
                            }
                            MenuItem("Bu sayfayı PNG kaydet") {
                                menu = false
                                val idx = current
                                pendingWrite = { u -> scope.launch { doc.exportPng(u, idx) } }
                                savePng.launch("${base}_sayfa${idx + 1}.png")
                            }
                            if (doc.undoCount > 0) MenuItem("Geri al") { menu = false; doc.undo() }
                            MenuItem(if (invert) "Koyu okumayı kapat" else "Koyu okuma") { menu = false; invert = !invert }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (hasDoc) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }) { Text("−") }
                    TextButton(onClick = { dialog = "goto" }) { Text("${current + 1} / ${doc.pageCount}") }
                    TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (!hasDoc) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Baba PDF", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Belgeler telefondan çıkmaz.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                    )
                    Button(onClick = { openLauncher.launch(arrayOf("application/pdf")) }) { Text("PDF aç") }
                    TextButton(onClick = { mergeLauncher.launch(arrayOf("application/pdf")) }) { Text("PDF birleştir") }
                }
            } else if (renderer != null) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF8E8AA3))) {
                    val widthDp = maxWidth * zoom
                    val widthPx = with(LocalDensity.current) { widthDp.roundToPx() }.coerceIn(100, 3000)
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(
                            Modifier.width(widthDp).fillMaxHeight(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(count = doc.pageCount, key = { it }) { i ->
                                PageItem(renderer, i, widthPx, invert)
                            }
                        }
                    }
                }
            }
            if (doc.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    // ---- Diyaloglar ----
    val total = doc.pageCount
    val all = "1-$total"
    when (dialog) {
        "search" -> InputDialog("Belgede ara", "Aranacak metin", "", { q ->
            dialog = null
            scope.launch { hits = doc.search(q).also { if (it.isEmpty()) doc.message = "Sonuç yok" } }
        }, { dialog = null })

        "goto" -> InputDialog("Sayfaya git", "Sayfa numarası (1-$total)", "${current + 1}", { v ->
            dialog = null
            val n = v.trim().toIntOrNull()
            if (n != null && n in 1..total) {
                scope.launch { listState.scrollToItem(n - 1) }
            } else {
                doc.message = "Geçersiz sayfa numarası"
            }
        }, { dialog = null }, number = true)

        "extract" -> InputDialog("Sayfaları çıkar", "Sayfa aralığı (örn. 1-3,5)", all, { v ->
            dialog = null
            scope.launch { doc.transform("Sayfalar çıkarıldı") { s, o -> PdfOps.keep(s, o, PdfOps.parseRange(v, total)) } }
        }, { dialog = null })

        "delete" -> InputDialog("Sayfaları sil", "Silinecek sayfalar (örn. 2,4-6)", "", { v ->
            dialog = null
            scope.launch { doc.transform("Sayfalar silindi") { s, o -> PdfOps.delete(s, o, PdfOps.parseRange(v, total)) } }
        }, { dialog = null })

        "rotR", "rotL", "rot180" -> {
            val deg = when (dialog) { "rotR" -> 90; "rotL" -> 270; else -> 180 }
            InputDialog("Döndür", "Sayfa aralığı", all, { v ->
                dialog = null
                scope.launch { doc.transform("Döndürüldü") { s, o -> PdfOps.rotate(s, o, PdfOps.parseRange(v, total), deg) } }
            }, { dialog = null })
        }

        "encrypt" -> InputDialog("Şifreli kopya", "Açma şifresi", "", { pw ->
            dialog = null
            if (pw.isBlank()) {
                doc.message = "Şifre boş olamaz"
            } else {
                pendingWrite = { u -> scope.launch { doc.exportEncrypted(u, pw) } }
                savePdf.launch("${base}_sifreli.pdf")
            }
        }, { dialog = null }, password = true)

        "compress" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Küçült") },
            text = {
                Column {
                    Text("Sayfalar JPEG görüntüye çevrilir; metin seçme ve arama kaybolur. Sonuç büyükse uygulanmaz, Geri al kullanılabilir.")
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(150, 0.75f) } }) { Text("Hafif (150 dpi)") }
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(110, 0.65f) } }) { Text("Orta (110 dpi)") }
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(80, 0.55f) } }) { Text("Güçlü (80 dpi)") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Vazgeç") } }
        )
    }

    if (doc.needPassword) {
        InputDialog("Şifreli belge", "Belge şifresi", "", { pw ->
            scope.launch { doc.submitPassword(pw) }
        }, { doc.cancelPassword() }, password = true)
    }

    hits?.let { list ->
        AlertDialog(
            onDismissRequest = { hits = null },
            title = { Text("${list.size} sonuç") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                hits = null
                                scope.launch { listState.scrollToItem(h.page - 1) }
                            }.padding(vertical = 8.dp)
                        ) {
                            Text("Sayfa ${h.page}", style = MaterialTheme.typography.labelMedium)
                            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { hits = null }) { Text("Kapat") } }
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun PageItem(renderer: PageRenderer, index: Int, widthPx: Int, invert: Boolean) {
    val bmp by produceState<android.graphics.Bitmap?>(null, renderer, index, widthPx) {
        value = try {
            renderer.render(index, widthPx)
        } catch (e: Exception) {
            null
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Sayfa ${index + 1}",
            modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height),
            colorFilter = if (invert) ColorFilter.colorMatrix(INVERT) else null
        )
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    }
}

@Composable
private fun InputDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    number: Boolean = false,
    password: Boolean = false
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        password -> KeyboardType.Password
                        number -> KeyboardType.Number
                        else -> KeyboardType.Text
                    }
                )
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Tamam") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
