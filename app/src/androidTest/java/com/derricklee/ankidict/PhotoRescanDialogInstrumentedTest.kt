package com.derricklee.ankidict

import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated dialog tests: no activity launch, photos, corpus, OCR, or database access. */
@RunWith(AndroidJUnit4::class)
class PhotoRescanDialogInstrumentedTest {
    @Test
    fun decisions_areDeliveredExactlyOnce_byActualMaterialButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_AnkiDict)
            for ((id, expected) in listOf(
                R.id.acceptRescanButton to PhotoRescanDecision.ACCEPT,
                R.id.skipExtractionButton to PhotoRescanDecision.SKIP_TEXT_EXTRACTION,
                R.id.cancelRescanButton to PhotoRescanDecision.CANCEL,
            )) {
                val decisions = mutableListOf<PhotoRescanDecision>()
                val dialog = PhotoRescanDialog.create(context) { decisions.add(it) }
                dialog.create()
                val button = dialog.findViewById<MaterialButton>(id)!!
                assertTrue(button.performClick())
                button.performClick()
                assertEquals(listOf(expected), decisions)
                dialog.dismiss()
            }
        }
    }

    @Test
    fun backOrOutsideCancellation_deliversCancelExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decisions = mutableListOf<PhotoRescanDecision>()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_AnkiDict)
            val dialog = PhotoRescanDialog.create(context) { decisions.add(it) }
            dialog.create()
            dialog.cancel()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(PhotoRescanDecision.CANCEL), decisions)
    }

    @Test
    fun acceptIsPrimary_skipOutlined_cancelText_andWarningUsesThemeError() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_AnkiDict)
            val dialog = PhotoRescanDialog.create(context) { }
            dialog.create()
            val accept = dialog.findViewById<MaterialButton>(R.id.acceptRescanButton)!!
            val skip = dialog.findViewById<MaterialButton>(R.id.skipExtractionButton)!!
            val cancel = dialog.findViewById<MaterialButton>(R.id.cancelRescanButton)!!
            val warning = dialog.findViewById<TextView>(R.id.rescanWarningText)!!
            assertEquals(context.getString(R.string.btn_accept_rescan), accept.text.toString())
            assertEquals(MaterialColors.getColor(accept, com.google.android.material.R.attr.colorPrimary),
                accept.backgroundTintList!!.getColorForState(accept.drawableState, 0))
            assertTrue(skip.strokeWidth > 0)
            assertNotEquals(MaterialColors.getColor(skip, com.google.android.material.R.attr.colorError),
                skip.currentTextColor)
            assertEquals(0, cancel.strokeWidth)
            assertEquals(MaterialColors.getColor(warning, com.google.android.material.R.attr.colorError),
                warning.currentTextColor)
            dialog.dismiss()
        }
    }
}
