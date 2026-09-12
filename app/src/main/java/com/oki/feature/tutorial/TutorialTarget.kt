package com.oki.feature.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
    private val owners = mutableMapOf<String, Any>()

    fun register(key: String, bounds: Rect, owner: Any) {
        owners[key] = owner
        targets[key] = bounds
    }

    /**
     * Only the composable that last registered [key] may remove it. When the first doctor card is
     * replaced, the new card registers before the old one is disposed; an unconditional remove
     * would erase the new card's bounds and the tour would treat the target as missing.
     */
    fun unregister(key: String, owner: Any) {
        if (owners[key] !== owner) return
        owners.remove(key)
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
    val owner = remember { Any() }
    DisposableEffect(key) { onDispose { registry.unregister(key, owner) } }
    return this.onGloballyPositioned { coordinates ->
        try {
            val bounds = coordinates.boundsInWindow()
            if (bounds.width > 0f && bounds.height > 0f) {
                registry.register(key, bounds, owner)
            }
        } catch (_: Exception) {
            // Layout may not be attached yet; ignore.
        }
    }
}
