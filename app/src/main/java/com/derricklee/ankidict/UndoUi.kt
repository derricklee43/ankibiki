package com.derricklee.ankidict

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * Snackbar delivery for the undo stack (cross-view undo, user go 2026-11-15).
 *
 * One entry gets exactly one visible offer: [maybeShow] atomically announces the newest
 * unannounced memento before showing it, so a child screen that `finish()`es and ALL its
 * possible parents resuming behind it compete safely — whichever onResume runs first wins,
 * the others find nothing pending. Still-open screens call it right after their write;
 * parents call it from onResume.
 */
internal object UndoUi {

    fun maybeShow(host: View, repo: MiningRepository, reload: () -> Unit) {
        val entry = repo.latestUnannouncedUndo() ?: return
        repo.markUndoAnnounced(entry.id)
        show(host, entry.label, repo, reload)
    }

    private fun show(host: View, label: String, repo: MiningRepository, reload: () -> Unit) {
        Snackbar.make(host, label, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo_action_label) {
                AppIo.pipeline.execute {
                    val outcome = UndoManager.undoLatest(repo)
                    val activity = host.context as? Activity ?: return@execute
                    if (activity.isFinishing || activity.isDestroyed) return@execute
                    activity.runOnUiThread {
                        when (outcome) {
                            UndoOutcome.UNDONE -> reload()
                            UndoOutcome.GUARD_FAILED, UndoOutcome.CORRUPT ->
                                Snackbar.make(host, R.string.undo_guard_failed, Snackbar.LENGTH_LONG).show()
                            UndoOutcome.NOTHING_LEFT -> Unit
                        }
                    }
                }
            }
            .show()
    }
}
