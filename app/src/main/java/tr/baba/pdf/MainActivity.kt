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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial: Uri? = intent?.data ?: (
            @Suppress("DEPRECATION") intent?.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
        )
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

private val SEPIA = ColorMatrix(
    floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
)

private val MODE_NAMES = listOf("Normal", "Koyu", "Sepya")

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
    var pageMode by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var hits by remember { mutableStateOf<List<PdfOps.Hit>>(emptyList()) }
    var hitIdx by remember { mutableIntStateOf(0) }
    var showHits by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf<List<PdfOps.Mark>>(emptyList()) }
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingWrite by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var report by remember { mutableStateOf("") }
    var atts by remember { mutableStateOf<List<PdfOps2.Att>>(emptyList()) }
    var pickedImage by remember { mutableStateOf<ByteArray?>(null) }

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
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) pendingWrite?.invoke(uri)
    }
    val saveAny = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingWrite?.invoke(uri)
    }
    val compareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            report = doc.compareWith(uri)
            dialog = "report"
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                pickedImage = doc.readBytes(uri)
                dialog = "placeimg"
            } catch (e: Exception) {
                doc.message = "Resim okunamadı"
            }
        }
    }

    var formList by remember { mutableStateOf<List<PdfOps4.Field>>(emptyList()) }
    val formEdits = remember { mutableStateMapOf<Pair<Int, Int>, String>() }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
        if (list.isNotEmpty()) scope.launch { doc.addImages(list) }
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        if (ok && u != null) scope.launch { doc.addImages(listOf(u)) }
    }
    var showGrid by remember { mutableStateOf(false) }
    var editMarkIdx by remember { mutableIntStateOf(0) }
    val saveZipImg = saveZip
    var p12Uri by remember { mutableStateOf<Uri?>(null) }
    val p12Launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            p12Uri = uri
            dialog = "sign"
        }
    }

    LaunchedEffect(Unit) { initialUri?.let { doc.open(it) } }
    LaunchedEffect(doc.message) {
        doc.message?.let {
            snack.showSnackbar(it)
            doc.message = null
        }
    }

    val base = doc.name.substringBeforeLast('.', doc.name).ifBlank { "belge" }
    val hasDoc = doc.file != null
    val total = doc.pageCount
    val all = "1-$total"

    val renderer = remember(doc.file, doc.version) { doc.file?.let { PageRenderer(it) } }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }

    fun goTo(page1: Int) {
        scope.launch { listState.scrollToItem((page1 - 1).coerceIn(0, (total - 1).coerceAtLeast(0))) }
    }

    fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        hitIdx = ((hitIdx + delta) % hits.size + hits.size) % hits.size
        goTo(hits[hitIdx].page)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (hasDoc) doc.name else "Baba PDF", maxLines = 1) },
                actions = {
                    if (hasDoc) TextButton(onClick = { dialog = "search" }) { Text("Ara") }
                    TextButton(onClick = { menu = true }) { Text("Menü") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false; menuFilter = "" }) {
                        OutlinedTextField(
                            value = menuFilter, onValueChange = { menuFilter = it },
                            label = { Text("Komut ara…") }, singleLine = true,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Header("Dosya")
                        MenuItem("Belge aç (PDF, e-kitap)") { menu = false; openLauncher.launch(arrayOf("*/*")) }
                        MenuItem("Kamerayla sayfa tara / ekle") {
                            menu = false
                            try {
                                val dir = java.io.File(ctx.cacheDir, "cam").also { it.mkdirs() }
                                val f = java.io.File(dir, "c${System.nanoTime()}.jpg")
                                val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                camUri = u
                                camLauncher.launch(u)
                            } catch (e: Exception) {
                                doc.message = "Kamera açılamadı: ${e.message}"
                            }
                        }
                        MenuItem("Resimlerden PDF yap / sayfa ekle") { menu = false; imagesLauncher.launch(arrayOf("image/*")) }
                        MenuItem("Belge ekle / birleştir") { menu = false; mergeLauncher.launch(arrayOf("*/*")) }
                        if (hasDoc) {
                            if (doc.canOverwrite) MenuItem("Kaydet (özgün dosyanın üzerine)") { menu = false; dialog = "overwrite" }
                            MenuItem("Farklı kaydet (PDF)") {
                                menu = false
                                pendingWrite = { u -> scope.launch { doc.exportPdf(u) } }
                                savePdf.launch("$base.pdf")
                            }
                            Header("Düzenle")
                            if (doc.undoCount > 0) MenuItem("Geri al") { menu = false; doc.undo() }
                            if (doc.redoCount > 0) MenuItem("Yinele") { menu = false; doc.redo() }
                            Header("Sayfalar")
                            MenuItem("Sağa döndür 90°") { menu = false; dialog = "rotR" }
                            MenuItem("Sola döndür 90°") { menu = false; dialog = "rotL" }
                            MenuItem("180° döndür") { menu = false; dialog = "rot180" }
                            MenuItem("Sayfaları sil") { menu = false; dialog = "delete" }
                            MenuItem("Sayfaları çıkar") { menu = false; dialog = "extract" }
                            MenuItem("Boş sayfa ekle") { menu = false; dialog = "blank" }
                            MenuItem("Sayfayı taşı") { menu = false; dialog = "move" }
                            MenuItem("Böl (ZIP içinde parçalar)") { menu = false; dialog = "split" }
                            Header("Yer imleri")
                            MenuItem("Yer imlerini göster") {
                                menu = false
                                scope.launch {
                                    marks = doc.bookmarks()
                                    dialog = "marks"
                                }
                            }
                            MenuItem("Bu sayfaya yer imi ekle") { menu = false; dialog = "addmark" }
                            Header("Açıklamalar")
                            MenuItem("Metni vurgula / altını çiz / üstünü çiz") { menu = false; dialog = "mark" }
                            MenuItem("Bu sayfaya not ekle") { menu = false; dialog = "note" }
                            MenuItem("Açıklamaları sil") {
                                menu = false
                                scope.launch {
                                    doc.transformWith<Int>({ n -> "$n açıklama silindi" }) { s, o -> PdfOps2.deleteAnnotations(s, o) }
                                }
                            }
                            MenuItem("Açıklamaları sayfaya işle (düzleştir)") {
                                menu = false
                                scope.launch { doc.transform("Açıklamalar sayfaya işlendi") { s, o -> PdfOps2.flatten(s, o) } }
                            }
                            Header("Filigran, damga, bindirme")
                            MenuItem("Filigran / metin damgası") { menu = false; dialog = "stamp" }
                            MenuItem("Üst-alt bilgi ve sayfa numarası") { menu = false; dialog = "header" }
                            MenuItem("Görsel / imza resmi yerleştir") { menu = false; imageLauncher.launch(arrayOf("image/*")) }
                            Header("Dışa aktar")
                            MenuItem("Metin (.txt)") { menu = false; dialog = "exptxt" }
                            MenuItem("Sayfa aralığı resim (ZIP)") { menu = false; dialog = "exppng" }
                            MenuItem("Bu sayfayı PNG kaydet") {
                                menu = false
                                val idx = current
                                pendingWrite = { u -> scope.launch { doc.exportPng(u, idx) } }
                                savePng.launch("${base}_sayfa${idx + 1}.png")
                            }
                            Header("Güvenlik")
                            MenuItem("Şifreli kopya kaydet") { menu = false; dialog = "encrypt" }
                            MenuItem("İzinleri değiştirerek kopya kaydet") { menu = false; dialog = "perm" }
                            MenuItem("Dijital imzala (.p12 / .pfx)") { menu = false; p12Launcher.launch(arrayOf("*/*")) }
                            MenuItem("İmzaları doğrula ve rapor") {
                                menu = false
                                scope.launch {
                                    report = doc.signReport()
                                    dialog = "sigreport"
                                }
                            }
                            MenuItem("Metni kalıcı karart (redaction)") { menu = false; dialog = "redact" }
                            MenuItem("Gizli veri ve belge bilgilerini temizle") {
                                menu = false
                                scope.launch { doc.transform("Gizli veriler temizlendi") { s, o -> PdfOps2.sanitize(s, o) } }
                            }
                            Header("Araçlar")
                            MenuItem("PDF boyutunu küçült") { menu = false; dialog = "compress" }
                            MenuItem("OCR: taranmış PDF'i aranabilir yap") { menu = false; dialog = "ocrpdf" }
                            MenuItem("OCR: sayfaları metne çıkar (.txt)") { menu = false; dialog = "ocrtxt" }
                            MenuItem("Form doldur") {
                                menu = false
                                scope.launch {
                                    val l = doc.formFields()
                                    if (l.isEmpty()) {
                                        doc.message = "Bu belgede doldurulabilir form alanı yok"
                                    } else {
                                        formEdits.clear()
                                        formList = l
                                        dialog = "form"
                                    }
                                }
                            }
                            MenuItem("Sayfa ızgarası (küçük resimler)") { menu = false; showGrid = true }
                            MenuItem("Gömülü görselleri ayıkla (ZIP)") { menu = false; dialog = "imgs" }
                            MenuItem("Sayfa boyutunu değiştir (A4 vb.)") { menu = false; dialog = "psize" }
                            MenuItem("Yan/ters sayfaları otomatik döndür") {
                                menu = false
                                scope.launch {
                                    doc.transformWith<Int>({ n -> if (n == 0) "Düzeltilecek sayfa bulunamadı" else "$n sayfa döndürüldü" }) { s, o ->
                                        PdfOps3.autoRotate(s, o)
                                    }
                                }
                            }
                            MenuItem("Sayfa kenarlarını kırp") { menu = false; dialog = "crop" }
                            MenuItem("Yazdır") {
                                menu = false
                                doc.file?.let { f ->
                                    val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                                    pm.print(base, object : android.print.PrintDocumentAdapter() {
                                        override fun onLayout(
                                            o: android.print.PrintAttributes?, n: android.print.PrintAttributes?,
                                            c: android.os.CancellationSignal?, cb: LayoutResultCallback, e: android.os.Bundle?
                                        ) {
                                            cb.onLayoutFinished(
                                                android.print.PrintDocumentInfo.Builder("$base.pdf")
                                                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                                    .build(), true
                                            )
                                        }

                                        override fun onWrite(
                                            pages: Array<out android.print.PageRange>?, d: android.os.ParcelFileDescriptor,
                                            c: android.os.CancellationSignal?, cb: WriteResultCallback
                                        ) {
                                            try {
                                                java.io.FileOutputStream(d.fileDescriptor).use { o -> f.inputStream().use { it.copyTo(o) } }
                                                cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                                            } catch (ex: Exception) {
                                                cb.onWriteFailed(ex.message)
                                            }
                                        }
                                    }, null)
                                }
                            }
                            MenuItem("Boş sayfaları sil") {
                                menu = false
                                scope.launch {
                                    doc.transformWith<Int>({ n -> if (n == 0) "Boş sayfa bulunamadı" else "$n boş sayfa silindi" }) { s, o ->
                                        PdfOps2.removeBlankPages(s, o)
                                    }
                                }
                            }
                            MenuItem("Sağlık denetimi") {
                                menu = false
                                scope.launch {
                                    report = doc.check()
                                    dialog = "report"
                                }
                            }
                            MenuItem("Bozuk dosyayı onar (yeniden yaz)") {
                                menu = false
                                scope.launch { doc.transform("Dosya yeniden yazıldı") { s, o -> PdfOps2.repair(s, o) } }
                            }
                            MenuItem("İki PDF'i karşılaştır") { menu = false; compareLauncher.launch(arrayOf("*/*")) }
                            MenuItem("Gömülü ekler") {
                                menu = false
                                scope.launch {
                                    atts = doc.attachments()
                                    dialog = "atts"
                                }
                            }
                            MenuItem("Belge bilgileri") {
                                menu = false
                                scope.launch {
                                    info = doc.info()
                                    dialog = "info"
                                }
                            }
                            MenuItem("Metadata düzenle") {
                                menu = false
                                scope.launch {
                                    info = doc.info()
                                    dialog = "meta"
                                }
                            }
                        }
                        Header("Görünüm")
                        MenuItem("Sayfa rengi: ${MODE_NAMES[pageMode]} (değiştir)") { menu = false; pageMode = (pageMode + 1) % 3 }
                        MenuItem("E-kitap yazı boyutu") { menu = false; dialog = "bookfont" }
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
                    if (hits.isNotEmpty()) {
                        TextButton(onClick = { stepHit(-1) }) { Text("‹") }
                        TextButton(onClick = { showHits = true }) { Text("${hitIdx + 1}/${hits.size}") }
                        TextButton(onClick = { stepHit(1) }) { Text("›") }
                        TextButton(onClick = {
                            hits = emptyList()
                            doc.query = ""
                        }) { Text("✕") }
                    }
                    TextButton(onClick = { dialog = "goto" }) { Text("${current + 1} / ${doc.pageCount}") }
                    TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (!hasDoc) {
                val recents = remember(doc.recentVersion) { Recent.list(ctx) }
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
                    Button(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text("Belge aç (PDF / e-kitap)") }
                    TextButton(onClick = { mergeLauncher.launch(arrayOf("*/*")) }) { Text("Belgeleri birleştir") }
                    if (recents.isNotEmpty()) {
                        Text("Son açılanlar", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
                        recents.take(6).forEach { (nm, u) ->
                            TextButton(onClick = { scope.launch { doc.open(Uri.parse(u)) } }) { Text(nm, maxLines = 1) }
                        }
                        TextButton(onClick = { doc.clearRecent() }) { Text("Listeyi temizle") }
                    }
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
                                PageItem(renderer, i, widthPx, pageMode, doc.query)
                            }
                        }
                    }
                }
            }
            if (doc.busy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (doc.progress >= 0f) {
                        LinearProgressIndicator(progress = { doc.progress }, modifier = Modifier.weight(1f))
                    } else {
                        LinearProgressIndicator(Modifier.weight(1f))
                    }
                    TextButton(onClick = { doc.cancel() }) { Text("İptal") }
                }
            }
        }
    }

    val doStamp: (List<String>) -> Unit = { v ->
        val size = v[1].trim().toFloatOrNull()
        val anchor = v[2].trim().toIntOrNull()
        val angle = v[3].trim().toFloatOrNull()
        val op = v[4].trim().toFloatOrNull()
        if (v[0].isBlank() || size == null || anchor == null || angle == null || op == null) {
            doc.message = "Değerleri kontrol edin"
        } else {
            try {
                val pages = PdfOps.parseRange(v[6], total)
                val color = PdfOps2.parseColor(v[5])
                scope.launch {
                    doc.transform("Eklendi") { s, o ->
                        PdfOps2.stampText(s, o, pages, v[0], size, color, anchor, angle, (op / 100f).coerceIn(0.05f, 1f))
                    }
                }
            } catch (e: Exception) {
                doc.message = e.message
            }
        }
    }

    // ---- Diyaloglar ----
    when (dialog) {
        "search" -> InputDialog("Belgede ara", "Aranacak metin", doc.query, { q ->
            dialog = null
            if (q.isNotBlank()) {
                doc.query = q.trim()
                scope.launch {
                    hits = doc.search(q)
                    hitIdx = 0
                    if (hits.isEmpty()) doc.message = "Sonuç yok" else showHits = true
                }
            }
        }, { dialog = null })

        "goto" -> InputDialog("Sayfaya git", "Sayfa numarası (1-$total)", "${current + 1}", { v ->
            dialog = null
            val n = v.trim().toIntOrNull()
            if (n != null && n in 1..total) {
                goTo(n)
            } else {
                doc.message = "Geçersiz sayfa numarası"
            }
        }, { dialog = null }, number = true)

        "overwrite" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Özgün dosya güncellensin mi?") },
            text = { Text("Yaptığınız değişiklikler açtığınız dosyanın üzerine yazılır. Geri dönüşü için önce Farklı kaydet ile yedek alabilirsiniz.") },
            confirmButton = { TextButton(onClick = { dialog = null; scope.launch { doc.saveOriginal() } }) { Text("Üzerine yaz") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Vazgeç") } }
        )

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

        "blank" -> InputDialog("Boş sayfa ekle", "Kaçıncı sayfadan sonra? (0 = en başa)", "${current + 1}", { v ->
            dialog = null
            val at = v.trim().toIntOrNull()
            if (at == null) {
                doc.message = "Geçersiz sayı"
            } else {
                scope.launch { doc.transform("Boş sayfa eklendi") { s, o -> PdfOps.blankPage(s, o, at) } }
            }
        }, { dialog = null }, number = true)

        "move" -> MultiInputDialog(
            "Sayfayı taşı",
            listOf("Taşınacak sayfa (1-$total)", "Yeni konum (1-$total)"),
            listOf("${current + 1}", "1"),
            { v ->
                dialog = null
                val a = v[0].trim().toIntOrNull()
                val b = v[1].trim().toIntOrNull()
                if (a == null || b == null) {
                    doc.message = "Geçersiz sayfa numarası"
                } else {
                    scope.launch { doc.transform("Sayfa taşındı") { s, o -> PdfOps.movePage(s, o, a - 1, b - 1) } }
                }
            },
            { dialog = null }
        )

        "split" -> InputDialog("Böl", "Kaç sayfada bir parça oluşsun?", "1", { v ->
            dialog = null
            val n = v.trim().toIntOrNull()
            if (n == null || n < 1) {
                doc.message = "Geçersiz sayı"
            } else {
                pendingWrite = { u -> scope.launch { doc.exportSplitZip(u, n) } }
                saveZip.launch("${base}_parcalar.zip")
            }
        }, { dialog = null }, number = true)

        "exptxt" -> InputDialog("Metni dışa aktar", "Sayfa aralığı", all, { v ->
            dialog = null
            try {
                val pages = PdfOps.parseRange(v, total)
                pendingWrite = { u -> scope.launch { doc.exportText(u, pages) } }
                saveTxt.launch("$base.txt")
            } catch (e: Exception) {
                doc.message = e.message
            }
        }, { dialog = null })

        "exppng" -> InputDialog("Resim olarak dışa aktar", "Sayfa aralığı", "${current + 1}", { v ->
            dialog = null
            try {
                val pages = PdfOps.parseRange(v, total)
                pendingWrite = { u -> scope.launch { doc.exportPngZip(u, pages) } }
                saveZip.launch("${base}_resimler.zip")
            } catch (e: Exception) {
                doc.message = e.message
            }
        }, { dialog = null })

        "encrypt" -> InputDialog("Şifreli kopya", "Açma şifresi", "", { pw ->
            dialog = null
            if (pw.isBlank()) {
                doc.message = "Şifre boş olamaz"
            } else {
                pendingWrite = { u -> scope.launch { doc.exportEncrypted(u, pw) } }
                savePdf.launch("${base}_sifreli.pdf")
            }
        }, { dialog = null }, password = true)

        "addmark" -> InputDialog("Yer imi ekle", "Başlık (sayfa ${current + 1})", "Sayfa ${current + 1}", { t ->
            dialog = null
            val pg = current + 1
            if (t.isBlank()) {
                doc.message = "Başlık boş olamaz"
            } else {
                scope.launch { doc.transform("Yer imi eklendi") { s, o -> PdfOps.addBookmark(s, o, t.trim(), pg) } }
            }
        }, { dialog = null })

        "mark" -> MultiInputDialog(
            "Metni işaretle",
            listOf("Aranacak metin", "Tür (1 vurgu, 2 altı çizili, 3 üstü çizili)", "Renk (sarı, yeşil, pembe, mavi, kırmızı)", "Sayfa aralığı"),
            listOf(doc.query, "1", "sarı", all),
            { v ->
                dialog = null
                val kind = v[1].trim().toIntOrNull() ?: 1
                try {
                    val pages = PdfOps.parseRange(v[3], total)
                    if (v[0].isBlank()) {
                        doc.message = "Aranacak metin boş"
                    } else {
                        scope.launch {
                            doc.transformWith<Int>({ n -> if (n == 0) "Eşleşme bulunamadı" else "$n yer işaretlendi" }) { s, o ->
                                PdfOps2.markText(s, o, pages, v[0].trim(), kind, v[2])
                            }
                        }
                    }
                } catch (e: Exception) {
                    doc.message = e.message
                }
            },
            { dialog = null }
        )

        "note" -> InputDialog("Not ekle", "Not metni (sayfa ${current + 1})", "", { t ->
            dialog = null
            val pg = current
            if (t.isBlank()) {
                doc.message = "Not boş olamaz"
            } else {
                scope.launch { doc.transform("Not eklendi") { s, o -> PdfOps2.addNote(s, o, pg, t.trim()) } }
            }
        }, { dialog = null })

        "stamp" -> MultiInputDialog(
            "Filigran / damga",
            listOf("Metin ({n} sayfa no, {N} toplam, {tarih})", "Boyut (pt)", "Konum (1-9, 5 orta)", "Açı (derece)", "Opaklık (%)", "Renk (kırmızı, siyah, gri, mavi, yeşil, turuncu)", "Sayfa aralığı"),
            listOf("TASLAK", "72", "5", "45", "25", "kırmızı", all),
            { v -> dialog = null; doStamp(v) },
            { dialog = null }
        )

        "header" -> MultiInputDialog(
            "Üst-alt bilgi",
            listOf("Metin ({n} sayfa no, {N} toplam, {tarih})", "Boyut (pt)", "Konum (2 üst orta, 8 alt orta)", "Açı (derece)", "Opaklık (%)", "Renk", "Sayfa aralığı"),
            listOf("{n} / {N}", "10", "8", "0", "100", "siyah", all),
            { v -> dialog = null; doStamp(v) },
            { dialog = null }
        )

        "placeimg" -> MultiInputDialog(
            "Görsel / imza yerleştir",
            listOf("Sayfa no (1-$total)", "Konum (1-9, 9 sağ alt)", "Genişlik (sayfa genişliğinin %)", "Opaklık (%)"),
            listOf("${current + 1}", "9", "25", "100"),
            { v ->
                dialog = null
                val pg = v[0].trim().toIntOrNull()
                val anchor = v[1].trim().toIntOrNull()
                val w = v[2].trim().toFloatOrNull()
                val op = v[3].trim().toFloatOrNull()
                val bytes = pickedImage
                if (pg == null || anchor == null || w == null || op == null || bytes == null || pg !in 1..total) {
                    doc.message = "Değerleri kontrol edin"
                } else {
                    scope.launch {
                        doc.transform("Görsel yerleştirildi") { s, o ->
                            PdfOps2.placePicture(s, o, pg - 1, bytes, anchor, w.coerceIn(2f, 100f), (op / 100f).coerceIn(0.05f, 1f))
                        }
                    }
                }
            },
            { dialog = null }
        )

        "redact" -> InputDialog("Metni kalıcı karart", "Karartılacak ifadeler (; ile ayırın)", "", { t ->
            dialog = null
            val terms = t.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (terms.isEmpty()) {
                doc.message = "İfade girilmedi"
            } else {
                scope.launch {
                    doc.transformWith<Int>({ n -> if (n == 0) "Eşleşme bulunamadı" else "$n yer kalıcı olarak karartıldı" }) { s, o ->
                        PdfOps2.redact(s, o, terms)
                    }
                }
            }
        }, { dialog = null })

        "perm" -> PermDialog(
            { owner, print, copy, modify, annotate ->
                dialog = null
                pendingWrite = { u ->
                    scope.launch {
                        val tmp = java.io.File(ctx.cacheDir, "perm${System.nanoTime()}.pdf")
                        val src = doc.file
                        if (src != null) {
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    PdfOps2.restrictPermissions(src, tmp, owner, print, copy, modify, annotate)
                                    ctx.contentResolver.openOutputStream(u, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                                }
                                doc.message = "Kopya kaydedildi"
                            } catch (e: Exception) {
                                doc.message = "Hata: ${e.message}"
                            } finally {
                                tmp.delete()
                            }
                        }
                    }
                }
                savePdf.launch("${base}_izinli.pdf")
            },
            { dialog = null }
        )

        "form" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Form doldur") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(formList.size) { n ->
                        val f = formList[n]
                        val key = f.page to f.index
                        val label = (f.name.ifBlank { "Alan ${n + 1}" }) + "  (s. ${f.page + 1})"
                        if (f.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_CHECKBOX || f.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_RADIOBUTTON) {
                            val on = (formEdits[key] ?: if (f.value.isNotEmpty() && f.value != "Off") "1" else "0") == "1"
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = on, enabled = !f.readOnly,
                                    onCheckedChange = { formEdits[key] = if (it) "1" else "0" }
                                )
                                Text(label)
                            }
                        } else {
                            OutlinedTextField(
                                value = formEdits[key] ?: f.value,
                                onValueChange = { formEdits[key] = it },
                                enabled = !f.readOnly,
                                label = { Text(label + if (f.options.isNotEmpty()) " [" + f.options.joinToString(" | ") + "]" else "") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    val ch = HashMap(formEdits.toMap())
                    if (ch.isEmpty()) {
                        doc.message = "Değişiklik yok"
                    } else {
                        scope.launch { doc.transform("Form dolduruldu") { s, o -> PdfOps4.fillForm(s, o, ch) } }
                    }
                }) { Text("Uygula") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Vazgeç") } }
        )

        "editmark" -> MultiInputDialog(
            "Yer imini düzenle",
            listOf("Başlık", "Hedef sayfa (boş: değişmesin)"),
            listOf(marks.getOrNull(editMarkIdx)?.title ?: "", ""),
            { v ->
                dialog = null
                val idx = editMarkIdx
                val pg = v[1].trim().toIntOrNull()
                if (v[0].isBlank()) {
                    doc.message = "Başlık boş olamaz"
                } else {
                    scope.launch { doc.transform("Yer imi güncellendi") { s, o -> PdfOps3.editBookmark(s, o, idx, v[0].trim(), pg) } }
                }
            },
            { dialog = null }
        )

        "imgs" -> InputDialog("Gömülü görselleri ayıkla (ZIP)", "Sayfa aralığı", all, { v ->
            dialog = null
            try {
                val pages = PdfOps.parseRange(v, total)
                pendingWrite = { u -> scope.launch { doc.exportImagesZip(u, pages) } }
                saveZipImg.launch("${base}_gorseller.zip")
            } catch (e: Exception) {
                doc.message = e.message
            }
        }, { dialog = null })

        "psize" -> MultiInputDialog(
            "Sayfa boyutu (A3, A4, A5, Letter veya GENxYÜK pt)",
            listOf("Sayfa aralığı", "Boyut"),
            listOf(all, "A4"),
            { v ->
                dialog = null
                try {
                    val pages = PdfOps.parseRange(v[0], total)
                    val sz = when (v[1].trim().uppercase()) {
                        "A3" -> 842f to 1191f
                        "A4" -> 595f to 842f
                        "A5" -> 420f to 595f
                        "LETTER" -> 612f to 792f
                        "LEGAL" -> 612f to 1008f
                        else -> {
                            val p = v[1].lowercase().split('x')
                            p[0].trim().toFloat() to p[1].trim().toFloat()
                        }
                    }
                    scope.launch { doc.transform("Sayfa boyutu değişti") { s, o -> PdfOps3.pageSize(s, o, pages, sz.first, sz.second) } }
                } catch (e: Exception) {
                    doc.message = "Geçersiz boyut: A4 veya 600x800 biçiminde yazın"
                }
            },
            { dialog = null }
        )

        "crop" -> MultiInputDialog(
            "Kenarları kırp (pt; 1 cm ≈ 28)",
            listOf("Sayfa aralığı", "Sol", "Üst", "Sağ", "Alt"),
            listOf(all, "0", "0", "0", "0"),
            { v ->
                dialog = null
                try {
                    val pages = PdfOps.parseRange(v[0], total)
                    val nums = v.drop(1).map { it.trim().replace(',', '.').toFloat() }
                    scope.launch { doc.transform("Sayfalar kırpıldı") { s, o -> PdfOps2.crop(s, o, pages, nums[0], nums[1], nums[2], nums[3]) } }
                } catch (e: Exception) {
                    doc.message = e.message ?: "Geçersiz değer"
                }
            },
            { dialog = null }
        )

        "ocrpdf" -> InputDialog("OCR (Türkçe+İngilizce) — uzun sürebilir", "Sayfa aralığı", all, { v ->
            dialog = null
            try {
                val pages = PdfOps.parseRange(v, total)
                scope.launch { doc.ocrSearchable(pages) }
            } catch (e: Exception) {
                doc.message = e.message
            }
        }, { dialog = null })

        "ocrtxt" -> InputDialog("OCR metne çıkar", "Sayfa aralığı", all, { v ->
            dialog = null
            try {
                val pages = PdfOps.parseRange(v, total)
                pendingWrite = { u -> scope.launch { doc.ocrText(u, pages) } }
                saveTxt.launch("${base}_ocr.txt")
            } catch (e: Exception) {
                doc.message = e.message
            }
        }, { dialog = null })

        "sign" -> MultiInputDialog(
            "Dijital imza",
            listOf("Sertifika parolası", "Sayfa (1-$total)", "Görünür kutu (e/h)", "Neden (isteğe bağlı)", "Konum (isteğe bağlı)"),
            listOf("", "${current + 1}", "e", "", ""),
            { v ->
                dialog = null
                val u = p12Uri
                val pg = v[1].trim().toIntOrNull()
                if (u == null || pg == null) {
                    doc.message = "Geçersiz sayfa numarası"
                } else {
                    scope.launch { doc.signWith(u, v[0], pg - 1, !v[2].trim().lowercase().startsWith("h"), v[3], v[4]) }
                }
            },
            { dialog = null }
        )

        "sigreport" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("İmza raporu") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    val txt = report
                    pendingWrite = { u -> scope.launch { doc.writeText(u, txt) } }
                    saveTxt.launch("${base}_imza_raporu.txt")
                }) { Text("TXT kaydet") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )

        "report" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Rapor") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )

        "atts" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Gömülü ekler") },
            text = {
                if (atts.isEmpty()) {
                    Text("Bu belgede gömülü ek yok.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(atts.size) { i ->
                            val a = atts[i]
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (a.size >= 0) "${a.size / 1024} KB" else "", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = {
                                    dialog = null
                                    pendingWrite = { u -> scope.launch { doc.exportAttachment(u, i) } }
                                    saveAny.launch(a.name)
                                }) { Text("Kaydet") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )

        "bookfont" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("E-kitap yazı boyutu") },
            text = {
                Column {
                    Text("Sonraki açılan e-kitaplarda geçerli olur; açık kitabı yeniden açın.")
                    listOf("Küçük" to 10f, "Orta" to 12f, "Büyük" to 15f, "Çok büyük" to 18f).forEach { (label, em) ->
                        TextButton(onClick = {
                            Recent.setBookFontSize(ctx, em)
                            dialog = null
                            doc.message = "Yazı boyutu: $label"
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )

        "compress" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Küçült") },
            text = {
                Column {
                    Text("Önce \"Yapıyı sıkıştır\" deneyin (metin korunur). Taranmış belgeler için resme çevirme daha çok küçültür ama metin seçme ve arama kaybolur. Sonuç büyükse uygulanmaz; Geri al kullanılabilir.")
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(false) } }) { Text("Yapıyı sıkıştır (metin korunur)") }
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(true, 150, 0.75f) } }) { Text("Resme çevir – hafif (150 dpi)") }
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(true, 110, 0.65f) } }) { Text("Resme çevir – orta (110 dpi)") }
                    TextButton(onClick = { dialog = null; scope.launch { doc.compress(true, 80, 0.55f) } }) { Text("Resme çevir – güçlü (80 dpi)") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Vazgeç") } }
        )

        "info" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Belge bilgileri") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(info.entries.toList()) { e ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(e.key, style = MaterialTheme.typography.labelMedium)
                            Text(e.value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )

        "meta" -> MultiInputDialog(
            "Metadata düzenle",
            listOf("Başlık", "Yazar", "Konu", "Anahtar sözcükler"),
            listOf(info["Başlık"] ?: "", info["Yazar"] ?: "", info["Konu"] ?: "", info["Anahtar sözcükler"] ?: ""),
            { v ->
                dialog = null
                val values = mapOf(
                    "info:Title" to v[0], "info:Author" to v[1], "info:Subject" to v[2], "info:Keywords" to v[3]
                )
                scope.launch { doc.transform("Metadata güncellendi") { s, o -> PdfOps.setMetadata(s, o, values) } }
            },
            { dialog = null }
        )

        "marks" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Yer imleri") },
            text = {
                if (marks.isEmpty()) {
                    Text("Bu belgede yer imi yok.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(marks.size) { mi ->
                            val m = marks[mi]
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    m.title.ifBlank { "(adsız)" } + if (m.page >= 0) "  ·  s. ${m.page + 1}" else "",
                                    modifier = Modifier.weight(1f)
                                        .clickable {
                                            dialog = null
                                            if (m.page >= 0) goTo(m.page + 1)
                                        }
                                        .padding(start = (m.level * 16).dp, top = 8.dp, bottom = 8.dp)
                                )
                                TextButton(onClick = { editMarkIdx = mi; dialog = "editmark" }) { Text("Düzenle") }
                                TextButton(onClick = {
                                    dialog = null
                                    scope.launch { doc.transform("Yer imi silindi") { s, o -> PdfOps3.deleteBookmark(s, o, mi) } }
                                }) { Text("Sil") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Kapat") } }
        )
    }

    if (showGrid && renderer != null && total > 0) {
        PageGridDialog(
            renderer = renderer,
            total = total,
            onGo = { goTo(it + 1) },
            onDelete = { s -> scope.launch { doc.transform("Sayfalar silindi") { a, o -> PdfOps.delete(a, o, s) } } },
            onRotate = { s, deg -> scope.launch { doc.transform("Sayfalar döndürüldü") { a, o -> PdfOps.rotate(a, o, s, deg) } } },
            onMove = { f, t -> scope.launch { doc.transform("Sayfa taşındı") { a, o -> PdfOps.movePage(a, o, f, t) } } },
            onExtract = { s ->
                pendingWrite = { u -> scope.launch { doc.exportKeep(u, s) } }
                savePdf.launch("${base}_secili.pdf")
            },
            onDismiss = { showGrid = false }
        )
    }

    if (doc.needPassword) {
        InputDialog("Şifreli belge", "Belge şifresi", "", { pw ->
            scope.launch { doc.submitPassword(pw) }
        }, { doc.cancelPassword() }, password = true)
    }

    if (showHits && hits.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showHits = false },
            title = { Text("${hits.size} sonuç") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(hits.size) { i ->
                        val h = hits[i]
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                showHits = false
                                hitIdx = i
                                goTo(h.page)
                            }.padding(vertical = 8.dp)
                        ) {
                            Text("Sayfa ${h.page}", style = MaterialTheme.typography.labelMedium)
                            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHits = false }) { Text("Kapat") } }
        )
    }
}

@Composable
private fun PermDialog(
    onConfirm: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var owner by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(false) }
    var modify by remember { mutableStateOf(false) }
    var annotate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İzinleri değiştir") },
        text = {
            Column {
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("Sahip şifresi (izinleri değiştirmek için)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = print, onCheckedChange = { print = it })
                    Text("Yazdırmaya izin ver")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = copy, onCheckedChange = { copy = it })
                    Text("Metin kopyalamaya izin ver")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = modify, onCheckedChange = { modify = it })
                    Text("Düzenlemeye izin ver")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = annotate, onCheckedChange = { annotate = it })
                    Text("Açıklama eklemeye izin ver")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(owner, print, copy, modify, annotate) }) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

private var menuFilter by mutableStateOf("")

@Composable
private fun Header(text: String) {
    if (menuFilter.isNotBlank()) return
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
        onClick = {},
        enabled = false
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    if (menuFilter.isNotBlank() && !text.contains(menuFilter.trim(), ignoreCase = true)) return
    DropdownMenuItem(text = { Text(text) }, onClick = { menuFilter = ""; onClick() })
}

@Composable
private fun PageItem(renderer: PageRenderer, index: Int, widthPx: Int, mode: Int, query: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, renderer, index, widthPx, query) {
        value = try {
            renderer.render(index, widthPx, query)
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
            colorFilter = when (mode) {
                1 -> ColorFilter.colorMatrix(INVERT)
                2 -> ColorFilter.colorMatrix(SEPIA)
                else -> null
            }
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
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
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

@Composable
private fun MultiInputDialog(
    title: String,
    labels: List<String>,
    initials: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val texts = remember { mutableStateOf(initials) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { i, l ->
                    OutlinedTextField(
                        value = texts.value[i],
                        onValueChange = { v ->
                            texts.value = texts.value.toMutableList().also { it[i] = v }
                        },
                        label = { Text(l) },
                        singleLine = true,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(texts.value) }) { Text("Tamam") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
