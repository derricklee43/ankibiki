package com.derricklee.ankidict

import android.content.Context

/**
 * Process-wide lazy MiningPipelineRunner for screens that only need its pipeline CONFIGS
 * (the Stremio console) without owning a second DI graph. Same construction as
 * MainActivity's createDefault call; single instance via synchronized lazy.
 */
object MiningPipelineHolder {

    @Volatile
    private var runner: MiningPipelineRunner? = null

    fun of(context: Context): MiningPipelineRunner =
        runner ?: synchronized(this) {
            runner ?: MiningPipelineRunner.createDefault(
                context = context.applicationContext,
                miningRepository = MiningRepository(context.applicationContext),
                dictionaryRepository = DictionaryRepository(context.applicationContext),
                shinjitaiRepository = ShinjitaiRepository(context.applicationContext),
                hskRepository = HskRepository(context.applicationContext),
            ).also { runner = it }
        }
}
