package com.example.scanapp.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.scanapp.collage.CollageTemplate
import com.example.scanapp.collage.CollageTemplates

/** One selectable page in the cross-document picker. */
data class CollagePickerPage(
    val pageId: Long,
    val uri: Uri,
    val documentTitle: String
)

/**
 * Cross-document collage builder: pick any pages from anywhere in the
 * library, choose a layout template, preview the composed result, save it
 * as a brand new standalone document.
 *
 * Selection order matters — pages fill template cells in the order they were
 * tapped, not their library order, so the user can control which page lands
 * in which cell just by tap sequence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(
    allPages: List<CollagePickerPage>,
    previewBitmap: android.graphics.Bitmap?,
    isComposing: Boolean,
    onBackClick: () -> Unit,
    onSelectionOrTemplateChanged: (selectedPageIds: List<Long>, template: CollageTemplate) -> Unit,
    onSaveClick: () -> Unit
) {
    var selectedPageIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var selectedTemplate by remember { mutableStateOf(CollageTemplates.ALL.first()) }

    fun togglePage(pageId: Long) {
        selectedPageIds = if (pageId in selectedPageIds) {
            selectedPageIds - pageId
        } else {
            selectedPageIds + pageId
        }
        onSelectionOrTemplateChanged(selectedPageIds, selectedTemplate)
    }

    fun selectTemplate(template: CollageTemplate) {
        selectedTemplate = template
        onSelectionOrTemplateChanged(selectedPageIds, selectedTemplate)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Collage") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSaveClick,
                        enabled = selectedPageIds.isNotEmpty() && !isComposing
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Live preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isComposing) {
                    CircularProgressIndicator()
                } else if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Collage preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                } else {
                    Text(
                        "Pick pages below to preview",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Template picker
            Text(
                "Layout",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems(CollageTemplates.ALL, key = { it.id }) { template ->
                    FilterChip(
                        selected = template.id == selectedTemplate.id,
                        onClick = { selectTemplate(template) },
                        label = { Text(template.displayName) }
                    )
                }
            }

            HorizontalDivider()

            // Cross-document page picker
            Text(
                "Pick pages (${selectedPageIds.size} selected, in tap order)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp)
            )

            if (allPages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No scanned pages yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(allPages, key = { it.pageId }) { page ->
                        val selectionOrder = selectedPageIds.indexOf(page.pageId)
                        PickerThumbnail(
                            page = page,
                            selectionNumber = if (selectionOrder >= 0) selectionOrder + 1 else null,
                            onClick = { togglePage(page.pageId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerThumbnail(
    page: CollagePickerPage,
    selectionNumber: Int?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = rememberAsyncImagePainter(page.uri),
            contentDescription = page.documentTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (selectionNumber != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(6.dp).align(Alignment.TopEnd).size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "$selectionNumber",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(4.dp).align(Alignment.BottomStart)
        ) {
            Text(
                page.documentTitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
