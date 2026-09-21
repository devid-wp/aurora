package com.aurora.app.source

/**
 * Minimal, dependency-free JSON field scanner for the online sources.
 *
 * Unlike a regex, it walks the JSON structurally (tracking string escapes and
 * nesting) so a key is only matched at the requested object depth. This matters
 * for Spotify, where a track's nested `album`/`artists` objects contain their
 * own `"name"` fields that would otherwise shadow the track name.
 */
internal object JsonScan {

    /** Raw value token text for a key at the top level of the root object. */
    fun topLevelValue(json: String, key: String): String? {
        var i = skipWhitespace(json, 0)
        if (i >= json.length || json[i] != '{') return null
        i++
        while (i < json.length) {
            i = skipWhitespace(json, i)
            // Entries after the first are comma-separated.
            if (i < json.length && json[i] == ',') i = skipWhitespace(json, i + 1)
            if (i >= json.length || json[i] == '}') return null
            if (json[i] != '"') return null
            val stringEnd = findStringEnd(json, i) ?: return null
            val name = unescape(json.substring(i + 1, stringEnd))
            i = skipWhitespace(json, stringEnd + 1)
            if (i >= json.length || json[i] != ':') return null
            i = skipWhitespace(json, i + 1)
            val valueEnd = findValueEnd(json, i) ?: return null
            if (name == key) return json.substring(i, valueEnd + 1)
            i = valueEnd + 1
        }
        return null
    }

    fun topLevelString(json: String, key: String): String {
        val raw = topLevelValue(json, key)?.trim() ?: return ""
        if (raw.length < 2 || raw[0] != '"' || raw[raw.length - 1] != '"') return ""
        return unescape(raw.substring(1, raw.length - 1))
    }

    fun topLevelLong(json: String, key: String): Long {
        val raw = topLevelValue(json, key)?.trim() ?: return 0L
        val token = raw.takeWhile { it == '-' || it.isDigit() }
        return token.toLongOrNull() ?: 0L
    }

    /** Object texts inside the top-level array value of [key], or empty. */
    fun topLevelArrayObjects(json: String, key: String): List<String> {
        val value = topLevelValue(json, key)?.trim() ?: return emptyList()
        if (!value.startsWith("[")) return emptyList()
        return splitTopLevelObjects(value)
    }

    // ── Scanner internals ─────────────────────────────────────────────────

    private fun skipWhitespace(json: String, from: Int): Int {
        var i = from
        while (i < json.length && json[i].isWhitespace()) i++
        return i
    }

    private fun findStringEnd(json: String, quoteStart: Int): Int? {
        var i = quoteStart + 1
        var escape = false
        while (i < json.length) {
            val ch = json[i]
            if (escape) {
                escape = false
            } else when (ch) {
                '\\' -> escape = true
                '"' -> return i
            }
            i++
        }
        return null
    }

    /** Index of the last character of the value starting at [start]. */
    private fun findValueEnd(json: String, start: Int): Int? {
        if (start >= json.length) return null
        return when (json[start]) {
            '"' -> findStringEnd(json, start)
            '{' -> findBalanced(json, start, '{', '}')
            '[' -> findBalanced(json, start, '[', ']')
            else -> {
                var i = start
                while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']' &&
                    !json[i].isWhitespace()
                ) {
                    i++
                }
                if (i == start) null else i - 1
            }
        }
    }

    private fun findBalanced(json: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < json.length) {
            val ch = json[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++
                continue
            }
            when (ch) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun splitTopLevelObjects(arrayJson: String): List<String> {
        val trimmed = arrayJson.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val content = trimmed.substring(1, trimmed.length - 1)
        val result = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var start = -1
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result.add(content.substring(start, i + 1))
                        start = -1
                    }
                }
            }
            i++
        }
        return result
    }

    fun unescape(value: String): String = value
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { m -> String(Character.toChars(m.groupValues[1].toInt(16))) }
}
