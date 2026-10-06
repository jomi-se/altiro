package org.altiro.core

/** Explicit user words. Diagnostics and object formatting must never expose their contents. */
class Vocabulary private constructor(val text: String, val count: Int, val prompt: String) {
    override fun toString(): String = "Vocabulary($count terms)"

    companion object {
        const val MAX_TERMS = 100
        const val MAX_BYTES = 4096
        const val MAX_DRAFT_CHARS = 8192
        val EMPTY = Vocabulary("", 0, "")

        fun parse(draft: String): VocabularyValidation {
            if (draft.length > MAX_DRAFT_CHARS)
                return VocabularyValidation.Invalid(VocabularyError.TOO_LARGE)
            var index = 0
            while (index < draft.length) {
                val char = draft[index]
                if (char.isHighSurrogate()) {
                    if (index + 1 >= draft.length || !draft[index + 1].isLowSurrogate())
                        return VocabularyValidation.Invalid(VocabularyError.INVALID_CHARACTERS)
                    index += 2
                    continue
                }
                if (char.isLowSurrogate() || (char.isISOControl() && char !in "\r\n\t"))
                    return VocabularyValidation.Invalid(VocabularyError.INVALID_CHARACTERS)
                index++
            }
            val terms =
                draft
                    .lineSequence()
                    .map { it.trim().replace(Regex("[ \\t]+"), " ") }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_TERMS + 1)
                    .toList()
            if (terms.size > MAX_TERMS)
                return VocabularyValidation.Invalid(VocabularyError.TOO_MANY_TERMS)
            val prompt = terms.joinToString(", ")
            if (prompt.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
                return VocabularyValidation.Invalid(VocabularyError.TOO_LARGE)
            return VocabularyValidation.Valid(
                if (terms.isEmpty()) EMPTY
                else Vocabulary(terms.joinToString("\n"), terms.size, prompt)
            )
        }
    }
}

enum class VocabularyError {
    TOO_MANY_TERMS,
    TOO_LARGE,
    INVALID_CHARACTERS,
}

sealed interface VocabularyValidation {
    data class Valid(val vocabulary: Vocabulary) : VocabularyValidation

    data class Invalid(val error: VocabularyError) : VocabularyValidation
}
