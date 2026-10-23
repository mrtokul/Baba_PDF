package tr.baba.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Sayfa küçük resim ızgarası: dokunarak seç; sil, döndür, taşı, ayrı PDF yap. */
@Composable
fun PageGridDialog(
    renderer: PageRenderer,
    total: Int,
    onGo: (Int) -> Unit,
    onDelete: (Set<Int>) -> Unit,
    onRotate: (Set<Int>, Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onExtract: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var sel by remember { mutableStateOf(setOf<Int>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sayfalar (${sel.size}/$total seçili)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { sel = if (sel.size == total) emptySet() else (0 until total).toSet() }) {
                        Text(if (sel.size == total) "Hiçbiri" else "Tümü")
                    }
                    TextButton(onClick = onDismiss) { Text("Kapat") }
                }
                Row(Modifier.horizontalScrollRow(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val has = sel.isNotEmpty()
                    OutlinedButton(enabled = sel.size == 1, onClick = { onGo(sel.first()); onDismiss() }) { Text("Git") }
                    OutlinedButton(enabled = has, onClick = { onRotate(sel, 90); sel = emptySet() }) { Text("↻ 90°") }
                    OutlinedButton(enabled = has, onClick = { onRotate(sel, -90); sel = emptySet() }) { Text("↺ 90°") }
                    OutlinedButton(enabled = has && sel.size < total, onClick = { onDelete(sel); sel = emptySet() }) { Text("Sil") }
                    OutlinedButton(enabled = has, onClick = { onExtract(sel) }) { Text("Ayrı PDF") }
                    OutlinedButton(enabled = sel.size == 1 && sel.first() > 0, onClick = {
                        val a = sel.first(); onMove(a, a - 1); sel = setOf(a - 1)
                    }) { Text("← Öne") }
                    OutlinedButton(enabled = sel.size == 1 && sel.first() < total - 1, onClick = {
                        val a = sel.first(); onMove(a, a + 1); sel = setOf(a + 1)
                    }) { Text("Sona →") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items((0 until total).toList()) { i ->
                        val picked = i in sel
                        val bmp by produceState<android.graphics.Bitmap?>(null, renderer, i) {
                            value = try { renderer.render(i, 240, "") } catch (e: Exception) { null }
                        }
                        Column(
                            Modifier
                                .clickable { sel = if (picked) sel - i else sel + i }
                                .background(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val b = bmp
                            if (b != null) {
                                Image(
                                    bitmap = b.asImageBitmap(), contentDescription = "Sayfa ${i + 1}",
                                    modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(Modifier.padding(16.dp)) }
                            }
                            Text(if (picked) "✓ ${i + 1}" else "${i + 1}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier = this.fillMaxWidth().horizontalScroll(rememberScrollState())
