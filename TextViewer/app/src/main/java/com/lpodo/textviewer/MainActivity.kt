package com.lpodo.textviewer

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnPreDraw
import com.lpodo.textviewer.ui.theme.TextViewerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            TextViewerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EditorScreen(viewModel, onExit = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("WrongConstant") // takeFlags is masked to exactly the two URI-permission flags
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val canWrite = (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        val startInEdit = intent.action == Intent.ACTION_EDIT && canWrite
        val takeFlags = intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (takeFlags != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
                // One-shot (non-persistable) grant — still usable right now.
            }
        }
        viewModel.loadFile(uri, canWrite, startInEdit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: MainViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    val currentText: () -> String = {
        if (viewModel.isEditing) {
            editTextRef.value?.text?.toString() ?: viewModel.editorInitialText()
        } else {
            viewModel.editorInitialText()
        }
    }
    val isModified: () -> Boolean = { viewModel.isModifiedAgainst(currentText()) }

    val enterEdit: () -> Unit = {
        showSearch = false
        viewModel.requestEdit(listState.firstVisibleItemIndex)
    }
    val exitEdit: () -> Unit = {
        val et = editTextRef.value
        val text = et?.text?.toString() ?: viewModel.editorInitialText()
        val topLine = et?.layout?.let { l ->
            viewModel.offsetToLine(l.getLineStart(l.getLineForVertical(et.scrollY)))
        } ?: viewModel.pendingTopLine
        viewModel.exitEditMode(text)
        val maxIndex = (viewModel.lines.size - 1).coerceAtLeast(0)
        scope.launch { listState.scrollToItem(topLine.coerceIn(0, maxIndex)) }
    }

    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val textSizeSp = MaterialTheme.typography.bodyLarge.fontSize.value

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri -> uri?.let { viewModel.saveFile(currentText(), it) { onExit() } } }
    )
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.openWritable(it) } }
    )

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(viewModel.currentMatch, viewModel.matches) {
        val line = viewModel.currentMatchLine
        if (line >= 0 && !viewModel.isEditing) listState.scrollToItem(line)
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backCallback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showSearch -> { showSearch = false; viewModel.updateSearch("") }
                    viewModel.isEditing -> exitEdit()
                    isModified() -> viewModel.showSaveDialog = true
                    else -> onExit()
                }
            }
        }
    }
    DisposableEffect(backDispatcher) {
        backDispatcher?.addCallback(backCallback)
        onDispose { backCallback.remove() }
    }

    if (viewModel.showSaveDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showSaveDialog = false },
            title = { Text("Save changes?") },
            text = { Text("Do you want to save changes to ${viewModel.fileName}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.showSaveDialog = false
                    if (viewModel.fileUri == null || viewModel.isReadOnly) {
                        saveLauncher.launch(viewModel.fileName.ifEmpty { "new_file.txt" })
                    } else {
                        viewModel.saveFile(currentText()) { onExit() }
                    }
                }) {
                    Text(if (viewModel.fileUri == null || viewModel.isReadOnly) "Save As" else "Save")
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { viewModel.showSaveDialog = false; onExit() }) {
                        Text("Don't save")
                    }
                    TextButton(onClick = { viewModel.showSaveDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    val noFileOpen = viewModel.fileUri == null && !viewModel.isEditing && viewModel.lines.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (noFileOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.newDocument() }) {
                                Icon(Icons.Default.Description, contentDescription = "New document")
                            }
                            IconButton(onClick = { openLauncher.launch(arrayOf("text/*")) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Open file")
                            }
                        }
                    } else {
                        Text(
                            text = viewModel.fileName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    val hasContent = viewModel.lines.isNotEmpty() || viewModel.isEditing
                    if (viewModel.isEditing) {
                        IconButton(onClick = exitEdit) {
                            Icon(Icons.Default.Check, contentDescription = "Done editing")
                        }
                    } else if (hasContent) {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = "Find")
                        }
                        IconButton(onClick = enterEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                    IconButton(onClick = {
                        if (isModified()) viewModel.showSaveDialog = true else onExit()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            Column {
                HorizontalDivider()
                if (showSearch && !viewModel.isEditing) {
                    SearchBar(
                        viewModel = viewModel,
                        onClose = { showSearch = false; viewModel.updateSearch("") }
                    )
                    HorizontalDivider()
                }
                if (viewModel.isEditing) {
                    EditorView(viewModel, editTextRef, textColor, textSizeSp)
                } else {
                    ReaderView(viewModel, listState)
                }
            }

            if (viewModel.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Compact single-line input used in the search/replace bar. */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .heightIn(min = 36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun SearchBar(viewModel: MainViewModel, onClose: () -> Unit) {
    var replaceText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.updateSearch(it) },
                placeholder = "Find",
                modifier = Modifier.weight(1f)
            )
            val count = viewModel.matches.size
            val cur = if (count == 0) 0 else viewModel.currentMatch + 1
            Text(
                "$cur/$count",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            IconButton(
                onClick = { viewModel.prevMatch() },
                enabled = count > 0,
                modifier = Modifier.size(36.dp)
            ) { Icon(Icons.Default.KeyboardArrowUp, "Previous", Modifier.size(22.dp)) }
            IconButton(
                onClick = { viewModel.nextMatch() },
                enabled = count > 0,
                modifier = Modifier.size(36.dp)
            ) { Icon(Icons.Default.KeyboardArrowDown, "Next", Modifier.size(22.dp)) }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "Close find", Modifier.size(20.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = replaceText,
                onValueChange = { replaceText = it },
                placeholder = "Replace with",
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { viewModel.replaceCurrent(replaceText) },
                enabled = viewModel.currentMatch >= 0,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("Replace", style = MaterialTheme.typography.bodyMedium) }
            TextButton(
                onClick = { viewModel.replaceAll(replaceText) },
                enabled = viewModel.matches.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("All", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Fast, virtualized read-only view with highlighting + auto-hiding fast-scroll. */
@Composable
private fun ReaderView(viewModel: MainViewModel, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val query = viewModel.searchQuery
    val curLine = viewModel.currentMatchLine
    val curStart = viewModel.currentMatchStart
    val matchBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val currentBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbHeight = 48.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val maxOffset = (trackPx - thumbPx).coerceAtLeast(1f)

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            ) {
                itemsIndexed(viewModel.lines) { index, line ->
                    Text(
                        text = highlightLine(line, index, query, curLine, curStart, matchBg, currentBg),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        val total = listState.layoutInfo.totalItemsCount
        val canScroll = listState.canScrollForward || listState.canScrollBackward
        if (canScroll && total > 1) {
            val posFraction =
                (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
            var dragTop by remember { mutableStateOf<Float?>(null) }
            val thumbTop = dragTop ?: (posFraction * maxOffset)

            // Auto-hide: visible while scrolling/dragging, fades out when idle.
            val active = listState.isScrollInProgress || dragTop != null
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(active) {
                if (active) visible = true else { delay(1500); visible = false }
            }
            val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "thumbAlpha")

            if (alpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbTop.roundToInt()) }
                        .width(44.dp)
                        .height(thumbHeight)
                        .pointerInput(maxOffset, total) {
                            detectVerticalDragGestures(
                                onDragStart = { dragTop = thumbTop },
                                onDragEnd = { dragTop = null },
                                onDragCancel = { dragTop = null }
                            ) { change, dy ->
                                change.consume()
                                val base = dragTop ?: thumbTop
                                val newTop = (base + dy).coerceIn(0f, maxOffset)
                                dragTop = newTop
                                val f = newTop / maxOffset
                                val target = (f * (total - 1)).roundToInt().coerceIn(0, total - 1)
                                scope.launch { listState.scrollToItem(target) }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp) // sit well clear of the curved bezel
                            .width(8.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = (if (dragTop != null) 0.7f else 0.45f) * alpha
                                ),
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
        }
    }
}

/** Builds an AnnotatedString with search matches highlighted. */
private fun highlightLine(
    line: String,
    lineIndex: Int,
    query: String,
    curLine: Int,
    curStart: Int,
    matchBg: Color,
    currentBg: Color
): AnnotatedString {
    if (line.isEmpty()) return AnnotatedString(" ")
    if (query.isEmpty()) return AnnotatedString(line)
    var idx = line.indexOf(query, 0, ignoreCase = true)
    if (idx < 0) return AnnotatedString(line)
    return buildAnnotatedString {
        var last = 0
        val step = query.length.coerceAtLeast(1)
        while (idx >= 0) {
            append(line.substring(last, idx))
            val isCurrent = lineIndex == curLine && idx == curStart
            withStyle(SpanStyle(background = if (isCurrent) currentBg else matchBg)) {
                append(line.substring(idx, idx + query.length))
            }
            last = idx + query.length
            idx = line.indexOf(query, last.coerceAtLeast(idx + step), ignoreCase = true)
        }
        append(line.substring(last))
    }
}

/** Native EditText for editing. Created only when entering edit mode. */
@Composable
private fun EditorView(
    viewModel: MainViewModel,
    editTextRef: androidx.compose.runtime.MutableState<EditText?>,
    textColor: Int,
    textSizeSp: Float
) {
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                isSingleLine = false
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = true
                inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setText(viewModel.editorInitialText())
                val target = viewModel.lineStartOffset(viewModel.pendingTopLine)
                    .coerceIn(0, text?.length ?: 0)
                setSelection(target)
                doOnPreDraw {
                    val l = layout ?: return@doOnPreDraw
                    scrollTo(0, l.getLineTop(l.getLineForOffset(target)))
                }
                editTextRef.value = this
            }
        },
        modifier = Modifier.fillMaxSize().padding(16.dp)
    )
}
