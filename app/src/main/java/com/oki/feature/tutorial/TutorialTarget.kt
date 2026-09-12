package com.oki.feature.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registry that holds the measured window-level bounds of every composable that has registered
 * itself as a tutorial target via [Modifier.tutorialTarget].
 *
 * The map is backed by [SnapshotStateMap] so reads inside Compose automatically trigger
 * recomposition when a target appears, moves, or disappears.
 */
class TutorialTargetRegistry {
    val targets: SnapshotStateMap<String, Rect> = SnapshotStateMap()

    fun register(key: String, bounds: Rect) {
        targets[key] = bounds
    }

    fun unregister(key: String) {
        targets.remove(key)
    }

    fun boundsFor(key: String): Rect? = targets[key]
}

/**
 * Marks a composable as a tutorial-highlight target. The composable's **window-level** bounds are
 * captured each time it is laid out and stored in [TutorialTargetRegistry].
 *
 * Usage:
 * ```kotlin
 * Button(
 *     onClick = { ... },
 *     modifier = Modifier.tutorialTarget("add_fab"),
 * ) { Text("Add") }
 * ```
 *
 * Unregistration happens automatically when the composable leaves the composition.
 */
@Composable
fun Modifier.tutorialTarget(key: String, registry: TutorialTargetRegistry): Modifier {
    DisposableEffect(key) { onDispose { registry.unregister(key) } }
    return this.onGloballyPositioned { coordinates ->
        try {
            val bounds = coordinates.boundsInWindow()
            if (bounds.width > 0f && bounds.height > 0f) {
                registry.register(key, bounds)
            }
        } catch (_: Exception) {
            // Layout may not be attached yet; ignore.
        }
    }
}
