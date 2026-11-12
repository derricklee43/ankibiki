// THROWAWAY SPIKE PROBE — spikes/001-anki-field-deck-probe.
// Copy to app/src/androidTest/java/com/derricklee/ankidict/AnkiContractProbeTest.kt,
// run with the Pixel attached (real AnkiDroid + collection), capture the log, then delete:
//   gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.derricklee.ankidict.AnkiContractProbeTest
//   adb logcat -s AnkiContractProbe
package com.derricklee.ankidict

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiContractProbeTest {
    private lateinit var context: Context
    private lateinit var api: AddContentApi

    private fun log(msg: String) { Log.i(TAG, msg) }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("AnkiDroid not installed", AddContentApi.getAnkiDroidPackageName(context) != null)
        api = AddContentApi(context)
    }

    // P1 — environment / provider spec version.
    @Test
    fun p1_environment() {
        val pkg = AddContentApi.getAnkiDroidPackageName(context)!!
        val version = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
        log("P1: AnkiDroid=$pkg version=$version apiSpec=${api.apiHostSpecVersion}")
    }

    // P2 — ALL decks with names AND counts (wizard screen 1 source). incl. empty ones.
    @Test
    fun p2_deckList() {
        val viaApi = runCatching { api.deckList }.toMutableResult()
        log("P2 api.getDeckList: ${viaApi.size} decks -> $viaApi")
        val rows = mutableListOf<Triple<Long, String, String>>()
        context.contentResolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI,
            arrayOf(
                FlashCardsContract.Deck.DECK_ID,
                FlashCardsContract.Deck.DECK_NAME,
                FlashCardsContract.Deck.DECK_COUNTS,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows += Triple(
                    c.getLong(0),
                    c.getString(1) ?: "?",
                    if (c.columnCount > 2) c.getString(2) ?: "?" else "?",
                )
            }
        }
        rows.forEach { (id, name, counts) -> log("P2 raw deck: did=$id name=$name counts=$counts") }
        assertTrue("no decks enumerated", rows.isNotEmpty())
        val empty = rows.filter { it.second != "deck" && it.third in setOf("0", "", "null") }
        log("P2: api=${viaApi.size} raw=${rows.size} agreement=${viaApi.keys == rows.map { it.first }.toSet()};" +
            " empty-deck candidates: ${empty.map { it.first to it.second }}")
    }

    // P3 — model enumeration: api.getModelList vs raw Model.CONTENT_URI.
    @Test
    fun p3_modelList() {
        val viaApi = runCatching { api.getModelList() }.getOrElse { emptyMap() }
        viaApi.forEach { (mid, name) -> log("P3 api model: mid=$mid name=$name") }
        var rawRows = 0
        context.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI,
            arrayOf(
                FlashCardsContract.Model._ID,
                FlashCardsContract.Model.NAME,
                FlashCardsContract.Model.FIELD_NAMES,
                FlashCardsContract.Model.NUM_CARDS,
                FlashCardsContract.Model.NOTE_COUNT,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rawRows++
                log("P3 raw model: mid=${c.getLong(0)} name=${c.getString(1)} " +
                    "fields=${c.getString(2)?.split(SEP)} numCards=${c.getOrNullString(3)} noteCount=${c.getOrNullString(4)}")
            }
        }
        log("P3: api models=${viaApi.size} raw rows=$rawRows")
        assertTrue("no models via either path", viaApi.isNotEmpty() || rawRows > 0)
    }

    // P4 — GRUDGE TEST: do FIELD_NAMES ordinals agree with our hardcoded indices?
    @Test
    fun p4_fieldNamesVsIndices() {
        for ((label, mid, expected) in listOf(
            Triple("NihongoShark", NIHONGO_SHARK_KANJI_MODEL_ID, 4 to "kanji"),
            Triple("ChineseWord", CHINESE_WORD_MODEL_ID, 0 to "Word"),
        )) {
            val viaApi = runCatching { api.getFieldList(mid)?.toList() }.getOrNull()
            val viaRaw = queryFieldNames(mid)
            val names = viaApi ?: viaRaw
            log("P4 $label: api=${viaApi} raw=${viaRaw}")
            if (names.isNullOrEmpty()) { log("P4 $label: NO NAMES for mid=$mid"); continue }
            val (ord, want) = expected
            val at = names.getOrNull(ord)?.trim()?.lowercase()
            log("P4 $label: index[$ord]='$at' expected~'$want' -> ${if (at == want.lowercase()) "MATCH" else "MISMATCH"}")
        }
        queryFieldNames(CHINESE_MNEMONICS_MODEL_ID)?.let {
            log("P4 ChineseMnemonics names=$it (assumed char field idx2='${it.getOrNull(2)}')")
        }
    }

    // P5 — names zip 1:1 with note FLDS values (label -> value wiring).
    @Test
    fun p5_nameValueZip() {
        val notes = AnkiRepository(context).queryAllNotes(NIHONGO_SHARK_KANJI_MODEL_ID).take(3)
        val names = (runCatching { api.getFieldList(NIHONGO_SHARK_KANJI_MODEL_ID)?.toList() }.getOrNull()
            ?: queryFieldNames(NIHONGO_SHARK_KANJI_MODEL_ID))
            ?: return log("P5: skipped (no names)")
        for (note in notes) {
            log("P5 zip: fields=${note.fields.size} names=${names.size} match=${note.fields.size == names.size}")
            names.zip(note.fields).forEach { (n, v) ->
                log("P5   [$n] = ${v.take(60).replace(Regex("\\s+"), " ")}")
            }
        }
        assertTrue("no notes to zip against", notes.isNotEmpty())
    }

    // P6 — /models/<id>/fields per-field rows (FIELD_NAME column present in the pinned contract).
    @Test
    fun p6_fieldsPath() {
        val uri = FlashCardsContract.Model.CONTENT_URI.buildUpon()
            .appendPath(NIHONGO_SHARK_KANJI_MODEL_ID.toString())
            .appendPath("fields").build()
        val result = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add((0 until c.columnCount).joinToString("|") { i ->
                            "${c.getColumnName(i)}=${c.getString(i)?.take(30)}"
                        })
                    }
                }
            } ?: emptyList()
        }
        if (result.isFailure) log("P6: /fields unsupported (${result.exceptionOrNull()?.message})")
        else log("P6: /fields rows=${result.getOrNull()?.size}; sample=${result.getOrNull()?.take(3)}")
    }

    // P7 — full write round-trip in a scratch sandbox (deck+model created; note deleted after).
    @Test
    fun p7_createRoundTrip() {
        val ts = System.currentTimeMillis()
        val deckId = api.addNewDeck("AnkiBiki spike $ts")
        val mid = api.addNewBasicModel("AnkiBikiSpikeModel$ts")
        log("P7: created deck=$deckId model=$mid")
        assumeTrue("deck/model creation not permitted", deckId != null && mid != null)
        val names = runCatching { api.getFieldList(mid!!)?.toList() }.getOrNull() ?: queryFieldNames(mid)
        log("P7: new model field names=$names")
        assertTrue("new model field names must be readable", !names.isNullOrEmpty())
        val noteId = api.addNote(mid, deckId!!, names!!.map { "spike value: $it" }.toTypedArray(), setOf("ankibiki_spike"))
        log("P7: addNote -> noteId=$noteId")
        assumeTrue("addNote failed", noteId != null && noteId > 0)
        val note = runCatching { api.getNote(noteId!!) }.getOrNull()
        log("P7 readback: id=${note?.id} fields=${note?.fields?.toList()} (must align with names=$names)")
        val deleted = runCatching {
            context.contentResolver.delete(
                FlashCardsContract.Note.CONTENT_URI.buildUpon().appendPath(noteId.toString()).build(),
                null, null,
            )
        }.getOrDefault(0)
        log("P7: note deleted rows=$deleted; noteCount(mid)=${runCatching { api.getNoteCount(mid) }.getOrNull()}. " +
            "Scratch deck=$deckId model=$mid left behind (clean by hand in Anki).")
    }

    private fun queryFieldNames(mid: Long): List<String>? {
        val uri = FlashCardsContract.Model.CONTENT_URI.buildUpon().appendPath(mid.toString()).build()
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES)
            if (col < 0 || !c.moveToNext()) return null
            c.getString(col)?.split(SEP)?.takeIf { it.isNotEmpty() && it.any { n -> n.isNotBlank() } }
        }
    }

    private fun <K, V> Result<Map<K, V>?>.toMutableResult(): MutableMap<K, V> =
        (getOrNull() ?: emptyMap()).toMutableMap()

    private fun android.database.Cursor.getOrNullString(i: Int): String? =
        if (i < columnCount) getString(i) else null

    companion object {
        private const val TAG = "AnkiContractProbe"
        private const val SEP = "\u001F"
    }
}
