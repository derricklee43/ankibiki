package com.derricklee.ankidict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for the audit FU1 call-site (2026-11-23): NameplateRuling's inline path must
 * keep passing authorship = false into reprocessPhotoText — the runtime instrumented spec
 * proves the parameter works; this proves the ruling actually USES it, without needing a
 * device lexicon.
 */
class RulingAuthorshipCallSiteTest {

    private fun source(name: String): String {
        val candidates = listOf("src/main/java/com/derricklee/ankidict/$name", "app/src/main/java/com/derricklee/ankidict/$name")
            .map { File(it) }
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$name not found from cwd " + File(".").absolutePath)
    }

    @Test
    fun nameplateRuling_inlinePath_passesAuthorshipFalse() {
        val src = source("NameplateRuling.kt")
            .replace(Regex("//[^\n]*"), "")   // comments may contain parens; strip before matching
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "the inline reprocessPhotoText call must carry authorship = false",
            Regex("reprocessPhotoText\\s*\\([^)]*authorship\\s*=\\s*false").containsMatchIn(src),
        )
    }
}
