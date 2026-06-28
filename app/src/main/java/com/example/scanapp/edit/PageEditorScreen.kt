package com.example.scanapp.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Edits one page in place. The only edit action is Auto-enhance — a histogram-
 * based contrast/brightness stretch tailored to the page's own luminance
 * range (see BitmapEditOps.autoEnhance), which evens out shadows and uneven
 * phone-camera lighting on scanned documents. On Save, the caller writes the
 * resulting bitmap back to the page's file path.
 *
 * Crop, rotate, and manual filters were intentionally dropped from this
 * screen — Auto-enhance is the one tool kept here. Re-cropping still lives at
 * scan time via ML Kit's own capture UI (see DocumentScannerLauncher).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    pageFilePath: String,
    onSave: (editedBitmap: Bitmap) -> Unit,
    onRescanRequested: () -> Unit,
    onCancel: () -> Unit
) {
    // originalBitmap = exactly what's on disk, never mutated — Auto-enhance
    // always re-applies onto this, so tapping it twice doesn't double-stretch.
    val originalBitmap = remember(pageFilePath) { BitmapFactory.decodeFile(pageFilePath) }
    var enhanced by remember { mutableStateOf(false) }
    val displayedBitmap by remember(enhanced) {
        mutableStateOf(if (enhanced) BitmapEditOps.autoEnhance(originalBitmap) else originalBitmap)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Edit Page",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(displayedBitmap) }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(
                            onClick = { enhanced = !enhanced }
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = "Auto-enhance")
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (enhanced) "Enhanced" else "Auto-enhance",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = displayedBitmap.asImageBitmap(),
                contentDescription = "Page preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(displayedBitmap.width.toFloat() / displayedBitmap.height.toFloat())
            )
        }
    }
}
