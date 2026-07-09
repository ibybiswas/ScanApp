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
import androidx.compose.ui.graphics.Color
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
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                pdfImportProgressText,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    SmallFloatingActionButton(
                        onClick = { if (!isImportingPdf) onImportPdfClick() }
                    ) {
                        if (isImportingPdf) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.UploadFile, contentDescription = "Import PDF")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    FloatingActionButton(onClick = onScanClick) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Scan document")
                    }
                }
            }
        }
    ) { padding ->
        var headerHeightPx by remember { mutableStateOf(0) }
        val headerHeightDp = with(LocalDensity.current) { headerHeightPx.toDp() }

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (recentDocuments.isEmpty()) {
                EmptyRecentsState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeightDp, bottom = navBarHeightDp),
                    isSearching = searchQuery.isNotBlank()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = headerHeightDp, bottom = navBarHeightDp)
                ) {
                    items(orderedDocs, key = { it.id }) { doc ->
                        val isDragging = draggingId == doc.id
                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                }
                                .onGloballyPositioned { coords ->
                                    if (rowHeightPx == 0) rowHeightPx = coords.size.height
                                }
                        ) {
                            Column {
                                RecentDocumentRow(
                                    doc = doc,
                                    selectionMode = selectionMode,
                                    selected = doc.id in selectedIds,
                                    onClick = {
                                        if (selectionMode) toggleSelected(doc) else onDocumentClick(doc)
                                    },
                                    onLongClick = {
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds = setOf(doc.id)
                                        } else {
                                            toggleSelected(doc)
                                        }
                                    },
                                    onMoreClick = { actionSheetTarget = doc },
                                    showDragHandle = selectionMode && searchQuery.isBlank(),
                                    onDragStart = { draggingId = doc.id; dragOffsetY = 0f },
                                    onDrag = { deltaY ->
                                        dragOffsetY += deltaY
                                        val heightPx = rowHeightPx
                                        if (heightPx > 0) {
                                            val fromIndex = orderedDocs.indexOfFirst { it.id == doc.id }
                                            if (fromIndex != -1) {
                                                if (dragOffsetY > heightPx / 2 && fromIndex < orderedDocs.lastIndex) {
                                                    orderedDocs = orderedDocs.toMutableList().apply {
                                                        add(fromIndex + 1, removeAt(fromIndex))
                                                    }
                                                    dragOffsetY -= heightPx
                                                } else if (dragOffsetY < -heightPx / 2 && fromIndex > 0) {
                                                    orderedDocs = orderedDocs.toMutableList().apply {
                                                        add(fromIndex - 1, removeAt(fromIndex))
                                                    }
                                                    dragOffsetY += heightPx
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = { endDrag() }
                                )
                                HorizontalDivider(modifier = Modifier.padding(start = 116.dp))
                            }
                        }
                    }
                }
            }

            // Absolutely transparent background area behind header text and status bar icons
            Surface(
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { headerHeightPx = it.size.height }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!selectionMode) Modifier.statusBarsPadding() else Modifier)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp), // Cut down to 0.dp
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search files") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    onSearchQueryChange("")
                                    searchExpanded = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                        )
                    } else {
                        Text(
                            "All files",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            IconButton(
                                onClick = { themeMenuExpanded = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .onGloballyPositioned { coords ->
                                        val pos = coords.positionInRoot()
                                        toggleButtonCenter = Offset(
                                            pos.x + coords.size.width / 2f,
                                            pos.y + coords.size.height / 2f
                                        )
                                    }
                            ) {
                                Icon(
                                    when (themeMode) {
                                        ThemeMode.AUTO -> Icons.Filled.BrightnessAuto
                                        ThemeMode.DARK -> Icons.Filled.DarkMode
                                        ThemeMode.LIGHT -> Icons.Filled.LightMode
                                    },
                                    contentDescription = "Day/night mode"
                                )
                            }
                            ThemeMenu(
                                expanded = themeMenuExpanded,
                                current = themeMode,
                                onDismiss = { themeMenuExpanded = false },
                                onSelect = { newMode ->
                                    themeMenuExpanded = false
                                    onThemeModeSelected(newMode, toggleButtonCenter)
                                }
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { searchExpanded = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        Spacer(Modifier.width(4.dp))
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            SortMenu(
                                expanded = sortMenuExpanded,
                                sortBy = sortBy,
                                direction = sortDirection,
                                onDismiss = { sortMenuExpanded = false },
                                onSelect = { newSortBy, newDirection ->
                                    sortMenuExpanded = false
                                    onSortChange(newSortBy, newDirection)
                                }
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        if (recentDocuments.isNotEmpty()) {
                            TextButton(onClick = {
                                selectionMode = true
                                selectedIds = recentDocuments.map { it.id }.toSet()
                            }) {
                                Text("Select all")
                            }
                        }
                    }
                }
            }

            ScanAppBottomNav(
                onSettingsClick = onSettingsClick,
                onToolsClick = onToolsClick,
                onBackupClick = onBackupClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { navBarHeightPx = it.size.height }
            )
        }
    }

    actionSheetTarget?.let { doc ->
        DocumentActionSheet(
            onDismiss = { actionSheetTarget = null },
            onRenameClick = { actionSheetTarget = null; renameTarget = doc },
            onDeleteClick = { actionSheetTarget = null; deleteTarget = doc },
            onShareClick = { actionSheetTarget = null; shareTarget = doc }
        )
    }

    renameTarget?.let { doc ->
        RenameDialog(
            currentTitle = doc.title,
            onConfirm = { newTitle -> renameTarget = null; onRename(doc, newTitle) },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete document?") },
            text = { Text("This will permanently delete \"${doc.title}\" and all its pages.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(doc) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (deleteMultipleConfirm) {
        val targets = recentDocuments.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { deleteMultipleConfirm = false },
            title = { Text("Delete ${targets.size} document${if (targets.size == 1) "" else "s"}?") },
            text = { Text("This will permanently delete the selected document(s) and all their pages.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteMultipleConfirm = false
                    onDeleteMultiple(targets)
                    clearSelection()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMultipleConfirm =