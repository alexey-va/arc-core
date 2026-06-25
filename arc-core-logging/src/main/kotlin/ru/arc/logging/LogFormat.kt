package ru.arc.logging

/** `{}` formatting and MiniMessage tag stripping for console / ops buffers. */
object LogFormat {
    fun escapeMiniMessage(s: String): String = s.replace("\\", "\\\\").replace("<", "\\<")

    fun plainForBuffer(text: String): String = text.replace(Regex("</?[^>]+>"), "")

    fun format(
        template: String,
        vararg args: Any?,
    ): String {
        val nonThrow = ArrayList<Any?>(args.size)
        val throws = ArrayList<Throwable>()
        for (a in args) {
            if (a is Throwable) throws += a else nonThrow += a
        }

        val main = substitute(template, nonThrow.map { render(it) }.toTypedArray())
        if (throws.isEmpty()) return main

        val sb = StringBuilder(main.length + 256)
        sb
            .append(main)
            .append(System.lineSeparator())
            .append("--- exceptions ---")
            .append(System.lineSeparator())
        throws.forEachIndexed { i, t ->
            sb
                .append('#')
                .append(i + 1)
                .append(' ')
                .append(t::class.java.name)
                .append(": ")
                .append(t.message ?: "")
                .append(System.lineSeparator())
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            sb.append(sw.toString())
        }
        return sb.toString()
    }

    private fun substitute(
        template: String,
        args: Array<String>,
    ): String {
        val sb = StringBuilder(template.length + 64)
        var i = 0
        var ai = 0
        while (i < template.length) {
            if (i + 1 < template.length && template[i] == '{' && template[i + 1] == '}') {
                sb.append(if (ai < args.size) args[ai++] else "{}")
                i += 2
            } else {
                sb.append(template[i++])
            }
        }
        return sb.toString()
    }

    private fun render(v: Any?): String =
        when (v) {
            null -> "null"
            is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
            is ByteArray -> v.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> v.joinToString(prefix = "[", postfix = "]")
            is IntArray -> v.joinToString(prefix = "[", postfix = "]")
            is LongArray -> v.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> v.joinToString(prefix = "[", postfix = "]")
            is CharArray -> v.joinToString(prefix = "[", postfix = "]")
            is Array<*> -> v.contentDeepToString()
            is Collection<*> -> v.joinToString(prefix = "[", postfix = "]") { render(it) }
            is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { "${render(it.key)}=${render(it.value)}" }
            else -> if (v.javaClass.isArray) reflectArray(v) else v.toString()
        }

    private fun reflectArray(arr: Any): String {
        val n = java.lang.reflect.Array.getLength(arr)
        return buildString {
            append('[')
            for (i in 0 until n) {
                if (i > 0) append(", ")
                append(render(java.lang.reflect.Array.get(arr, i)))
            }
            append(']')
        }
    }

    fun extractThrowable(vararg args: Any?): Throwable? = args.firstOrNull { it is Throwable } as? Throwable
}
