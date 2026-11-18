package com.bitchat.droid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler

class MeshForegroundService : Service() {
    interface Listener {
        fun onStatus(status: String)
        fun onPeersChanged(peers: List<MeshPeer>)
        fun onMessage(message: MeshChatMessage)
        fun onLinkCountChanged(count: Int)
    }

    inner class LocalBinder : Binder() {
        fun service(): MeshForegroundService = this@MeshForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<Listener>()
    private var engine: MeshProtocolEngine? = null
    private var transport: BleMeshTransport? = null
    private var activePeers: List<MeshPeer> = emptyList()
    private var linkCount = 0
    private var statusText = "Starting nearby mesh"

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (engine == null) initializeMesh()
        return START_STICKY
    }

    override fun onDestroy() {
        transport?.stop()
        transport = null
        engine = null
        super.onDestroy()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStatus(statusText)
        listener.onPeersChanged(activePeers)
        listener.onLinkCountChanged(linkCount)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun sendText(text: String) {
        val activeTransport = transport ?: error("Bluetooth mesh is not ready.")
        activeTransport.sendText(text)
    }

    fun localNickname(): String = engine?.nickname() ?: "Android"

    fun changeNickname(nickname: String) {
        IdentityStore(this).saveNickname(nickname)
        transport?.changeNickname(nickname)
    }

    private fun initializeMesh() {
        try {
            val store = IdentityStore(this)
            val identity = store.loadOrCreate()
            val mesh = MeshProtocolEngine(
                identity = identity,
                onMessage = { message -> listeners.toList().forEach { it.onMessage(message) } },
                onPeersChanged = { peers ->
                    activePeers = peers
                    listeners.toList().forEach { it.onPeersChanged(peers) }
                },
                onRelay = { relay ->
                    mainHandler.postDelayed({ transport?.relay(relay) }, relay.delayMs)
                },
                peerAddresses = { peerId -> transport?.peerAddresses(peerId) ?: emptySet() },
                initialSigningKeys = store.loadPeerPins(),
                onPeerPinned = store::savePeerPin
            )
            mesh.setNickname(store.nickname(identity))
            engine = mesh
            transport = BleMeshTransport(
                this,
                mesh,
                object : BleMeshTransport.Listener {
                    override fun onStatus(status: String) = updateStatus(status)
                    override fun onLinkCountChanged(count: Int) {
                        linkCount = count
                        listeners.toList().forEach { it.onLinkCountChanged(count) }
                    }

                    override fun onPacket(packet: MeshPacket, address: String) {
                        try {
                            mesh.receive(packet, address)
                        } catch (error: Exception) {
                            updateStatus("Could not securely accept a nearby peer: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            ).also { it.start() }
            updateStatus("Nearby shared mesh chat is active")
            transport?.announce()
        } catch (error: Exception) {
            updateStatus("Could not start Bluetooth mesh: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun updateStatus(status: String) {
        statusText = status
        listeners.toList().forEach { it.onStatus(status) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nearby mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bluetooth mesh chat and message relay"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("bitchat · Nearby mesh")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bitchat-mesh"
        private const val NOTIFICATION_ID = 7301
    }
}
