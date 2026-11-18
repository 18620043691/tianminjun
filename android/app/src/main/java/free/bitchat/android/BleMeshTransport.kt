package com.bitchat.droid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

internal class BleMeshTransport(
    context: Context,
    private val engine: MeshProtocolEngine,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(status: String)
        fun onLinkCountChanged(count: Int)
        fun onPacket(packet: MeshPacket, address: String)
    }

    private data class ClientLink(
        val address: String,
        val gatt: BluetoothGatt,
        var characteristic: BluetoothGattCharacteristic? = null,
        val writes: ArrayDeque<ByteArray> = ArrayDeque(),
        var writing: Boolean = false,
        var retries: Int = 0,
        var queuedBytes: Int = 0,
        var mtu: Int = 23,
        var ready: Boolean = false,
        val assembler: MeshFrameAssembler = MeshFrameAssembler()
    )

    private data class ServerLink(
        val device: BluetoothDevice,
        val writes: ArrayDeque<ByteArray> = ArrayDeque(),
        var writing: Boolean = false,
        var retries: Int = 0,
        var queuedBytes: Int = 0,
        var mtu: Int = 23,
        var subscribed: Boolean = false,
        val assembler: MeshFrameAssembler = MeshFrameAssembler()
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString(BuildConfig.BLE_SERVICE_UUID)
    private val characteristicId = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val serviceData = ParcelUuid(serviceId)
    private val clients = linkedMapOf<String, ClientLink>()
    private val servers = linkedMapOf<String, ServerLink>()
    private val connecting = mutableSetOf<String>()
    private val serverCharacteristic = BluetoothGattCharacteristic(
        characteristicId,
        BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                cccdId,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
    }

    private var gattServer: BluetoothGattServer? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var started = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mainHandler.post { connectTo(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post { listener.onStatus("BLE scan failed ($errorCode)") }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            mainHandler.post { listener.onStatus("Advertising and scanning for nearby peers") }
        }

        override fun onStartFailure(errorCode: Int) {
            mainHandler.post { listener.onStatus("BLE advertising failed ($errorCode)") }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (servers.size >= MAX_LINKS && !servers.containsKey(address)) {
                    gattServer?.cancelConnection(device)
                    return
                }
                val alreadyActive = hasActiveLink(address)
                servers.getOrPut(address) { ServerLink(device) }
                if (!alreadyActive) engine.linkConnected(address)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                servers.remove(address)
                if (!hasActiveLink(address)) engine.linkDisconnected(address)
            }
            publishLinkCount()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            servers[device.address]?.mtu = mtu.coerceAtLeast(23)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                beginAdvertising()
            } else {
                listener.onStatus("Unable to register the BitChat BLE service ($status)")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = descriptor.uuid == cccdId &&
                !preparedWrite &&
                offset == 0 &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null
                )
            }
            if (valid && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                val link = servers[device.address] ?: return
                link.subscribed = true
                sendPacket(engine.announcePacket(), targetAddress = device.address)
                publishLinkCount()
            } else if (valid) {
                servers[device.address]?.subscribed = false
                publishLinkCount()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val response = if (characteristic.uuid == characteristicId && offset == 0) {
                BluetoothGatt.GATT_SUCCESS
            } else {
                BluetoothGatt.GATT_FAILURE
            }
            gattServer?.sendResponse(device, requestId, response, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = characteristic.uuid == characteristicId && !preparedWrite && offset == 0
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null
                )
            }
            if (valid) consumeChunk(device.address, value, true)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = servers[device.address] ?: return
            link.writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE notification failed ($status)")
                clearWrites(link)
            }
            link.retries = 0
            pumpServerWrites(link)
        }
    }

    fun start() {
        if (started) return
        val bluetoothAdapter = adapter ?: error("This device does not provide Bluetooth.")
        if (!bluetoothAdapter.isEnabled) error("Turn on Bluetooth to join the nearby mesh.")
        started = true
        scanner = bluetoothAdapter.bluetoothLeScanner
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val server = manager?.openGattServer(appContext, serverCallback, mainHandler)
            ?: error("Unable to open the BLE GATT server.")
        gattServer = server

        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(serverCharacteristic)
        if (!server.addService(service)) {
            server.close()
            gattServer = null
            started = false
            error("Unable to register the BitChat BLE service.")
        }
        startScanning()
        listener.onStatus("Starting nearby mesh")
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            scanner?.stopScan(scanCallback)
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
            listener.onStatus("Bluetooth permission was revoked; radio stopped")
        }
        clients.values.toList().forEach { it.gatt.close() }
        clients.clear()
        connecting.clear()
        servers.clear()
        gattServer?.close()
        gattServer = null
        publishLinkCount()
    }

    fun sendText(text: String) {
        if (clients.values.none { it.ready } && servers.values.none { it.subscribed }) {
            throw IllegalStateException("No nearby Bluetooth peers are connected; message was not sent.")
        }
        val packet = engine.messagePacket(text)
        if (!sendPacket(packet)) {
            throw IllegalStateException("The Bluetooth send queues are full; message was not sent.")
        }
        engine.noteLocalMessage(packet)
    }

    fun changeNickname(nickname: String) {
        engine.setNickname(nickname)
        sendPacket(engine.announcePacket())
    }

    fun peerAddresses(peerId: String): Set<String> =
        engine.addressesForPeer(peerId)

    fun localNickname(): String = engine.nickname()

    fun announce() {
        sendPacket(engine.announcePacket())
    }

    fun relay(relay: MeshRelay) {
        if (relay.targetAddress == null) {
            sendPacket(relay.packet, excludedAddress = relay.ingressAddress)
        } else {
            sendPacket(relay.packet, targetAddress = relay.targetAddress)
        }
    }

    private fun startScanning() {
        val leScanner = scanner ?: run {
            listener.onStatus("BLE scanning is unavailable on this device")
            return
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(serviceData).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()
        try {
            leScanner.startScan(filters, settings, scanCallback)
        } catch (error: SecurityException) {
            listener.onStatus("Bluetooth scan permission is missing: ${error.message}")
        }
    }

    private fun beginAdvertising() {
        val leAdvertiser = advertiser ?: run {
            listener.onStatus("BLE advertising is unavailable on this device")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceData)
            .setIncludeDeviceName(false)
            .build()
        try {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (error: SecurityException) {
            listener.onStatus("Bluetooth advertising permission is missing: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        if (!started || clients.size >= MAX_LINKS || clients.containsKey(device.address) ||
            connecting.contains(device.address)) return
        connecting += device.address
        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    appContext,
                    false,
                    clientCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    mainHandler
                )
            } else {
                device.connectGatt(appContext, false, clientCallback)
            }
            if (gatt == null) {
                connecting.remove(device.address)
                listener.onStatus("Could not connect to a nearby BLE peer")
            } else {
                clients[device.address] = ClientLink(device.address, gatt)
            }
        } catch (error: SecurityException) {
            connecting.remove(device.address)
            listener.onStatus("Bluetooth connection permission is missing: ${error.message}")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(address)
                try {
                    gatt.requestMtu(512)
                    gatt.discoverServices()
                } catch (error: SecurityException) {
                    listener.onStatus("Bluetooth connection permission is missing: ${error.message}")
                }
                if (!hasActiveLink(address)) engine.linkConnected(address)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting.remove(address)
                clients.remove(address)?.gatt?.close()
                if (!hasActiveLink(address)) engine.linkDisconnected(address)
                publishLinkCount()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(address)
                clients.remove(address)?.gatt?.close()
                if (!hasActiveLink(address)) engine.linkDisconnected(address)
                listener.onStatus("BLE connection failed ($status)")
                publishLinkCount()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            clients[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Could not discover BitChat BLE service ($status)")
                return
            }
            val characteristic = gatt.getService(serviceId)?.getCharacteristic(characteristicId)
            val descriptor = characteristic?.getDescriptor(cccdId)
            val link = clients[gatt.device.address]
            if (characteristic == null || descriptor == null || link == null) {
                listener.onStatus("Nearby device does not expose a compatible BitChat BLE service")
                gatt.disconnect()
                return
            }
            link.characteristic = characteristic
            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    listener.onStatus("Could not enable BLE notifications")
                    gatt.disconnect()
                    return
                }
                val result = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        GATT_STATUS_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (!result) listener.onStatus("Could not subscribe to the peer's BLE notifications")
            } catch (error: SecurityException) {
                listener.onStatus("Bluetooth connection permission is missing: ${error.message}")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val link = clients[gatt.device.address] ?: return
            if (descriptor.uuid == cccdId && status == BluetoothGatt.GATT_SUCCESS) {
                link.ready = true
                enqueueFrames(link, encodeForTransport(engine.announcePacket()))
                publishLinkCount()
            } else {
                listener.onStatus("BLE notification subscription failed ($status)")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val link = clients[gatt.device.address] ?: return
            link.writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE write failed ($status)")
                clearWrites(link)
            }
            pumpClientWrites(link)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            consumeChunk(gatt.device.address, characteristic.value ?: return, false)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            consumeChunk(gatt.device.address, value, false)
        }
    }

    private fun sendPacket(
        packet: MeshPacket,
        excludedAddress: String? = null,
        targetAddress: String? = null
    ): Boolean {
        val frames = encodeForTransport(packet)
        if (frames.isEmpty()) {
            listener.onStatus("Unable to encode the mesh packet")
            return false
        }
        val targetClients = clients.values.filter {
            it.ready && it.address != excludedAddress && (targetAddress == null || it.address == targetAddress)
        }
        val targetServers = servers.values.filter {
            it.subscribed && it.device.address != excludedAddress &&
                (targetAddress == null || it.device.address == targetAddress)
        }
        var queued = false
        targetClients.forEach { link -> queued = enqueueFrames(link, frames) || queued }
        targetServers.forEach { link -> queued = enqueueFrames(link, frames) || queued }
        return queued
    }

    private fun encodeForTransport(packet: MeshPacket): List<ByteArray> {
        val fullFrame = MeshPacketCodec.encode(packet) ?: return emptyList()
        if (fullFrame.size <= MAX_LINK_FRAME_BYTES) return listOf(fullFrame)
        if (packet.type != MeshMessageType.MESSAGE || packet.version != 1) return emptyList()
        val chunkSize = FRAGMENT_CHUNK_BYTES
        val total = (fullFrame.size + chunkSize - 1) / chunkSize
        if (total !in 1..MAX_FRAGMENT_COUNT) return emptyList()
        val fragmentId = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return (0 until total).map { index ->
            val offset = index * chunkSize
            val fragmentData = fullFrame.copyOfRange(offset, minOf(offset + chunkSize, fullFrame.size))
            val payload = ByteArrayOutputStream().apply {
                write(fragmentId)
                write(index ushr 8)
                write(index)
                write(total ushr 8)
                write(total)
                write(MeshMessageType.MESSAGE)
                write(fragmentData)
            }.toByteArray()
            val fragment = packet.copy(
                type = MeshMessageType.FRAGMENT,
                payload = payload,
                signature = null,
                recipientId = null,
                version = 1,
                route = emptyList(),
                isRsr = false
            )
            MeshPacketCodec.encode(fragment) ?: return emptyList()
        }
    }

    private fun consumeChunk(address: String, data: ByteArray, fromServer: Boolean) {
        val assembler = if (fromServer) servers[address]?.assembler else clients[address]?.assembler
        if (assembler == null) return
        assembler.append(data).forEach { frame ->
            val packet = MeshPacketCodec.decode(frame)
            if (packet == null) {
                listener.onStatus("Dropped a malformed BLE packet")
            } else {
                listener.onPacket(packet, address)
            }
        }
    }

    private fun enqueue(link: ClientLink, frame: ByteArray): Boolean {
        return enqueueFrames(link, listOf(frame))
    }

    private fun enqueueFrames(link: ClientLink, frames: List<ByteArray>): Boolean {
        val chunks = frames.flatMap { splitForMtu(it, link.mtu) }
        val size = chunks.sumOf { it.size }
        if (link.queuedBytes + size > MAX_QUEUED_BYTES) {
            listener.onStatus("BLE send queue is full; message was not queued")
            return false
        }
        chunks.forEach(link.writes::addLast)
        link.queuedBytes += size
        pumpClientWrites(link)
        return true
    }

    private fun enqueue(link: ServerLink, frame: ByteArray): Boolean {
        return enqueueFrames(link, listOf(frame))
    }

    private fun enqueueFrames(link: ServerLink, frames: List<ByteArray>): Boolean {
        val chunks = frames.flatMap { splitForMtu(it, link.mtu) }
        val size = chunks.sumOf { it.size }
        if (link.queuedBytes + size > MAX_QUEUED_BYTES) {
            listener.onStatus("BLE send queue is full; message was not queued")
            return false
        }
        chunks.forEach(link.writes::addLast)
        link.queuedBytes += size
        pumpServerWrites(link)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pumpClientWrites(link: ClientLink) {
        if (!started || !link.ready || link.writing || link.writes.isEmpty()) return
        val characteristic = link.characteristic ?: return
        val value = link.writes.first()
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                link.gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == GATT_STATUS_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                link.gatt.writeCharacteristic(characteristic)
            }
            if (accepted) {
                link.writes.removeFirst()
                link.queuedBytes -= value.size
                link.writing = true
                link.retries = 0
            } else {
                retryClientWrite(link)
            }
        } catch (error: SecurityException) {
            listener.onStatus("Bluetooth connection permission is missing: ${error.message}")
            clearWrites(link)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpServerWrites(link: ServerLink) {
        if (!started || !link.subscribed || link.writing || link.writes.isEmpty()) return
        val server = gattServer ?: return
        val value = link.writes.first()
        val status = try {
            if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(link.device, serverCharacteristic, false, value)
            } else {
                @Suppress("DEPRECATION")
                serverCharacteristic.value = value
                @Suppress("DEPRECATION")
                if (server.notifyCharacteristicChanged(link.device, serverCharacteristic, false)) {
                    GATT_STATUS_SUCCESS
                } else {
                    GATT_STATUS_FAILURE
                }
            }
        } catch (error: SecurityException) {
            listener.onStatus("Bluetooth connection permission is missing: ${error.message}")
            clearWrites(link)
            return
        }
        if (status == GATT_STATUS_SUCCESS) {
            link.writes.removeFirst()
            link.queuedBytes -= value.size
            link.writing = true
            link.retries = 0
        } else {
            retryServerWrite(link, status)
        }
    }

    private fun retryClientWrite(link: ClientLink) {
        link.retries++
        if (link.retries >= MAX_WRITE_RETRIES) {
            listener.onStatus("BLE write queue stayed blocked; queued data was discarded")
            clearWrites(link)
            return
        }
        if (link.retries == 1) listener.onStatus("Waiting for the BLE write queue")
        mainHandler.postDelayed({ pumpClientWrites(link) }, WRITE_RETRY_MS)
    }

    private fun retryServerWrite(link: ServerLink, status: Int) {
        link.retries++
        if (link.retries >= MAX_WRITE_RETRIES) {
            listener.onStatus("BLE notification queue stayed blocked ($status); queued data was discarded")
            clearWrites(link)
            return
        }
        if (link.retries == 1) listener.onStatus("Waiting for the BLE notification queue")
        mainHandler.postDelayed({ pumpServerWrites(link) }, WRITE_RETRY_MS)
    }

    private fun clearWrites(link: ClientLink) {
        link.writes.clear()
        link.queuedBytes = 0
        link.retries = 0
    }

    private fun clearWrites(link: ServerLink) {
        link.writes.clear()
        link.queuedBytes = 0
        link.retries = 0
    }

    private fun splitForMtu(frame: ByteArray, mtu: Int): List<ByteArray> {
        val chunkSize = (mtu - ATT_OVERHEAD).coerceAtLeast(20)
        return (frame.indices step chunkSize).map { start ->
            frame.copyOfRange(start, minOf(start + chunkSize, frame.size))
        }
    }

    private fun publishLinkCount() {
        val activeAddresses = clients.values.filter { it.ready }.map { it.address } +
            servers.values.filter { it.subscribed }.map { it.device.address }
        listener.onLinkCountChanged(activeAddresses.toSet().size)
    }

    private fun hasActiveLink(address: String): Boolean =
        clients[address]?.ready == true || servers[address]?.subscribed == true

    companion object {
        private const val MAX_LINKS = 6
        private const val ATT_OVERHEAD = 3
        private const val MAX_LINK_FRAME_BYTES = 512
        private const val FRAGMENT_CHUNK_BYTES = 469
        private const val MAX_FRAGMENT_COUNT = 256
        private const val MAX_QUEUED_BYTES = 1_000_000
        private const val MAX_WRITE_RETRIES = 80
        private const val WRITE_RETRY_MS = 25L
        private const val GATT_STATUS_SUCCESS = 0
        private const val GATT_STATUS_FAILURE = -1
    }
}
