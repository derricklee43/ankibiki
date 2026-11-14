package com.derricklee.ankidict

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Process-wide IO lanes replacing the 19 hand-rolled per-screen executors (roadmap D3,
 * user go 2026-11-14). Activities no longer own their work queues: destroying or recreating
 * a screen (rotation, backgrounding) must not cancel an in-flight scan, reprocess, or ruling,
 * and re-creating must not spawn a second queue racing the first.
 *
 * Lanes (same semantics the per-activity single-thread executors gave, centralized):
 * - [io]: short reads and quick writes — dashboards, lists, lookups, adjudication verdicts.
 * - [pipeline]: ONE serial lane for long-running corpus mutations (image rescan, text
 *   reprocess, photo scan, word sync). The single-thread invariant keeps exactly one
 *   pipeline run active app-wide, so two screens can never reprocess the same photo at once.
 * - [thumbnails]: bounded image decode pool (was newFixedThreadPool(3) in the adapter).
 *
 * Deliberately NOT coroutines: §5's acceptance text says prefer incremental changes over a
 * wholesale architecture migration, and this repo carries zero new runtime dependencies by
 * choice. java.util.concurrent only.
 */
object AppIo {

    private fun lane(core: Int, max: Int, keepAliveSeconds: Long, name: String): ExecutorService =
        ThreadPoolExecutor(
            core, max, keepAliveSeconds, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { r -> Thread(r, "ankibiki-$name").apply { isDaemon = false } },
        )

    val io: ExecutorService = lane(2, 4, 30, "io")

    val pipeline: ExecutorService = lane(1, 1, 0, "pipeline")

    val thumbnails: ExecutorService = lane(3, 3, 30, "thumb")
}
