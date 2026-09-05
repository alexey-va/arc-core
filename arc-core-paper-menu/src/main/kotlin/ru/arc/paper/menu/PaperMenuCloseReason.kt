package ru.arc.paper.menu

enum class PaperMenuCloseReason(val wire: String) {
    USER("user"),
    REPLACE("replace"),
    QUIT("quit"),
    SHUTDOWN("shutdown"),
    CENSORED("censored"),
}
