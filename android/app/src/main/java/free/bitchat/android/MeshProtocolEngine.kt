package com.bitchat.droid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class MeshPeer(val id: String, val nickname: String, val connected: Boolean)

internal data class MeshChatMessage(
    val senderId: String,
    val nickname: String,
    val text: String,
    val timestamp: Long,
    val isLocal: Boolean
)

internal data class MeshRelay(
    val packet: MeshPacket,
    val ingressAddress: String,
    val targetAddress: String?,
    val delayMs: Long
)

internal class MeshProtocolEngine(
    private val identity: MeshIdentity,
    private val onMessage: (MeshChatMessage) -> Unit,
    private val onPeersChanged: (List<MeshPeer>) -> Unit,
    private val onRelay: (MeshRelay) -> Unit,
    private val peerAddresses: (String) -> Set<String>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    initialSigningKeys: Map<String, ByteArray> = emptyMap(),
    private val onPeerPinned: (String, ByteArray) -> Unit = { _, _ -> }
) {
    private data class Announcement(val nickname: String, val noiseKey: ByteArray, val signingKey: ByteArray)
    private data class FragmentSet(
        val total: Int,
        val originalType: Int,
        val createdAt: Long,
        val pieces: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private val signingKeys = initialSigningKeys.mapValuesTo(mutableMapOf()) { it.value.copyOf() }
    private val nicknames = mutableMapOf<String, String>()
    private val addressPeers = mutableMapOf<String, String>()
    private val seen = LinkedHashMap<String, Unit>()
    private val fragments = LinkedHashMap<String, FragmentSet>()
    private val localId = identity.peerIdHex
    private val maxTextBytes = 65_535

    fun setNickname(nickname: String) {
        require(nickname.isNotBlank() && nickname.toByteArray(Charsets.UTF_8).size <= 64)
        nicknames[localId] = nickname.trim()
        publishPeers()
    }

    fun nickname(): String = nicknames[localId] ?: "Android-${localId.take(4)}"

    fun localPeerId(): String = localId

    fun peers(): List<MeshPeer> = nicknames.keys
        .filter { it != localId }
        .map { id -> MeshPeer(id, nicknames[id] ?: id.take(8), addressPeers.values.contains(id)) }
        .sortedBy { it.nickname.lowercase() }

    fun addressesForPeer(peerId: String): Set<String> =
        addressPeers.filterValues { it == peerId }.keys

    fun announcePacket(): MeshPacket {
        val nicknameBytes = nickname().toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            writeTlv(0x01, nicknameBytes)
            writeTlv(0x02, identity.noisePublic)
            writeTlv(0x03, identity.signingPublic)
        }.toByteArray()
        return sign(
            MeshPacket(
                type = MeshMessageType.ANNOUNCE,
                senderId = identity.peerId,
                timestamp = nowMs(),
                payload = payload,
                ttl = DEFAULT_TTL
            )
        )
    }

    fun messagePacket(text: String): MeshPacket {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.isNotEmpty() && payload.size <= maxTextBytes) { "Message must be between 1 and 65,535 UTF-8 bytes." }
        val unsigned = MeshPacket(
            type = MeshMessageType.MESSAGE,
            senderId = identity.peerId,
            timestamp = nowMs(),
            payload = payload,
            ttl = DEFAULT_TTL
        )
        return sign(unsigned)
    }

    fun noteLocalMessage(packet: MeshPacket) {
        val text = packet.payload.toString(Charsets.UTF_8)
        onMessage(MeshChatMessage(localId, nickname(), text, packet.timestamp, true))
        remember(dedupKey(packet))
    }

    fun linkConnected(address: String) {
        addressPeers.remove(address)
        publishPeers()
    }

    fun linkDisconnected(address: String) {
        addressPeers.remove(address)
        publishPeers()
    }

    fun receive(packet: MeshPacket, ingressAddress: String) {
        if (packet.senderId.size != 8 || packet.senderId.hex() == localId) return
        if (packet.type != MeshMessageType.FRAGMENT && seen.containsKey(dedupKey(packet))) return
        val claimedId = packet.senderId.hex()
        val boundId = addressPeers[ingressAddress]
        if (boundId != null && packet.ttl == DEFAULT_TTL && boundId != claimedId) return

        when (packet.type) {
            MeshMessageType.ANNOUNCE -> if (!acceptAnnouncement(packet, ingressAddress)) return
            MeshMessageType.MESSAGE -> if (!acceptMessage(packet)) return
            MeshMessageType.FRAGMENT -> if (!receiveFragment(packet)) return
            else -> return
        }

        if (packet.type != MeshMessageType.FRAGMENT) remember(dedupKey(packet))
        scheduleRelay(packet, ingressAddress)
    }

    private fun acceptAnnouncement(packet: MeshPacket, ingressAddress: String): Boolean {
        val announcement = decodeAnnouncement(packet.payload) ?: return false
        if (!announcement.noiseKey.sha256().copyOfRange(0, 8).contentEquals(packet.senderId)) {
            return false
        }
        if (!timestampIsCurrent(packet.timestamp)) return false
        if (!identity.verify(MeshPacketCodec.signingBytes(packet) ?: return false, packet.signature ?: return false, announcement.signingKey)) {
            return false
        }
        val peerId = packet.senderId.hex()
        val pinnedKey = signingKeys[peerId]
        if (pinnedKey != null && !pinnedKey.contentEquals(announcement.signingKey)) return false
        if (pinnedKey == null) {
            onPeerPinned(peerId, announcement.signingKey.copyOf())
            signingKeys[peerId] = announcement.signingKey.copyOf()
        }
        nicknames[peerId] = announcement.nickname
        if (packet.ttl == DEFAULT_TTL) {
            val linkOwner = addressPeers[ingressAddress]
            if (linkOwner != null && linkOwner != peerId) return false
            addressPeers[ingressAddress] = peerId
        }
        publishPeers()
        return true
    }

    private fun acceptMessage(packet: MeshPacket): Boolean {
        if (packet.recipientId != null || packet.payload.size > maxTextBytes || !timestampIsCurrent(packet.timestamp)) return false
        val peerId = packet.senderId.hex()
        val signingKey = signingKeys[peerId] ?: return false
        if (!identity.verify(MeshPacketCodec.signingBytes(packet) ?: return false, packet.signature ?: return false, signingKey)) {
            return false
        }
        val text = packet.payload.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(packet.payload)) return false
        onMessage(MeshChatMessage(peerId, nicknames[peerId] ?: peerId.take(8), text, packet.timestamp, false))
        return true
    }

    private fun receiveFragment(packet: MeshPacket): Boolean {
        val payload = packet.payload
        if (payload.size < FRAGMENT_HEADER_SIZE || packet.recipientId != null) return false
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val id = ByteArray(8).also(input::get).hex()
        val index = input.short.toInt() and 0xFFFF
        val total = input.short.toInt() and 0xFFFF
        val originalType = input.get().toInt() and 0xFF
        if (total !in 1..MAX_FRAGMENTS || index >= total || originalType != MeshMessageType.MESSAGE) return false
        if (!remember("fragment:${packet.senderId.hex()}:$id:$index")) return false

        val key = "${packet.senderId.hex()}:$id"
        val now = nowMs()
        pruneFragments(now)
        val assembly = fragments[key] ?: run {
            while (fragments.size >= MAX_ASSEMBLIES) fragments.remove(fragments.keys.first())
            FragmentSet(total, originalType, now).also { fragments[key] = it }
        }
        if (assembly.total != total || assembly.originalType != originalType) {
            fragments.remove(key)
            return false
        }
        val bytes = ByteArray(input.remaining()).also(input::get)
        assembly.pieces.putIfAbsent(index, bytes)
        if (assembly.pieces.values.sumOf { it.size } > MAX_REASSEMBLED_BYTES) {
            fragments.remove(key)
            return false
        }
        if (assembly.pieces.size == total) {
            val fullFrame = ByteArrayOutputStream().apply {
                repeat(total) { part -> write(assembly.pieces[part] ?: return false) }
            }.toByteArray()
            fragments.remove(key)
            val original = MeshPacketCodec.decode(fullFrame) ?: return false
            if (original.type != originalType || original.senderId.hex() != packet.senderId.hex()) return false
            if (!acceptMessage(original)) return false
            remember(dedupKey(original))
        }
        return true
    }

    private fun scheduleRelay(packet: MeshPacket, ingressAddress: String) {
        if (packet.type == MeshMessageType.REQUEST_SYNC || packet.ttl <= 1) return
        val localRecipient = packet.recipientId?.contentEquals(identity.peerId) == true
        if (localRecipient) return

        val linkedPeers = peers().filter { it.connected }.size
        val routingTarget = routeTarget(packet)
        if (packet.route.isNotEmpty() && routingTarget != null) {
            val targets = peerAddresses(routingTarget).filterNot { it == ingressAddress }
            if (targets.isNotEmpty()) {
                val relayed = packet.copy(ttl = packet.ttl - 1)
                targets.forEach { target -> onRelay(MeshRelay(relayed, ingressAddress, target, 20L)) }
                return
            }
        }

        val ttlLimit = if (packet.type == MeshMessageType.FRAGMENT) {
            minOf(packet.ttl, if (linkedPeers >= 6) 5 else DEFAULT_TTL)
        } else if (linkedPeers >= 6) {
            minOf(packet.ttl, if (packet.type == MeshMessageType.ANNOUNCE) 7 else 5)
        } else if (linkedPeers <= 2) {
            packet.ttl
        } else {
            minOf(packet.ttl, if (packet.type == MeshMessageType.ANNOUNCE) 7 else 6)
        }
        if (ttlLimit <= 1) return
        val delay = if (packet.type == MeshMessageType.FRAGMENT) 8L else when {
            linkedPeers <= 2 -> 20L
            linkedPeers <= 5 -> 80L
            else -> 140L
        }
        onRelay(MeshRelay(packet.copy(ttl = ttlLimit - 1), ingressAddress, null, delay))
    }

    private fun routeTarget(packet: MeshPacket): String? {
        val route = packet.route
        val recipient = packet.recipientId?.hex()
        if (route.isEmpty() || recipient == null) return null
        val localIndex = route.indexOfFirst { it.hex() == localId }
        val nextId = when {
            localIndex < 0 -> route.first().hex()
            localIndex == route.lastIndex -> recipient
            else -> route[localIndex + 1].hex()
        }
        return if (nextId == localId) null else nextId
    }

    private fun sign(packet: MeshPacket): MeshPacket {
        val bytes = MeshPacketCodec.signingBytes(packet) ?: error("Unable to encode signed mesh packet.")
        return packet.copy(signature = identity.sign(bytes))
    }

    private fun decodeAnnouncement(payload: ByteArray): Announcement? {
        var offset = 0
        var nickname: String? = null
        var noiseKey: ByteArray? = null
        var signingKey: ByteArray? = null
        while (offset + 2 <= payload.size) {
            val type = payload[offset++].toInt() and 0xFF
            val size = payload[offset++].toInt() and 0xFF
            if (offset + size > payload.size) return null
            val value = payload.copyOfRange(offset, offset + size)
            offset += size
            when (type) {
                0x01 -> {
                    val decoded = value.toString(Charsets.UTF_8)
                    if (!decoded.toByteArray(Charsets.UTF_8).contentEquals(value)) return null
                    nickname = decoded
                }
                0x02 -> noiseKey = value
                0x03 -> signingKey = value
            }
        }
        val name = nickname?.trim()?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 64 } ?: return null
        val noise = noiseKey?.takeIf { it.size == 32 } ?: return null
        val signing = signingKey?.takeIf { it.size == 32 } ?: return null
        return Announcement(name, noise, signing)
    }

    private fun timestampIsCurrent(timestamp: Long): Boolean {
        if (timestamp < 0) return false
        val now = nowMs()
        return if (timestamp >= now) timestamp - now <= MAX_CLOCK_SKEW_MS else now - timestamp <= MAX_CLOCK_SKEW_MS
    }

    private fun dedupKey(packet: MeshPacket): String {
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(packet.payload).copyOfRange(0, 4).hex()
        return "${packet.senderId.hex()}-${packet.timestamp}-${packet.type}-$payloadHash"
    }

    private fun remember(key: String): Boolean {
        if (seen.containsKey(key)) return false
        seen[key] = Unit
        while (seen.size > MAX_SEEN_PACKETS) seen.remove(seen.keys.first())
        return true
    }

    private fun pruneFragments(now: Long) {
        fragments.entries.removeAll { now - it.value.createdAt > FRAGMENT_LIFETIME_MS }
    }

    private fun publishPeers() = onPeersChanged(peers())

    private fun ByteArrayOutputStream.writeTlv(type: Int, bytes: ByteArray) {
        require(bytes.size <= 255)
        write(type)
        write(bytes.size)
        write(bytes)
    }

    private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

    companion object {
        private const val DEFAULT_TTL = 7
        private const val MAX_CLOCK_SKEW_MS = 120_000L
        private const val MAX_SEEN_PACKETS = 4096
        private const val FRAGMENT_HEADER_SIZE = 13
        private const val MAX_FRAGMENTS = 256
        private const val MAX_ASSEMBLIES = 32
        private const val MAX_REASSEMBLED_BYTES = 1_048_576
        private const val FRAGMENT_LIFETIME_MS = 60_000L
    }
}
