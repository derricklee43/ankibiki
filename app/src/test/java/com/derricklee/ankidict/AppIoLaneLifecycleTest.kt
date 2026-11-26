package com.derricklee.ankidict

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard (device crash 2026-11-26 07:43:33): MainActivity.onDestroy used to call
 * syncExecutor.shutdown() where syncExecutor ALIASES the process-lifetime AppIo.pipeline
 * singleton. One activity destruction then poisoned the serial corpus lane for the whole
 * process, and the next 抽出 tap threw RejectedExecutionException on the main thread
 * (pool state: [Terminated, completed tasks = 3]) - mining silently dead until relaunch.
 *
 * Rule pinned here: no file under app/src/main may call shutdown()/shutdownNow() on an
 * AppIo lane (directly or via an alias). Lanes die with the process; per-activity code
 * owns only its own Handler removals, receivers, and adapters.
 */
class AppIoLaneLifecycleTest {

    private fun mainSources(): List<File> {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("no app main source root found from test cwd ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun noShutdownCallsInMainSources() {
        val offenders = mainSources().filter { f ->
            val text = f.readText()
            Regex("""\b\w*[Ee]xecutor\w*\s*\.\s*shutdown(Now)?\s*\(""").containsMatchIn(text) ||
                Regex("""\bAppIo\s*\.\s*\w+\s*\.\s*shutdown(Now)?\s*\(""").containsMatchIn(text) ||
                Regex("""AppIo\s*\.\s*(io|pipeline|thumbnails)\s*\.\s*shutdown""").containsMatchIn(text)
        }.map { it.name }
        assertTrue("AppIo lanes must never be shut down from activity/service code: $offenders",
            offenders.isEmpty())
    }

    @Test
    fun pipelineLaneIsTheSharedCorpusSerialLane() {
        // Structural pin: if AppIo ever gains per-caller pools the aliasing bug class
        // returns; MainActivity's sync/scan lanes MUST stay aliases of the singletons.
        val main = File("src/main/java/com/derricklee/ankidict/MainActivity.kt").let {
            if (it.isFile) it else File("app/src/main/java/com/derricklee/ankidict/MainActivity.kt")
        }
        assertTrue("MainActivity.kt not found from test cwd", main.isFile)
        val text = main.readText()
        assertTrue("syncExecutor must alias AppIo.pipeline (design §scan serial lane)",
            Regex("""val\s+syncExecutor\s*=\s*AppIo\.pipeline""").containsMatchIn(text))
        assertTrue("scanExecutor must alias AppIo.pipeline",
            Regex("""val\s+scanExecutor\s*=\s*AppIo\.pipeline""").containsMatchIn(text))
    }
}
