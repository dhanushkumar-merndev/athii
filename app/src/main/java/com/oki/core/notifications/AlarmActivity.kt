package com.oki.core.notifications

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.oki.OkiApplication
import com.oki.core.storage.*
import com.oki.core.ui.OkiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A lock-screen control surface. The Android notification system owns the alarm sound. */
class AlarmActivity : ComponentActivity() {
    private var taskId by mutableStateOf<String?>(null)
    private val c
        get() = (application as OkiApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        taskId = intent.getStringExtra("task_id")
        setContent {
            var task by remember(taskId) { mutableStateOf<Task?>(null) }
            var error by remember(taskId) { mutableStateOf<String?>(null) }
            var busy by remember(taskId) { mutableStateOf(false) }
            LaunchedEffect(taskId) {
                val id =
                    taskId
                        ?: run {
                            finish()
                            return@LaunchedEffect
                        }
                while (true) {
                    val current = c.tasks.get(id)
                    if (
                        current == null ||
                            current.isCompleted ||
                            !current.reminderEnabled ||
                            current.alertMode != TaskAlertMode.ALARM ||
                            !c.publisher.isAlarmActive(id)
                    ) {
                        c.publisher.dismissStart(id)
                        finish()
                        return@LaunchedEffect
                    }
                    task = current
                    delay(500)
                }
            }
            OkiTheme(Appearance.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Alarm,
                            null,
                            Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text("Time to start", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            task?.title.orEmpty(),
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                        )
                        task?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                Instant.ofEpochMilli(it.dueAt)
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(40.dp))
                        Button(
                            onClick = {
                                val id = taskId ?: return@Button
                                busy = true
                                lifecycleScope.launch {
                                    try {
                                        c.tasks.complete(id, true)
                                        finish()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Could not mark this task complete."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Mark complete")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                val id = taskId ?: return@OutlinedButton
                                busy = true
                                lifecycleScope.launch {
                                    try {
                                        c.tasks.snooze(id)
                                        finish()
                                    } catch (e: Exception) {
                                        error =
                                            e.message
                                                ?: "Could not snooze. Check alarm access in Settings."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Snooze 5 minutes")
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                taskId?.let(c.publisher::dismissStart)
                                finish()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Stop alarm")
                        }
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Athii · Alarm stops automatically after 5 minutes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        taskId = intent.getStringExtra("task_id")
    }
}
