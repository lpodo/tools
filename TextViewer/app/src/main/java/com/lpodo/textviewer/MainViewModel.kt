package com.lpodo.textviewer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/** A single search hit: [start] is the offset within line [line]. */
data class SearchMatch(val line: Int, val start: Int)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var fullText: String = ""

    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    private var lineStartOffsets: IntArray = intArrayOf(0)

    private var originalText: String = ""

    var pendingTopLine: Int = 0
        private set

    var isEditing by mutableStateOf(false)
        private set

    var fileName by mutableStateOf("New File")
        private set

    var fileUri by mutableStateOf<Uri?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var showSaveDialog by mutableStateOf(false)

    var isReadOnly by mutableStateOf(false)
        private set

    // ---- Search / replace state ----
    var searchQuery by mutableStateOf("")
        private set
    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set
    var currentMatch by mutableStateOf(-1)
        private set

    val currentMatchLine: Int get() = matches.getOrNull(currentMatch)?.line ?: -1
    val currentMatchStart: Int get() = matches.getOrNull(currentMatch)?.start ?: -1

    private val contentResolver = application.contentResolver

    fun editorInitialText(): String = fullText

    /** Start a blank, editable document (saved later via "Save As"). */
    fun newDocument() {
        fullText = ""
        lines = listOf("")
        lineStartOffsets = intArrayOf(0)
        originalText = ""
        fileName = "New File"
        fileUri = null
        isReadOnly = false
        pendingTopLine = 0
        searchQuery = ""
        matches = emptyList()
        currentMatch = -1
        isEditing = true
    }

    /** Open a file chosen via ACTION_OPEN_DOCUMENT, keeping read+write so it can
     *  later be saved in place under the same name. */
    fun openWritable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Provider didn't grant persistable write; we still try, and the save
            // path falls back to "Save As" if writing is actually denied.
        }
        loadFile(uri, canWrite = true)
    }

    fun requestEdit(topLine: Int) {
        pendingTopLine = topLine
        isEditing = true
    }

    fun exitEditMode(editedText: String) {
        applyText(editedText)
        refreshMatches()
        isEditing = false
    }

    fun isModifiedAgainst(currentText: String): Boolean = currentText != originalText

    fun lineStartOffset(line: Int): Int {
        if (lineStartOffsets.isEmpty()) return 0
        return lineStartOffsets[line.coerceIn(0, lineStartOffsets.size - 1)]
    }

    fun offsetToLine(offset: Int): Int {
        val arr = lineStartOffsets
        if (arr.isEmpty()) return 0
        var lo = 0
        var hi = arr.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= offset) {
                ans = mid; lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    // ---- Search ----
    fun updateSearch(query: String) {
        searchQuery = query
        if (query.isEmpty()) {
            matches = emptyList()
            currentMatch = -1
            return
        }
        val snapshot = lines
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { computeMatches(query, snapshot) }
            if (query == searchQuery) {
                matches = result
                currentMatch = if (result.isEmpty()) -1 else 0
            }
        }
    }

    fun nextMatch() {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch + 1).mod(matches.size)
    }

    fun prevMatch() {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch - 1).mod(matches.size)
    }

    fun replaceCurrent(replacement: String) {
        val q = searchQuery
        val m = matches.getOrNull(currentMatch) ?: return
        if (q.isEmpty()) return
        val globalStart = lineStartOffset(m.line) + m.start
        val globalEnd = globalStart + q.length
        if (globalEnd > fullText.length) return
        val newText = fullText.substring(0, globalStart) + replacement + fullText.substring(globalEnd)
        applyText(newText)
        // Re-find and point to the next hit at/after where we just edited.
        val rebuilt = computeMatches(q, lines)
        matches = rebuilt
        currentMatch = when {
            rebuilt.isEmpty() -> -1
            else -> rebuilt.indexOfFirst { lineStartOffset(it.line) + it.start >= globalStart }
                .let { if (it < 0) 0 else it }
        }
    }

    fun replaceAll(replacement: String) {
        val q = searchQuery
        if (q.isEmpty()) return
        val newText = fullText.replace(q, replacement, ignoreCase = true)
        applyText(newText)
        matches = computeMatches(q, lines)
        currentMatch = if (matches.isEmpty()) -1 else 0
    }

    private fun refreshMatches() {
        if (searchQuery.isEmpty()) {
            matches = emptyList(); currentMatch = -1
        } else {
            matches = computeMatches(searchQuery, lines)
            currentMatch = if (matches.isEmpty()) -1 else 0
        }
    }

    private fun computeMatches(query: String, src: List<String>): List<SearchMatch> {
        val out = ArrayList<SearchMatch>()
        val step = query.length.coerceAtLeast(1)
        src.forEachIndexed { idx, line ->
            var from = 0
            while (true) {
                val i = line.indexOf(query, from, ignoreCase = true)
                if (i < 0) break
                out.add(SearchMatch(idx, i))
                from = i + step
            }
        }
        return out
    }

    fun loadFile(uri: Uri, canWrite: Boolean, startInEditMode: Boolean = false) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            fileUri = uri
            isReadOnly = !canWrite
            try {
                val size = withContext(Dispatchers.IO) {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }
                if (size > 10 * 1024 * 1024) {
                    errorMessage = "File is too large (max 10MB)"
                    isLoading = false
                    return@launch
                }
                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        InputStreamReader(inputStream, Charsets.UTF_8).use { it.readText() }
                    } ?: ""
                }
                val computed = withContext(Dispatchers.Default) {
                    splitToLines(content) to buildLineOffsets(content)
                }
                fullText = content
                lines = computed.first
                lineStartOffsets = computed.second
                originalText = content
                fileName = getFileName(uri) ?: "Unknown File"
                pendingTopLine = 0
                isEditing = startInEditMode
                searchQuery = ""
                matches = emptyList()
                currentMatch = -1
            } catch (e: Exception) {
                errorMessage = "Failed to open file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun saveFile(text: String, uri: Uri? = null, onSuccess: () -> Unit = {}) {
        val targetUri = uri ?: fileUri ?: return
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(targetUri, "wt")?.use { os ->
                        os.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw Exception("Could not open output stream")
                }
                if (uri != null) {
                    fileUri = uri
                    fileName = getFileName(uri) ?: "Unknown File"
                    isReadOnly = false
                }
                applyText(text)
                originalText = text
                onSuccess()
            } catch (e: SecurityException) {
                isReadOnly = true
                errorMessage = "Access denied. Use 'Save As' instead."
            } catch (e: Exception) {
                errorMessage = "Failed to save file: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun clearError() {
        errorMessage = null
    }

    private fun applyText(text: String) {
        fullText = text
        lines = splitToLines(text)
        lineStartOffsets = buildLineOffsets(text)
    }

    private fun splitToLines(text: String): List<String> =
        text.split('\n').map { it.removeSuffix("\r") }

    private fun buildLineOffsets(text: String): IntArray {
        val offsets = ArrayList<Int>()
        offsets.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') offsets.add(i + 1)
        }
        return offsets.toIntArray()
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val i = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && i >= 0) cursor.getString(i) else null
            }
        } catch (e: Exception) {
            uri.path?.substringAfterLast('/')
        }
    }
}
