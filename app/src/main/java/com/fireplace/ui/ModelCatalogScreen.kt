package com.fireplace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireplace.ChatViewModel
import com.fireplace.ModelCatalogEntry
import com.fireplace.ModelFit

/* Semantic fit colors that read on both light and dark backgrounds. */
private val FitGreen = Color(0xFF3BA776)
private val FitAmber = Color(0xFFCB8A14)

/**
 * The model catalogue: install, switch between, and remove on-device models, each
 * tagged with how well it fits this phone's RAM. Doubles as the first-run / recovery
 * screen when no model is installed (then [onClose] is null, so there's no back arrow
 * and it can never be dismissed into a dead end).
 */
@Composable
fun ModelCatalogScreen(vm: ChatViewModel, onClose: (() -> Unit)? = null) {
    val ram = remember { vm.deviceRam() }
    val installed = vm.modelCatalog.count { it.onDisk }
    var confirmDelete by remember { mutableStateOf<ModelCatalogEntry?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${"%.0f".format(ram)} GB RAM · $installed installed · runs fully offline",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onClose == null) item {
                Text(
                    "Pick a model to download. It runs on your phone - no server, no account. " +
                        "You can add or switch models here anytime.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(vm.modelCatalog, key = { it.id }) { entry ->
                ModelCard(
                    entry = entry,
                    downloading = vm.downloadingModelId == entry.downloadItem?.id,
                    progress = if (vm.downloadingModelId == entry.downloadItem?.id) vm.downloadProgress else null,
                    speedBps = vm.downloadSpeedBps,
                    etaSec = vm.downloadEtaSec,
                    anyDownloading = vm.downloading,
                    onDownload = { vm.downloadModel(entry) },
                    onUse = { vm.selectModel(entry.fileName) },
                    onCancel = { vm.cancelDownload() },
                    onDelete = { confirmDelete = entry },
                )
            }
        }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${e.name}?") },
            text = { Text("This deletes the model weights and frees ${e.sizeLabel}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(e.fileName); confirmDelete = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModelCard(
    entry: ModelCatalogEntry,
    downloading: Boolean,
    progress: com.fireplace.data.Downloader.Progress?,
    speedBps: Double,
    etaSec: Long,
    anyDownloading: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (entry.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = if (entry.active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.sizeLabel + (if (entry.recommended) "  ·  Recommended" else ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FitBadge(entry.fit, entry.minRamGb)

            Spacer(Modifier.height(8.dp))
            Text(entry.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))
            when {
                downloading -> DownloadProgress(progress, speedBps, etaSec, onCancel)
                entry.onDisk && entry.active -> OutlinedButton(onClick = onDelete, modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove")
                }
                entry.onDisk -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onUse, enabled = !anyDownloading, modifier = Modifier.weight(1f).height(44.dp)) { Text("Use this model") }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.height(44.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                    }
                }
                else -> Button(
                    onClick = onDownload,
                    enabled = !anyDownloading && entry.downloadItem != null,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (entry.fit == ModelFit.INCOMPATIBLE) "Download anyway  ·  ${entry.sizeLabel}" else "Download  ·  ${entry.sizeLabel}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FitBadge(fit: ModelFit, minRamGb: Double) {
    val (color, label) = when (fit) {
        ModelFit.GREAT -> FitGreen to "Runs great on this device"
        ModelFit.OK -> FitAmber to "Runs on this device - a bit heavy"
        ModelFit.INCOMPATIBLE -> MaterialTheme.colorScheme.error to "Needs ~${minRamGb.toInt()} GB RAM - may be slow or fail"
    }
    Surface(color = color.copy(alpha = 0.14f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DownloadProgress(p: com.fireplace.data.Downloader.Progress?, speedBps: Double, etaSec: Long, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (p != null && p.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            val doneMb = (p.doneBytes / 1_048_576.0).toInt()
            val totalMb = (p.totalBytes / 1_048_576.0).toInt()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(p.fraction * 100).toInt()}%  ·  $doneMb / $totalMb MB",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(buildString {
                    append(formatSpeed(speedBps))
                    if (etaSec > 0) append("  ·  ${formatEta(etaSec)} left")
                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Starting…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onCancel) { Text("Cancel download") }
    }
}

private fun formatSpeed(bps: Double): String =
    if (bps >= 1_048_576) "%.1f MB/s".format(bps / 1_048_576.0)
    else "%.0f KB/s".format((bps / 1024.0).coerceAtLeast(0.0))

private fun formatEta(sec: Long): String =
    if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
