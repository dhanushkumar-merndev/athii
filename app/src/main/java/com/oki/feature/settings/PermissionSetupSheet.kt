package com.oki.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.oki.AppContainer

/** One Android permission Athii needs, plus how to ask for it. */
private data class SetupStep(
    val title: String,
    val why: String,
    val icon: ImageVector,
    val granted: Boolean,
    val required: Boolean,
    val grant: () -> Unit,
)

/**
 * First-run sheet that collects every permission reminders depend on, so the user never has to hunt
 * through Settings. It appears only while something essential is missing and has not been
 * dismissed.
 */
@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSetupSheet(c: AppContainer, dismiss: () -> Unit) {
    val context = LocalContext.current
    val notificationManager = remember { context.getSystemService(NotificationManager::class.java) }
    var notifications by remember { mutableStateOf(c.publisher.canNotify()) }
    var exact by remember { mutableStateOf(c.reminders.hasExactAccess()) }
    var battery by remember { mutableStateOf(c.publisher.ignoresBatteryOptimizations()) }
    var fullScreen by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent())
    }
    fun refresh() {
        notifications = c.publisher.canNotify()
        exact = c.reminders.hasExactAccess()
        battery = c.publisher.ignoresBatteryOptimizations()
        fullScreen = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
    }
    val notificationRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    // Returning from a system screen is the only signal that a choice was made.
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose {}
    }
    val steps = buildList {
        add(
            SetupStep(
                "Notifications",
                "Without this Athii cannot show any reminder at all.",
                Icons.Outlined.Notifications,
                notifications,
                required = true,
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !notifications)
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.openAppNotificationSettings()
            }
        )
        add(
            SetupStep(
                "On-time alerts",
                "Android calls this Alarms & reminders. Without it your alerts can arrive late.",
                Icons.Outlined.Schedule,
                exact,
                required = true,
            ) {
                if (Build.VERSION.SDK_INT >= 31)
                    context.safeStart(
                        Intent(
                            AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
            }
        )
        add(
            SetupStep(
                "Keep working when closed",
                "Lets Athii wake up after you close it. Also turn on Autostart in your phone's battery settings.",
                Icons.Outlined.BatteryFull,
                battery,
                required = true,
            ) {
                context.safeStart(
                    Intent(
                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        .takeIf { !battery }
                        ?: Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        )
        if (Build.VERSION.SDK_INT >= 34)
            add(
                SetupStep(
                    "Alarm on lock screen",
                    "Lets an alarm open full screen instead of only showing a notification.",
                    Icons.Outlined.Lock,
                    fullScreen,
                    required = false,
                ) {
                    context.safeStart(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            )
    }
    val outstanding = steps.count { it.required && !it.granted }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Set up your reminders", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (outstanding == 0)
                    "Everything is ready. You can change any of this later in Settings."
                else
                    "Athii needs these Android permissions to alert you on time. You can change them later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            steps.forEach { step ->
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (step.granted) Icons.Outlined.CheckCircle else step.icon,
                            null,
                            Modifier.size(24.dp),
                            tint =
                                if (step.granted) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.title + if (!step.required) " · optional" else "",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (step.granted) "Allowed" else step.why,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!step.granted) Button(onClick = step.grant) { Text("Allow") }
                    }
                }
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (outstanding == 0) "Done" else "Continue anyway")
            }
        }
    }
}

private fun Context.safeStart(intent: Intent) {
    runCatching { startActivity(intent) }
}

private fun Context.openAppNotificationSettings() {
    safeStart(
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
    )
}
