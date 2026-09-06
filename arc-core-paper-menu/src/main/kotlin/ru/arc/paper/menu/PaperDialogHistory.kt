package ru.arc.paper.menu

/**
 * Actual visits shared through player metadata across shaded core copies.
 * Only JDK collections, scalars and Runnable cross the classloader boundary;
 * each owner retains its typed screen and callbacks inside those Runnables.
 * All access is on the primary server thread.
 */
internal class PaperDialogHistory(private val state: MutableMap<String, Any>, private val limit: Int = 64) {
    @Suppress("UNCHECKED_CAST")
    private val visits: MutableList<MutableMap<String, Any>>
        get() = state.getOrPut("visits") { java.util.ArrayList<MutableMap<String, Any>>() } as MutableList<MutableMap<String, Any>>
    private val current get() = visits.lastOrNull()
    val owner: String? get() = current?.get("owner") as? String
    val entryOwner: String? get() = owner ?: state["rootOwner"] as? String
    private val callbackDepth get() = state["callbackDepth"] as? Int ?: 0
    private val restoring get() = state["restoring"] == true
    private val clearing get() = state["clearing"] == true

    fun show(owner: String, key: String, deactivate: Runnable, dismiss: Runnable, resume: Runnable): Boolean {
        if (clearing) return false
        if (callbackDepth == 0 && !restoring && this.owner != owner &&
            !(current == null && state["acceptRoot"] != false &&
                (state["rootOwner"] == null || state["rootOwner"] == owner))) return false
        val previous = current
        val navigate = callbackDepth > 0 && !restoring &&
            previous != null && (previous["owner"] != owner || previous["key"] != key)
        previous?.run("deactivate")
        // A refresh has already established the new domain generation. Calling
        // the old onDismiss here would invalidate that generation in consumers.
        if (previous != null && !navigate) visits.removeAt(visits.lastIndex)
        visits.add(java.util.HashMap<String, Any>().apply {
            put("owner", owner)
            put("key", key)
            put("deactivate", deactivate)
            put("dismiss", dismiss)
            put("resume", resume)
        })
        if (visits.size > limit) visits.removeAt(0).run("deactivate")
        state["acceptRoot"] = false
        state.remove("rootOwner")
        return true
    }

    fun beginFlow(owner: String? = null) {
        if (callbackDepth > 0 || restoring || clearing) return
        clear()
        state["acceptRoot"] = true
        if (owner != null) state["rootOwner"] = owner
    }

    fun dispatch(action: () -> Unit) {
        val previous = callbackDepth
        state["callbackDepth"] = previous + 1
        try { action() } finally { state["callbackDepth"] = previous }
    }

    /** Returns true when a previous owner was restored, false when the flow ended. */
    fun back(): Boolean {
        val removed = if (visits.isEmpty()) null else visits.removeAt(visits.lastIndex)
        removed?.run("dismiss")
        val previous = current ?: return false
        val wasRestoring = restoring
        state["restoring"] = true
        try { previous.run("resume") } finally { state["restoring"] = wasRestoring }
        return true
    }

    fun clear() {
        val removed = visits.toList()
        visits.clear()
        state["acceptRoot"] = false
        state.remove("rootOwner")
        state["clearing"] = true
        try { removed.asReversed().forEach { it.run("dismiss") } }
        finally { state["clearing"] = false }
    }

    /** An inactive owner can unload without closing the visible foreign screen. */
    fun removeOwner(owner: String) {
        val removed = visits.filter { it["owner"] == owner }
        visits.removeAll(removed.toSet())
        removed.forEach { it.run("dismiss") }
    }

    private fun Map<String, Any>.run(action: String) { (getValue(action) as Runnable).run() }
}
