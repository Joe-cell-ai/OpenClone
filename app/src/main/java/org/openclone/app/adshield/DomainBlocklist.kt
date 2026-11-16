package org.openclone.app.adshield

import java.net.IDN
import java.util.Locale

/**
 * Immutable domain policy primitive for Ad Shield's future DNS decision layer.
 *
 * It deliberately performs no network interception by itself. A VpnService and
 * DNS forwarder must call this policy before it can affect device traffic.
 */
class DomainBlocklist private constructor(
    private val blocked: Set<String>,
    private val allowed: Set<String>
) {
    val blockedDomainCount: Int get() = blocked.size
    val allowedDomainCount: Int get() = allowed.size

    /** Allow rules take precedence; otherwise a match includes all subdomains. */
    fun isBlocked(hostname: String): Boolean {
        val host = normalize(hostname) ?: return false
        if (matches(host, allowed)) return false
        return matches(host, blocked)
    }

    companion object {
        fun fromLines(
            blocklistLines: Iterable<String>,
            allowlistLines: Iterable<String> = emptyList()
        ): DomainBlocklist {
            return DomainBlocklist(
                blocked = parseLines(blocklistLines),
                allowed = parseLines(allowlistLines)
            )
        }

        fun normalize(hostname: String): String? {
            val candidate = hostname.trim().trimEnd('.')
            if (candidate.isEmpty() || candidate.length > 253 || candidate.any { it.isWhitespace() }) {
                return null
            }
            val ascii = try {
                IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) return null
            if (ascii == "localhost" || ascii.endsWith(".localhost")) return null
            return ascii
        }

        private fun parseLines(lines: Iterable<String>): Set<String> = buildSet {
            lines.forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val fields = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                val candidate = when {
                    fields.size >= 2 && (fields[0] == "0.0.0.0" || fields[0] == "127.0.0.1" ||
                        fields[0] == "::" || fields[0] == "::1") -> fields[1]
                    fields.size == 1 -> fields[0]
                    else -> return@forEach
                }
                normalize(candidate)?.let { add(it) }
            }
        }

        private fun matches(host: String, rules: Set<String>): Boolean {
            var candidate = host
            while (true) {
                if (candidate in rules) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) return false
                candidate = candidate.substring(dot + 1)
            }
        }
    }
}
