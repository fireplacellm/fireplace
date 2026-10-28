package com.fireplace.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireplace.ChatMessage
import com.fireplace.ChatViewModel
import com.fireplace.Mode
import com.fireplace.SourceRef
import com.fireplace.Status
import com.fireplace.agents.Agent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    var selectedSource by remember { mutableStateOf<SourceRef?>(null) }
    var showPacks by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            DrawerContent(
                vm = vm,
                onNewChat = { scope.launch { drawer.close() }; vm.newConversation() },
                onPickAgent = { scope.launch { drawer.close() }; vm.selectAgent(it) },
                onOpenConversation = { scope.launch { drawer.close() }; vm.loadConversation(it) },
                onDeleteConversation = { vm.deleteConversation(it) },
                onOpenPacks = { scope.launch { drawer.close() }; showPacks = true },
                onOpenModels = { scope.launch { drawer.close() }; showModels = true },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Surface(color = Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { showAgents = true }) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${vm.agent.emoji}  ${vm.agent.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (vm.activeModelName.isNotEmpty()) {
                                    Text(vm.activeModelName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Menu") }
                    },
                    actions = {
                        IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Rounded.Add, "New chat") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = { if (vm.status == Status.READY) InputBar(vm, input, { input = it }) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (vm.status) {
                    Status.LOADING -> LoadingState()
                    // No model yet: show the catalogue itself (not a dead end) - or the
                    // live download if the default is already being fetched.
                    Status.NO_MODEL -> if (vm.downloading) DownloadingState(vm) else ModelCatalogScreen(vm)
                    Status.NO_PACKS -> SetupScreen(vm)
                    Status.ERROR -> ErrorState(vm.statusDetail, onOpenModels = { showModels = true })
                    Status.READY ->
                        if (vm.messages.isEmpty()) WelcomeState(
                            agent = vm.agent,
                            onSuggestion = { vm.send(it) },
                        )
                        else MessageList(vm) { selectedSource = it }
                }
                vm.importMsg?.let { ImportBanner(it) { vm.importMsg = null } }
            }
        }
    }

    selectedSource?.let { SourceSheet(it) { selectedSource = null } }
    if (showPacks) PacksSheet(vm) { showPacks = false }
    if (showAgents) AgentSheet(vm, onPick = { vm.selectAgent(it); showAgents = false }) { showAgents = false }
    if (showModels) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ModelCatalogScreen(vm, onClose = { showModels = false })
        }
    }
}

/** Full-screen progress while the default model downloads on first run. */
@Composable
private fun DownloadingState(vm: ChatViewModel) {
    val p = vm.downloadProgress
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlameLogo(Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("Getting your model ready…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("This one-time download runs on Wi-Fi in the background. Afterwards Fireplace works fully offline.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (p != null && p.totalBytes > 0) {
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))
            Spacer(Modifier.height(10.dp))
            val doneMb = (p.doneBytes / 1_048_576.0).toInt(); val totalMb = (p.totalBytes / 1_048_576.0).toInt()
            Text("${(p.fraction * 100).toInt()}%  ·  $doneMb / $totalMb MB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { vm.cancelDownload() }) { Text("Choose a different model") }
    }
}

/** Error screen that always offers a way forward - into the model catalogue. */
@Composable
private fun ErrorState(detail: String, onOpenModels: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Something needs attention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenModels, modifier = Modifier.height(46.dp)) { Text("Open model catalogue") }
    }
}

// ---------- Brand ----------

/** The Fireplace brand mark (the nested glowing-hearth logo), rendered from the
 *  vector drawable so it stays crisp from the 16dp message avatar up to the splash. */
@Composable
internal fun FlameLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(com.fireplace.R.drawable.ic_fireplace_logo),
        contentDescription = "Fireplace",
        modifier = modifier,
    )
}

// ---------- Drawer ----------

@Composable
private fun DrawerContent(
    vm: ChatViewModel,
    onNewChat: () -> Unit,
    onPickAgent: (Agent) -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenPacks: () -> Unit,
    onOpenModels: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.systemBars,
        modifier = Modifier.widthIn(max = 340.dp),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlameLogo(Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Fireplace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Private local AI · offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalRow("New chat", Icons.Rounded.Add, onNewChat)
            Spacer(Modifier.height(20.dp))

            SectionLabel("Agents")
            vm.agents.forEach { a ->
                val selected = a.id == vm.agent.id
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPickAgent(a) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        Text(a.tagline, style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("History")
            if (vm.conversations.isEmpty())
                Text("No past chats yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            vm.conversations.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(if (c.id == vm.currentConvId) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable { onOpenConversation(c.id) }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDeleteConversation(c.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            DrawerLink("Knowledge packs", Icons.Rounded.Tune, onOpenPacks)
            DrawerLink("Models", Icons.Rounded.Storage, onOpenModels)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))

@Composable
private fun FilledTonalRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun DrawerLink(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- States ----------

@Composable
private fun LoadingState() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlameLogo(Modifier.size(56.dp))
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        Text("Warming up the model & packs…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoState(title: String, body: String) = Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlameLogo(Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WelcomeState(
    agent: Agent,
    onSuggestion: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))),
            contentAlignment = Alignment.Center,
        ) { Text(agent.emoji, fontSize = 40.sp) }
        Spacer(Modifier.height(16.dp))
        Text(agent.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(agent.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        agent.safetyLine?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        Text("Try asking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        agent.suggestions.forEach { s ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onSuggestion(s) },
            ) {
                Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

// ---------- Message list ----------

@Composable
private fun MessageList(vm: ChatViewModel, onSourceClick: (SourceRef) -> Unit) {
    val listState = rememberLazyListState()
    // Keep pinned to the newest message as it arrives / streams, but only when the user
    // is already near the bottom - so scrolling up to read isn't yanked back down.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= vm.messages.lastIndex - 1
        }
    }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text) {
        if (vm.messages.isNotEmpty() && atBottom) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Reserve space with contentPadding (not Spacer items) so rows scroll fully clear
        // of the bars and the last answer isn't hidden behind the input.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(vm.messages) { MessageBubble(it, vm.genStatus, onSourceClick, onFollowUp = { q -> vm.send(q) }) }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, genStatus: String = "", onSourceClick: (SourceRef) -> Unit, onFollowUp: (String) -> Unit = {}) {
    val isUser = m.role == "user"
    if (isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Text(m.text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        return
    }
    // Assistant: no bubble - plain content with a small avatar, for a clean, modern feel.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            FlameLogo(Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            if (m.text.isEmpty() && m.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingDots()
                    if (genStatus.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(genStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                MarkdownText(m.text)
                // Keep a live "still working" signal below the text while streaming, so
                // an instant preview is never mistaken for the finished answer.
                if (m.streaming) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinkingDots()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            genStatus.ifBlank { "Composing a fuller answer…" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // While streaming, show sources compactly as chips (they appear with the
            // instant preview). Once the answer is final, lay out the full evidence +
            // next-step structure that makes the answer read as thorough and trustworthy.
            if (m.sources.isNotEmpty() && m.streaming) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.sources.forEach { s -> SourceChip(s) { onSourceClick(s) } }
                }
            }
            if (!m.streaming) {
                m.citationPassRate?.let {
                    Spacer(Modifier.height(12.dp))
                    VerificationBadge(it)
                }
                if (m.sources.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SourcesSection(m.sources, onSourceClick)
                }
                val followUps = remember(m.sources) { followUpsFrom(m) }
                if (followUps.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    FollowUpSection(followUps, onFollowUp)
                }
                if (m.text.isNotBlank()) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Copy answer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                            .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text)) }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** Follow-up questions derived from the retrieved sources - each is a real article
 *  in the same pack, so the suggestion is always answerable offline. Turns a finished
 *  answer into an invitation to keep exploring. */
private fun followUpsFrom(m: ChatMessage): List<String> {
    if (m.sources.isEmpty()) return emptyList()
    return m.sources.map { it.title.trim() }
        .filter { it.length in 3..56 }
        .distinct()
        .take(3)
        .map { "Tell me more about $it" }
}

/** The retrieved sources, each shown with the exact passage text we pulled the
 *  answer from - transparent evidence under the answer. Expanded by default; tap a
 *  source to read its full passage. */
@Composable
private fun SourcesSection(sources: List<SourceRef>, onSourceClick: (SourceRef) -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Storage, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "Sources · ${sources.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            sources.forEach { s ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSourceClick(s) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                                Text("[${s.n}]", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(s.title, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.text.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.text.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Continue exploring" - tappable next questions that keep the user learning. */
@Composable
private fun FollowUpSection(followUps: List<String>, onFollowUp: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Continue exploring", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        followUps.forEach { q ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onFollowUp(q) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(q, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Colour the inline [n] citation markers so grounded answers read clearly. */
@Composable
private fun highlightCitations(text: String) = buildAnnotatedString {
    val cite = Regex("\\[\\d+]")
    var last = 0
    val color = MaterialTheme.colorScheme.primary
    for (m in cite.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(m.value) }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun SourceChip(s: SourceRef, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text("[${s.n}] ${s.title}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun VerificationBadge(rate: Double) {
    val pct = (rate * 100).toInt()
    val (c, label) = when {
        rate >= 0.8 -> MaterialTheme.colorScheme.tertiary to "Well grounded"
        rate >= 0.5 -> MaterialTheme.colorScheme.secondary to "Partly grounded"
        else -> MaterialTheme.colorScheme.error to "Low grounding"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text("$label · $pct% verified in sources", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = a)))
        }
    }
}

// ---------- Input ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(vm: ChatViewModel, input: String, onInput: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Compact mode segmented control + Deep-research toggle (Research only).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(3.dp)) {
                        ModeTab("Research", vm.mode == Mode.RESEARCH) { vm.mode = Mode.RESEARCH }
                        ModeTab("Chat", vm.mode == Mode.CHAT) { vm.mode = Mode.CHAT }
                    }
                }
                if (vm.mode == Mode.RESEARCH) {
                    Spacer(Modifier.width(8.dp))
                    DeepToggle(vm.deepResearch) { vm.deepResearch = !vm.deepResearch }
                }
            }
            if (vm.mode == Mode.RESEARCH && vm.deepResearch) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Deep research: outlines the question, researches each part from your sources, then writes one in-depth, cited answer. Slower - a few minutes.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // The input pill holds the field + send.
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 6.dp, end = 5.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    TextField(
                        value = input, onValueChange = onInput,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (vm.mode == Mode.RESEARCH) "Ask your packs & pods…" else "Chat with Fireplace…") },
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    val active = vm.generating
                    Surface(
                        shape = CircleShape,
                        color = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp).size(44.dp).clickable {
                            if (active) vm.stop()
                            else { val q = input.trim(); if (q.isNotEmpty()) { vm.send(q); onInput("") } }
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (active) Icon(Icons.Rounded.Stop, "Stop", tint = MaterialTheme.colorScheme.onSurface)
                            else Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepToggle(on: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(CircleShape).clickable { onToggle() },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔬", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(5.dp))
            Text("Deep", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

// ---------- Sheets ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(src: SourceRef, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                    Text("[${src.n}]", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(src.title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(src.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PacksSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Knowledge packs & data pods", style = MaterialTheme.typography.titleLarge)
            Text("Toggle what Research draws on. Switch a pack off to save memory - it stays installed.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            if (vm.packInfos.isEmpty()) Text("No knowledge packs installed yet.", style = MaterialTheme.typography.bodyMedium)
            vm.packInfos.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📚", fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.labelLarge)
                        val mb = p.sizeBytes / (1024.0 * 1024.0)
                        val sub = buildString {
                            if (p.description.isNotBlank()) { append(p.description); append(" · ") }
                            if (p.loaded && p.passages > 0) { append("${p.passages} passages · ") }
                            append("%.1f MB".format(mb))
                            if (!p.enabled) append(" · off")
                        }
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.enabled, onCheckedChange = { vm.setPackEnabled(p.id, it) })
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { vm.deletePack(p.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (vm.models.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text("Model", style = MaterialTheme.typography.labelLarge)
                Text("Bigger models give richer answers but run slower. Smaller models are snappier.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                vm.models.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.selectModel(m.fileName) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = m.selected, onClick = { vm.selectModel(m.fileName) })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, style = MaterialTheme.typography.labelLarge)
                            Text("%.0f MB".format(m.sizeBytes / (1024.0 * 1024.0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (vm.deviceInfo.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("Active model & device", style = MaterialTheme.typography.labelLarge)
                Text(vm.deviceInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(vm: ChatViewModel, onPick: (Agent) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Agents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            vm.agents.forEach { a ->
                val selected = a.id == vm.agent.id
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onPick(a) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.labelLarge)
                        Text(a.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ---------- Banner ----------

@Composable
private fun ImportBanner(msg: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        }
    }
}
