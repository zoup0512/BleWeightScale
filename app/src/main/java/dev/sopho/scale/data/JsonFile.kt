package dev.sopho.scale.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small JSON codec independent of Android's stubbed org.json classes, so storage is JVM-testable. */
internal object JsonCodec {
    fun parse(text: String): Any? = Parser(text).parse()

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String)
            "${stringify(it.key)}:${stringify(it.value)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> error("Unsupported JSON value: ${value::class}")
    }

    private class Parser(private val text: String) {
        private var position = 0
        fun parse(): Any? {
            val result = value()
            space()
            require(position == text.length) { "Trailing JSON content" }
            return result
        }
        private fun space() { while (position < text.length && text[position].isWhitespace()) position++ }
        private fun take(ch: Char) {
            space()
            require(position < text.length && text[position++] == ch) { "Expected $ch" }
        }
        private fun value(): Any? {
            space()
            require(position < text.length) { "Unexpected end of JSON" }
            return when (text[position]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, position)) { "Expected $word" }
            position += word.length
            return result
        }
        private fun obj(): Map<String, Any?> {
            take('{')
            val result = linkedMapOf<String, Any?>()
            space()
            if (position < text.length && text[position] == '}') { position++; return result }
            while (true) {
                space()
                val key = string()
                take(':')
                result[key] = value()
                space()
                if (position < text.length && text[position] == '}') { position++; return result }
                take(',')
            }
        }
        private fun array(): List<Any?> {
            take('[')
            val result = mutableListOf<Any?>()
            space()
            if (position < text.length && text[position] == ']') { position++; return result }
            while (true) {
                result += value()
                space()
                if (position < text.length && text[position] == ']') { position++; return result }
                take(',')
            }
        }
        private fun string(): String {
            take('"')
            val result = StringBuilder()
            while (position < text.length) {
                val ch = text[position++]
                if (ch == '"') return result.toString()
                if (ch != '\\') { require(ch >= ' '); result.append(ch); continue }
                require(position < text.length)
                result.append(when (val escape = text[position++]) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(position + 4 <= text.length)
                        val code = text.substring(position, position + 4).toInt(16)
                        position += 4
                        code.toChar()
                    }
                    else -> error("Invalid JSON escape: $escape")
                })
            }
            error("Unterminated JSON string")
        }
        private fun number(): Number {
            val start = position
            while (position < text.length && text[position] in "-+0123456789.eE") position++
            require(position > start)
            val token = text.substring(start, position)
            return if (token.contains('.') || token.contains('e', true)) token.toDouble().also { require(it.isFinite()) }
                else token.toLong()
        }
    }
}

internal fun jsonObject(value: Any?): Map<String, Any?> =
    (value as? Map<*, *>)?.entries?.associate { (key, v) -> (key as String) to v }
        ?: error("Expected JSON object")

internal fun jsonArray(value: Any?): List<Any?> = value as? List<*> ?: error("Expected JSON array")
internal fun Map<String, Any?>.requiredString(key: String): String = this[key] as? String ?: error("Missing $key")
internal fun Map<String, Any?>.requiredNumber(key: String): Number = this[key] as? Number ?: error("Missing $key")
internal fun Map<String, Any?>.optionalNumber(key: String): Number? = this[key] as? Number
internal fun Map<String, Any?>.optionalString(key: String): String? = this[key] as? String

/** Write a synced temporary file and atomically replace the destination in the same directory. */
internal fun writeJsonAtomically(file: File, content: String) {
    file.parentFile?.mkdirs()
    val temporary = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { stream ->
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
