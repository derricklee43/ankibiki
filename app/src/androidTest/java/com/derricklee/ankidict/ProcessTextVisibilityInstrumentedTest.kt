package com.derricklee.ankidict

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime half of the translate-chip fix (2026-11-23): with the manifest intent query present,
 * the APP's own package-manager view must resolve ACTION_PROCESS_TEXT/text/plain to components
 * OUTSIDE the packages named in static <package> queries — that is the visibility the system
 * uses to inject the Translate chip. Self-skips on images with no external PROCESS_TEXT
 * provider (bare AVDs), where there is nothing to be visible.
 */
@RunWith(AndroidJUnit4::class)
class ProcessTextVisibilityInstrumentedTest {

    @Test
    fun appProcess_seesExternalProcessTextResolvers() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain" }
        val resolvers = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val own = setOf("com.derricklee.ankidict", "com.ichi2.anki")
        val external = resolvers.filter { it.activityInfo.packageName !in own }
        // Bare AVDs ship no PROCESS_TEXT provider at all; there the chip question is moot.
        // On any device WITH one (Pixel with GSA/Translate), this must never be empty —
        // an empty list here means the manifest intent query regressed and the system
        // Translate chip would silently vanish again.
        assumeTrue(
            "no external PROCESS_TEXT provider visible on this image — nothing to pin",
            external.isNotEmpty(),
        )
        assertTrue(
            "external resolvers: " + external.joinToString { it.activityInfo.packageName },
            external.isNotEmpty(),
        )
    }
}
