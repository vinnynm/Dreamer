package com.enigma.dreamer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import com.enigma.devlyric.core.LyricFormat
import com.enigma.devlyric.core.LyricParser
import com.enigma.dreamer.core.Song
import com.enigma.dreamer.ui.components.AlbumArtwork
import com.enigma.dreamer.ui.components.formatDuration
import com.enigma.dreamer.ui.theme.*

/**
 * Full-screen lyric editor.
 *
 * Modes:
 *  - RAW  : Free-text editor for LRC / plain text. User types or pastes lyrics.
 *  - LINE : Line-by-line view where each line can be timestamped individually
 *           (useful when listening to the song and tapping timestamps in real-time).
 *
 * Workflow:
 *  1. User opens the editor from the Song Detail sheet.
 *  2. The editor pre-fills with existing lyrics (if any).
 *  3. User edits in RAW mode or timestamps in LINE mode.
 *  4. Preview panel shows how the parsed LRC will look.
 *  5. "Save & Bake" embeds lyrics into the audio file (MP3 USLT/SYLT or M4A ©lyr).
 *     For unsupported formats a sidecar .lrc is saved instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricEditorScreen(
    song: Song,
    onSaveAndBake: (lyricText: String, format: LyricFormat) -> Unit,
    onBack: () -> Unit,
    isSaving: Boolean = false,
    saveMessage: String? = null
) {
    // ── Editor state ──────────────────────────────────────────────────────────

    // Initial text: existing LRC if available, else blank
    val initialText = remember(song.id) {
        song.lyricDocument?.toLrcText() ?: ""
    }

    var editorMode by remember { mutableStateOf(EditorMode.RAW) }
    var rawText    by remember { mutableStateOf(TextFieldValue(initialText)) }

    // LINE mode: split the raw text into a mutable list of editable lines
    var lineItems  by remember(initialText) {
        mutableStateOf(parseToLineItems(initialText))
    }

    // Preview toggle
    var showPreview by remember { mutableStateOf(false) }

    // Format selector
    var selectedFormat by remember { mutableStateOf(LyricFormat.LRC) }
    var showFormatMenu by remember { mutableStateOf(false) }

    // Discard confirmation
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Sync raw↔line when switching modes
    fun syncRawToLine() {
        lineItems = parseToLineItems(rawText.text)
    }
    fun syncLineToRaw() {
        rawText = TextFieldValue(lineItemsToLrc(lineItems))
    }

    // ── Derived preview ───────────────────────────────────────────────────────
    val previewDoc = remember(rawText.text, selectedFormat) {
        runCatching { LyricParser.parse(rawText.text, selectedFormat) }.getOrNull()
    }

    Scaffold(
        containerColor = Amoled,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Edit Lyrics",
                            style      = MaterialTheme.typography.titleMedium,
                            color      = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            song.title,
                            style   = MaterialTheme.typography.bodySmall,
                            color   = TextMuted,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (rawText.text != initialText) showDiscardDialog = true
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
                actions = {
                    // Format picker
                    Box {
                        TextButton(onClick = { showFormatMenu = true }) {
                            Text(
                                selectedFormat.name,
                                color = Amber,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Icon(Icons.Filled.ArrowDropDown, null, tint = Amber,
                                modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(
                            expanded         = showFormatMenu,
                            onDismissRequest = { showFormatMenu = false },
                            containerColor   = Surface2
                        ) {
                            listOf(LyricFormat.LRC, LyricFormat.PLAIN).forEach { fmt ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment     = Alignment.CenterVertically
                                        ) {
                                            if (selectedFormat == fmt)
                                                Icon(Icons.Filled.Check, null,
                                                    tint = Amber, modifier = Modifier.size(14.dp))
                                            else Spacer(Modifier.size(14.dp))
                                            Text(fmt.name, color = TextPrimary)
                                        }
                                    },
                                    onClick = { selectedFormat = fmt; showFormatMenu = false }
                                )
                            }
                        }
                    }
                    // Preview toggle
                    IconButton(onClick = { showPreview = !showPreview }) {
                        Icon(
                            Icons.Filled.Visibility, "Preview",
                            tint = if (showPreview) Amber else TextSecondary
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = Surface1, tonalElevation = 3.dp) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    // Mode tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EditorMode.values().forEach { mode ->
                            val selected = editorMode == mode
                            FilterChip(
                                selected = selected,
                                onClick  = {
                                    if (mode == EditorMode.LINE && editorMode == EditorMode.RAW)
                                        syncRawToLine()
                                    if (mode == EditorMode.RAW && editorMode == EditorMode.LINE)
                                        syncLineToRaw()
                                    editorMode = mode
                                },
                                label    = { Text(mode.label) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberDim,
                                    selectedLabelColor     = Amber,
                                    containerColor         = Surface3,
                                    labelColor             = TextSecondary
                                )
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Line count badge
                        val lineCount = previewDoc?.lines?.size ?: 0
                        if (lineCount > 0) {
                            Text(
                                "$lineCount lines",
                                style  = MaterialTheme.typography.bodySmall,
                                color  = TextMuted,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }

                    // Save button
                    Button(
                        onClick  = {
                            if (editorMode == EditorMode.LINE) syncLineToRaw()
                            onSaveAndBake(rawText.text, selectedFormat)
                        },
                        enabled  = !isSaving && rawText.text.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = Amber,
                            disabledContainerColor = AmberDim
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color       = Amoled,
                                strokeWidth = 2.dp,
                                modifier    = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Saving…", color = Amoled, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.Save, null,
                                modifier = Modifier.size(18.dp), tint = Amoled)
                            Spacer(Modifier.width(8.dp))
                            Text("Save & Bake into File", color = Amoled,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->

        // Save result snackbar
        saveMessage?.let { msg ->
            LaunchedEffect(msg) {
                // Caller controls this via a snackbar host if desired
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Editor panel ──────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                // Song info strip
                SongInfoStrip(song = song)

                // Helper bar (LRC format only)
                if (selectedFormat == LyricFormat.LRC && editorMode == EditorMode.RAW) {
                    LrcHelperBar(
                        onInsertTimestamp = { ts ->
                            val cursor = rawText.selection.start
                            val newText = buildString {
                                append(rawText.text.substring(0, cursor))
                                append(ts)
                                append(rawText.text.substring(cursor))
                            }
                            rawText = TextFieldValue(
                                text      = newText,
                                selection = TextRange(cursor + ts.length)
                            )
                        }
                    )
                }

                when (editorMode) {
                    EditorMode.RAW  -> RawEditor(
                        value    = rawText,
                        onChange = { rawText = it },
                        modifier = Modifier.weight(1f)
                    )
                    EditorMode.LINE -> LineEditor(
                        lines    = lineItems,
                        onChange = { lineItems = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Preview panel (side-by-side on wide screens) ──────────────────
            AnimatedVisibility(
                visible = showPreview,
                enter   = slideInHorizontally { it },
                exit    = slideOutHorizontally { it }
            ) {
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(Surface2)
                        .padding(12.dp)
                ) {
                    Text(
                        "PREVIEW",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Amber,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (previewDoc == null || previewDoc.lines.isEmpty()) {
                        Text(
                            "No lines parsed yet.\nType some lyrics first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(previewDoc.lines) { line ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    if (line.timestampMs != null) {
                                        val ts = line.timestampMs!!
                                        Text(
                                            formatDuration(ts),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Amber
                                        )
                                    }
                                    Text(
                                        line.text.ifBlank { "♪" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Discard confirmation ──────────────────────────────────────────────────
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor   = Surface2,
            icon    = { Icon(Icons.Filled.Warning, null, tint = Amber) },
            title   = { Text("Discard changes?", color = TextPrimary) },
            text    = { Text("Your edits haven't been saved.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text("Discard", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing", color = Amber)
                }
            }
        )
    }
}

// ── Song Info Strip ───────────────────────────────────────────────────────────

@Composable
private fun SongInfoStrip(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AlbumArtwork(song, size = 44.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title,
                style      = MaterialTheme.typography.bodyMedium,
                color      = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1)
            Text("${song.artist} · ${formatDuration(song.duration)}",
                style   = MaterialTheme.typography.bodySmall,
                color   = TextMuted,
                maxLines = 1)
        }
        // Existing lyrics indicator
        if (song.lyricDocument != null) {
            AssistChip(
                onClick = {},
                label   = {
                    Text("${song.lyricDocument.lines.size} lines",
                        style = MaterialTheme.typography.labelSmall)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Lyrics, null,
                        modifier = Modifier.size(14.dp), tint = Amber)
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = AmberDim,
                    labelColor     = Amber
                )
            )
        }
    }
}

// ── LRC helper bar ────────────────────────────────────────────────────────────

@Composable
private fun LrcHelperBar(onInsertTimestamp: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Insert:",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted)

        // Common metadata tags
        listOf(
            "[ti:]" to "Title",
            "[ar:]" to "Artist",
            "[al:]" to "Album"
        ).forEach { (tag, label) ->
            SuggestionChip(
                onClick  = { onInsertTimestamp(tag) },
                label    = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors   = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = Surface3,
                    labelColor     = TextSecondary
                )
            )
        }

        Spacer(Modifier.weight(1f))

        // Quick timestamp at 00:00.00 — useful as a template
        SuggestionChip(
            onClick = { onInsertTimestamp("[00:00.00]") },
            label   = { Text("[00:00.00]", style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace) },
            colors  = SuggestionChipDefaults.suggestionChipColors(
                containerColor = AmberDim,
                labelColor     = Amber
            )
        )
    }
}

// ── Raw Editor ────────────────────────────────────────────────────────────────

@Composable
private fun RawEditor(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        modifier      = modifier
            .fillMaxSize()
            .padding(12.dp),
        placeholder   = {
            Text(
                "[ti:Song Title]\n[ar:Artist Name]\n\n[00:10.00]First lyric line\n[00:14.50]Second line\n…",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                fontFamily = FontFamily.Monospace
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            color      = TextPrimary
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = Amber,
            unfocusedBorderColor    = Surface3,
            focusedContainerColor   = Surface1,
            unfocusedContainerColor = Surface1,
            cursorColor             = Amber
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction      = ImeAction.Default
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ── Line Editor ───────────────────────────────────────────────────────────────

@Composable
private fun LineEditor(
    lines: List<LineItem>,
    onChange: (List<LineItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Column(modifier = modifier) {
        // Toolbar: add blank line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { onChange(lines + LineItem(text = "", timestampMs = null)) },
                colors  = ButtonDefaults.textButtonColors(contentColor = Amber)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add line", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    onChange(lines.map { it.copy(timestampMs = null) })
                },
                colors = ButtonDefaults.textButtonColors(contentColor = TextMuted)
            ) {
                Icon(Icons.Filled.TimerOff, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear timestamps", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(lines, key = { idx, _ -> idx }) { idx, item ->
                LineEditorItem(
                    item      = item,
                    index     = idx,
                    onTextChange = { newText ->
                        onChange(lines.toMutableList().also { it[idx] = item.copy(text = newText) })
                    },
                    onTimestampChange = { tsMs ->
                        onChange(lines.toMutableList().also { it[idx] = item.copy(timestampMs = tsMs) })
                    },
                    onDelete = {
                        onChange(lines.toMutableList().also { it.removeAt(idx) })
                    },
                    onMoveUp = if (idx > 0) ({
                        val mut = lines.toMutableList()
                        val tmp = mut[idx - 1]; mut[idx - 1] = mut[idx]; mut[idx] = tmp
                        onChange(mut)
                    }) else null,
                    onMoveDown = if (idx < lines.lastIndex) ({
                        val mut = lines.toMutableList()
                        val tmp = mut[idx + 1]; mut[idx + 1] = mut[idx]; mut[idx] = tmp
                        onChange(mut)
                    }) else null
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineEditorItem(
    item: LineItem,
    index: Int,
    onTextChange: (String) -> Unit,
    onTimestampChange: (Long?) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    var showTimestampDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Line number
        Text(
            "${index + 1}",
            style    = MaterialTheme.typography.labelSmall,
            color    = TextMuted,
            modifier = Modifier.width(24.dp)
        )

        // Timestamp chip
        val tsText = item.timestampMs?.let { formatLrcTimestamp(it) } ?: "--:--.--"
        SuggestionChip(
            onClick = { showTimestampDialog = true },
            label   = {
                Text(
                    tsText,
                    style      = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color      = if (item.timestampMs != null) Amber else TextMuted
                )
            },
            modifier = Modifier.widthIn(min = 80.dp),
            colors   = SuggestionChipDefaults.suggestionChipColors(
                containerColor = if (item.timestampMs != null) AmberDim else Surface3
            )
        )

        // Lyric text
        BasicLineTextField(
            value    = item.text,
            onChange = onTextChange,
            modifier = Modifier.weight(1f)
        )

        // Move up/down
        Column {
            IconButton(
                onClick  = { onMoveUp?.invoke() },
                enabled  = onMoveUp != null,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, null,
                    tint     = if (onMoveUp != null) TextSecondary else Surface3,
                    modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick  = { onMoveDown?.invoke() },
                enabled  = onMoveDown != null,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, null,
                    tint     = if (onMoveDown != null) TextSecondary else Surface3,
                    modifier = Modifier.size(16.dp))
            }
        }

        // Delete
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.DeleteOutline, "Delete",
                tint     = ErrorRed.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp))
        }
    }

    // ── Timestamp editor dialog ───────────────────────────────────────────────
    if (showTimestampDialog) {
        TimestampDialog(
            currentMs  = item.timestampMs,
            onConfirm  = { ms -> onTimestampChange(ms); showTimestampDialog = false },
            onClear    = { onTimestampChange(null); showTimestampDialog = false },
            onDismiss  = { showTimestampDialog = false }
        )
    }
}

@Composable
private fun BasicLineTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        modifier      = modifier,
        textStyle     = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
        singleLine    = true,
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = Amber,
            unfocusedBorderColor    = Surface3,
            focusedContainerColor   = Surface1,
            unfocusedContainerColor = Color.Transparent,
            cursorColor             = Amber
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        shape = RoundedCornerShape(8.dp)
    )
}

// ── Timestamp dialog ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimestampDialog(
    currentMs: Long?,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val initial  = currentMs ?: 0L
    var minutes  by remember { mutableStateOf((initial / 60000).toString()) }
    var seconds  by remember { mutableStateOf(((initial % 60000) / 1000).toString()) }
    var millis   by remember { mutableStateOf((initial % 1000).toString().padStart(3, '0')) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = { Text("Set Timestamp", color = TextPrimary) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("mm : ss . ms", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    TimestampField(
                        value    = minutes,
                        label    = "min",
                        onChange = { minutes = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    TimestampField(
                        value    = seconds,
                        label    = "sec",
                        onChange = { seconds = it.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(".", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    TimestampField(
                        value    = millis,
                        label    = "ms",
                        onChange = { millis = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.weight(1.5f)
                    )
                }
                // Computed preview
                val previewMs = ((minutes.toLongOrNull() ?: 0L) * 60_000L) +
                                ((seconds.toLongOrNull() ?: 0L) * 1_000L) +
                                 (millis.toLongOrNull()  ?: 0L)
                Text(
                    "→ ${formatLrcTimestamp(previewMs)}",
                    style      = MaterialTheme.typography.bodySmall,
                    color      = Amber,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ms = ((minutes.toLongOrNull() ?: 0L) * 60_000L) +
                         ((seconds.toLongOrNull() ?: 0L) * 1_000L) +
                          (millis.toLongOrNull()  ?: 0L)
                onConfirm(ms)
            }) { Text("Set", color = Amber) }
        },
        dismissButton = {
            Row {
                if (currentMs != null) {
                    TextButton(onClick = onClear) { Text("Clear", color = ErrorRed) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
            }
        }
    )
}

@Composable
private fun TimestampField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        modifier      = modifier,
        label         = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle     = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            color      = TextPrimary
        ),
        singleLine    = true,
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Amber,
            unfocusedBorderColor = Surface3,
            cursorColor          = Amber,
            focusedLabelColor    = Amber
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
}

// ── Data types ────────────────────────────────────────────────────────────────

private enum class EditorMode(val label: String) {
    RAW("Raw LRC"),
    LINE("Line by line")
}

data class LineItem(
    val text: String,
    val timestampMs: Long?
)

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Parse raw LRC/plain text into [LineItem] list for the line editor. */
private fun parseToLineItems(raw: String): List<LineItem> {
    if (raw.isBlank()) return listOf(LineItem("", null))
    return runCatching {
        val doc = LyricParser.parse(raw, LyricFormat.LRC)
        if (doc.lines.isNotEmpty()) {
            doc.lines.map { LineItem(it.text, it.timestampMs) }
        } else {
            raw.lines().filter { it.isNotBlank() }.map { LineItem(it.trim(), null) }
        }
    }.getOrElse {
        raw.lines().filter { it.isNotBlank() }.map { LineItem(it.trim(), null) }
    }
}

/** Convert [LineItem] list back to LRC text. */
private fun lineItemsToLrc(items: List<LineItem>): String = buildString {
    for (item in items) {
        val ts = item.timestampMs
        if (ts != null) {
            append(formatLrcTimestamp(ts))
        }
        appendLine(item.text)
    }
}

/** Format milliseconds as [mm:ss.xx] LRC timestamp. */
fun formatLrcTimestamp(ms: Long): String {
    val mins = ms / 60_000
    val secs = (ms % 60_000) / 1000.0
    return "[%02d:%05.2f]".format(mins, secs)
}
