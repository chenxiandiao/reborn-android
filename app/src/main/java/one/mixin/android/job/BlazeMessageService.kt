package one.mixin.android.job

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.room.InvalidationTracker
import com.birbit.android.jobqueue.network.NetworkEventProvider
import com.birbit.android.jobqueue.network.NetworkUtil
import dagger.android.AndroidInjection
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.mixin.android.Constants
import one.mixin.android.MixinApplication
import one.mixin.android.R
import one.mixin.android.api.NetworkException
import one.mixin.android.api.WebSocketException
import one.mixin.android.crypto.Base64
import one.mixin.android.db.FloodMessageDao
import one.mixin.android.db.JobDao
import one.mixin.android.db.MixinDatabase
import one.mixin.android.di.type.DatabaseCategory
import one.mixin.android.di.type.DatabaseCategoryEnum
import one.mixin.android.extension.networkConnected
import one.mixin.android.extension.supportsOreo
import one.mixin.android.receiver.ExitBroadcastReceiver
import one.mixin.android.ui.home.MainActivity
import one.mixin.android.util.ErrorHandler
import one.mixin.android.util.GsonHelper
import one.mixin.android.util.Session
import one.mixin.android.vo.CallState
import one.mixin.android.websocket.BlazeAckMessage
import one.mixin.android.websocket.BlazeMessage
import one.mixin.android.websocket.BlazeMessageData
import one.mixin.android.websocket.ChatWebSocket
import one.mixin.android.websocket.PlainDataAction
import one.mixin.android.websocket.TransferPlainAckData
import one.mixin.android.websocket.createAckListParamBlazeMessage
import one.mixin.android.websocket.createAckSessionListParamBlazeMessage
import one.mixin.android.websocket.createParamSessionMessage
import one.mixin.android.websocket.createPlainJsonParam
import org.jetbrains.anko.notificationManager

class BlazeMessageService : LifecycleService(), NetworkEventProvider.Listener, ChatWebSocket.WebSocketObserver {

    companion object {
        val TAG = BlazeMessageService::class.java.simpleName
        const val CHANNEL_NODE = "channel_node"
        const val FOREGROUND_ID = 666666
        const val ACTION_TO_BACKGROUND = "action_to_background"
        const val ACTION_ACTIVITY_RESUME = "action_activity_resume"
        const val ACTION_ACTIVITY_PAUSE = "action_activity_pause"

        fun startService(ctx: Context, action: String? = null) {
            val intent = Intent(ctx, BlazeMessageService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stopService(ctx: Context) {
            val intent = Intent(ctx, BlazeMessageService::class.java)
            ctx.stopService(intent)
        }
    }

    @Inject
    lateinit var networkUtil: JobNetworkUtil
    @Inject
    @field:[DatabaseCategory(DatabaseCategoryEnum.BASE)]
    lateinit var database: MixinDatabase
    @Inject
    lateinit var webSocket: ChatWebSocket
    @Inject
    lateinit var floodMessageDao: FloodMessageDao
    @Inject
    lateinit var jobDao: JobDao
    @Inject
    lateinit var jobManager: MixinJobManager
    @Inject
    lateinit var callState: CallState

    private val accountId = Session.getAccountId()
    private val gson = GsonHelper.customGson

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        webSocket.setWebSocketObserver(this)
        webSocket.connect()
        startAckJob()
        startFloodJob()
        networkUtil.setListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_STICKY
        when (ACTION_TO_BACKGROUND) {
            intent.action -> {
                stopForeground(true)
                return START_STICKY
            }
        }
        setForegroundIfNecessary()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAckJob()
        stopFloodJob()
        webSocket.disconnect()
    }

    override fun onNetworkChange(networkStatus: Int) {
        if (networkStatus != NetworkUtil.DISCONNECTED && MixinApplication.get().onlining.get()) {
            webSocket.connect()
        }
    }

    override fun onSocketClose() {
    }

    override fun onSocketOpen() {
        runAckJob()
        runFloodJob()
    }

    @SuppressLint("NewApi")
    private fun setForegroundIfNecessary() {
        val exitIntent = Intent(this, ExitBroadcastReceiver::class.java).apply {
            action = ACTION_TO_BACKGROUND
        }
        val exitPendingIntent = PendingIntent.getBroadcast(this, 0, exitIntent, 0)

        val builder = NotificationCompat.Builder(this, CHANNEL_NODE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_connection_enabled))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setWhen(0)
            .setDefaults(0)
            .setSound(null)
            .setDefaults(0)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.gray_light))
            .setSmallIcon(R.drawable.ic_msg_default)
            .addAction(R.drawable.ic_close_black_24dp, getString(R.string.exit), exitPendingIntent)

        val pendingIntent = PendingIntent.getActivity(this, 0, MainActivity.getSingleIntent(this), 0)
        builder.setContentIntent(pendingIntent)

        supportsOreo {
            val channel = NotificationChannel(CHANNEL_NODE,
                MixinApplication.get().getString(R.string.notification_node), NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.setSound(null, null)
            channel.setShowBadge(false)
            notificationManager.createNotificationChannel(channel)
        }
        startForeground(FOREGROUND_ID, builder.build())
    }

    private fun startAckJob() {
        database.invalidationTracker.addObserver(ackObserver)
    }

    private fun stopAckJob() {
        database.invalidationTracker.removeObserver(ackObserver)
        ackJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
    }

    private val ackObserver =
        object : InvalidationTracker.Observer("jobs") {
            override fun onInvalidated(tables: MutableSet<String>) {
                runAckJob()
            }
        }

    @Synchronized
    private fun runAckJob() {
        if (ackJob?.isActive == true || !networkConnected()) {
            return
        }
        ackJob = lifecycleScope.launch(Dispatchers.IO) {
            ackJobBlock()
            Session.getExtensionSessionId()?.let {
                ackSessionJobBlock()
                syncMessageStatusToExtension(it)
            }
        }
    }

    private var ackJob: Job? = null

    private suspend fun ackJobBlock() {
        try {
            processAck()
        } catch (e: Exception) {
            runAckJob()
        } finally {
            ackJob = null
        }
    }

    private tailrec suspend fun processAck(): Boolean {
        val ackMessages = jobDao.findAckJobs()
        if (!ackMessages.isNullOrEmpty()) {
            ackMessages.map { gson.fromJson(it.blazeMessage, BlazeAckMessage::class.java) }.let {
                deliver(createAckListParamBlazeMessage(it)).let {
                    jobDao.deleteList(ackMessages)
                }
            }
            return processAck()
        } else {
            return false
        }
    }

    private suspend fun ackSessionJobBlock() {
        jobDao.findSessionAckJobs()?.let { list ->
            if (list.isNotEmpty()) {
                list.map { gson.fromJson(it.blazeMessage, BlazeAckMessage::class.java) }.let {
                    try {
                        deliver(createAckSessionListParamBlazeMessage(it)).let {
                            jobDao.deleteList(list)
                        }
                    } catch (e: Exception) {
                        runAckJob()
                    } finally {
                        ackJob = null
                    }
                }
            }
        }
    }

    private suspend fun syncMessageStatusToExtension(sessionId: String) {
        jobDao.findCreatePlainSessionJobs()?.let { list ->
            if (list.isNotEmpty()) {
                list.map { gson.fromJson(it.blazeMessage, BlazeAckMessage::class.java) }.let {
                    try {
                        val plainText = gson.toJson(TransferPlainAckData(
                            action = PlainDataAction.ACKNOWLEDGE_MESSAGE_RECEIPTS.name,
                            messages = it
                        ))
                        val encoded = Base64.encodeBytes(plainText.toByteArray())
                        val bm = createParamSessionMessage(createPlainJsonParam(accountId!!, accountId, encoded, sessionId))
                        jobManager.addJobInBackground(SendSessionStatusMessageJob(bm))
                        jobDao.deleteList(list)
                    } catch (e: Exception) {
                        runAckJob()
                    } finally {
                        ackJob = null
                    }
                }
            }
        }
    }

    private val messageDecrypt by lazy { DecryptMessage() }
    private val callMessageDecrypt by lazy { DecryptCallMessage(callState, lifecycleScope) }
    private val sessionMessageDecrypt by lazy { DecryptSessionMessage() }

    private fun startFloodJob() {
        database.invalidationTracker.addObserver(floodObserver)
    }

    private fun stopFloodJob() {
        database.invalidationTracker.removeObserver(floodObserver)
        floodJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
    }

    private val floodObserver =
        object : InvalidationTracker.Observer("flood_messages") {
            override fun onInvalidated(tables: MutableSet<String>) {
                runFloodJob()
            }
        }

    @Synchronized
    private fun runFloodJob() {
        if (floodJob?.isActive == true) {
            return
        }
        floodJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                processFloodMessage()
            } catch (e: Exception) {
                runFloodJob()
            } finally {
                floodJob = null
            }
        }
    }

    private var floodJob: Job? = null

    private tailrec suspend fun processFloodMessage(): Boolean {
        val messages = floodMessageDao.findFloodMessages()
        if (!messages.isNullOrEmpty()) {
            messages.forEach { message ->
                val data = gson.fromJson(message.data, BlazeMessageData::class.java)
                if (data.category.startsWith("WEBRTC_")) {
                    callMessageDecrypt.onRun(data)
                } else if (data.userId == accountId && !data.sessionId.isNullOrEmpty()) {
                    sessionMessageDecrypt.onRun(data)
                } else {
                    messageDecrypt.onRun(data)
                }
                floodMessageDao.delete(message)
            }
            return processFloodMessage()
        } else {
            return false
        }
    }

    private fun deliver(blazeMessage: BlazeMessage): Boolean {
        val bm = webSocket.sendMessage(blazeMessage)
        if (bm == null) {
            Thread.sleep(Constants.SLEEP_MILLIS)
            throw WebSocketException()
        } else if (bm.error != null) {
            if (bm.error.code == ErrorHandler.FORBIDDEN) {
                return true
            } else {
                Thread.sleep(Constants.SLEEP_MILLIS)
                throw NetworkException()
            }
        }
        return true
    }
}
