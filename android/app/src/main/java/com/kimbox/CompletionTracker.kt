package com.kimbox

/** Arm only on a main turn; background work, old history and cancellations aren't completions. */
class CompletionTracker {
    private val active = LinkedHashMap<String, Boolean>()
    private val revisions = HashMap<String, Long>()
    private var revision = 0L

    @Synchronized
    fun observe(id: String, mainActive: Boolean, reason: String?): String? {
        if (!validSessionId(id)) return null
        revisions[id] = ++revision
        val wasActive = active.put(id, mainActive) == true
        if (active.size > 512) {
            val oldest = active.keys.first()
            active.remove(oldest)
            revisions.remove(oldest)
        }
        return if (wasActive && !mainActive && reason in setOf("completed", "failed")) reason else null
    }

    @Synchronized
    fun revision(): Long = revision

    /** Don't let a slow REST snapshot overwrite newer WebSocket events. */
    @Synchronized
    fun reconcile(id: String, mainActive: Boolean, reason: String?, beforeRequest: Long): String? {
        if ((revisions[id] ?: 0L) > beforeRequest) return null
        return observe(id, mainActive, reason)
    }

    companion object {
        fun validSessionId(id: String) = id.length in 1..160 && id.matches(Regex("[A-Za-z0-9_-]+")) && id != "__global__"
    }
}
