package com.example.scanapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Drives the Telegram-style circular "day/night" reveal: on toggle, the
 * currently-displayed theme is snapshotted as a bitmap, the app is switched
 * to the new theme immediately underneath (invisible for now, since the
 * snapshot is covering it), and that snapshot is then punched away by a
 * circle growing from wherever the person tapped — uncovering the new
 * theme exactly like Telegram's classic dark-mode transition.
 */
class ThemeRevealState {
    var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set
    var center by mutableStateOf(Offset.Zero)
        private set
    var radius by mutableFloatStateOf(0f)
        private set

    internal var containerSize: IntSize = IntSize.Zero
    private var isAnimating = false

    /**
     * Captures [graphicsLayer]'s current pixels (the theme about to be
     * replaced), flips the theme via [switchTheme], then animates a circle
     * centered on [tapCenter] growing until it clears every corner of the
     * screen, revealing the already-switched content beneath.
     */
    fun trigger(
        scope: CoroutineScope,
        graphicsLayer: GraphicsLayer,
        tapCenter: Offset,
        switchTheme: () -> Unit
    ) {
        if (isAnimating) return
        isAnimating = true
        scope.launch {
            val oldThemeSnapshot = graphicsLayer.toImageBitmap()

            // Switch the real theme now — it renders underneath immediately,
            // hidden by the snapshot overlay until the reveal circle passes.
            switchTheme()

            val corners = listOf(
                Offset(0f, 0f),
                Offset(containerSize.width.toFloat(), 0f),
                Offset(0f, containerSize.height.toFloat()),
                Offset(containerSize.width.toFloat(), containerSize.height.toFloat())
            )
            val maxRadius = corners.maxOf { corner ->
                hypot(corner.x - tapCenter.x, corner.y - tapCenter.y)
            }

            snapshot = oldThemeSnapshot
            center = tapCenter
            radius = 0f

            animate(
                initialValue = 0f,
                targetValue = maxRadius,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            ) { value, _ -> radius = value }

            snapshot = null
            isAnimating = false
        }
    }
}

@Composable
fun rememberThemeRevealState(): ThemeRevealState = remember { ThemeRevealState() }

/**
 * Wraps [content], continuously recording its pixels into [graphicsLayer]
 * (so [ThemeRevealState.trigger] can snapshot it on demand) and, while a
 * reveal animation is in progress, overlays the pre-switch snapshot with a
 * growing circular hole cut out of it — see [ThemeRevealState].
 */
@Composable
fun ThemeRevealContainer(
    state: ThemeRevealState,
    graphicsLayer: GraphicsLayer,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .onSizeChanged { state.containerSize = it }
            .drawWithContent {
                graphicsLayer.record { this@drawWithContent.drawContent() }
                drawLayer(graphicsLayer)
            }
    ) {
        content()

        val snapshot = state.snapshot
        if (snapshot != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val holePath = Path().apply {
                    addOval(Rect(center = state.center, radius = state.radius))
                }
                clipPath(path = holePath, clipOp = ClipOp.Difference) {
                    drawImage(snapshot)
                }
            }
        }
    }
}
