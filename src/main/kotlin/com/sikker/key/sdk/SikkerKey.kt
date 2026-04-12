package com.sikker.key.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

/**
 * SikkerKey SDK — manage secrets from a SikkerKey vault.
 *
 * ## Quick Start
 *
 * ```kotlin
 * val sk = SikkerKey("vault_abc123")
 * val secret = sk.getSecret("sk_a1b2c3d4e5")
 * ```
 *
 * ## Structured Secrets
 *
 * ```kotlin
 * val db = sk.getFields("sk_db_prod")
 * val host = db["host"]
 * val pass = db["password"]
 * ```
 *
 *
 * ## Identity Resolution
 *
 * 1. Explicit path: `SikkerKey("/path/to/identity.json")`
 * 2. By vault ID: `~/.sikkerkey/vaults/{vaultId}/identity.json`
 * 3. Environment variable: `SIKKERKEY_IDENTITY`
 * 4. Auto-detect: if only one vault is registered, use it
 *
 * @throws SikkerKeyException if the identity file is missing or malformed.
 */
class SikkerKey private constructor(
    private val identity: Identity,
    private val privateKey: PrivateKey,
) : AutoCloseable {
    private val secureRandom = SecureRandom()
    private val watchers = java.util.concurrent.ConcurrentHashMap<String, (WatchEvent) -> Unit>()
    @Volatile private var pollThread: Thread? = null
    @Volatile private var pollIntervalMs: Long = 15_000

    /** The machine ID assigned during bootstrap. */
    val machineId: String get() = identity.machineId

    /** The machine name assigned during bootstrap. */
    val machineName: String get() = identity.machineName

    /** The vault ID this machine is registered with. */
    val vaultId: String get() = identity.vaultId

    /** The SikkerKey API URL. */
    val apiUrl: String get() = identity.apiUrl

    companion object {
        private fun baseDir(): String =
            System.getenv("SIKKERKEY_HOME") ?: "${System.getProperty("user.home")}/.sikkerkey"
        private fun vaultsDir(): String = "${baseDir()}/vaults"

        /**
         * Create a SikkerKey instance for the given vault.
         *
         * @param vaultOrPath A vault ID (e.g. `vault_abc123`), a path to an identity.json file,
         *                    or null to auto-detect.
         */
        operator fun invoke(vaultOrPath: String? = null): SikkerKey {
            val identityFile = resolveIdentity(vaultOrPath)
            return loadFromFile(identityFile)
        }

        private fun resolveIdentity(vaultOrPath: String?): File {
            if (vaultOrPath != null && (vaultOrPath.startsWith("/") || vaultOrPath.contains("identity.json"))) {
                return File(vaultOrPath)
            }

            if (vaultOrPath != null) {
                val vaultId = if (vaultOrPath.startsWith("vault_")) vaultOrPath else "vault_$vaultOrPath"
                val vaultFile = File("${vaultsDir()}/$vaultId/identity.json")
                if (vaultFile.exists()) return vaultFile
                throw ConfigurationException("No identity found for vault '$vaultId'. Expected: ${vaultFile.absolutePath}. Run the bootstrap command first.")
            }

            val envPath = System.getenv("SIKKERKEY_IDENTITY")
            if (!envPath.isNullOrBlank()) return File(envPath)

            val vaultsDirFile = File(vaultsDir())
            if (vaultsDirFile.isDirectory) {
                val vaultDirs = vaultsDirFile.listFiles()?.filter {
                    it.isDirectory && File(it, "identity.json").exists()
                } ?: emptyList()

                if (vaultDirs.size == 1) return File(vaultDirs[0], "identity.json")
                if (vaultDirs.size > 1) {
                    val names = vaultDirs.map { it.name }.joinToString(", ")
                    throw ConfigurationException("Multiple vaults registered: $names. Specify which vault to use: SikkerKey(\"vault_id\")")
                }
            }

            throw ConfigurationException(
                "No SikkerKey identity found. Run the bootstrap command first.\n" +
                "  Checked: ${vaultsDir()}/*/identity.json"
            )
        }

        private fun loadFromFile(file: File): SikkerKey {
            if (!file.exists()) throw ConfigurationException("Identity file not found: ${file.absolutePath}. Run the bootstrap command first.")
            if (!file.canRead()) throw ConfigurationException("Cannot read identity file: ${file.absolutePath}. Check file permissions.")

            val identity = try {
                Json.decodeFromString<Identity>(file.readText())
            } catch (e: Exception) {
                throw ConfigurationException("Failed to parse identity file: ${e.message}", e)
            }

            if (!identity.apiUrl.startsWith("https://") && !identity.apiUrl.startsWith("http://localhost")) {
                throw ConfigurationException("API URL must use HTTPS: ${identity.apiUrl}. Use http://localhost only for local development.")
            }

            val keyFile = File(identity.privateKeyPath)
            if (!keyFile.exists()) throw ConfigurationException("Private key not found: ${identity.privateKeyPath}")
            if (!keyFile.canRead()) throw ConfigurationException("Cannot read private key: ${identity.privateKeyPath}. Check file permissions.")

            val privateKey = try {
                loadEd25519PrivateKey(keyFile)
            } catch (e: Exception) {
                throw ConfigurationException("Failed to load private key: ${e.message}", e)
            }

            return SikkerKey(identity, privateKey)
        }

        /**
         * List all vaults this machine is registered with.
         */
        fun listVaults(): List<String> {
            val vaultsDirFile = File(vaultsDir())
            if (!vaultsDirFile.isDirectory) return emptyList()
            return vaultsDirFile.listFiles()
                ?.filter { it.isDirectory && File(it, "identity.json").exists() }
                ?.map { it.name }
                ?: emptyList()
        }

        private fun loadEd25519PrivateKey(file: File): PrivateKey {
            val pem = file.readText()
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(base64)
            val keySpec = PKCS8EncodedKeySpec(bytes)
            return KeyFactory.getInstance("Ed25519").generatePrivate(keySpec)
        }
    }

    // ── Read ──

    /**
     * Fetch a secret value by ID.
     *
     * @param secretId The secret ID (e.g. `sk_a1b2c3d4e5`).
     * @return The decrypted secret value as a string.
     */
    fun getSecret(secretId: String): String {
        return request("GET", "/v1/secret/$secretId").parseValue()
    }

    /**
     * Fetch a structured secret and return its fields as a map.
     *
     * @param secretId The secret ID.
     * @return A map of field names to values.
     * @throws SikkerKeyException if the secret is not a structured JSON object.
     */
    fun getFields(secretId: String): Map<String, String> {
        val raw = getSecret(secretId)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            throw SecretStructureException("Secret $secretId is not a structured secret")
        }
    }

    /**
     * Fetch a single field from a structured secret.
     *
     * @param secretId The secret ID.
     * @param field The field name.
     * @return The field value.
     * @throws SikkerKeyException if the secret is not structured or the field doesn't exist.
     */
    fun getField(secretId: String, field: String): String {
        val fields = getFields(secretId)
        return fields[field]
            ?: throw FieldNotFoundException("Field '$field' not found in secret $secretId. Available: ${fields.keys.joinToString()}")
    }

    // ── List ──

    /**
     * List all secrets this machine has access to.
     *
     * @return A list of secret metadata (ID, name, field names, project ID).
     */
    fun listSecrets(): List<SecretListItem> {
        val body = request("GET", "/v1/secrets")
        return Json.decodeFromString<SecretListResponse>(body).secrets
    }

    /**
     * List secrets in a specific project.
     *
     * @param projectId The project ID.
     * @return A list of secret metadata in the project.
     */
    fun listSecretsByProject(projectId: String): List<SecretListItem> {
        val payload = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("projectId", JsonPrimitive(projectId))
        })
        val body = request("POST", "/v1/secrets/list", payload)
        return Json.decodeFromString<SecretListResponse>(body).secrets
    }

    // ── Export ──

    /**
     * Export all secrets this machine can access as a flat key-value map (single round trip).
     * Structured secrets are flattened: `SECRET_NAME_FIELD_NAME`.
     *
     * @param projectId Optional project ID to scope the export. Null = all projects.
     * @return A map of environment variable names to values.
     */
    fun export(projectId: String? = null): Map<String, String> {
        val payload = if (projectId != null) {
            Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("projectId", JsonPrimitive(projectId)) })
        } else null
        val body = request("POST", "/v1/secrets/export", payload)
        val resp = Json.decodeFromString<ExportResponseBody>(body)
        val result = linkedMapOf<String, String>()
        for (entry in resp.secrets) {
            val envName = toEnvName(entry.name)
            if (entry.fieldNames != null) {
                try {
                    val obj = Json.parseToJsonElement(entry.value).jsonObject
                    for ((k, v) in obj) {
                        result["${envName}_${toEnvName(k)}"] = v.jsonPrimitive.content
                    }
                    continue
                } catch (_: Exception) { /* fall through to simple */ }
            }
            result[envName] = entry.value
        }
        return result
    }

    // ── Watch ──

    /**
     * Watch a secret for changes. When the secret is updated, rotated, deleted,
     * or access is revoked, the callback fires with a [WatchEvent] describing
     * what changed and the new value (if applicable).
     *
     * Polling starts automatically when the first watch is registered.
     * The poll interval is 15 seconds by default (server enforces a 10-second minimum).
     *
     * ```kotlin
     * sk.watch("sk_db_password") { event ->
     *     if (event.status == WatchStatus.CHANGED) {
     *         connectionPool.reconfigure(password = event.value!!)
     *     }
     * }
     * ```
     *
     * @param secretId The secret ID to watch.
     * @param callback Called on the polling thread when the secret changes.
     */
    fun watch(secretId: String, callback: (WatchEvent) -> Unit) {
        watchers[secretId] = callback
        ensurePolling()
    }

    /**
     * Stop watching a secret. If no watches remain, polling stops automatically.
     */
    fun unwatch(secretId: String) {
        watchers.remove(secretId)
        if (watchers.isEmpty()) stopPolling()
    }

    /**
     * Set the poll interval in seconds. Minimum 10 (enforced by the server).
     * Default is 15 seconds.
     */
    fun setPollInterval(seconds: Int) {
        pollIntervalMs = maxOf(10, seconds).toLong() * 1000
    }

    /**
     * Stop all watches and shut down the polling thread.
     */
    override fun close() {
        stopPolling()
        watchers.clear()
    }

    private fun ensurePolling() {
        if (pollThread?.isAlive == true) return
        synchronized(this) {
            if (pollThread?.isAlive == true) return
            val thread = Thread({
                while (watchers.isNotEmpty() && !Thread.currentThread().isInterrupted) {
                    try {
                        pollOnce()
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        // Log but don't crash the poll loop
                        System.err.println("SikkerKey poll error: ${e.message}")
                    }
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "sikkerkey-poll")
            thread.isDaemon = true
            thread.start()
            pollThread = thread
        }
    }

    private fun stopPolling() {
        pollThread?.interrupt()
        pollThread = null
    }

    private fun pollOnce() {
        val watchList = watchers.keys().toList()
        if (watchList.isEmpty()) return

        val payload = Json.encodeToString(PollRequestBody.serializer(), PollRequestBody(watch = watchList))
        val responseBody = request("POST", "/v1/secrets/poll", payload)
        val response = Json.decodeFromString<PollResponseBody>(responseBody)

        for ((secretId, change) in response.changes) {
            val callback = watchers[secretId] ?: continue

            when (change.status) {
                "changed" -> {
                    try {
                        val newValue = getSecret(secretId)
                        val fields = try {
                            val obj = Json.parseToJsonElement(newValue).jsonObject
                            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                        } catch (_: Exception) { null }
                        callback(WatchEvent(secretId, WatchStatus.CHANGED, newValue, fields = fields))
                    } catch (e: Exception) {
                        callback(WatchEvent(secretId, WatchStatus.ERROR, null, error = e.message))
                    }
                }
                "deleted" -> {
                    callback(WatchEvent(secretId, WatchStatus.DELETED, null))
                    watchers.remove(secretId)
                }
                "access_denied" -> {
                    callback(WatchEvent(secretId, WatchStatus.ACCESS_DENIED, null))
                    watchers.remove(secretId)
                }
            }
        }
    }

    // ── Internal HTTP ──

    private val retryableCodes = setOf(429, 503)
    private val maxRetries = 3
    private val backoffMs = longArrayOf(1000, 2000, 4000)

    private fun request(method: String, path: String, body: String? = null, expectStatus: Int = 200): String {
        var lastException: SikkerKeyException? = null

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                Thread.sleep(backoffMs[(attempt - 1).coerceAtMost(backoffMs.size - 1)])
            }

            // Fresh nonce + timestamp per attempt (replay protection)
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonce = Base64.getEncoder().encodeToString(ByteArray(16).also { secureRandom.nextBytes(it) })
            val bodyHash = sha256(body ?: "")
            val signature = sign("$method:$path:$timestamp:$nonce:$bodyHash")

            val url = URI("${identity.apiUrl}$path").toURL()
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.setRequestProperty("X-Machine-Id", identity.machineId)
                conn.setRequestProperty("X-Timestamp", timestamp)
                conn.setRequestProperty("X-Nonce", nonce)
                conn.setRequestProperty("X-Signature", signature)
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000

                if (body != null) {
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }

                val code: Int
                val responseBody: String
                try {
                    code = conn.responseCode
                    responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.use { it.bufferedReader().readText() } ?: ""
                } catch (e: java.io.IOException) {
                    lastException = ApiException("Network error: ${e.message}", 0)
                    continue
                }

                if (code == expectStatus) {
                    return responseBody
                }

                val error = try {
                    Json.decodeFromString<ErrorBody>(responseBody).error
                } catch (_: Exception) { responseBody.ifBlank { "HTTP $code" } }

                val exception = when (code) {
                    401 -> AuthenticationException(error)
                    403 -> AccessDeniedException(error)
                    404 -> NotFoundException(error)
                    409 -> ConflictException(error)
                    429 -> RateLimitedException(error)
                    503 -> ServerSealedException(error)
                    else -> ApiException(error, code)
                }

                if (code in retryableCodes && attempt < maxRetries) {
                    lastException = exception
                    continue
                }

                throw exception
            } finally {
                conn.disconnect()
            }
        }

        throw lastException ?: ApiException("Request failed after $maxRetries retries", 0)
    }

    private fun sign(message: String): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun String.parseValue(): String {
        return Json.decodeFromString<SecretBody>(this).value
    }
}

// ── Exceptions ──

/** Base exception for all SikkerKey SDK errors. */
open class SikkerKeyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Identity file missing, malformed, or private key not found. */
class ConfigurationException(message: String, cause: Throwable? = null) : SikkerKeyException(message, cause)

/** HTTP error from the SikkerKey API. */
open class ApiException(message: String, val httpStatus: Int) : SikkerKeyException(message)

/** 401 — signature verification failed or machine unknown. */
class AuthenticationException(message: String, httpStatus: Int = 401) : ApiException(message, httpStatus)

/** 403 — machine not approved, disabled, or no access grant. */
class AccessDeniedException(message: String, httpStatus: Int = 403) : ApiException(message, httpStatus)

/** 404 — secret or resource not found. */
class NotFoundException(message: String, httpStatus: Int = 404) : ApiException(message, httpStatus)

/** 409 — conflict (e.g. secret already exists). */
class ConflictException(message: String, httpStatus: Int = 409) : ApiException(message, httpStatus)

/** 429 — too many requests. */
class RateLimitedException(message: String, httpStatus: Int = 429) : ApiException(message, httpStatus)

/** 503 — server is sealed, awaiting unseal. */
class ServerSealedException(message: String, httpStatus: Int = 503) : ApiException(message, httpStatus)

/** Tried to use getFields/getField on a non-structured secret. */
class SecretStructureException(message: String) : SikkerKeyException(message)

/** Field not found in a structured secret. */
class FieldNotFoundException(message: String) : SikkerKeyException(message)

/** Secret metadata returned by list operations. */
@Serializable
data class SecretListItem(
    val id: String,
    val name: String,
    val fieldNames: String? = null,
    val projectId: String? = null,
)

// ── Internal types ──

@Serializable
internal data class Identity(
    val machineId: String,
    val machineName: String,
    val vaultId: String = "",
    val apiUrl: String,
    val privateKeyPath: String,
)

@Serializable
internal data class SecretBody(val value: String)

@Serializable
internal data class ErrorBody(val error: String)

@Serializable
internal data class SecretListResponse(val secrets: List<SecretListItem>)

@Serializable
internal data class ExportSecretEntry(val id: String, val name: String, val value: String, val fieldNames: String? = null)

@Serializable
internal data class ExportResponseBody(val secrets: List<ExportSecretEntry>)

// ── Watch types ──

/** Status of a watched secret change. */
enum class WatchStatus {
    /** Secret value was updated or rotated. [WatchEvent.value] contains the new value. */
    CHANGED,
    /** Secret was deleted. */
    DELETED,
    /** Machine no longer has access to this secret. */
    ACCESS_DENIED,
    /** Failed to fetch the updated value. [WatchEvent.error] contains the reason. */
    ERROR,
}

/** Event delivered to a [SikkerKey.watch] callback. */
data class WatchEvent(
    val secretId: String,
    val status: WatchStatus,
    val value: String?,
    val error: String? = null,
    /** Parsed fields for structured secrets. Null for simple secrets or non-CHANGED events. */
    val fields: Map<String, String>? = null,
)

@Serializable
internal data class PollRequestBody(val watch: List<String>)

@Serializable
internal data class PollChangeBody(val status: String)

@Serializable
internal data class PollResponseBody(val changes: Map<String, PollChangeBody>)

// ── Utilities ──

private fun toEnvName(name: String): String {
    return name.uppercase()
        .replace(Regex("[^A-Z0-9]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
}

