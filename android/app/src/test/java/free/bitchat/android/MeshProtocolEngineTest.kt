package com.bitchat.droid

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

class MeshProtocolEngineTest {
    @Test
    fun signedPublicGroupMessageIsDeliveredAndRelayedOnce() {
        val now = 1_800_000_000_000L
        val senderIdentity = newIdentity()
        val sender = newEngine(senderIdentity, now)
        sender.setNickname("Alice")

        val messages = mutableListOf<MeshChatMessage>()
        val relays = mutableListOf<MeshRelay>()
        val peers = mutableListOf<List<MeshPeer>>()
        val receiver = MeshProtocolEngine(
            identity = newIdentity(),
            onMessage = messages::add,
            onPeersChanged = peers::add,
            onRelay = relays::add,
            peerAddresses = { emptySet() },
            nowMs = { now }
        )

        receiver.receive(sender.announcePacket(), "phone-a")
        val message = sender.messagePacket("Hello from the offline group")
        receiver.receive(message, "phone-a")

        assertEquals(1, messages.size)
        assertEquals("Alice", messages.single().nickname)
        assertEquals("Hello from the offline group", messages.single().text)
        assertEquals(6, relays.last().packet.ttl)
        assertTrue(peers.flatten().any { it.id == senderIdentity.peerIdHex && it.connected })

        val relayCount = relays.size
        receiver.receive(message.copy(ttl = 6), "phone-b")
        assertEquals("A duplicate must not trigger another relay", relayCount, relays.size)
        assertEquals("A duplicate must not be delivered twice", 1, messages.size)
    }

    @Test
    fun fragmentedIosStylePublicMessageIsReassembledBeforeDelivery() {
        val now = 1_800_000_000_000L
        val senderIdentity = newIdentity()
        val sender = newEngine(senderIdentity, now)
        sender.setNickname("Long sender")
        val received = mutableListOf<MeshChatMessage>()
        val receiver = MeshProtocolEngine(
            identity = newIdentity(),
            onMessage = received::add,
            onPeersChanged = {},
            onRelay = {},
            peerAddresses = { emptySet() },
            nowMs = { now }
        )
        receiver.receive(sender.announcePacket(), "sender-link")

        val text = buildString {
            val random = Random(87)
            repeat(1400) { append(('a'.code + random.nextInt(26)).toChar()) }
        }
        val original = sender.messagePacket(text)
        val encoded = MeshPacketCodec.encode(original)!!
        assertTrue("fixture must require BLE fragmentation", encoded.size > 512)

        val chunkSize = 469
        val total = (encoded.size + chunkSize - 1) / chunkSize
        val fragmentId = ByteArray(8) { (it + 1).toByte() }
        repeat(total) { index ->
            val start = index * chunkSize
            val fragmentBytes = encoded.copyOfRange(start, minOf(start + chunkSize, encoded.size))
            val payload = fragmentId +
                byteArrayOf(0, index.toByte(), (total ushr 8).toByte(), total.toByte(), MeshMessageType.MESSAGE.toByte()) +
                fragmentBytes
            receiver.receive(
                MeshPacket(
                    type = MeshMessageType.FRAGMENT,
                    senderId = senderIdentity.peerId,
                    timestamp = original.timestamp,
                    payload = payload,
                    ttl = 7
                ),
                "sender-link"
            )
        }

        assertEquals(1, received.size)
        assertEquals(text, received.single().text)
    }

    @Test
    fun relayAndDedupDoNotDependOnMutableHopTtl() {
        val now = 1_800_000_000_000L
        val sender = newEngine(newIdentity(), now).apply { setNickname("Relay test") }
        val relays = mutableListOf<MeshRelay>()
        val receiver = MeshProtocolEngine(
            identity = newIdentity(),
            onMessage = {},
            onPeersChanged = {},
            onRelay = relays::add,
            peerAddresses = { emptySet() },
            nowMs = { now }
        )
        receiver.receive(sender.announcePacket(), "direct")

        val message = sender.messagePacket("hop independent")
        receiver.receive(message, "direct")
        val firstRelayCount = relays.size
        receiver.receive(message.copy(ttl = 5), "another-link")
        assertEquals(firstRelayCount, relays.size)
    }

    @Test
    fun compressedLongPublicMessageIsReassembledAndAuthenticated() {
        val now = 1_800_000_000_000L
        val senderIdentity = newIdentity()
        val sender = newEngine(senderIdentity, now).apply { setNickname("Long sender") }
        val received = mutableListOf<MeshChatMessage>()
        val receiver = MeshProtocolEngine(
            identity = newIdentity(),
            onMessage = received::add,
            onPeersChanged = {},
            onRelay = {},
            peerAddresses = { emptySet() },
            nowMs = { now }
        )
        receiver.receive(sender.announcePacket(), "sender-link")

        val text = "offline mesh ".repeat(1_200)
        val packet = sender.messagePacket(text)
        val encoded = MeshPacketCodec.encode(packet)!!
        assertTrue("long text should use the shared zlib envelope", (encoded[11].toInt() and 0x04) != 0)
        val decoded = MeshPacketCodec.decode(encoded)!!
        receiver.receive(decoded, "sender-link")

        assertEquals(1, received.size)
        assertEquals(text, received.single().text)
    }

    private fun newEngine(identity: MeshIdentity, now: Long) = MeshProtocolEngine(
        identity = identity,
        onMessage = {},
        onPeersChanged = {},
        onRelay = {},
        peerAddresses = { emptySet() },
        nowMs = { now }
    )

    private fun newIdentity(): MeshIdentity {
        val random = SecureRandom()
        val noise = X25519PrivateKeyParameters(random)
        val signing = Ed25519PrivateKeyParameters(random)
        return MeshIdentity(
            noisePrivate = noise.encoded,
            noisePublic = noise.generatePublicKey().encoded,
            signingPrivate = signing.encoded,
            signingPublic = signing.generatePublicKey().encoded
        )
    }
}
