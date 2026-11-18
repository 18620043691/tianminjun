package com.bitchat.droid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class MeshIdentity(
    val noisePrivate: ByteArray,
    val noisePublic: ByteArray,
    val signingPrivate: ByteArray,
    val signingPublic: ByteArray
) {
    val peerId: ByteArray = MessageDigest.getInstance("SHA-256").digest(noisePublic).copyOfRange(0, 8)
    val peerIdHex: String = peerId.hex()

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(signingPrivate, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}

internal class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("mesh-identity", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val keyAlias = "bitchat-mesh-identity-key"

    @Synchronized
    fun loadOrCreate(): MeshIdentity {
        val saved = prefs.getString("encryptedIdentity", null)
        val secret = if (saved == null) {
            val noise = X25519PrivateKeyParameters(secureRandom)
            val signing = Ed25519PrivateKeyParameters(secureRandom)
            val identityBytes = noise.encoded + signing.encoded
            encrypt(identityBytes).also {
                check(prefs.edit().putString("encryptedIdentity", Base64.encodeToString(it, Base64.NO_WRAP)).commit()) {
                    "Unable to persist the encrypted mesh identity."
                }
            }
        } else {
            decrypt(Base64.decode(saved, Base64.NO_WRAP))
        }

        require(secret.size == 64) { "Stored mesh identity has an invalid length." }
        val noisePrivate = secret.copyOfRange(0, 32)
        val signingPrivate = secret.copyOfRange(32, 64)
        val noisePublic = X25519PrivateKeyParameters(noisePrivate, 0).generatePublicKey().encoded
        val signingPublic = Ed25519PrivateKeyParameters(signingPrivate, 0).generatePublicKey().encoded
        return MeshIdentity(noisePrivate, noisePublic, signingPrivate, signingPublic)
    }

    fun nickname(identity: MeshIdentity): String =
        prefs.getString("nickname", null)?.takeIf(String::isNotBlank) ?: "Android-${identity.peerIdHex.take(4)}"

    fun saveNickname(nickname: String) {
        require(nickname.isNotBlank() && nickname.toByteArray(Charsets.UTF_8).size <= 64)
        check(prefs.edit().putString("nickname", nickname.trim()).commit()) { "Unable to save nickname." }
    }

    @Synchronized
    fun loadPeerPins(): Map<String, ByteArray> {
        val saved = prefs.getString("encryptedPeerPins", null) ?: return emptyMap()
        val plain = decrypt(Base64.decode(saved, Base64.NO_WRAP))
        require(plain.size >= 2) { "Stored peer identities are truncated." }
        val input = ByteBuffer.wrap(plain)
        val count = input.short.toInt() and 0xFFFF
        require(count <= MAX_PEER_PINS && input.remaining() == count * PEER_PIN_BYTES) {
            "Stored peer identities have an invalid format."
        }
        return buildMap(count) {
            repeat(count) {
                val peerId = ByteArray(8).also(input::get).hex()
                val key = ByteArray(32).also(input::get)
                put(peerId, key)
            }
        }
    }

    @Synchronized
    fun savePeerPin(peerId: String, signingKey: ByteArray) {
        require(signingKey.size == 32)
        val id = peerId.hexBytes()
        require(id.size == 8)
        val pins = loadPeerPins().toMutableMap()
        val existing = pins[peerId]
        if (existing != null) {
            check(existing.contentEquals(signingKey)) { "Refusing to replace a pinned peer signing key." }
            return
        }
        check(pins.size < MAX_PEER_PINS) { "The saved peer-identity limit has been reached." }
        pins[peerId] = signingKey.copyOf()
        val bytes = ByteArrayOutputStream().apply {
            write(pins.size ushr 8)
            write(pins.size)
            pins.toSortedMap().forEach { (key, value) ->
                write(key.hexBytes())
                write(value)
            }
        }.toByteArray()
        val encrypted = Base64.encodeToString(encrypt(bytes), Base64.NO_WRAP)
        check(prefs.edit().putString("encryptedPeerPins", encrypted).commit()) {
            "Unable to persist the peer signing-key pin."
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(data)
        return cipher.iv + encrypted
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 12) { "Encrypted mesh identity is truncated." }
        val iv = data.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data, 12, data.size - 12)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0 && all { it in "0123456789abcdefABCDEF" })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val MAX_PEER_PINS = 4096
        private const val PEER_PIN_BYTES = 40
    }
}
