package ai.deepmost.corridyx.upload

import ai.deepmost.corridyx.config.Settings
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueues the upload drain as unique work with the configured network constraint + backoff. */
object UploadScheduler {
    private const val WORK = "corridyx_upload"

    fun schedule(context: Context, settings: Settings) {
        if (settings.uploadEndpoint.isBlank()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.uploadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }
}
