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

    /** Read-only projection for a prepared footer, including an async return to an ancestor. */
    fun hasPreviousAfterShow(owner: String, key: String): Boolean {
        if (limit < 2) return false
        val ancestor = visits.indexOfLast { it["owner"] == owner && it["key"] == key }
        return if (ancestor >= 0) ancestor > 0 else visits.size > if (navigates(owner, key)) 0 else 1
    }

    private fun navigates(owner: String, key: String): Boolean =
        callbackDepth > 0 && !restoring && current != null &&
            (current?.get("owner") != owner || current?.get("key") != key)

    fun show(
        owner: String,
        key: String,
        deactivate: Runnable,
        dismiss: Runnable,
        resume: Runnable,
        allowImplicitRoot: Boolean = true,
        reservation: String? = null,
    ): Boolean {
        if (!canShow(owner, allowImplicitRoot, reservation)) return false
        val previous = current
        val navigate = navigates(owner, key)
        previous?.run("deactivate")
        // Returning to an existing screen is a refresh of that visit, not a
        // new child. The consumer has already built its new domain generation,
        // so same-owner entries use refresh cleanup rather than onDismiss.
        val ancestor = visits.indexOfLast { it["owner"] == owner && it["key"] == key }
        if (ancestor >= 0 && ancestor < visits.lastIndex) {
            val retired = visits.subList(ancestor, visits.size).toList()
            visits.subList(ancestor, visits.size).clear()
            state["clearing"] = true
            try {
                retired.asReversed().forEach {
                    if (it["owner"] != owner) it.run("dismiss")
                    else if (it !== previous) it.run("deactivate")
                }
            } finally { state["clearing"] = false }
        } else if (previous != null && !navigate) {
            // A refresh must not invalidate the newly established generation.
            visits.removeAt(visits.lastIndex)
        }
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
        state.remove("rootReservation")
        return true
    }

    fun canShow(owner: String, allowImplicitRoot: Boolean, reservation: String? = null): Boolean {
        if (clearing) return false
        if (reservation != null) return isPendingRoot(owner, reservation)
        val rootOwner = state["rootOwner"]
        if (current == null && rootOwner == owner && state["rootReservation"] != null) return false
        if (callbackDepth > 0 || restoring || this.owner == owner) return true
        if (current != null) return false
        return if (allowImplicitRoot) {
            state["acceptRoot"] != false && (rootOwner == null || rootOwner == owner)
        } else {
            state["acceptRoot"] == true && rootOwner == owner
        }
    }

    fun beginFlow(owner: String? = null, reservation: String? = null) {
        if (callbackDepth > 0 || restoring || clearing) return
        clear()
        state["acceptRoot"] = true
        if (owner != null) state["rootOwner"] = owner
        if (reservation != null) state["rootReservation"] = reservation
    }

    fun isPendingRoot(owner: String, reservation: String): Boolean =
        current == null && state["acceptRoot"] == true &&
            state["rootOwner"] == owner && state["rootReservation"] == reservation

    fun dispatch(action: () -> Unit) {
        val previous = callbackDepth
        state["callbackDepth"] = previous + 1
        try { action() } finally { state["callbackDepth"] = previous }
    }

    /** Returns true when an ancestor resume was invoked. It may present its screen later. */
    fun back(resume: (Runnable) -> Unit = Runnable::run): Boolean {
        val removed = if (visits.isEmpty()) null else visits.removeAt(visits.lastIndex)
        removed?.run("dismiss")
        val previous = current ?: return false
        val wasRestoring = restoring
        state["restoring"] = true
        try { resume(previous.getValue("resume") as Runnable) } finally { state["restoring"] = wasRestoring }
        return true
    }

    fun clear() {
        val removed = visits.toList()
        visits.clear()
        state["acceptRoot"] = false
        state.remove("rootOwner")
        state.remove("rootReservation")
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
