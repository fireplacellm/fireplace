package com.fireplace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireplace.ChatViewModel
import com.fireplace.Status
import com.fireplace.data.DownloadItem

/**
 * First-run setup: pick a model (RAM-aware), optionally the knowledge pack, and
 * download them via the OS. Shown while the app has no model (or no pack) yet.
 */
@Composable
fun SetupScreen(vm: ChatViewModel) {
    val ram = remember { vm.deviceRam() }
    val needModel = vm.status == Status.NO_MODEL
    // Default selection: the recommended model that fits this device's RAM.
    val fitting = vm.catalogModels.filter { it.minRamGb <= ram + 0.5 }
    var selectedModel by remember { mutableStateOf((fitting.lastOrNull { it.recommended } ?: fitting.lastOrNull() ?: vm.catalogModels.first()).id) }
    var includePack by remember { mutableStateOf(vm.packHostConfigured) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (vm.downloading) { DownloadingView(vm); return@Surface }
        Box(Modifier.fillMaxSize()) {
            // Scrollable content, with bottom room reserved so the last item is never
            // hidden behind the pinned Download button.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(72.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))),
                    contentAlignment = Alignment.Center) { FlameLogo(Modifier.size(40.dp)) }
                Spacer(Modifier.height(16.dp))
                Text(if (needModel) "Choose a model" else "Add knowledge", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Your device has ${"%.0f".format(ram)} GB RAM. Everything downloads once and then runs fully offline.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))

                if (needModel) vm.catalogModels.forEach { m ->
                    val fits = m.minRamGb <= ram + 0.5
                    ModelCard(m, selected = selectedModel == m.id, fits = fits) { if (fits) selectedModel = m.id }
                }

                // Knowledge pack
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.catalogPack.name, style = MaterialTheme.typography.labelLarge)
                            val sub = if (vm.packInstalled) "~21,000 core articles for grounded, cited answers - included"
                                      else vm.catalogPack.desc + "  ·  " + vm.catalogPack.sizeLabel
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (vm.packInstalled) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Included", tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Switch(checked = includePack, enabled = vm.packHostConfigured, onCheckedChange = { includePack = it })
                        }
                    }
                }
                if (vm.packInstalled) {
                    Spacer(Modifier.height(6.dp))
                    Text("Built in - Research works offline as soon as a model finishes downloading.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
                } else if (!vm.packHostConfigured) {
                    Spacer(Modifier.height(6.dp))
                    Text("Knowledge-pack hosting isn't configured yet. You can still download a model now and add the pack later.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
                }
            }

            val items = buildList<DownloadItem> {
                if (needModel) vm.catalogModels.firstOrNull { it.id == selectedModel }?.let { add(it) }
                if (includePack && vm.packHostConfigured) { add(vm.catalogPack); add(vm.catalogEmbedder) }
            }
            val totalGb = items.sumOf { it.totalBytes } / 1_073_741_824.0
            // Pinned to the bottom so it is always visible regardless of content height.
            Button(
                onClick = { vm.downloadItems(items) },
                enabled = items.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 12.dp).height(52.dp),
            ) { Text(if (items.isEmpty()) "Nothing to download" else "Download  (%.1f GB)".format(totalGb), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ModelCard(m: DownloadItem, selected: Boolean, fits: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(enabled = fits, onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, enabled = fits, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.labelLarge, color = if (fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    val (tag, tagColor) = when {
                        !fits -> "Needs ${m.minRamGb.toInt()} GB" to MaterialTheme.colorScheme.error
                        m.recommended -> "Recommended" to MaterialTheme.colorScheme.tertiary
                        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    if (tag.isNotEmpty()) Surface(color = tagColor.copy(alpha = 0.15f), shape = CircleShape) {
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = tagColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Text(m.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(m.sizeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadingView(vm: ChatViewModel) {
    val p = vm.downloadProgress
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        FlameLogo(Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("Downloading…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("This runs once. It continues if you switch away, and afterwards Fireplace is fully offline.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(24.dp))
        if (p != null && p.totalBytes > 0) {
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(8.dp).clip(CircleShape))
            Spacer(Modifier.height(10.dp))
            val doneMb = (p.doneBytes / 1_048_576.0).toInt()
            val totalMb = (p.totalBytes / 1_048_576.0).toInt()
            Text("${(p.fraction * 100).toInt()}%  ·  $doneMb / $totalMb MB",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
    }
}
