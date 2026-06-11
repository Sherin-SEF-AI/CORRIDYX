package ai.deepmost.corridyx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber

class CorridyxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        AppGraph.get(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_CAPTURE, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.capture_channel_desc) }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        const val CH_CAPTURE = "corridyx_capture"
        const val CH_ALERT = "corridyx_alert"
    }
}
