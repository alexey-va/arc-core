package ru.arc.ai.llm

enum class ModerationOutcome {
    OK,
    BAD,
    UNKNOWN,
}

data class ModerResult(
    val outcome: ModerationOutcome,
    val comment: String,
)
