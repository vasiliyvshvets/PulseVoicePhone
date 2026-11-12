package com.pulsevoicephone

object DiagnosticBuffer {
    const val LIMIT = 100
    fun append(previous: String, event: String): String =
        (previous.lineSequence().filter { it.isNotBlank() }.toList() +
            event.replace('\n', ' ').replace('\r', ' ').take(1200))
            .takeLast(LIMIT).joinToString("\n")
}
