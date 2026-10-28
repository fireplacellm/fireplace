package com.fireplace.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class Page(val emoji: String, val title: String, val body: String, val brand: Boolean = false)

private val PAGES = listOf(
    Page("", "Fireplace", "A private AI that runs entirely on your phone - no internet, no accounts, nothing ever leaves your device.", brand = true),
    Page("🔎", "Answers you can trust", "Ask research questions and get clear, detailed answers drawn from on-device knowledge packs - with a citation on every fact so you can check it."),
    Page("🔒", "Your data, your device", "Import your health, fitness or export-archive data into private pods and ask about your own life. It's stored only on your phone, and you see every access in the privacy log."),
    Page("🧭", "Expert agents", "Switch between specialists - Research, Health, Fitness, Crypto and Trading - each grounded in the right data for the job."),
    Page("⚡", "Fast or rich - your call", "Pick a snappy small model or a richer larger one anytime in Packs ▸ Model, and add or remove knowledge packs whenever you like."),
)

/** First-run intro carousel: explains how Fireplace works (offline, cited, private). */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.lastIndex

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text("Skip") }
                else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
                val p = PAGES[i]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(120.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))),
                        contentAlignment = Alignment.Center,
                    ) { if (p.brand) FlameLogo(Modifier.size(64.dp)) else Text(p.emoji, fontSize = 56.sp) }
                    Spacer(Modifier.height(28.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (p.brand) {
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Offline", "Cited", "Private").forEach { label ->
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                repeat(PAGES.size) { i ->
                    val selected = i == pager.currentPage
                    val w by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dot")
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(RoundedCornerShape(4.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (last) "Get started" else "Next", fontWeight = FontWeight.SemiBold) }
        }
    }
}
