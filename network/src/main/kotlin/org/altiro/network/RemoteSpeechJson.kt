package org.altiro.network

/** Strict, bounded JSON parsing for the small server contract, without Android runtime classes. */
internal class RemoteSpeechJson(private val source: String) {
    private var offset = 0

    fun objectValue(): Map<*, *> {
        val result = value(0)
        whitespace()
        if (offset != source.length || result !is Map<*, *>) invalid()
        return result
    }

    private fun value(depth: Int): Any? {
        if (depth > 32) invalid()
        whitespace()
        return when (peek()) {
            '{' -> {
                offset++
                val result = mutableMapOf<String, Any?>()
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string()
                        if (result.containsKey(key)) invalid()
                        whitespace()
                        expect(':')
                        result[key] = value(depth + 1)
                        whitespace()
                    } while (take(','))
                    expect('}')
                }
                result
            }
            '[' -> {
                offset++
                val result = mutableListOf<Any?>()
                whitespace()
                if (!take(']')) {
                    do {
                        result.add(value(depth + 1))
                        whitespace()
                    } while (take(','))
                    expect(']')
                }
                result
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-',
            in '0'..'9' -> number()
            else -> invalid()
        }
    }

    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = next()
            if (character == '"') break
            if (character < ' ') invalid()
            if (character != '\\') result.append(character)
            else {
                result.append(
                    when (val escaped = next()) {
                        '"',
                        '\\',
                        '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val digit = next()
                                if (digit !in '0'..'9' && digit !in 'a'..'f' && digit !in 'A'..'F')
                                    invalid()
                                code = code * 16 + digit.digitToInt(16)
                            }
                            code.toChar()
                        }
                        else -> invalid()
                    }
                )
            }
        }
        var index = 0
        while (index < result.length) {
            val character = result[index++]
            if (character.isHighSurrogate()) {
                if (index == result.length || !result[index++].isLowSurrogate()) invalid()
            } else if (character.isLowSurrogate()) invalid()
        }
        return result.toString()
    }

    private fun number(): Any {
        take('-')
        if (!take('0')) {
            if (peek() !in '1'..'9') invalid()
            do {
                offset++
            } while (peek() in '0'..'9')
        }
        if (take('.')) digits()
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            digits()
        }
        // Numeric fields are outside the adapter's contract; do not coerce them into strings.
        return JsonNumber
    }

    private fun digits() {
        if (peek() !in '0'..'9') invalid()
        do {
            offset++
        } while (peek() in '0'..'9')
    }

    private fun literal(token: String, result: Any?): Any? {
        if (!source.startsWith(token, offset)) invalid()
        offset += token.length
        return result
    }

    private fun whitespace() {
        while (true) {
            when (peek()) {
                ' ',
                '\t',
                '\r',
                '\n' -> offset++
                else -> return
            }
        }
    }

    private fun peek(): Char = source.getOrNull(offset) ?: '\u0000'

    private fun next(): Char = source.getOrNull(offset++) ?: invalid()

    private fun take(character: Char): Boolean {
        if (peek() != character) return false
        offset++
        return true
    }

    private fun expect(character: Char) {
        if (!take(character)) invalid()
    }

    private fun invalid(): Nothing = throw RemoteSpeechException("remote_invalid_response")

    private object JsonNumber
}
