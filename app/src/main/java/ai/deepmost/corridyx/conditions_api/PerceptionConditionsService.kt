package ai.deepmost.corridyx.conditions_api

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bound local API exposing live [PerceptionConditions] to sibling ai.deepmost.* perception apps
 * (FERYX, LANYX) so they can tell when their detections are untrustworthy. Messenger-based (AIDL-lite).
 *
 * CONTRACT (also documented in README):
 *   - Bind:    Intent action "ai.deepmost.perception.CONDITIONS", package "ai.deepmost.corridyx".
 *              Caller must hold permission "ai.deepmost.corridyx.permission.READ_PERCEPTION_CONDITIONS".
 *   - MSG_REGISTER (what=1, replyTo=clientMessenger): subscribe; receive a push on every change.
 *   - MSG_UNREGISTER (what=2, replyTo=clientMessenger): unsubscribe.
 *   - MSG_QUERY (what=3, replyTo=clientMessenger): one-shot reply with the current conditions.
 *   - Reply MSG_CONDITIONS (what=10): Bundle keys lens,glare,atmo,nightq,trust (Float 0..1),
 *     capturing (Boolean), ts (Long epoch ms).
 */
class PerceptionConditionsService : Service() {

    private val clients = CopyOnWriteArrayList<Messenger>()
    private lateinit var handlerThread: HandlerThread
    private lateinit var incoming: Messenger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("conditions-api").apply { start() }
        incoming = Messenger(Handler(handlerThread.looper) { msg -> onClientMessage(msg); true })

        // push updates to all registered clients
        PerceptionConditionsBus.state
            .onEach { c -> clients.forEach { trySend(it, c) } }
            .launchIn(scope)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    private fun onClientMessage(msg: Message) {
        when (msg.what) {
            MSG_REGISTER -> msg.replyTo?.let { if (!clients.contains(it)) { clients.add(it); trySend(it, PerceptionConditionsBus.current()) } }
            MSG_UNREGISTER -> msg.replyTo?.let { clients.remove(it) }
            MSG_QUERY -> msg.replyTo?.let { trySend(it, PerceptionConditionsBus.current()) }
            else -> Timber.w("Unknown conditions API msg.what=%d", msg.what)
        }
    }

    private fun trySend(client: Messenger, c: PerceptionConditions) {
        val b = Bundle().apply {
            putFloat("lens", c.lens); putFloat("glare", c.glare); putFloat("atmo", c.atmo)
            putFloat("nightq", c.nightq); putFloat("trust", c.overallTrust)
            putBoolean("capturing", PerceptionConditionsBus.capturing); putLong("ts", c.timestampMs)
        }
        val reply = Message.obtain(null, MSG_CONDITIONS).apply { data = b }
        try {
            client.send(reply)
        } catch (e: RemoteException) {
            clients.remove(client) // dead client
        }
    }

    override fun onDestroy() {
        scope.cancel()
        handlerThread.quitSafely()
        clients.clear()
        super.onDestroy()
    }

    companion object {
        const val MSG_REGISTER = 1
        const val MSG_UNREGISTER = 2
        const val MSG_QUERY = 3
        const val MSG_CONDITIONS = 10
    }
}
