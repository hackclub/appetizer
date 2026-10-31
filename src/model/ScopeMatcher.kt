package com.fantamomo.slack.approver.model

sealed interface ScopeMatcher {
    val priority: Int

    fun match(scope: String): Boolean

    fun hasPriorityOver(other: ScopeMatcher): Boolean = priority < other.priority

    data class DirectScopeMatcher(
        val scope: String
    ) : ScopeMatcher {
        override val priority = 1

        override fun match(scope: String): Boolean =
            this.scope.equals(scope.trim(), ignoreCase = true)
    }

    data class PrefixScopeMatcher(
        val prefix: String
    ) : ScopeMatcher {
        override val priority = 2

        override fun match(scope: String): Boolean =
            scope.trim().startsWith(prefix, ignoreCase = true)
    }

    data class WildcardScopeMatcher(
        val pattern: String
    ) : ScopeMatcher {
        override val priority = 3

        private val regex: Regex = buildRegex(pattern)

        override fun match(scope: String): Boolean = regex.matches(scope.trim())

        private fun buildRegex(pattern: String): Regex {
            val regex = buildString {
                append("^")

                pattern.forEach { char ->
                    when (char) {
                        '*' -> append(".*")
                        else -> append(Regex.escape(char.toString()))
                    }
                }

                append("$")
            }

            return Regex(regex, RegexOption.IGNORE_CASE)
        }
    }

    data class RegexScopeMatcher(val pattern: String) : ScopeMatcher {
        val regex: Regex = Regex(pattern, RegexOption.IGNORE_CASE)
        override val priority: Int = 4

        override fun match(scope: String): Boolean = regex.matches(scope.trim())
    }

    data object AllScopeMatcher : ScopeMatcher {
        override val priority: Int = 100
        override fun match(scope: String): Boolean = true
    }

    companion object {

        val ALL_SCOPES = AllScopeMatcher

        fun fromString(scope: String): ScopeMatcher {
            val trimmed = scope.trim()
            if (trimmed == "*") return ALL_SCOPES
            if (trimmed.startsWith('/') && trimmed.endsWith('/') && trimmed.length > 2) {
                return RegexScopeMatcher(trimmed.substring(1, trimmed.length - 1))
            }
            if (trimmed.startsWith('/')) {
                return RegexScopeMatcher(trimmed.substring(1))
            }
            val starIndex = trimmed.indexOf('*')
            if (starIndex != -1) {
                if (trimmed.length == 1) return ALL_SCOPES
                if (trimmed.length == starIndex + 1 && (trimmed[starIndex - 1] == ':' || trimmed[starIndex - 1] == '.')) {
                    return PrefixScopeMatcher(trimmed.substring(0, starIndex))
                }
                return WildcardScopeMatcher(trimmed)
            }
            if (trimmed.endsWith(':') || trimmed.endsWith('.')) {
                return PrefixScopeMatcher(trimmed)
            }
            return DirectScopeMatcher(trimmed)
        }
    }
}