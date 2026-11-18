package com.bitchat.droid

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MeshForegroundService.Listener {
    private lateinit var status: TextView
    private lateinit var members: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var nicknameInput: EditText
    private lateinit var messageInput: EditText
    private var meshService: MeshForegroundService? = null
    private var bound = false
    private val transcriptLines = ArrayDeque<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? MeshForegroundService.LocalBinder ?: return
            meshService = localBinder.service()
            nicknameInput.setText(meshService?.localNickname())
            meshService?.addListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService?.removeListener(this@MainActivity)
            meshService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        requestMeshPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (hasMeshPermissions()) startAndBindMesh()
    }

    override fun onStop() {
        meshService?.removeListener(this)
        if (bound) unbindService(serviceConnection)
        bound = false
        meshService = null
        super.onStop()
    }

    override fun onStatus(status: String) {
        this.status.text = status
    }

    override fun onPeersChanged(peers: List<MeshPeer>) {
        members.text = if (peers.isEmpty()) {
            "No peers connected yet"
        } else {
            "${peers.size} peer${if (peers.size == 1) "" else "s"} · " +
                peers.joinToString { it.nickname + if (it.connected) "" else " (offline)" }
        }
    }

    override fun onLinkCountChanged(count: Int) {
        status.text = if (count == 0) {
            "Scanning for nearby devices…"
        } else {
            "Connected over Bluetooth · $count link${if (count == 1) "" else "s"}"
        }
    }

    override fun onMessage(message: MeshChatMessage) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
        transcriptLines.addLast("$time  ${if (message.isLocal) "You" else message.nickname}: ${message.text}")
        while (transcriptLines.size > MAX_TRANSCRIPT_MESSAGES) transcriptLines.removeFirst()
        transcript.text = transcriptLines.joinToString("\n\n", postfix = "\n\n")
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFFF6F4EF.toInt())
        }
        val heading = TextView(this).apply {
            text = "bitchat"
            textSize = 26f
            setTextColor(0xFF16221F.toInt())
        }
        root.addView(heading)

        val room = TextView(this).apply {
            text = "Nearby mesh · shared group"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(room)
        status = TextView(this).apply { text = "Requesting Bluetooth permission…"; textSize = 13f }
        root.addView(status)
        members = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(members)

        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nicknameInput = EditText(this).apply {
            hint = "Nickname"
            singleLine = true
            setText(getSharedPreferences("mesh-identity", Context.MODE_PRIVATE).getString("nickname", "Android"))
        }
        nameRow.addView(nicknameInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        val saveName = Button(this).apply {
            text = "Set name"
            setOnClickListener {
                val name = nicknameInput.text.toString().trim()
                if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 64) {
                    status.text = "Nickname must be 1–64 UTF-8 bytes"
                } else {
                    meshService?.changeNickname(name)
                }
            }
        }
        nameRow.addView(saveName)
        root.addView(nameRow)

        transcript = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF1E2A26.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        transcriptScroll = ScrollView(this).apply {
            addView(transcript)
            isFillViewport = true
        }
        root.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val composeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        messageInput = EditText(this).apply {
            hint = "Message the nearby group"
            setSingleLine(false)
            maxLines = 3
        }
        composeRow.addView(messageInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composeRow.addView(Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        })
        root.addView(composeRow)
        setContentView(root)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        try {
            meshService?.sendText(text) ?: run {
                status.text = "Bluetooth mesh is still starting"
                return
            }
            messageInput.text.clear()
        } catch (error: IllegalArgumentException) {
            status.text = error.message ?: "Message is not valid"
        } catch (error: IllegalStateException) {
            status.text = error.message ?: "Bluetooth mesh is unavailable"
        }
    }

    private fun requestMeshPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startAndBindMesh()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasMeshPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAndBindMesh() {
        if (!hasMeshPermissions()) return
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter?.isEnabled == false) {
            status.text = "Bluetooth is off"
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }
        val serviceIntent = Intent(this, MeshForegroundService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            if (!bound) {
                bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (error: SecurityException) {
            status.text = "Bluetooth permission is needed to start mesh chat"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_PERMISSIONS && hasMeshPermissions()) {
            startAndBindMesh()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            status.text = "Nearby-device Bluetooth permissions are needed for mesh chat"
        }
    }

    @Deprecated("The Bluetooth enable prompt is handled by the system.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH && resultCode == RESULT_OK) {
            startAndBindMesh()
        } else if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            status.text = "Turn on Bluetooth to use nearby mesh chat"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 1301
        private const val REQUEST_ENABLE_BLUETOOTH = 1302
        private const val MAX_TRANSCRIPT_MESSAGES = 200
    }
}
