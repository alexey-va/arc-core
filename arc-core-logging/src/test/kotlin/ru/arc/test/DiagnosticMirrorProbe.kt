package ru.arc.test

import ru.arc.logging.ArcLogging

/** A real first-party caller outside the logging facade's skipped stack frames. */
object DiagnosticMirrorProbe {
    fun emit(message: String) = ArcLogging.info(message)
}
