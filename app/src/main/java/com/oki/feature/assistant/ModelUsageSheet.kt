package com.oki.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.oki.core.ai.*
import com.oki.core.security.Provider
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelUsageSheet(
    usage: List<ModelUsage>,
    summary: String,
    compacting: Boolean,
    dismiss: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    var showMemory by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 22.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Models & usage", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("Groq first · Gemini fallback", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Usage updates after each response. Limits shown are free-tier baselines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(CHAT_MODELS, key = { _, model -> model.label }) { index, identity ->
                val entry = usage.firstOrNull { it.identity == identity }
                var showDetails by rememberSaveable(identity.label) { mutableStateOf(false) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${index + 1}. ${identity.label}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        DocumentedLimitCard(identity)
                        Text(
                            if (entry == null) "No usage yet"
                            else "Used · ${entry.totalTokens} tokens · ${entry.requests} requests",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (entry != null) {
                            QuotaMeter("Tokens / minute", entry.tokens, now)
                            QuotaMeter("Requests / day", entry.requestQuota, now)
                            val retry = entry.retryAt
                            if (entry.status == 429)
                                Text(
                                    if (retry != null && retry > now)
                                        "Rate limited · retry in ${countdown(retry - now)}"
                                    else
                                        "Last request rate limited · ${if (retry != null) "retry window elapsed" else "reset time not supplied"}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            if (entry.status in listOf(401, 403))
                                Text(
                                    "Key rejected or access denied · check Settings",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            if (entry.status == 404)
                                Text(
                                    "Model unavailable for this key",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                        }
                        if (identity.provider == Provider.GEMINI) {
                            Text(
                                "Remaining quota unavailable · check Usage",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val next =
                                Instant.ofEpochMilli(now)
                                    .atZone(ZoneId.of("America/Los_Angeles"))
                                    .toLocalDate()
                                    .plusDays(1)
                                    .atStartOfDay(ZoneId.of("America/Los_Angeles"))
                                    .toInstant()
                                    .toEpochMilli()
                            Text(
                                "Daily reset in ${countdown(next - now)} · midnight Pacific",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { showDetails = !showDetails }) {
                            Text(if (showDetails) "Hide details" else "Details")
                        }
                        if (showDetails) {
                            Text(
                                "Scans: Qwen → Gemini Flash → Flash-Lite. Used totals are cumulative on this device since key setup, not just the last message. They include prompts, tool definitions, replies, memory, scans and diagnostics; usage elsewhere may differ.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (entry != null) {
                                Text(
                                    "Input ${entry.inputTokens} · Output ${entry.outputTokens}\n${entry.measuredResponses} responses reported usage; totals may include thinking tokens.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    "Last response ${DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.updatedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            documentedFreeTierLimits(identity)?.let {
                                Text(
                                    "Docs checked ${it.checkedOn}. Account limits may differ. Remaining balances use API snapshots, not published caps.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showMemory = !showMemory }) {
                    Text(if (showMemory) "Hide memory" else "Conversation memory")
                }
                if (showMemory) {
                    Text(
                        if (compacting) "Updating compact memory…"
                        else if (summary.isBlank())
                            "Memory appears after a reply. Recent messages are used while it updates."
                        else summary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "The next request uses this summary and recent messages. Summarizing also consumes tokens and can omit older details; full chat history remains on this device.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentedLimitCard(identity: ModelIdentity) {
    val limits = documentedFreeTierLimits(identity) ?: return
    val uri = LocalUriHandler.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Free tier", style = MaterialTheme.typography.labelLarge)
            if (limits.tokensPerMinute != null) {
                listOf(
                        "Tokens" to
                            "${numbers.format(limits.tokensPerMinute)} / min · ${numbers.format(limits.tokensPerDay)} / day",
                        "Requests" to
                            "${numbers.format(limits.requestsPerMinute)} / min · ${numbers.format(limits.requestsPerDay)} / day",
                    )
                    .forEach { (label, value) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                value,
                                modifier = Modifier.weight(2f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
            } else {
                Text(
                    "Limits vary by project · see Usage",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        uri.openUri(
                            if (identity.provider == Provider.GROQ)
                                "https://console.groq.com/settings/limits"
                            else "https://aistudio.google.com/usage?tab=rate-limit"
                        )
                    },
                ) {
                    Text("Usage")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { uri.openUri(limits.sourceUrl) },
                ) {
                    Text("Docs")
                }
            }
        }
    }
}

@Composable
private fun QuotaMeter(label: String, window: QuotaWindow?, now: Long) {
    if (window == null) {
        Text("$label · remaining not reported", style = MaterialTheme.typography.labelSmall)
        return
    }
    val expired = window.resetAt?.let { it <= now } == true
    val remaining = window.remaining.toFloat() / window.limit
    val color =
        if (expired) MaterialTheme.colorScheme.outline
        else
            when {
                remaining <= .15f -> Color(0xFFE45756)
                remaining <= .4f -> Color(0xFFCC8A16)
                else -> Color(0xFF2C9D78)
            }
    Text(
        if (expired) "$label · window elapsed; awaiting next response"
        else "$label · ${window.remaining} / ${window.limit} left",
        style = MaterialTheme.typography.labelSmall,
    )
    LinearProgressIndicator(
        progress = { remaining.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(7.dp),
        color = color,
        trackColor = color.copy(alpha = .14f),
    )
    if (!expired)
        window.resetAt?.let {
            Text("Resets in ${countdown(it - now)}", style = MaterialTheme.typography.labelSmall)
        }
}

internal fun countdown(milliseconds: Long): String {
    val seconds = ((milliseconds.coerceAtLeast(0) + 999) / 1000)
    return if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m ${seconds % 60}s"
    else "${seconds / 60}m ${seconds % 60}s"
}
