
package com.openrouter.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*

sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var inCodeBlock = false
    val codeBuffer = java.lang.StringBuilder()
    var codeLang = ""

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLang, codeBuffer.toString().trimEnd()))
                codeBuffer.setLength(0)
                codeLang = ""
                inCodeBlock = false
            } else {
                inCodeBlock = true
                codeLang = line.replace("```", "").trim()
            }
        } else if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
        } else {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                val headerText = trimmed.dropWhile { it == '#' || it == ' ' }.trim()
                blocks.add(MarkdownBlock.Header(level, headerText))
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                val itemText = trimmed.substring(2).trim()
                blocks.add(MarkdownBlock.BulletItem(itemText))
            } else {
                if (trimmed.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(line))
                }
            }
        }
    }
    if (inCodeBlock && codeBuffer.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(codeLang, codeBuffer.toString().trimEnd()))
    }
    return blocks
}

@Composable
fun renderInlineMarkdown(text: String): AnnotatedString {
    return remember(text) {
        val builder = AnnotatedString.Builder()
        var i = 0
        val len = text.length
        while (i < len) {
            when {
                i < len - 1 && text[i] == '`' -> {
                    val nextBacktick = text.indexOf('`', i + 1)
                    if (nextBacktick != -1) {
                        builder.pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color.LightGray.copy(alpha = 0.3f),
                                color = Color(0xFFC7254E)
                            )
                        )
                        builder.append(text.substring(i + 1, nextBacktick))
                        builder.pop()
                        i = nextBacktick + 1
                    } else {
                        builder.append(text[i].toString())
                        i++
                    }
                }
                i < len - 3 && text.substring(i, i + 2) == "**" -> {
                    val nextBold = text.indexOf("**", i + 2)
                    if (nextBold != -1) {
                        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        builder.append(text.substring(i + 2, nextBold))
                        builder.pop()
                        i = nextBold + 2
                    } else {
                        builder.append(text[i].toString())
                        i++
                    }
                }
                i < len - 2 && text[i] == '*' && text[i + 1] != '*' -> {
                    val nextItalic = text.indexOf('*', i + 1)
                    if (nextItalic != -1) {
                        builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        builder.append(text.substring(i + 1, nextItalic))
                        builder.pop()
                        i = nextItalic + 1
                    } else {
                        builder.append(text[i].toString())
                        i++
                    }
                }
                else -> {
                    builder.append(text[i].toString())
                    i++
                }
            }
        }
        builder.toAnnotatedString()
    }
}

@Composable
fun MarkdownRender(text: String, isUser: Boolean) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = style.copy(fontWeight = FontWeight.Bold, color = textColor)
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge.copy(color = textColor))
                        Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        if (block.language.isNotEmpty()) {
                            Text(
                                text = block.language.uppercase(Locale.ROOT),
                                style = MaterialTheme.typography.labelSmall.copy(color = Color.LightGray)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        SelectionContainer {
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF80F080)
                                )
                            )
                        }
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                    )
                }
            }
        }
    }
}

@Composable
fun CursorEffect() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Text(
        text = "▋",
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
fun AppNavigationContainer(viewModel: ChatViewModel, onAuthenticateRequested: () -> Unit) {
    var activeTab by remember { mutableStateOf("chat") }
    val isKeyAuthorized by viewModel.isKeyAuthorized.collectAsState()
    val context = LocalContext.current

    val bgUriStr by viewModel.bgOverrideUri.collectAsState()
    val bgModifier = if (bgUriStr.isNotEmpty()) {
        val bitmap = remember(bgUriStr) {
            runCatching {
                val resolver = context.contentResolver
                val stream = resolver.openInputStream(android.net.Uri.parse(bgUriStr))
                BitmapFactory.decodeStream(stream)
            }.getOrNull()
        }
        if (bitmap != null) {
            Modifier.paint(
                painter = painterResource(id = android.R.drawable.stat_sys_warning), // Safe compile fallback
                contentScale = ContentScale.Crop
            ).background(MaterialTheme.colorScheme.background.copy(alpha = 0.1f)) // Blend
            Modifier.paint(
                painter = androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap()),
                contentScale = ContentScale.Crop
            )
        } else {
            Modifier.background(MaterialTheme.colorScheme.background)
        }
    } else {
        Modifier.background(MaterialTheme.colorScheme.background)
    }

    Box(modifier = Modifier.fillMaxSize().then(bgModifier)) {
        if (!isKeyAuthorized) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Secured",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Encrypted OpenRouter Client",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onAuthenticateRequested) {
                    Text("Unlock Secure Keystore")
                }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        modifier = Modifier.height(80.dp) // Large reach area
                    ) {
                        NavigationBarItem(
                            selected = activeTab == "chat",
                            onClick = { activeTab = "chat" },
                            icon = { Icon(Icons.Default.Chat, "Chat") },
                            label = { Text("Chat") }
                        )
                        NavigationBarItem(
                            selected = activeTab == "presets",
                            onClick = { activeTab = "presets" },
                            icon = { Icon(Icons.Default.EditNote, "Presets") },
                            label = { Text("Prompts") }
                        )
                        NavigationBarItem(
                            selected = activeTab == "logs",
                            onClick = { activeTab = "logs" },
                            icon = { Icon(Icons.Default.ReceiptLong, "Logs") },
                            label = { Text("Logs") }
                        )
                        NavigationBarItem(
                            selected = activeTab == "settings",
                            onClick = { activeTab = "settings" },
                            icon = { Icon(Icons.Default.Settings, "Config") },
                            label = { Text("Settings") }
                        )
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when (activeTab) {
                        "chat" -> ChatScreen(viewModel)
                        "presets" -> PresetsScreen(viewModel)
                        "logs" -> LogsScreen(viewModel)
                        "settings" -> SettingsScreen(viewModel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val currentSession by viewModel.currentSession.collectAsState()
    val sessions by viewModel.chatSessions.collectAsState()
    val messages by viewModel.currentMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingText by viewModel.streamingContent.collectAsState()
    val availablePricing by viewModel.availablePricing.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var chatInputText by remember { mutableStateOf("") }
    var showSessionCreator by remember { mutableStateOf(false) }
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var editInputText by remember { mutableStateOf("") }
    var editContextOption by remember { mutableStateOf("BRANCH") }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            lazyListState.animateScrollToItem(messages.size)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sony Xperia 21:9 Bottom Heavy View Adjustments (Uncluttered viewing window at top)
        Spacer(modifier = Modifier.height(28.dp))

        if (currentSession == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom // Push interactions to lower dynamic reach area
            ) {
                Text(
                    text = "Select or Create Chat Session",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.setSearchQuery(it)
                    },
                    label = { Text("Search title, prompts, history...") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    leadingIcon = { Icon(Icons.Default.Search, "Search") }
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectSession(session) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        session.systemPrompt.take(60) + if (session.systemPrompt.length > 60) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Row {
                                    IconButton(onClick = { viewModel.toggleArchiveSession(session) }) {
                                        Icon(Icons.Default.Archive, "Archive")
                                    }
                                    IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                        Icon(Icons.Default.Delete, "Delete")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showSessionCreator = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(Icons.Default.Add, "Create Session")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Session")
                }
            }
        } else {
            // Active session container
            Column(modifier = Modifier.fillMaxSize()) {
                // Header (Active session status)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.selectSession(null) }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                    Text(
                        text = currentSession?.title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    var modelMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { modelMenuExpanded = true }) {
                            Text(
                                selectedModel.split("/").lastOrNull() ?: "Select Model",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            availablePricing.forEach { pricing ->
                                DropdownMenuItem(
                                    text = { Text(pricing.name) },
                                    onClick = {
                                        viewModel.selectModel(pricing.id)
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Messages View Block
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msgWithVer ->
                        val activeVersion = msgWithVer.versions.firstOrNull { it.id == msgWithVer.message.currentVersionId }
                            ?: msgWithVer.versions.lastOrNull()

                        if (activeVersion != null) {
                            val isUser = msgWithVer.message.role == "user"
                            val align = if (isUser) Alignment.End else Alignment.Start
                            val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer

                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(bubbleColor)
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        MarkdownRender(activeVersion.content, isUser)
                                        Spacer(modifier = Modifier.height(4.dp))

                                        // Meta & Version Arrow Controls
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (msgWithVer.versions.size > 1) {
                                                val sorted = msgWithVer.versions.sortedBy { it.versionNumber }
                                                val currentIndex = sorted.indexOf(activeVersion)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(
                                                        onClick = {
                                                            if (currentIndex > 0) {
                                                                viewModel.switchMessageVersion(msgWithVer.message.id, sorted[currentIndex - 1].id)
                                                            }
                                                        },
                                                        enabled = currentIndex > 0,
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.ChevronLeft, "Prev", tint = Color.Gray)
                                                    }
                                                    Text(
                                                        text = "${activeVersion.versionNumber}/${msgWithVer.versions.size}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            if (currentIndex < sorted.size - 1) {
                                                                viewModel.switchMessageVersion(msgWithVer.message.id, sorted[currentIndex + 1].id)
                                                            }
                                                        },
                                                        enabled = currentIndex < sorted.size - 1,
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.ChevronRight, "Next", tint = Color.Gray)
                                                    }
                                                }
                                            } else {
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        editingMessageId = msgWithVer.message.id
                                                        editInputText = activeVersion.content
                                                        editContextOption = activeVersion.contextOption
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, "Edit", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Raw response streaming channel
                    if (isStreaming && streamingText.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(12.dp)
                                ) {
                                    Row {
                                        Box(modifier = Modifier.weight(1f)) {
                                            MarkdownRender(streamingText, false)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        CursorEffect()
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom heavy interaction panel
                Surface(
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = chatInputText,
                                onValueChange = { chatInputText = it },
                                placeholder = { Text("Write message...") },
                                modifier = Modifier.weight(1f),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (chatInputText.isNotEmpty()) {
                                        viewModel.sendMessage(chatInputText)
                                        chatInputText = ""
                                    }
                                })
                            )

                            FloatingActionButton(
                                onClick = {
                                    if (chatInputText.isNotEmpty()) {
                                        viewModel.sendMessage(chatInputText)
                                        chatInputText = ""
                                    }
                                },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(Icons.Default.Send, "Send")
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Session Builder Dialog
    if (showSessionCreator) {
        var newTitle by remember { mutableStateOf("") }
        var newPrompt by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showSessionCreator = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("New Session Config", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("Session Name") })
                    OutlinedTextField(value = newPrompt, onValueChange = { newPrompt = it }, label = { Text("System Context Prompt") }, modifier = Modifier.height(120.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSessionCreator = false }) { Text("Cancel") }
                        Button(onClick = {
                            if (newTitle.isNotEmpty()) {
                                viewModel.createSession(newTitle, newPrompt)
                                showSessionCreator = false
                            }
                        }) { Text("Create") }
                    }
                }
            }
        }
    }

    // Modal Message Editor Dialog
    if (editingMessageId != null) {
        Dialog(onDismissRequest = { editingMessageId = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit Message (Max 20 Versions)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = editInputText,
                        onValueChange = { editInputText = it },
                        modifier = Modifier.height(140.dp).fillMaxWidth()
                    )

                    // Context toggle configurations
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Context Options:", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = editContextOption == "BRANCH",
                                onClick = { editContextOption = "BRANCH" }
                            )
                            Text("Branch", modifier = Modifier.clickable { editContextOption = "BRANCH" })
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(
                                selected = editContextOption == "UNIFIED",
                                onClick = { editContextOption = "UNIFIED" }
                            )
                            Text("Unified", modifier = Modifier.clickable { editContextOption = "UNIFIED" })
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editingMessageId = null }) { Text("Cancel") }
                        Button(onClick = {
                            val id = editingMessageId
                            if (id != null && editInputText.isNotEmpty()) {
                                viewModel.editMessage(id, editInputText, editContextOption)
                                editingMessageId = null
                            }
                        }) { Text("Save Edit") }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetsScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentSession by viewModel.currentSession.collectAsState()
    var promptEditorText by remember { mutableStateOf(currentSession?.systemPrompt ?: "") }

    LaunchedEffect(currentSession) {
        promptEditorText = currentSession?.systemPrompt ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("System Prompt Presets", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Configure active behavioral prompt instructions for the target model engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            if (currentSession == null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Please open a session first in the Chat tab to customize active prompt presets.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                OutlinedTextField(
                    value = promptEditorText,
                    onValueChange = { promptEditorText = it },
                    label = { Text("Active System Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )

                // Visual preview block
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Instant Visual Preview:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        MarkdownRender(promptEditorText, false)
                    }
                }
            }
        }

        // Bottom-heavy thumb-reachable presets operations panel
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (currentSession != null) {
                Button(
                    onClick = {
                        viewModel.updateSessionSystemPrompt(currentSession!!, promptEditorText)
                        Toast.makeText(context, "System Prompt applied successfully!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Save, "Save")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apply System Prompt")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val json = viewModel.exportSystemPromptToJson(promptEditorText)
                        clipboard.setText(AnnotatedString(json))
                        Toast.makeText(context, "Copied preset config as JSON to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, "Export")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export Preset")
                }

                OutlinedButton(
                    onClick = {
                        val clipText = clipboard.getText()?.text ?: ""
                        val result = viewModel.importSystemPromptFromJson(clipText)
                        if (result != null) {
                            promptEditorText = result
                            Toast.makeText(context, "Prompt Preset loaded from clipboard", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid preset config in clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, "Import")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import Preset")
                }
            }
        }
    }
}

@Composable
fun LogsScreen(viewModel: ChatViewModel) {
    val logs by viewModel.apiLogs.collectAsState()
    val totalCost = remember(logs) { logs.sumOf { it.cost } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("API Cost Activity Logger", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        // Large high visibility dashboard cards
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Estimated Costs:", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = String.format(Locale.getDefault(), "$%.6f USD", totalCost),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(log.endpoint, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(
                                String.format(Locale.getDefault(), "$%.6f", log.cost),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Tokens: Prompt ${log.requestTokens} | Completion ${log.responseTokens}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                            Text(
                                formatter.format(Date(log.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.clearLogs() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.DeleteSweep, "Clear")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Operational Logs")
        }
    }
}

@Composable
fun SettingsScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val apiKey by viewModel.apiKey.collectAsState()
    val bgOverride by viewModel.bgOverrideUri.collectAsState()
    val soundOverride by viewModel.soundOverrideUri.collectAsState()

    var keyInputText by remember { mutableStateOf(apiKey) }

    val bgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.setBgOverrideUri(uri.toString())
                Toast.makeText(context, "Asset: Custom Chat Background applied!", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val soundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.setSoundOverrideUri(uri.toString())
                Toast.makeText(context, "Asset: Custom notification audio loaded!", Toast.LENGTH_SHORT).show()
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Global Configurations", style = MaterialTheme.typography.headlineSmall)

            // Secure API Key configuration
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Secure Cryptographic Keystore", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = keyInputText,
                        onValueChange = { keyInputText = it },
                        label = { Text("OpenRouter API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveApiKey(keyInputText)
                                Toast.makeText(context, "API Key Saved Securely", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save securely")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.logout()
                                keyInputText = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Lock Key")
                        }
                    }
                }
            }

            // Asset customization override section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Asset Override Mechanics", style = MaterialTheme.typography.titleMedium)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Custom Chat Background Override", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { bgLauncher.launch(arrayOf("image/*")) },
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Text("Select Image")
                            }
                            if (bgOverride.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setBgOverrideUri("") }) {
                                    Icon(Icons.Default.Clear, "Clear")
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Custom Completed Alert Sound", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { soundLauncher.launch(arrayOf("audio/*")) },
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Text("Select Audio")
                            }
                            if (soundOverride.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSoundOverrideUri("") }) {
                                    Icon(Icons.Default.Clear, "Clear")
                                }
                            }
                        }
                        Button(
                            onClick = { viewModel.triggerSoundNotification() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.VolumeUp, "Test Tone")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Notification Sound")
                        }
                    }
                }
            }
        }

        // Manual Force Override: Models & Cost rates cache sync
        Button(
            onClick = {
                viewModel.checkAndSyncPricing(force = true)
                Toast.makeText(context, "Pricing parameters updated successfully!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.Sync, "Force Sync")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sync Pricing (Last 24h)")
        }
    }
}
