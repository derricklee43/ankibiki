package com.derricklee.ankidict

import org.json.JSONObject

data class RadicalEntry(
    val character: String,
    val heisigKeyword: String,
    val radicalMeaning: String,
    val strokeCount: Int? = null,
    val variants: List<String> = emptyList(),
)

data class DecompositionNode(
    val character: String,
    val operator: String?,
    val meaning: String?,
    val isHeisigPrimitive: Boolean,
    val children: List<DecompositionNode>,
)

class HeisigRadicalRepository(
    private val idsRepository: IdsRepository,
    private val dictionaryRepository: DictionaryRepository? = null,
    private val ankiRepository: AnkiRepository? = null,
) {

    @Volatile
    private var ankiKeywordsByChar: Map<String, String>? = null

    fun lookupRadical(character: String): RadicalEntry? {
        val builtIn = BUILT_IN_PRIMITIVES[character]
        val ankiKeyword = resolveAnkiHeisigKeyword(character)
        val kanjiEntry = dictionaryRepository
            ?.lookupExact(character, setOf(SearchSource.DICT_KANJI))
            ?.filterIsInstance<DictionaryEntry.Kanji>()
            ?.firstOrNull()
        val cedictEntry = dictionaryRepository
            ?.lookupChineseWords(character)
            ?.firstOrNull()

        if (builtIn != null) {
            return if (ankiKeyword != null && ankiKeyword != builtIn.heisigKeyword) {
                builtIn.copy(
                    heisigKeyword = "$ankiKeyword / ${builtIn.heisigKeyword}",
                    strokeCount = builtIn.strokeCount ?: kanjiEntry?.strokeCount,
                )
            } else {
                builtIn.copy(strokeCount = builtIn.strokeCount ?: kanjiEntry?.strokeCount)
            }
        }

        if (ankiKeyword != null) {
            return RadicalEntry(
                character = character,
                heisigKeyword = ankiKeyword,
                radicalMeaning = kanjiEntry?.meanings?.ifBlank { null }
                    ?: cedictEntry?.definitions.orEmpty(),
                strokeCount = kanjiEntry?.strokeCount,
            )
        }

        val treeJson = idsRepository.constituentsTreeJson(character)
        if (kanjiEntry != null || cedictEntry != null || treeJson.isNotBlank()) {
            val primaryMeaning = kanjiEntry?.meanings?.takeIf { it.isNotBlank() }
                ?: cedictEntry?.definitions?.takeIf { it.isNotBlank() }
                ?: "CJK component / radical"
            val shortKeyword = primaryMeaning.substringBefore(";").substringBefore("/").trim()
            return RadicalEntry(
                character = character,
                heisigKeyword = shortKeyword,
                radicalMeaning = primaryMeaning,
                strokeCount = kanjiEntry?.strokeCount,
            )
        }

        return null
    }

    /**
     * Resolves a concise Heisig keyword or radical label for a node in a character decomposition tree.
     */
    fun resolveComponentLabel(character: String): Pair<String?, Boolean> {
        val builtIn = BUILT_IN_PRIMITIVES[character]
        if (builtIn != null) {
            return builtIn.heisigKeyword to true
        }
        val ankiKeyword = resolveAnkiHeisigKeyword(character)
        if (!ankiKeyword.isNullOrBlank()) {
            return ankiKeyword to true
        }
        val kanjiEntry = dictionaryRepository
            ?.lookupExact(character, setOf(SearchSource.DICT_KANJI))
            ?.filterIsInstance<DictionaryEntry.Kanji>()
            ?.firstOrNull()
        if (kanjiEntry != null && kanjiEntry.meanings.isNotBlank()) {
            return kanjiEntry.meanings.substringBefore(";").trim() to false
        }
        val cedict = dictionaryRepository?.lookupChineseWords(character)?.firstOrNull()
        if (cedict != null && cedict.definitions.isNotBlank()) {
            return cedict.definitions.substringBefore(";").trim() to false
        }
        return null to false
    }

    /**
     * Builds the recursive [DecompositionNode] tree for [character] from `ids.db`, annotated with
     * Heisig primitive keywords and dictionary meanings at every node.
     */
    fun buildDecompositionTree(character: String): DecompositionNode? {
        if (character.isBlank()) return null
        val rawJson = idsRepository.constituentsTreeJson(character)
        if (rawJson.isBlank()) {
            val (meaning, isHeisig) = resolveComponentLabel(character)
            return DecompositionNode(
                character = character,
                operator = null,
                meaning = meaning,
                isHeisigPrimitive = isHeisig,
                children = emptyList(),
            )
        }
        return runCatching {
            val rootObj = JSONObject(rawJson)
            parseNode(rootObj)
        }.getOrNull()
    }

    /**
     * Builds decomposition trees for all CJK ideographs inside [wordOrExpression] (in order of
     * appearance, deduplicated). Returns an empty list if [wordOrExpression] is pure kana.
     */
    fun buildWordDecompositionTrees(wordOrExpression: String): List<DecompositionNode> {
        val seen = LinkedHashSet<String>()
        val trees = mutableListOf<DecompositionNode>()
        for (ch in wordOrExpression) {
            if (!MlKitImageTagger.isCjkIdeograph(ch) || ch == '々') continue
            val charStr = ch.toString()
            if (seen.add(charStr)) {
                buildDecompositionTree(charStr)?.let { trees.add(it) }
            }
        }
        return trees
    }

    private fun parseNode(obj: JSONObject): DecompositionNode {
        val rawChar = if (obj.isNull("char")) "" else obj.optString("char", "")
        val rawOp = if (obj.isNull("op")) null else obj.optString("op", "").takeIf { it.isNotBlank() }
        val childrenArr = obj.optJSONArray("children")
        val rawChildren = mutableListOf<DecompositionNode>()
        if (childrenArr != null) {
            for (i in 0 until childrenArr.length()) {
                val childObj = childrenArr.optJSONObject(i) ?: continue
                rawChildren.add(parseNode(childObj))
            }
        }

        // Flatten anonymous operator-only intermediate nodes (where char is blank/null) so every
        // visible tree node is a clickable character/radical!
        val flattenedChildren = rawChildren.flatMap { child ->
            if (child.character.isBlank() && child.children.isNotEmpty()) {
                child.children
            } else {
                listOf(child)
            }
        }

        val (meaning, isHeisig) = if (rawChar.isNotBlank()) {
            resolveComponentLabel(rawChar)
        } else {
            null to false
        }

        return DecompositionNode(
            character = rawChar,
            operator = rawOp,
            meaning = meaning,
            isHeisigPrimitive = isHeisig,
            children = flattenedChildren,
        )
    }

    private fun resolveAnkiHeisigKeyword(character: String): String? {
        val cached = ankiKeywordsByChar
        if (cached != null) return cached[character]

        val repo = ankiRepository ?: return null
        if (!repo.isAnkiDroidAvailable()) return null

        val loaded = runCatching {
            val map = HashMap<String, String>()
            val jpNotes = repo.queryAllNotes(NIHONGO_SHARK_KANJI_MODEL_ID)
            val jpCharIdx = CHARACTER_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)
            val jpMeanIdx = MEANING_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID)
            for (note in jpNotes) {
                val ch = note.fields.getOrNull(jpCharIdx)?.trim().orEmpty()
                val kw = note.fields.getOrNull(jpMeanIdx)?.trim().orEmpty()
                if (ch.isNotEmpty() && kw.isNotEmpty()) {
                    map[ch] = kw
                }
            }
            val zhNotes = repo.queryAllNotes(CHINESE_MNEMONICS_MODEL_ID)
            val zhCharIdx = CHARACTER_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)
            val zhMeanIdx = MEANING_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID)
            for (note in zhNotes) {
                val ch = note.fields.getOrNull(zhCharIdx)?.trim().orEmpty()
                val kw = note.fields.getOrNull(zhMeanIdx)?.trim().orEmpty()
                if (ch.isNotEmpty() && kw.isNotEmpty() && ch !in map) {
                    map[ch] = kw
                }
            }
            map
        }.getOrNull()

        if (loaded != null && loaded.isNotEmpty()) {
            ankiKeywordsByChar = loaded
            return loaded[character]
        }
        return null
    }

    companion object {
        private fun p(
            char: String,
            heisig: String,
            meaning: String,
            strokes: Int? = null,
            variants: List<String> = emptyList(),
        ): Pair<String, RadicalEntry> = char to RadicalEntry(
            character = char,
            heisigKeyword = heisig,
            radicalMeaning = meaning,
            strokeCount = strokes,
            variants = variants,
        )

        /**
         * Curated Heisig RTK / RSH primitive & Kangxi radical dictionary (including non-Joyo
         * intermediate primitives like 冓, 𠀎, 冉, 夂, etc.).
         */
        val BUILT_IN_PRIMITIVES: Map<String, RadicalEntry> = mapOf(
            // Non-Joyo / Bound Heisig Primitives frequently appearing in IDS decompositions
            p("冓", "framework / lattice", "Heisig primitive: framework, lattice, put together (used in 構, 講, 購, 溝)", 10),
            p("𠀎", "well-frame / salad", "Heisig primitive: top framework of 冓 (well 井 + one 一)", 5),
            p("冉", "tender / fragile", "Heisig primitive: tender, inner frame (bottom of 冓, 那)", 5),
            p("処", "dispose / place", "Heisig RTK: dispose, deal with (walking legs 夂 + wind 几)", 5),
            p("夂", "walking legs", "Heisig primitive: walking legs (Kangxi radical 34)", 3),
            p("夊", "walking legs (slowly)", "Heisig primitive: walking legs dragging (Kangxi radical 35)", 3),
            p("几", "wind / table", "Heisig primitive: wind / small table (Kangxi radical 16)", 2),
            p("扌", "fingers", "Heisig primitive: fingers / hand on left (variant of 手, radical 64)", 3, listOf("手")),
            p("氵", "water / water droplets", "Heisig primitive: water droplets (3-stroke left variant of 水, radical 85)", 3, listOf("水")),
            p("忄", "state of mind", "Heisig primitive: state of mind (vertical heart variant of 心, radical 61)", 3, listOf("心")),
            p("⺗", "heart (bottom)", "Heisig primitive: Valentine heart at bottom (variant of 心, radical 61)", 4, listOf("心")),
            p("艹", "flowers", "Heisig primitive: flowers / grass crown (variant of 艸, radical 140)", 3, listOf("艸")),
            p("辶", "road", "Heisig primitive: road / walk enclosure (variant of 辵, radical 162)", 3, listOf("辵", "⻌")),
            p("⻌", "road", "Heisig primitive: road / walk enclosure (variant of 辵, radical 162)", 3, listOf("辶")),
            p("阝", "pinnacle / city walls", "Heisig primitive: pinnacle (left, 阜) or city walls (right, 邑)", 3, listOf("阜", "邑")),
            p("礻", "altar", "Heisig primitive: altar / spirit (left variant of 示, radical 113)", 4, listOf("示")),
            p("衤", "cloak", "Heisig primitive: cloak / garment (left variant of 衣, radical 145)", 5, listOf("衣")),
            p("钅", "metal (simplified)", "Heisig RSH primitive: metal / gold (left variant of 金, radical 167)", 5, listOf("金")),
            p("纟", "thread (simplified)", "Heisig RSH primitive: thread / silk (left variant of 糸, radical 120)", 3, listOf("糸")),
            p("讠", "words / say (simplified)", "Heisig RSH primitive: words / speech (left variant of 言, radical 149)", 2, listOf("言")),
            p("饣", "food (simplified)", "Heisig RSH primitive: food / eat (left variant of 食, radical 184)", 3, listOf("食", "飠")),
            p("飠", "food", "Heisig primitive: food / eat (left variant of 食, radical 184)", 8, listOf("食")),
            p("宀", "house", "Heisig primitive: house / crowned roof (Kangxi radical 40)", 3),
            p("冖", "crown", "Heisig primitive: crown / wa-crown (Kangxi radical 14)", 2),
            p("广", "cave", "Heisig primitive: cave / dotted cliff (Kangxi radical 53)", 3),
            p("厂", "cliff", "Heisig primitive: cliff (Kangxi radical 27)", 2),
            p("尸", "flag", "Heisig primitive: flag / corpse (Kangxi radical 44)", 3),
            p("彳", "going / column", "Heisig primitive: going / step / column (Kangxi radical 60)", 3),
            p("彡", "hair / shape", "Heisig primitive: hair / bristle / mystical (Kangxi radical 59)", 3),
            p("攵", "taskmaster", "Heisig primitive: taskmaster / director (variant of 攴, radical 66)", 4, listOf("攴")),
            p("灬", "oven fire", "Heisig primitive: oven fire / fire dots (bottom variant of 火, radical 86)", 4, listOf("火")),
            p("爫", "claw / vulture", "Heisig primitive: claw / vulture (top variant of 爪, radical 87)", 4, listOf("爪")),
            p("疒", "sickness", "Heisig primitive: sickness / hospital bed (Kangxi radical 104)", 5),
            p("癶", "footsteps", "Heisig primitive: footsteps / dotted tent (Kangxi radical 105)", 5),
            p("⺮", "bamboo", "Heisig primitive: bamboo crown (top variant of 竹, radical 118)", 6, listOf("竹")),
            p("罒", "net", "Heisig primitive: net / eye on its side (top variant of 网, radical 122)", 5, listOf("网")),
            p("耂", "old man", "Heisig primitive: old man / caveman (top variant of 老, radical 125)", 4, listOf("老")),
            p("⺼", "flesh / part of the body", "Heisig primitive: flesh / part of the body (variant of 肉, radical 130)", 4, listOf("肉", "月")),
            p("辶", "road", "Heisig primitive: road / movement (Kangxi radical 162)", 3),
            p("廴", "stretch", "Heisig primitive: stretch / yoga (Kangxi radical 54)", 3),
            p("廾", "twenty / two hands", "Heisig primitive: twenty / two hands joined (Kangxi radical 55)", 3),
            p("弋", "arrow / ceremony", "Heisig primitive: arrow / quiver (Kangxi radical 56)", 3),
            p("弓", "bow", "Heisig RTK: bow (Kangxi radical 57)", 3),
            p("彐", "pig's head / broom", "Heisig primitive: pig's head / broom head (Kangxi radical 58)", 3, listOf("彑")),
            p("囗", "pent in", "Heisig primitive: pent in / enclosure (Kangxi radical 31)", 3),
            p("凵", "shovel / open box", "Heisig primitive: shovel / receptacle (Kangxi radical 17)", 2),
            p("匚", "box", "Heisig primitive: box on its side (Kangxi radical 22)", 2),
            p("冂", "hood / glass canopy", "Heisig primitive: hood / upside-down box (Kangxi radical 13)", 2),
            p("冫", "ice", "Heisig primitive: ice / two drops (Kangxi radical 15)", 2),
            p("刂", "saber", "Heisig primitive: saber / standing knife (right variant of 刀, radical 18)", 2, listOf("刀")),
            p("刀", "sword", "Heisig RTK: sword / katana (Kangxi radical 18)", 2, listOf("刂")),
            p("力", "power / muscle", "Heisig RTK: power / muscle (Kangxi radical 19)", 2),
            p("勹", "bound up", "Heisig primitive: bound up / wrapping (Kangxi radical 20)", 2),
            p("匕", "spoon", "Heisig RTK: spoon / sitting person (Kangxi radical 21)", 2),
            p("十", "ten / needle", "Heisig RTK: ten / needle (Kangxi radical 24)", 2),
            p("卜", "divining rod", "Heisig RTK: divining rod / magic wand (Kangxi radical 25)", 2),
            p("卩", "stamp", "Heisig primitive: stamp / seal (Kangxi radical 26)", 2, listOf("㔾")),
            p("厶", "elbow", "Heisig primitive: elbow / muzzle (Kangxi radical 28)", 2),
            p("又", "again / crotch", "Heisig RTK: again / right hand (Kangxi radical 29)", 2),
            p("口", "mouth", "Heisig RTK: mouth (Kangxi radical 30)", 3),
            p("土", "soil / ground", "Heisig RTK: soil / dirt / ground (Kangxi radical 32)", 3),
            p("士", "gentleman / samurai", "Heisig RTK: gentleman / samurai (Kangxi radical 33)", 3),
            p("夕", "evening", "Heisig RTK: evening (Kangxi radical 36)", 3),
            p("大", "large / St. Bernard", "Heisig RTK: large / St. Bernard dog (Kangxi radical 37)", 3),
            p("女", "woman", "Heisig RTK: woman (Kangxi radical 38)", 3),
            p("子", "child", "Heisig RTK: child (Kangxi radical 39)", 3),
            p("寸", "measurement / glue", "Heisig RTK: measurement / glue (Kangxi radical 41)", 3),
            p("小", "little", "Heisig RTK: little / small (Kangxi radical 42)", 3),
            p("山", "mountain", "Heisig RTK: mountain (Kangxi radical 46)", 3),
            p("川", "stream / river", "Heisig RTK: stream / flood (Kangxi radical 47)", 3, listOf("巛")),
            p("工", "craft / I-beam", "Heisig RTK: craft / work (Kangxi radical 48)", 3),
            p("己", "self / snake", "Heisig RTK: self / snake (Kangxi radical 49)", 3),
            p("巾", "towel", "Heisig RTK: towel / cloth (Kangxi radical 50)", 3),
            p("干", "dry / clothesline", "Heisig RTK: dry (Kangxi radical 51)", 3),
            p("幺", "short thread / cocoon", "Heisig primitive: short thread / cocoon (Kangxi radical 52)", 3),
            p("心", "heart", "Heisig RTK: heart / mind (Kangxi radical 61)", 4, listOf("忄")),
            p("戈", "fiesta / halberd", "Heisig primitive: fiesta / tassled spear (Kangxi radical 62)", 4),
            p("戸", "door", "Heisig RTK: door (Kangxi radical 63)", 4, listOf("户")),
            p("手", "hand", "Heisig RTK: hand (Kangxi radical 64)", 4, listOf("扌")),
            p("支", "branch", "Heisig RTK: branch (Kangxi radical 65)", 4),
            p("文", "literature / plaid", "Heisig RTK: literature / writing (Kangxi radical 67)", 4),
            p("斗", "Big Dipper / measuring cup", "Heisig RTK: Big Dipper (Kangxi radical 68)", 4),
            p("斤", "axe", "Heisig RTK: axe (Kangxi radical 69)", 4),
            p("方", "direction / compass", "Heisig RTK: direction / compass (Kangxi radical 70)", 4),
            p("日", "day / sun", "Heisig RTK: day / sun (Kangxi radical 72)", 4),
            p("月", "month / moon / flesh", "Heisig RTK: month / moon / part of the body (Kangxi radical 74)", 4),
            p("木", "tree / wood", "Heisig RTK: tree / wood (Kangxi radical 75)", 4),
            p("欠", "lack / yawn", "Heisig RTK: lack / yawn (Kangxi radical 76)", 4),
            p("止", "stop / footprint", "Heisig RTK: stop / footprint (Kangxi radical 77)", 4),
            p("歹", "bones / death", "Heisig primitive: bones / bare bones (Kangxi radical 78)", 4),
            p("殳", "missile / cruise missile", "Heisig primitive: missile / weapon (Kangxi radical 79)", 4),
            p("比", "compare / spoons", "Heisig RTK: compare (Kangxi radical 81)", 4),
            p("毛", "fur / hair", "Heisig RTK: fur / hair (Kangxi radical 82)", 4),
            p("氏", "family name", "Heisig RTK: family name / surname (Kangxi radical 83)", 4),
            p("水", "water", "Heisig RTK: water (Kangxi radical 85)", 4, listOf("氵")),
            p("火", "fire", "Heisig RTK: fire (Kangxi radical 86)", 4, listOf("灬")),
            p("父", "father", "Heisig RTK: father (Kangxi radical 88)", 4),
            p("牛", "cow", "Heisig RTK: cow (Kangxi radical 93)", 4, listOf("牜")),
            p("犬", "dog", "Heisig RTK: dog / Chihuahua (Kangxi radical 94)", 4, listOf("犭")),
            p("犭", "pack of wild dogs", "Heisig primitive: pack of wild dogs (left variant of 犬, radical 94)", 3, listOf("犬")),
            p("王", "king / ball", "Heisig RTK: king / jewel (variant of 玉, radical 96)", 4, listOf("玉")),
            p("玉", "jewel", "Heisig RTK: jewel / ball (Kangxi radical 96)", 5, listOf("王")),
            p("田", "rice field / brains", "Heisig RTK: rice field / brains (Kangxi radical 102)", 5),
            p("白", "white / dove", "Heisig RTK: white / white bird (Kangxi radical 106)", 5),
            p("皿", "dish", "Heisig RTK: dish / plate (Kangxi radical 108)", 5),
            p("目", "eye", "Heisig RTK: eye (Kangxi radical 109)", 5),
            p("矢", "dart / arrow", "Heisig RTK: dart / arrow (Kangxi radical 111)", 5),
            p("石", "stone / rock", "Heisig RTK: stone (Kangxi radical 112)", 5),
            p("示", "show / altar", "Heisig RTK: show / altar (Kangxi radical 113)", 5, listOf("礻")),
            p("禾", "two-branch tree / wheat", "Heisig primitive: two-branch tree / grain (Kangxi radical 115)", 5),
            p("穴", "hole", "Heisig RTK: hole / cave (Kangxi radical 116)", 5),
            p("立", "stand up / vase", "Heisig RTK: stand up / vase (Kangxi radical 117)", 5),
            p("竹", "bamboo", "Heisig RTK: bamboo (Kangxi radical 118)", 6, listOf("⺮")),
            p("米", "rice", "Heisig RTK: rice (Kangxi radical 119)", 6),
            p("糸", "thread", "Heisig RTK: thread / spiderman (Kangxi radical 120)", 6, listOf("纟")),
            p("羊", "sheep", "Heisig RTK: sheep (Kangxi radical 123)", 6),
            p("羽", "feathers / wings", "Heisig RTK: feathers / wings (Kangxi radical 124)", 6),
            p("耳", "ear", "Heisig RTK: ear (Kangxi radical 128)", 6),
            p("肉", "meat", "Heisig RTK: meat / flesh (Kangxi radical 130)", 6, listOf("月")),
            p("臣", "retainer / slave", "Heisig RTK: retainer / minister (Kangxi radical 131)", 7),
            p("自", "oneself / nose", "Heisig RTK: oneself / nose (Kangxi radical 132)", 6),
            p("至", "climax / mole", "Heisig RTK: climax / arrive (Kangxi radical 133)", 6),
            p("舌", "tongue", "Heisig RTK: tongue (Kangxi radical 135)", 6),
            p("舟", "boat", "Heisig RTK: boat (Kangxi radical 137)", 6),
            p("艮", "silver / stopping", "Heisig primitive: silver / tough (Kangxi radical 138)", 6),
            p("虫", "insect", "Heisig RTK: insect (Kangxi radical 142)", 6),
            p("血", "blood", "Heisig RTK: blood (Kangxi radical 143)", 6),
            p("行", "going / line", "Heisig RTK: going / walk (Kangxi radical 144)", 6),
            p("衣", "garment / kimono", "Heisig RTK: garment / clothes (Kangxi radical 145)", 6, listOf("衤")),
            p("西", "west / Old West", "Heisig RTK: west (Kangxi radical 146)", 6, listOf("覀")),
            p("覀", "Old West", "Heisig primitive: Old West cover (top variant of 西, radical 146)", 6, listOf("西")),
            p("見", "see", "Heisig RTK: see (Kangxi radical 147)", 7, listOf("见")),
            p("角", "angle / horns", "Heisig RTK: angle / horn (Kangxi radical 148)", 7),
            p("言", "say / words", "Heisig RTK: say / words (Kangxi radical 149)", 7, listOf("讠")),
            p("谷", "valley", "Heisig RTK: valley (Kangxi radical 150)", 7),
            p("豆", "beans / table", "Heisig RTK: beans (Kangxi radical 151)", 7),
            p("豕", "hog / pig", "Heisig primitive: hog / pig (Kangxi radical 152)", 7),
            p("貝", "shellfish / clam", "Heisig RTK: shellfish / money (Kangxi radical 154)", 7, listOf("贝")),
            p("走", "run", "Heisig RTK: run (Kangxi radical 156)", 7),
            p("足", "leg / wooden leg", "Heisig RTK: leg / foot (Kangxi radical 157)", 7),
            p("身", "somebody / body", "Heisig RTK: somebody / body (Kangxi radical 158)", 7),
            p("車", "car / wagon", "Heisig RTK: car / cart (Kangxi radical 159)", 7, listOf("车")),
            p("辛", "spicy / needle", "Heisig RTK: spicy / bitter (Kangxi radical 160)", 7),
            p("酉", "whiskey bottle", "Heisig primitive: whiskey bottle / sign of the bird (Kangxi radical 164)", 7),
            p("里", "ri / computer", "Heisig RTK: ri / village (Kangxi radical 166)", 7),
            p("金", "gold / metal", "Heisig RTK: gold / metal (Kangxi radical 167)", 8, listOf("钅")),
            p("長", "long", "Heisig RTK: long / leader (Kangxi radical 168)", 8, listOf("长")),
            p("門", "gates / saloon doors", "Heisig RTK: gates (Kangxi radical 169)", 8, listOf("门")),
            p("隹", "turkey", "Heisig primitive: turkey / small bird (Kangxi radical 172)", 8),
            p("雨", "rain / weather", "Heisig RTK: rain (Kangxi radical 173)", 8),
            p("青", "blue", "Heisig RTK: blue / green (Kangxi radical 174)", 8),
            p("非", "un- / jailcell", "Heisig RTK: un- / mistake (Kangxi radical 175)", 8),
            p("面", "mask / face", "Heisig RTK: mask / face (Kangxi radical 176)", 9),
            p("革", "leather", "Heisig RTK: leather (Kangxi radical 177)", 9),
            p("音", "sound", "Heisig RTK: sound (Kangxi radical 180)", 9),
            p("頁", "page / head", "Heisig RTK: page / head (Kangxi radical 181)", 9, listOf("页")),
            p("風", "wind", "Heisig RTK: wind (Kangxi radical 182)", 9, listOf("风")),
            p("食", "eat / food", "Heisig RTK: eat / food (Kangxi radical 184)", 9, listOf("飠", "饣")),
            p("首", "neck", "Heisig RTK: neck (Kangxi radical 185)", 9),
            p("馬", "horse / team of horses", "Heisig RTK: horse (Kangxi radical 187)", 10, listOf("马")),
            p("骨", "skeleton / bone", "Heisig RTK: skeleton / bone (Kangxi radical 188)", 10),
            p("高", "tall", "Heisig RTK: tall / high (Kangxi radical 189)", 10),
            p("鬼", "ghost", "Heisig RTK: ghost / demon (Kangxi radical 194)", 10),
            p("魚", "fish", "Heisig RTK: fish (Kangxi radical 195)", 11, listOf("鱼")),
            p("鳥", "bird", "Heisig RTK: bird (Kangxi radical 196)", 11, listOf("鸟")),
            p("黒", "black", "Heisig RTK: black (Kangxi radical 203)", 11, listOf("黑")),
            p("一", "one / floor / ceiling", "Heisig RTK: one (Kangxi radical 1)", 1),
            p("丨", "walking stick", "Heisig primitive: walking stick / vertical stroke (Kangxi radical 2)", 1),
            p("丶", "drop of", "Heisig primitive: drop of (Kangxi radical 3)", 1),
            p("丿", "slash", "Heisig primitive: slash / bend (Kangxi radical 4)", 1),
            p("乙", "fishhook", "Heisig RTK: fishhook / second (Kangxi radical 5)", 1),
            p("亅", "barb", "Heisig primitive: hooked vertical stroke (Kangxi radical 6)", 1),
            p("二", "two", "Heisig RTK: two (Kangxi radical 7)", 2),
            p("亠", "top hat", "Heisig primitive: top hat / lid (Kangxi radical 8)", 2),
            p("人", "person", "Heisig RTK: person (Kangxi radical 9)", 2, listOf("亻")),
            p("亻", "person / Mr. T", "Heisig primitive: person on left (variant of 人, radical 9)", 2, listOf("人")),
            p("儿", "human legs", "Heisig primitive: human legs (Kangxi radical 10)", 2),
            p("入", "enter", "Heisig RTK: enter (Kangxi radical 11)", 2),
            p("八", "eight", "Heisig RTK: eight (Kangxi radical 12)", 2),
            p("正", "correct", "Heisig RTK: correct / justice (one 一 + footprint 止)", 5),
            p("井", "well", "Heisig RTK: well (water well)", 4),
        )
    }
}
