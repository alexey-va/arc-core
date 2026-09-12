package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

@JvmInline
value class PaperDialogActionId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("[a-z0-9][a-z0-9_]{0,47}")

        fun of(value: String): PaperDialogActionId {
            require(pattern.matches(value)) {
                "Paper dialog action id must match ${pattern.pattern}: '$value'"
            }
            return PaperDialogActionId(value)
        }
    }
}

@JvmInline
value class PaperDialogInputId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("[a-z0-9][a-z0-9_]{0,31}")

        fun of(value: String): PaperDialogInputId {
            require(pattern.matches(value)) {
                "Paper dialog input id must match ${pattern.pattern}: '$value'"
            }
            return PaperDialogInputId(value)
        }
    }
}

data class PaperDialogBody(
    val text: Component,
    val width: Int = 400,
) {
    init {
        require(width in 1..1024) { "Paper dialog body width must be in 1..1024" }
    }
}

data class PaperDialogTextInput(
    val id: PaperDialogInputId,
    val label: Component,
    val initial: String = "",
    val width: Int = 300,
    val maxLength: Int = 32,
    val labelVisible: Boolean = true,
) {
    init {
        require(width in 1..1024) { "Paper dialog input width must be in 1..1024" }
        require(maxLength in 1..1024) { "Paper dialog input maxLength must be in 1..1024" }
        require(initial.length <= maxLength) { "Paper dialog input initial value exceeds maxLength" }
    }
}

data class PaperDialogNumberRangeInput(
    val id: PaperDialogInputId,
    val label: Component,
    val start: Float,
    val end: Float,
    val initial: Float? = null,
    val step: Float? = null,
    val width: Int = 300,
    val labelFormat: String = "options.generic_value",
) {
    init {
        require(width in 1..1024) { "Paper dialog number input width must be in 1..1024" }
        require(start.isFinite() && end.isFinite() && start <= end) {
            "Paper dialog number input range must be finite and ordered"
        }
        require(initial == null || initial.isFinite() && initial in start..end) {
            "Paper dialog number input initial value must be finite and inside its range"
        }
        require(step == null || step.isFinite() && step > 0f) {
            "Paper dialog number input step must be finite and positive"
        }
        require(labelFormat.isNotBlank()) { "Paper dialog number input labelFormat must not be blank" }
    }
}

fun interface PaperDialogClickHandler {
    fun handle(context: PaperDialogClickContext)
}

class PaperDialogClickContext internal constructor(
    val player: Player,
    private val textInputs: (PaperDialogInputId) -> String?,
    private val numberInputs: (PaperDialogInputId) -> Float?,
) {
    fun text(id: PaperDialogInputId): String? = textInputs(id)

    fun number(id: PaperDialogInputId): Float? = numberInputs(id)
}

data class PaperDialogButton(
    val id: PaperDialogActionId,
    val label: Component,
    val tooltip: Component = Component.empty(),
    val width: Int = 150,
    /** Close the native screen before dispatching actions that leave the dialog flow. */
    val closeDialogBeforeAction: Boolean = false,
    val onClick: PaperDialogClickHandler,
) {
    init {
        require(width in 1..1024) { "Paper dialog button width must be in 1..1024" }
    }
}

data class PaperDialogScreen(
    val title: Component,
    val externalTitle: Component = title,
    val body: List<PaperDialogBody> = emptyList(),
    val inputs: List<PaperDialogTextInput> = emptyList(),
    val numberInputs: List<PaperDialogNumberRangeInput> = emptyList(),
    val buttons: List<PaperDialogButton>,
    val exitButton: PaperDialogButton? = null,
    val columns: Int = 1,
    val canCloseWithEscape: Boolean = true,
    val pause: Boolean = false,
    /** Stable telemetry namespace for this screen; defaults to its action namespace. */
    val id: String = "dialog",
) {
    init {
        require(columns in 1..5) { "Paper dialog columns must be in 1..5" }
        val actionIds = (buttons + listOfNotNull(exitButton)).map { it.id }
        require(actionIds.size == actionIds.distinct().size) { "Paper dialog action ids must be unique" }
        val inputIds = inputs.map { it.id } + numberInputs.map { it.id }
        require(inputIds.size == inputIds.distinct().size) { "Paper dialog input ids must be unique" }
        require(id.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}"))) { "Paper dialog id must be lowercase" }
    }
}
