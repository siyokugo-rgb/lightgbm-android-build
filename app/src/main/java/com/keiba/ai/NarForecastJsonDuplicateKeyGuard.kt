package com.keiba.ai

/**
 * Fail-closed duplicate object-member detector for ForecastWeather JSON.
 *
 * Runs before platform `org.json.JSONObject` construction so Android
 * last-wins duplicate-key behavior cannot silently accept ambiguous
 * Forecast evidence.
 *
 * Object scopes are tracked with an explicit stack (no unbounded
 * recursion). JSON string escapes including `\uXXXX` are decoded before
 * key comparison, so semantic duplicates such as `"latitude"` vs
 * `"latit\u0075de"` are rejected.
 */
internal object NarForecastJsonDuplicateKeyGuard {

    private const val MAX_NESTING_DEPTH =
        64

    fun rejectDuplicateKeys(
        text: String
    ) {
        require(text.isNotEmpty()) {
            "empty weather response"
        }

        val scanner =
            Scanner(text)

        scanner.skipWhitespace()

        require(!scanner.exhausted()) {
            "weather response is not valid JSON"
        }

        when (scanner.peek()) {
            '{' ->
                scanner.parseValue()

            else ->
                throw IllegalArgumentException(
                    "weather response is not JSON object"
                )
        }

        scanner.skipWhitespace()

        require(scanner.exhausted()) {
            "weather response has trailing content"
        }
    }

    private class Scanner(
        private val text: String
    ) {
        private var index = 0

        private val frameStack =
            ArrayList<Frame>(8)

        fun exhausted(): Boolean =
            index >= text.length

        fun peek(): Char {
            require(!exhausted()) {
                "unexpected end of weather JSON"
            }

            return text[index]
        }

        fun skipWhitespace() {
            while (index < text.length) {
                val c =
                    text[index]

                if (
                    c == ' ' ||
                    c == '\n' ||
                    c == '\r' ||
                    c == '\t'
                ) {
                    index++
                } else {
                    break
                }
            }
        }

        fun parseValue() {
            skipWhitespace()

            require(!exhausted()) {
                "unexpected end of weather JSON"
            }

            when (val c = peek()) {
                '{' ->
                    parseObject()

                '[' ->
                    parseArray()

                '"' ->
                    parseString()

                't' ->
                    parseLiteral("true")

                'f' ->
                    parseLiteral("false")

                'n' ->
                    parseLiteral("null")

                else -> {
                    if (
                        c == '-' ||
                        c in '0'..'9'
                    ) {
                        parseNumber()
                    } else {
                        throw IllegalArgumentException(
                            "unexpected JSON token: $c"
                        )
                    }
                }
            }
        }

        private fun parseObject() {
            expect('{')

            require(
                frameStack.size <
                    MAX_NESTING_DEPTH
            ) {
                "weather JSON nesting too deep"
            }

            val keys =
                HashSet<String>()

            frameStack.add(
                Frame.ObjectKeys(keys)
            )

            skipWhitespace()

            if (!exhausted() && peek() == '}') {
                index++
                frameStack.removeAt(
                    frameStack.lastIndex
                )
                return
            }

            while (true) {
                skipWhitespace()

                require(!exhausted()) {
                    "unexpected end of weather JSON object"
                }

                require(peek() == '"') {
                    "weather JSON object key must be a string"
                }

                val key =
                    parseString()

                require(keys.add(key)) {
                    "duplicate JSON object key: $key"
                }

                skipWhitespace()
                expect(':')
                parseValue()
                skipWhitespace()

                require(!exhausted()) {
                    "unexpected end of weather JSON object"
                }

                when (peek()) {
                    ',' -> {
                        index++
                        continue
                    }

                    '}' -> {
                        index++
                        frameStack.removeAt(
                            frameStack.lastIndex
                        )
                        return
                    }

                    else ->
                        throw IllegalArgumentException(
                            "malformed weather JSON object"
                        )
                }
            }
        }

        private fun parseArray() {
            expect('[')

            require(
                frameStack.size <
                    MAX_NESTING_DEPTH
            ) {
                "weather JSON nesting too deep"
            }

            frameStack.add(Frame.Array)

            skipWhitespace()

            if (!exhausted() && peek() == ']') {
                index++
                frameStack.removeAt(
                    frameStack.lastIndex
                )
                return
            }

            while (true) {
                parseValue()
                skipWhitespace()

                require(!exhausted()) {
                    "unexpected end of weather JSON array"
                }

                when (peek()) {
                    ',' -> {
                        index++
                        continue
                    }

                    ']' -> {
                        index++
                        frameStack.removeAt(
                            frameStack.lastIndex
                        )
                        return
                    }

                    else ->
                        throw IllegalArgumentException(
                            "malformed weather JSON array"
                        )
                }
            }
        }

        private fun parseString(): String {
            expect('"')

            val decoded =
                StringBuilder()

            while (!exhausted()) {
                when (val c = text[index++]) {
                    '"' ->
                        return decoded.toString()

                    '\\' -> {
                        require(!exhausted()) {
                            "truncated JSON string escape"
                        }

                        when (val escaped = text[index++]) {
                            '"', '\\', '/' ->
                                decoded.append(escaped)

                            'b' ->
                                decoded.append('\b')

                            'f' ->
                                decoded.append('\u000c')

                            'n' ->
                                decoded.append('\n')

                            'r' ->
                                decoded.append('\r')

                            't' ->
                                decoded.append('\t')

                            'u' -> {
                                require(
                                    index + 4 <=
                                        text.length
                                ) {
                                    "truncated Unicode escape"
                                }

                                var code = 0

                                for (offset in 0 until 4) {
                                    val hex =
                                        text[index + offset]

                                    val digit =
                                        hexDigit(hex)

                                    code =
                                        (code shl 4) or
                                            digit
                                }

                                index += 4
                                decoded.append(
                                    code.toChar()
                                )
                            }

                            else ->
                                throw IllegalArgumentException(
                                    "invalid JSON string escape"
                                )
                        }
                    }

                    else -> {
                        require(c.code >= 0x20) {
                            "unescaped control character in JSON string"
                        }

                        decoded.append(c)
                    }
                }
            }

            throw IllegalArgumentException(
                "unterminated JSON string"
            )
        }

        private fun parseNumber() {
            val start =
                index

            if (peek() == '-') {
                index++
            }

            require(!exhausted()) {
                "truncated JSON number"
            }

            if (peek() == '0') {
                index++
            } else {
                require(peek() in '1'..'9') {
                    "invalid JSON number"
                }

                while (
                    !exhausted() &&
                    peek() in '0'..'9'
                ) {
                    index++
                }
            }

            if (!exhausted() && peek() == '.') {
                index++

                require(
                    !exhausted() &&
                        peek() in '0'..'9'
                ) {
                    "invalid JSON number fraction"
                }

                while (
                    !exhausted() &&
                    peek() in '0'..'9'
                ) {
                    index++
                }
            }

            if (
                !exhausted() &&
                (peek() == 'e' || peek() == 'E')
            ) {
                index++

                require(!exhausted()) {
                    "truncated JSON number exponent"
                }

                if (
                    peek() == '+' ||
                    peek() == '-'
                ) {
                    index++
                }

                require(
                    !exhausted() &&
                        peek() in '0'..'9'
                ) {
                    "invalid JSON number exponent"
                }

                while (
                    !exhausted() &&
                    peek() in '0'..'9'
                ) {
                    index++
                }
            }

            require(index > start) {
                "invalid JSON number"
            }
        }

        private fun parseLiteral(
            literal: String
        ) {
            require(
                index + literal.length <=
                    text.length
            ) {
                "truncated JSON literal"
            }

            for (offset in literal.indices) {
                require(
                    text[index + offset] ==
                        literal[offset]
                ) {
                    "invalid JSON literal"
                }
            }

            index += literal.length
        }

        private fun expect(
            expected: Char
        ) {
            require(!exhausted() && peek() == expected) {
                "expected '$expected' in weather JSON"
            }

            index++
        }

        private fun hexDigit(
            c: Char
        ): Int =
            when (c) {
                in '0'..'9' ->
                    c - '0'

                in 'a'..'f' ->
                    10 + (c - 'a')

                in 'A'..'F' ->
                    10 + (c - 'A')

                else ->
                    throw IllegalArgumentException(
                        "invalid Unicode escape digit"
                    )
            }
    }

    private sealed class Frame {
        class ObjectKeys(
            val keys: MutableSet<String>
        ) : Frame()

        data object Array : Frame()
    }
}
