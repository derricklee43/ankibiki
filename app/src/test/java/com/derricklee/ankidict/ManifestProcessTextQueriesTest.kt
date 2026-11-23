package com.derricklee.ankidict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Translate-chip checklist item 1 (user-provided doc 2026-11-23): the manifest must declare
 * intent visibility for ACTION_PROCESS_TEXT + text/plain, or targetSdk 30+ package visibility
 * hides GSA/Translate's PROCESS_TEXT components and the system Translate chip never renders
 * in this app's selection toolbar (observed live on the Pixel before the fix).
 */
class ManifestProcessTextQueriesTest {

    private fun manifestText(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),          // gradle JVM test cwd = app module
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: fail("AndroidManifest.xml not found from cwd " + File(".").absolutePath)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    @Test
    fun queries_declareProcessTextIntent_forSystemTranslateVisibility() {
        val manifest = manifestText()
        val queriesBlock = Regex("<queries>.*?</queries>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value ?: fail("no <queries> block in manifest")
        assertTrue(
            "<queries> must contain an <intent> with the PROCESS_TEXT action",
            Regex("<intent>[^<]*<action\\s+android:name=\"android\\.intent\\.action\\.PROCESS_TEXT\"\\s*/>", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(queriesBlock.replace(Regex("<!--.*?-->"), "")),
        )
        assertTrue(
            "the PROCESS_TEXT intent query must constrain mimeType text/plain",
            Regex("<intent>.*<data\\s+android:mimeType=\"text/plain\"\\s*/>.*</intent>", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(queriesBlock.replace(Regex("<!--.*?-->"), "")),
        )
    }
}
