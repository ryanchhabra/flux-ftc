package dev.ryanchhab.flux.idea

import java.io.File

/**
 * Lightweight source-level scanner for `@FluxLive` fields, used by the Live Tuning section of the
 * Flux tool window to (a) know each field's declared type -- `fluxRead`'s plain-text table
 * (CONTRACT.md Amendment 4) prints FIELD/SOURCE/ROBOT but not TYPE, and the wire payload for
 * `LIVE_TUNE` (Amendment 3) requires one -- and (b) locate the exact literal span to rewrite when
 * a slider or text box commits a new value.
 *
 * This is a deliberate, scoped-down duplicate of `flux-gradle`'s
 * `dev.ryanchhab.flux.gradle.LiveTuneDetector` scan step (same regexes, same brace-matching, same
 * eligibility rules from CONTRACT.md Amendment 3) -- not a dependency on it. flux-idea does not
 * depend on flux-gradle (the two modules are built and versioned independently; see
 * [AdbLocator]'s doc for the same reasoning already established in this codebase), and this
 * scanner only needs the "what fields exist and where" half, not LiveTuneDetector's masked-hash
 * diffing baseline. `final`/Kotlin `val` fields are simply skipped (not returned) -- they are
 * never live-writable (Amendment 3), and this scanner has no warnings machinery to report that;
 * `fluxTune`/`fluxRead` already do.
 *
 * [scanText] is pure (no I/O) and is exactly what [SourceFieldWriter] re-runs immediately before
 * editing a file -- never against a cached offset from an earlier Refresh, so a file edited since
 * the last Refresh can't cause a corrupt write.
 */
object FluxLiveSourceScanner {

    data class ScannedField(
        val file: File,
        val packageName: String,
        val simpleClassName: String,
        val fieldName: String,
        val type: String,
        /** Value with source-level quoting stripped for String/char (matches LiveTuneDetector). */
        val value: String,
        /** Char offset range of the literal within the scanned text (end-inclusive). */
        val literalRange: IntRange,
    ) {
        val fqClassName: String get() = if (packageName.isEmpty()) simpleClassName else "$packageName.$simpleClassName"
        val label: String get() = "$simpleClassName.$fieldName"
    }

    private val SKIP_DIRS = setOf("build", "out", ".git", ".gradle", ".idea", "node_modules")

    /** Walks [root] for every eligible `@FluxLive` field. Call off the EDT -- this reads files. */
    fun scan(root: File): List<ScannedField> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<ScannedField>()
        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file -> out += scanFile(file) }
        return out
    }

    /** Re-scans a single file fresh from disk. */
    fun scanFile(file: File): List<ScannedField> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (!text.contains("@FluxLive")) return emptyList()
        return scanText(file, text)
    }

    /**
     * Pure scan of [text] as if it were [file] (only [file]'s extension/name is used, it need not
     * exist on disk) -- what makes this directly unit-testable, and what write-back re-runs
     * against the live editor document instead of the on-disk copy.
     */
    fun scanText(file: File, text: String): List<ScannedField> {
        val packageName = PACKAGE_REGEX.find(text)?.groupValues?.get(1) ?: ""
        val isKotlin = file.extension == "kt"
        val fields = mutableListOf<ScannedField>()

        for (m in CLASS_HEADER_REGEX.findAll(text)) {
            val simpleClassName = m.groupValues[1]
            val headerEnd = m.range.last + 1
            val braceOpen = text.indexOf('{', headerEnd)
            if (braceOpen < 0) continue
            val braceClose = matchBrace(text, braceOpen) ?: continue

            var scanStart = braceOpen + 1
            var scanEnd = braceClose

            // Kotlin's usual shape is `class Foo { companion object { val K = ... } }` -- scope
            // the scan to the companion body when present (matches LiveTuneDetector).
            if (isKotlin) {
                val companionMatch = COMPANION_OBJECT_REGEX.find(text, scanStart)
                if (companionMatch != null && companionMatch.range.first < scanEnd) {
                    val cBraceOpen = text.indexOf('{', companionMatch.range.last)
                    if (cBraceOpen in scanStart until scanEnd) {
                        val cBraceClose = matchBrace(text, cBraceOpen)
                        if (cBraceClose != null && cBraceClose <= scanEnd) {
                            scanStart = cBraceOpen + 1
                            scanEnd = cBraceClose
                        }
                    }
                }
            }

            val regionText = text.substring(scanStart, scanEnd)
            val matches = if (isKotlin) KOTLIN_FIELD_REGEX.findAll(regionText) else JAVA_FIELD_REGEX.findAll(regionText)

            for (fm in matches) {
                val parsed = if (isKotlin) parseKotlinField(fm, text) else parseJavaField(fm, text)
                if (parsed == null) continue // includes final/val -- never live-writable
                val literalGroup = fm.groups[parsed.literalGroupIndex] ?: continue
                val absoluteRange = (literalGroup.range.first + scanStart)..(literalGroup.range.last + scanStart)
                fields += ScannedField(file, packageName, simpleClassName, parsed.fieldName, parsed.type, parsed.value, absoluteRange)
            }
        }
        return fields
    }

    // ------------------------------------------------------------------------------------------
    // Everything below mirrors LiveTuneDetector's scanFluxLiveClasses/parseJavaField/
    // parseKotlinField/eligibleLiteral/matchBrace. Kept in sync by hand since there is no shared
    // module between flux-idea and flux-gradle (see class doc).
    // ------------------------------------------------------------------------------------------

    private val JAVA_FIELD_REGEX = Regex(
        "(?m)^[ \\t]*((?:(?:public|protected|private|static|final|volatile|transient)\\s+)+)" +
            "(\\S+)\\s+(\\w+)\\s*=\\s*([^;]+?);",
    )
    private val KOTLIN_FIELD_REGEX = Regex(
        "(?m)^[ \\t]*((?:(?:public|internal|private|protected|const)\\s+)*)(var|val)\\s+(\\w+)\\s*" +
            "(?::\\s*(\\S+)\\s*)?=\\s*([^\\n]+?)\\s*$",
    )
    private val CLASS_HEADER_REGEX = Regex("@FluxLive\\b[\\s\\S]{0,200}?\\b(?:class|object)\\s+(\\w+)")
    private val COMPANION_OBJECT_REGEX = Regex("\\bcompanion\\s+object\\b")
    private val PACKAGE_REGEX = Regex("(?m)^\\s*package\\s+([\\w.]+)")
    private val ENUM_TYPE_NAME_REGEX = Regex("^[A-Za-z_]\\w*$")
    private val STATIC_MOD_REGEX = Regex("\\bstatic\\b")
    private val FINAL_MOD_REGEX = Regex("\\bfinal\\b")

    private val STRING_LITERAL = Regex("^\"(?:[^\"\\\\]|\\\\.)*\"$")
    private val CHAR_LITERAL = Regex("^'(?:[^'\\\\]|\\\\.)*'$")
    private val BOOL_LITERAL = Regex("^(?:true|false)$")
    private val INT_LITERAL = Regex("^-?(?:0[xX][0-9a-fA-F]+|0[bB][01]+|\\d+)[lL]?$")
    private val FLOAT_LITERAL = Regex(
        "^-?\\d+\\.\\d+(?:[eE][-+]?\\d+)?[fFdD]?$|^-?\\d+[fFdD]$|^-?\\d+[eE][-+]?\\d+[fFdD]?$",
    )
    private val ENUM_LITERAL = Regex("^[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)?$")

    private val PRIMITIVE_TYPES = setOf("byte", "short", "int", "long", "float", "double", "boolean", "char")
    private val KOTLIN_PRIMITIVE_MAP = mapOf(
        "Byte" to "byte", "Short" to "short", "Int" to "int", "Long" to "long",
        "Float" to "float", "Double" to "double", "Boolean" to "boolean", "Char" to "char",
        "String" to "String",
    )

    private data class ParsedField(
        val fieldName: String,
        val type: String,
        val value: String,
        val literalGroupIndex: Int,
    )

    private fun parseJavaField(fm: MatchResult, fileText: String): ParsedField? {
        val modifiers = fm.groupValues[1]
        if (!STATIC_MOD_REGEX.containsMatchIn(modifiers)) return null
        if (FINAL_MOD_REGEX.containsMatchIn(modifiers)) return null // constant-folded -- see class doc
        val fieldName = fm.groupValues[3]
        val normalizedType = normalizeJavaType(fm.groupValues[2]) ?: return null
        val rawLiteral = stripTrailingComment(fm.groupValues[4]).trim()
        val eligible = eligibleLiteral(normalizedType, rawLiteral, fileText) ?: return null
        return ParsedField(fieldName, eligible.first, literalValue(eligible.first, eligible.second), literalGroupIndex = 4)
    }

    private fun parseKotlinField(fm: MatchResult, fileText: String): ParsedField? {
        val kind = fm.groupValues[2] // "var" or "val"
        if (kind == "val") return null // constant-folded -- see class doc
        val fieldName = fm.groupValues[3]
        val explicitType = fm.groupValues[4].takeIf { it.isNotBlank() }
        val rawLiteral = stripTrailingComment(fm.groupValues[5]).trim()
        if (rawLiteral.isEmpty() || rawLiteral.endsWith("{") || rawLiteral.endsWith("(")) return null
        val normalizedType = explicitType?.let { normalizeKotlinType(it) } ?: inferKotlinType(rawLiteral)
        if (normalizedType == null) return null
        val eligible = eligibleLiteral(normalizedType, rawLiteral, fileText) ?: return null
        return ParsedField(fieldName, eligible.first, literalValue(eligible.first, eligible.second), literalGroupIndex = 5)
    }

    private fun normalizeJavaType(raw: String): String? {
        if (raw in PRIMITIVE_TYPES) return raw
        if (raw == "String") return "String"
        if (ENUM_TYPE_NAME_REGEX.matches(raw) && raw[0].isUpperCase()) return raw
        return null
    }

    private fun normalizeKotlinType(raw: String): String? {
        KOTLIN_PRIMITIVE_MAP[raw]?.let { return it }
        if (ENUM_TYPE_NAME_REGEX.matches(raw) && raw[0].isUpperCase()) return raw
        return null
    }

    private fun inferKotlinType(literal: String): String? = when {
        STRING_LITERAL.matches(literal) -> "String"
        BOOL_LITERAL.matches(literal) -> "boolean"
        CHAR_LITERAL.matches(literal) -> "char"
        FLOAT_LITERAL.matches(literal) -> "double"
        INT_LITERAL.matches(literal) -> "int"
        else -> null
    }

    private fun eligibleLiteral(type: String, rawLiteral: String, fileText: String): Pair<String, String>? = when (type) {
        "boolean" -> if (BOOL_LITERAL.matches(rawLiteral)) type to rawLiteral else null
        "String" -> if (STRING_LITERAL.matches(rawLiteral)) type to rawLiteral else null
        "char" -> if (CHAR_LITERAL.matches(rawLiteral)) type to rawLiteral else null
        "byte", "short", "int", "long" -> if (INT_LITERAL.matches(rawLiteral)) type to rawLiteral else null
        "float", "double" -> if (FLOAT_LITERAL.matches(rawLiteral) || INT_LITERAL.matches(rawLiteral)) type to rawLiteral else null
        else -> {
            if (type.isNotEmpty() && type[0].isUpperCase() &&
                Regex("\\benum\\b[\\s\\S]{0,40}\\b${Regex.escape(type)}\\b").containsMatchIn(fileText) &&
                ENUM_LITERAL.matches(rawLiteral)
            ) {
                type to rawLiteral
            } else {
                null
            }
        }
    }

    private fun literalValue(type: String, rawLiteral: String): String = when (type) {
        "String" -> rawLiteral.removeSurrounding("\"")
        "char" -> rawLiteral.removeSurrounding("'")
        else -> rawLiteral
    }

    private fun stripTrailingComment(raw: String): String {
        val trimmed = raw.trim()
        if (STRING_LITERAL.matches(trimmed)) return trimmed
        val idx = raw.indexOf("//")
        return if (idx >= 0) raw.substring(0, idx) else raw
    }

    private fun matchBrace(text: String, openIndex: Int): Int? {
        var depth = 0
        var i = openIndex
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        while (i < text.length) {
            val c = text[i]
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    inBlockComment = false
                    i++
                }
                inString -> when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
                inChar -> when (c) {
                    '\\' -> i++
                    '\'' -> inChar = false
                }
                else -> when {
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { inLineComment = true; i++ }
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { inBlockComment = true; i++ }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }
}
