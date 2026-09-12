package com.oki.feature.assistant

internal fun chatAge(timestamp: Long?, now: Long): String? {
    if (timestamp == null) return null
    val elapsed = (now - timestamp).coerceAtLeast(0)
    val (count, unit) =
        when {
            elapsed < 60_000 -> return "Just now"
            elapsed < 3_600_000 -> elapsed / 60_000 to "minute"
            elapsed < 86_400_000 -> elapsed / 3_600_000 to "hour"
            else -> elapsed / 86_400_000 to "day"
        }
    return "$count $unit${if (count == 1L) "" else "s"} ago"
}

/** One sheet timer, waking at the next visible age boundary, at most once a minute otherwise. */
internal fun nextChatAgeUpdate(timestamps: List<Long>, now: Long): Long =
    timestamps.minOfOrNull { timestamp ->
        val elapsed = (now - timestamp).coerceAtLeast(0)
        val unit =
            when {
                elapsed < 3_600_000 -> 60_000L
                elapsed < 86_400_000 -> 3_600_000L
                else -> 86_400_000L
            }
        (unit - elapsed % unit).coerceIn(1, 60_000)
    } ?: 60_000L
