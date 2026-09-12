package com.oki.core.security

/** Ephemeral, single-use approval. Never persisted or reused for another deletion. */
internal class DeletionApproval {
    private var generation = 0L
    private var pending: Pair<Long, () -> Unit>? = null

    fun begin(action: () -> Unit): Long? {
        if (pending != null) return null
        val id = ++generation
        pending = id to action
        return id
    }

    fun isPending(id: Long) = pending?.first == id

    fun approve(id: Long) {
        val action = pending?.takeIf { it.first == id }?.second ?: return
        pending = null
        action()
    }

    fun cancel() {
        pending = null
    }
}
