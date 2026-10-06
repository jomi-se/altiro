package org.altiro.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VocabularyTest {
    private fun value(text: String) =
        (Vocabulary.parse(text) as VocabularyValidation.Valid).vocabulary

    @Test
    fun normalizesLinesWithoutChangingAccentsCaseOrMeaning() {
        val result = value("  Kubernetes \r\n\n Altiro\t CLI  \nKubernetes\nPeñalolén\nÉcole")
        assertEquals(4, result.count)
        assertEquals("Kubernetes\nAltiro CLI\nPeñalolén\nÉcole", result.text)
        assertEquals("Kubernetes, Altiro CLI, Peñalolén, École", result.prompt)
        assertSame(Vocabulary.EMPTY, value(" \r\n\t"))
    }

    @Test
    fun enforcesPromptUtf8BytesRatherThanCharacterCount() {
        assertEquals(4096, value("á".repeat(2048)).prompt.toByteArray().size)
        assertEquals(
            VocabularyValidation.Invalid(VocabularyError.TOO_LARGE),
            Vocabulary.parse("á".repeat(2049)),
        )
        assertEquals(4096, value("🔬".repeat(1024)).prompt.toByteArray().size)
        assertEquals(
            VocabularyValidation.Invalid(VocabularyError.TOO_LARGE),
            Vocabulary.parse("🔬".repeat(1025)),
        )
        // Separators count toward the native byte budget too.
        assertEquals(
            VocabularyValidation.Invalid(VocabularyError.TOO_LARGE),
            Vocabulary.parse("a".repeat(4095) + "\nb"),
        )
    }

    @Test
    fun enforcesTermCountAndRejectsInvalidUnicodeAndControls() {
        assertEquals(100, value((1..100).joinToString("\n") { "term$it" }).count)
        assertEquals(
            VocabularyValidation.Invalid(VocabularyError.TOO_MANY_TERMS),
            Vocabulary.parse((1..101).joinToString("\n") { "term$it" }),
        )
        for (input in
            listOf("a\u0000b", "a\u0007b", "a\u007fb", "\uD800", "\uDC00", "\uD800a")) assertEquals(
            VocabularyValidation.Invalid(VocabularyError.INVALID_CHARACTERS),
            Vocabulary.parse(input),
        )
        assertEquals(
            VocabularyValidation.Invalid(VocabularyError.TOO_LARGE),
            Vocabulary.parse("a".repeat(8193)),
        )
    }

    @Test
    fun aRecordingSnapshotKeepsItsWordsAndFormattingDoesNotLeakThem() {
        val first = value("private-uncommon-name")
        val input =
            RecognitionInput(
                ModelSpec("test", 1, "a".repeat(64)),
                File("unused"),
                vocabulary = first,
            )
        val replacement = value("different-name")
        assertEquals("private-uncommon-name", input.vocabulary.prompt)
        assertNotEquals(input.vocabulary.prompt, replacement.prompt)
        assertFalse(input.toString().contains("private-uncommon-name"))
        assertEquals("Vocabulary(1 terms)", first.toString())
        assertEquals("different-name", value(replacement.text).prompt)
    }

    @Test
    fun diagnosticsExposeOnlyTheConfiguredCount() {
        val words = value("private-uncommon-name\nsecond-name")
        val trace = RecognitionDiagnostics()
        trace.begin("es", listOf("test"), false, vocabularyTerms = words.count)
        val exported = trace.report.value!!.export()
        assertTrue(exported.contains("Vocabulary hints configured: 2 terms"))
        assertFalse(exported.contains("private-uncommon-name"))
        assertThrows(IllegalArgumentException::class.java) {
            trace.begin("es", listOf("test"), false, vocabularyTerms = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            trace.begin("es", listOf("test"), false, vocabularyTerms = -1)
        }
    }
}
