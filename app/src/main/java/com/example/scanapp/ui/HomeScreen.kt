package com.example.scanapp.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color // Added missing import
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.rememberAsyncImagePainter
import com.example.scanapp.export.OutputFormat
import com.example.scanapp.data.DocumentSortBy
import com.example.scanapp.data.SortDirection

data class RecentDocument(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUri: Uri?,
    val pageCount: Int,
    val modifiedAtMillis: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recentDocuments: List<RecentDocument>,
    onScanClick: () -> Unit,
    onImportPdfClick: () -> Unit = {},
    isImportingPdf: Boolean = false,
    pdfImportError: String? = null,
    pdfImportProgressText: String? = null,
    onDocumentClick: (RecentDocument) -> Unit,
    onRename: (RecentDocument, newTitle: String) -> Unit = { _, _ -> },
    onDelete: (RecentDocument) -> Unit = {},
    onShare: (RecentDocument, OutputFormat) -> Unit = { _, _ -> },
    onDeleteMultiple: (List<RecentDocument>) -> Unit = {},
    onReorder: (List<RecentDocument>) -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    sortBy: DocumentSortBy = DocumentSortBy.DATE_MODIFIED,
    sortDirection: SortDirection = SortDirection.DESCENDING,
    onSortChange: (DocumentSortBy, SortDirection) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onToolsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.AUTO,
    onThemeModeSelected: (ThemeMode, Offset) -> Unit = { _, _ -> },
    listState: LazyListState = rememberLazyListState()
) {
    var actionSheetTarget by remember { mutableStateOf<RecentDocument?>(null) }
    var renameTarget by remember { mutableStateOf<RecentDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<RecentDocument?>(null) }
    var shareTarget by remember { mutableStateOf<RecentDocument?>(null) }
    var deleteMultipleConfirm by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var searchExpanded by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var themeMenuExpanded by remember { mutableStateOf(false) }
    var toggleButtonCenter by remember { mutableStateOf(Offset.Zero) }

    var orderedDocs by remember { mutableStateOf(recentDocuments) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(recentDocuments) {
        if (draggingId == null) orderedDocs = recentDocuments
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(pdfImportError) {
        if (pdfImportError != null) {
            snackbarHostState.showSnackbar(pdfImportError)
        }
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelected(doc: RecentDocument) {
        selectedIds = if (selectedIds.contains(doc.id)) {
            selectedIds - doc.id
        } else {
            selectedIds + doc.id
        }
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun endDrag() {
        draggingId = null
        dragOffsetY = 0f
        onReorder(orderedDocs)
    }

    BackHandler(enabled = selectionMode || searchExpanded) {
        if (selectionMode) {
            clearSelection()
        } else if (searchExpanded) {
            onSearchQueryChange("")
            searchExpanded = false
        }
    }

    var navBarHeightPx by remember { mutableStateOf(0) }
    val navBarHeightDp = with(LocalDensity.current) { navBarHeightPx.toDp() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            Box(Modifier.padding(bottom = navBarHeightDp)) {
                SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            shareTarget = recentDocuments.firstOrNull { it.id in selectedIds }
                        }, enabled = selectedIds.size == 1) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(
                            onClick = { deleteMultipleConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = navBarHeightDp)
                ) {
                    if (isImportingPdf && pdfImportProgressText != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(bottom = 8.