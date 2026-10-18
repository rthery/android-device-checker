package io.github.rthery.devicechecker.pack

/**
 * Read-only commands a pack may run through Shizuku, as a positive list: one allowed source
 * (getprop, cat under /sys or /proc, dumpsys with safe flags, settings get/list, known cmd read verbs)
 * optionally piped into text filters without file arguments. No redirection, substitution, chaining or variables.
 */
object CommandAllowlist {

    private const val MAX_LENGTH = 500
    private val PROP_NAME = Regex("[A-Za-z0-9._-]+")
    private val SERVICE_NAME = Regex("[A-Za-z0-9._/-]+")
    private val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
    private val DUMPSYS_FLAGS = setOf("-a", "-l", "--charged", "--brief", "--short")
    private val CMD_VERBS = mapOf(
        "package" to setOf("list"),
        "overlay" to setOf("list"),
        "wifi" to setOf("get-country-code", "list-networks", "list-scan-results"),
        "settings" to setOf("get", "list"),
        "location" to setOf("is-location-enabled"),
    )
    private val CMD_ARGUMENT = Regex("-{0,2}[A-Za-z0-9._:]+")

    /** Options a filter accepts, those that take a value, and how many positional arguments it may have. */
    private class FilterSpec(val flags: Set<Char>, val valued: Set<Char>, val maxPositional: Int)

    private val FILTERS = mapOf(
        "grep" to FilterSpec(flags = "EFGiIvwxcnohqsH".toSet(), valued = setOf('m', 'A', 'B', 'C'), maxPositional = 1),
        "head" to FilterSpec(flags = emptySet(), valued = setOf('n', 'c'), maxPositional = 0),
        "tail" to FilterSpec(flags = emptySet(), valued = setOf('n', 'c'), maxPositional = 0),
        "wc" to FilterSpec(flags = "lwcm".toSet(), valued = emptySet(), maxPositional = 0),
        "cut" to FilterSpec(flags = "s".toSet(), valued = setOf('d', 'f', 'c', 'b'), maxPositional = 0),
        "tr" to FilterSpec(flags = "dsc".toSet(), valued = emptySet(), maxPositional = 2),
        "sort" to FilterSpec(flags = "nru".toSet(), valued = emptySet(), maxPositional = 0),
    )
    private val UNQUOTED_FORBIDDEN = setOf(';', '&', '>', '<', '`', '$', '(', ')', '{', '}', '\\', '!', '~', '#', '*', '?', '[', ']', '\n', '\r')
    private val KERNEL_PATH = Regex("/(sys|proc)/[A-Za-z0-9._/,:@+-]+")
    private val PROC_LINKS = setOf("root", "cwd", "exe", "fd", "map_files")
    private val DOUBLE_QUOTED_FORBIDDEN = setOf('$', '`', '\\', '\n', '\r')

    /** Null when allowed, otherwise why not. */
    fun check(command: String): String? {
        if (command.isBlank()) return "Empty command"
        if (command.length > MAX_LENGTH) return "Longer than $MAX_LENGTH characters"
        val stages = try {
            tokenize(command)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        if (stages.any { it.isEmpty() }) return "Empty pipeline stage"
        checkSource(stages.first())?.let { return it }
        stages.drop(1).forEach { stage -> checkFilter(stage)?.let { return it } }
        return null
    }

    fun isAllowed(command: String): Boolean = check(command) == null

    private fun checkSource(tokens: List<String>): String? {
        val args = tokens.drop(1)
        return when (tokens.first()) {
            "getprop" -> when {
                args.size > 1 -> "getprop takes at most one property name"
                args.any { !PROP_NAME.matches(it) } -> "Bad property name"
                else -> null
            }
            "cat" -> when {
                args.isEmpty() -> "cat needs a path"
                args.any { !isSafeKernelPath(it) } -> "cat only reads under /sys or /proc, not through process links"
                else -> null
            }
            "dumpsys" -> when {
                args.isEmpty() -> "dumpsys needs a service name"
                !SERVICE_NAME.matches(args.first()) -> "Bad service name"
                else -> args.drop(1).firstOrNull { it !in DUMPSYS_FLAGS }
                    ?.let { "dumpsys only takes a service name and ${DUMPSYS_FLAGS.joinToString()}, not \"$it\"" }
            }
            "settings" -> when {
                args.firstOrNull() == "get" && args.size == 3 && args[1] in SETTINGS_NAMESPACES && PROP_NAME.matches(args[2]) -> null
                args.firstOrNull() == "list" && args.size == 2 && args[1] in SETTINGS_NAMESPACES -> null
                else -> "Only settings get <namespace> <key> or settings list <namespace>"
            }
            "cmd" -> {
                val verbs = args.firstOrNull()?.let(CMD_VERBS::get)
                when {
                    verbs == null -> "cmd only allows ${CMD_VERBS.keys.joinToString()}"
                    args.getOrNull(1) !in verbs -> "cmd ${args.first()} only allows ${verbs.joinToString()}"
                    args.drop(2).any { !CMD_ARGUMENT.matches(it) } -> "Bad cmd argument"
                    args.first() == "settings" && args.getOrNull(2) !in SETTINGS_NAMESPACES -> "cmd settings needs a namespace"
                    else -> null
                }
            }
            else -> "\"${tokens.first()}\" is not an allowed command"
        }
    }

    private fun checkFilter(tokens: List<String>): String? {
        val name = tokens.first()
        val spec = FILTERS[name] ?: return "\"$name\" is not an allowed filter"
        var positional = 0
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.startsWith("--") -> return "$name long options are not allowed"
                token.startsWith("-") && token.length > 1 -> {
                    val cluster = token.drop(1)
                    for ((position, option) in cluster.withIndex()) {
                        when {
                            option in spec.flags -> Unit
                            option in spec.valued -> {
                                if (position == cluster.lastIndex) index++
                                break
                            }
                            option.isDigit() && (name == "head" || name == "tail") -> break
                            else -> return "$name option -$option is not allowed"
                        }
                    }
                }
                else -> if (++positional > spec.maxPositional) return "$name cannot take \"$token\" (no file arguments)"
            }
            index++
        }
        return null
    }

    /**
     * A /sys or /proc path that cannot leave them: no dot segments, nothing under /proc/self, /proc/thread-self
     * or /proc/<pid>, and no root, cwd, exe, fd or map_files links (they point at arbitrary files).
     */
    fun isSafeKernelPath(path: String): Boolean {
        if (!KERNEL_PATH.matches(path)) return false
        val segments = path.split('/').drop(1)
        if (segments.drop(1).any { it.isEmpty() || it == "." || it == ".." || it in PROC_LINKS }) return false
        val top = segments.getOrNull(1) ?: return false
        return segments.first() == "sys" || !(top == "self" || top == "thread-self" || top.all(Char::isDigit))
    }

    /** Splits into pipeline stages of tokens, honoring quotes; throws on anything outside the allowed grammar. */
    internal fun tokenize(command: String): List<List<String>> {
        val stages = mutableListOf<MutableList<String>>(mutableListOf())
        val current = StringBuilder()
        var inToken = false
        var index = 0

        fun endToken() {
            if (inToken) stages.last().add(current.toString())
            current.clear()
            inToken = false
        }

        while (index < command.length) {
            val char = command[index]
            when {
                char == '\'' -> {
                    val end = command.indexOf('\'', index + 1)
                    require(end >= 0) { "Unclosed single quote" }
                    val quoted = command.substring(index + 1, end)
                    require(quoted.none { it == '\n' || it == '\r' }) { "Line break in quotes" }
                    current.append(quoted)
                    inToken = true
                    index = end
                }
                char == '"' -> {
                    val end = command.indexOf('"', index + 1)
                    require(end >= 0) { "Unclosed double quote" }
                    val quoted = command.substring(index + 1, end)
                    require(quoted.none { it in DOUBLE_QUOTED_FORBIDDEN }) { "\$, backtick or backslash inside double quotes" }
                    current.append(quoted)
                    inToken = true
                    index = end
                }
                char == '|' -> {
                    endToken()
                    require(command.getOrNull(index + 1) != '|') { "|| is not allowed" }
                    stages.add(mutableListOf())
                }
                char == ' ' || char == '\t' -> endToken()
                char in UNQUOTED_FORBIDDEN -> throw IllegalArgumentException("\"$char\" is not allowed")
                else -> {
                    current.append(char)
                    inToken = true
                }
            }
            index++
        }
        endToken()
        return stages
    }
}
