package com.derricklee.ankidict

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * D3 lifecycle invariants (roadmap §5, user go 2026-11-14): work runs on process-wide lanes
 * ([AppIo]), NOT per-screen queues, so an activity teardown can no longer cancel in-flight
 * work (the old shutdownNow-on-destroy behavior) and no recreation can spawn a rival queue.
 * These three specs pin the invariants; the live rotation walk + the Pixel's real Chrome/
 * AnkiDroid versions complete the on-device evidence at the next device session.
 */
@RunWith(AndroidJUnit4::class)
class AppIoLifecycleTest {

    @Test
    fun workSurvivesActivityDestroy() {
        // Simulates rotation/teardown mid-work: submit a job, destroy the visible activity,
        // the job must still run — exactly once. Under the old per-activity executors the
        // shutdownNow in onDestroy cancelled whatever was queued behind the current task.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val runs = AtomicInteger(0)

        val scenario = ActivityScenario.launch(PhotoLibraryActivity::class.java)
        AppIo.io.execute {
            started.countDown()
            Thread.sleep(1_200)          // stay in-flight across the destroy
            runs.incrementAndGet()
            finished.countDown()
        }
        assertTrue("job never started", started.await(5, TimeUnit.SECONDS))
        scenario.close()                  // destroy the screen while the job is mid-flight
        assertTrue("in-flight work was cancelled by activity teardown!", finished.await(10, TimeUnit.SECONDS))
        assertEquals("no duplicate execution", 1, runs.get())
    }

    @Test
    fun pipelineLaneRunsOneAtATime() {
        // The scan/rescan/reprocess invariant: ONE pipeline run app-wide. Six jobs that would
        // overlap if the lane were a pool must strictly serialize.
        val overlap = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val all = CountDownLatch(6)
        repeat(6) {
            AppIo.pipeline.execute {
                val now = overlap.incrementAndGet()
                maxOverlap.updateAndGet { maxOf(maxOf(now, it), it) }
                // hold the lane briefly so a sibling could interleave if one existed
                try { Thread.sleep(150) } finally {
                    overlap.decrementAndGet()
                    all.countDown()
                }
            }
        }
        assertTrue("pipeline jobs stalled", all.await(20, TimeUnit.SECONDS))
        assertEquals("pipeline lane must serialize corpus mutations", 1, maxOverlap.get())
    }

    @Test
    fun ioPoolServesTwoReadersConcurrently() {
        // Reads are NOT serialized behind the single pipeline lane: two concurrent readers must
        // make progress simultaneously (AppIo.io core size is 2 — the test pins that contract).
        val both = CountDownLatch(2)
        val done = CountDownLatch(2)
        repeat(2) {
            AppIo.io.execute {
                both.countDown()
                try { Thread.sleep(200) } finally { done.countDown() }
            }
        }
        assertTrue("io lane serialized two readers", both.await(3, TimeUnit.SECONDS))
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }
}
