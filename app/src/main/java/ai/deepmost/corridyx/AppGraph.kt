package ai.deepmost.corridyx

import ai.deepmost.corridyx.config.SettingsRepository
import ai.deepmost.corridyx.packet.db.CorridyxDb
import android.content.Context
import androidx.room.Room

/**
 * Minimal manual service-locator for process-wide singletons (settings + Room DB). Session-scoped
 * components (model registry, dimension pipeline, accumulator actor, blurrer) are owned by the
 * [ai.deepmost.corridyx.service.CaptureService] for their lifetime, not here.
 */
class AppGraph private constructor(context: Context) {
    val appContext: Context = context.applicationContext

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val db: CorridyxDb by lazy {
        Room.databaseBuilder(appContext, CorridyxDb::class.java, "corridyx.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
