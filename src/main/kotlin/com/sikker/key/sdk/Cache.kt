package com.sikker.key.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A decrypted cache entry. */
data class CacheResult(
    val secretId: String,
    val name: String,
    val value: String,
    val fieldNames: String?,
    val cachedAt: Long,
)

/**
 * On-disk fallback secret cache — the Kotlin/JVM port of the .skc format defined by the
 * SikkerKey CLI. Files are byte-compatible with the CLI and the other SDKs: same key
 * derivation, AES-256-GCM sealing, AAD, envelope, and path, so a cache written by one is
 * readable by all.
 *
 * Strictly opt-in ([SikkerKey.enableCache]) and inert until then.
 *
 *   key   = HKDF-SHA256(ikm = ed25519_seed, salt = vaultId, info = "sikkerkey-cache-v1")  -> 32 bytes
 *   entry = AES-256-GCM(key, nonce = random 12B, plaintext = {name,value,fieldNames} JSON,
 *                       aad = "sikkerkey-cache-v1 {vaultId} {machineId} {secretId} {cachedAt}")
 */
class SecretCache(
    private val vaultId: String,
    private val machineId: String,
    private val key: ByteArray,
) {
    @Serializable
    private data class Envelope(val v: Int, val nonce: String, val ct: String, val cachedAt: Long)

    fun store(secretId: String, name: String, value: String, fieldNames: String?) {
        require(SAFE_SECRET_ID.matches(secretId)) { "refusing to cache unsafe secret id '$secretId'" }
        val cachedAt = System.currentTimeMillis() / 1000
        val payload = buildJsonObject {
            put("value", value)
            if (name.isNotEmpty()) put("name", name)
            if (fieldNames != null) put("fieldNames", fieldNames)
        }.toString()

        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val ct = seal(key, payload.toByteArray(Charsets.UTF_8), nonce, aad(secretId, cachedAt))
        val envelope = json.encodeToString(
            Envelope.serializer(),
            Envelope(
                FORMAT_VERSION,
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ct),
                cachedAt,
            ),
        )
        writeAtomic(filePath(secretId), envelope.toByteArray(Charsets.UTF_8))
    }

    /** Return the cached entry, or null on a miss. A decrypt failure (tampered, or from a
     *  different identity) throws. */
    fun load(secretId: String): CacheResult? {
        if (!SAFE_SECRET_ID.matches(secretId)) return null
        val file = File(filePath(secretId))
        if (!file.exists()) return null
        return decode(secretId, file.readBytes())
    }

    fun decode(secretId: String, data: ByteArray): CacheResult? {
        val env = json.decodeFromString(Envelope.serializer(), data.toString(Charsets.UTF_8))
        if (env.v != FORMAT_VERSION) return null // a newer format wrote this; treat as a miss
        val nonce = Base64.getDecoder().decode(env.nonce)
        val ct = Base64.getDecoder().decode(env.ct)
        val pt = open(key, nonce, ct, aad(secretId, env.cachedAt)) // throws on wrong key / tamper
        val obj = json.parseToJsonElement(pt.toString(Charsets.UTF_8)).jsonObject
        return CacheResult(
            secretId,
            obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            obj["value"]?.jsonPrimitive?.contentOrNull ?: "",
            obj["fieldNames"]?.jsonPrimitive?.contentOrNull,
            env.cachedAt,
        )
    }

    private fun filePath(secretId: String): String = "${cacheDir(vaultId)}/$secretId$FILE_EXT"

    // domain || vault || machine || secret || timestamp, null-separated.
    private fun aad(secretId: String, cachedAt: Long): ByteArray =
        "$KDF_INFO${Char(0)}$vaultId${Char(0)}$machineId${Char(0)}$secretId${Char(0)}$cachedAt".toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext) // ciphertext || tag(16)
    }

    private fun open(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct) // throws AEADBadTagException on tamper / wrong key
    }

    private fun writeAtomic(path: String, data: ByteArray) {
        val file = File(path)
        val dir = file.parentFile
        dir?.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir!!.toPath(), PosixFilePermissions.fromString("rwx------")) }
        val tmp = File(dir, ".skc-tmp-${java.lang.Long.toHexString(secureRandom.nextLong())}")
        tmp.writeBytes(data)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KDF_INFO = "sikkerkey-cache-v1"
        private const val FILE_EXT = ".skc"

        // Guards the on-disk filename against traversal; real secret ids are sk_<alnum>.
        private val SAFE_SECRET_ID = Regex("^[A-Za-z0-9_-]+$")
        private val secureRandom = SecureRandom()
        private val json = Json { ignoreUnknownKeys = true }

        // Mirrors SikkerKey.baseDir so the cache lands beside the identity.
        private fun baseDir(): String =
            System.getenv("SIKKERKEY_HOME") ?: "${System.getProperty("user.home")}/.sikkerkey"

        fun cacheDir(vaultId: String): String = "${baseDir()}/vaults/$vaultId/cache"

        /** Derive the 32-byte AES-256 cache key from the Ed25519 seed, bound to the vault. */
        fun deriveKey(seed: ByteArray, vaultId: String): ByteArray =
            hkdfSha256(seed, vaultId.toByteArray(Charsets.UTF_8), KDF_INFO.toByteArray(Charsets.UTF_8), 32)

        // RFC 5869 HKDF over HMAC-SHA256, hand-rolled (no extra dependency).
        private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
            mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var i = 1
            while (pos < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(t)
                mac.update(info)
                mac.update(i.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                i++
            }
            return out
        }
    }
}
