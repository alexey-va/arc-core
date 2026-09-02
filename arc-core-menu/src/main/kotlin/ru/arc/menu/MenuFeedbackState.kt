package ru.arc.menu

@ConsistentCopyVisibility
data class MenuFeedbackToken internal constructor(
    val sequence: Long,
    val element: MenuElementId,
    val expiresAtTick: Long,
)

class MenuFeedbackState {
    private var sequence = 0L

    var active: MenuFeedbackToken? = null
        private set

    fun show(element: MenuElementId, expiresAtTick: Long): MenuFeedbackToken {
        require(expiresAtTick >= 0) { "Feedback expiry tick must be non-negative" }
        return MenuFeedbackToken(++sequence, element, expiresAtTick).also { active = it }
    }

    fun expire(token: MenuFeedbackToken, currentTick: Long): MenuElementId? {
        if (active != token || currentTick < token.expiresAtTick) return null
        active = null
        return token.element
    }

    fun invalidateForRender() {
        active = null
        sequence++
    }

    fun invalidateForGeneration(generation: Long) {
        require(generation >= 0) { "Menu generation must be non-negative" }
        active = null
        sequence++
    }
}
