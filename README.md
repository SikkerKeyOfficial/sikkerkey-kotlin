# SikkerKey Kotlin/JVM SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sikkerkeyofficial/sikkerkey-sdk?color=green)](https://central.sonatype.com/artifact/io.github.sikkerkeyofficial/sikkerkey-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-17+-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)

Use the official SikkerKey Kotlin/JVM SDK to give a Kotlin or Java application read access to the secrets its machine is authorized to use.

The SDK can:

- Read standard and structured secrets.
- List the secrets available to a machine.
- Export accessible secrets as application-friendly key/value pairs.
- Monitor selected secrets for changes.
- Use persistent machine identities or memory-only ephemeral identities.
- Keep an optional encrypted fallback cache for temporary service or network outages.

After the client is initialized, every secret request is authenticated with the machine's Ed25519 identity. The SDK is synchronous, uses the Java 17 networking and cryptography APIs, and has one direct runtime dependency: `kotlinx-serialization-json`.

## Requirements

- Java 17 or newer.
- A SikkerKey vault.
- A machine identity with access to the secrets your application needs.

Persistent applications use an identity provisioned on the host. Serverless jobs and other short-lived workloads can enroll an ephemeral machine in memory with an enrollment token.

## Install the SDK

### Gradle

```kotlin
dependencies {
    implementation("io.github.sikkerkeyofficial:sikkerkey-sdk:1.3.0")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.sikkerkeyofficial</groupId>
    <artifactId>sikkerkey-sdk</artifactId>
    <version>1.3.0</version>
</dependency>
```

## Read your first secret

Create a client for your vault and pass a secret ID to `getSecret`:

```kotlin
import com.sikker.key.sdk.SikkerKey

val sikkerKey = SikkerKey("vault_abc123")
val apiKey = sikkerKey.getSecret("sk_stripe_key")
```

The SDK loads the machine identity from:

```text
~/.sikkerkey/vaults/vault_abc123/identity.json
```

It signs the request with the machine's Ed25519 private key and returns the secret value as a `String`. Your application's access remains limited by the machine's configured access.

All SDK methods are synchronous. Run network calls on an appropriate worker or I/O dispatcher when calling them from asynchronous application code.

## Create a client

Choose the form that fits how identities are installed in your environment:

```kotlin
// Select a registered vault.
val byVault = SikkerKey("vault_abc123")

// Load a specific identity file.
val byPath = SikkerKey("/etc/sikkerkey/vaults/vault_abc123/identity.json")

// Use SIKKERKEY_IDENTITY or auto-select the only registered vault.
val automatically = SikkerKey()
```

When no argument is supplied, the SDK checks `SIKKERKEY_IDENTITY` first. If that variable is not set, it uses the only registered vault under `~/.sikkerkey/vaults/`.

If more than one vault is registered, select a vault explicitly. Missing identities, unreadable keys, invalid identity files, and ambiguous vault selection produce a `ConfigurationException`.

The `vault_` prefix is added when a vault ID is supplied without it.

### Use a different identity directory

Set `SIKKERKEY_HOME` to move the SDK's base directory:

```bash
export SIKKERKEY_HOME=/var/lib/sikkerkey
```

The SDK will look for identities under:

```text
/var/lib/sikkerkey/vaults/<vault-id>/identity.json
```

## Use an ephemeral identity

`bootstrapInMemory` is designed for short-lived or read-only environments where an identity should not be stored on disk.

```kotlin
val sikkerKey = SikkerKey.bootstrapInMemory(
    vaultId = System.getenv("SIKKERKEY_VAULT_ID"),
    token = System.getenv("SIKKERKEY_ENROLLMENT_TOKEN"),
)

val databaseUrl = sikkerKey.getSecret("sk_db_prod")
```

During bootstrap, the SDK:

1. Generates an Ed25519 key pair in memory.
2. Uses the enrollment token to register an ephemeral machine.
3. Keeps the private key inside the running process.
4. Returns a client ready to read the secrets allowed by the token's access policy.

Nothing is written to disk by `bootstrapInMemory`. The private key disappears when the process exits.

The enrollment token registers the machine; it does not read secrets itself. The resulting machine remains subject to the token's permitted scope, use limit, hostname rules, and machine lifetime. Once the machine expires, subsequent reads produce an `AuthenticationException`.

### Set the machine hostname and name

```kotlin
val sikkerKey = SikkerKey.bootstrapInMemory(
    vaultId = vaultId,
    token = enrollmentToken,
    hostname = "worker-1",
    name = "invoice-runner",
)
```

`hostname` defaults to the `HOSTNAME` environment variable and then to `serverless`. A name pattern configured on the enrollment token takes precedence over the `name` argument.

For reliable ephemeral deployments:

- Set a machine lifetime long enough for the workload to finish.
- Allow enough token uses for expected cold starts and concurrency.
- Use a unique name pattern such as `worker-{uuid8}`.
- Ensure the vault's IP allowlist permits the workload's outbound address when an allowlist is enabled.

Each active ephemeral machine uses a machine slot until it expires.

## Read secrets

### Standard secrets

Use `getSecret` when you need the complete value:

```kotlin
val apiKey: String = sikkerKey.getSecret("sk_stripe_prod")
```

### Structured secrets

Use `getFields` to read a structured secret as field names and values:

```kotlin
val database = sikkerKey.getFields("sk_db_prod")

val host = database["host"]
val username = database["username"]
val password = database["password"]
```

`getFields` expects the stored value to be a JSON object with scalar field values. It throws `SecretStructureException` when the value cannot be read as that structure.

Use `getField` when your application needs one field:

```kotlin
val password = sikkerKey.getField("sk_db_prod", "password")
```

If the field is missing, `FieldNotFoundException` includes the available field names.

## Discover accessible secrets

`listSecrets` returns metadata for every secret the machine can access:

```kotlin
val secrets = sikkerKey.listSecrets()

for (secret in secrets) {
    println("${secret.id}: ${secret.name}")
}
```

Use `listSecretsByProject` to limit the result to one project:

```kotlin
val productionSecrets =
    sikkerKey.listSecretsByProject("proj_production")
```

Each `SecretListItem` contains:

| Property | Type | Meaning |
|---|---|---|
| `id` | `String` | Secret ID used by read methods |
| `name` | `String` | Display name |
| `fieldNames` | `String?` | Optional structured-field metadata |
| `projectId` | `String?` | Owning project, when present |

Listing returns metadata, not secret values.

## Export secrets for application configuration

`export` retrieves accessible values in one request and returns a flat `Map<String, String>`:

```kotlin
val configuration = sikkerKey.export()

configuration.forEach { (name, value) ->
    System.setProperty(name, value)
}
```

Limit the export to a project when the application only needs that scope:

```kotlin
val productionConfiguration =
    sikkerKey.export("proj_production")
```

Names are converted to uppercase environment-style keys. Unsupported characters become underscores. Structured secrets are expanded into one entry per field:

```text
API_KEY
DB_CREDENTIALS_HOST
DB_CREDENTIALS_USERNAME
DB_CREDENTIALS_PASSWORD
```

## Continue reads during temporary outages

The fallback cache is disabled by default. Enable it for persistent hosts that should continue using a recently retrieved value when SikkerKey or the network is temporarily unreachable:

```kotlin
val sikkerKey = SikkerKey("vault_abc123")
    .enableCache()
```

After the cache is enabled, each successful `getSecret` read stores an encrypted entry under:

```text
~/.sikkerkey/vaults/<vault-id>/cache/
```

`getFields` and `getField` use `getSecret`, so their successful reads are cached as well.

The SDK can return a cached value after:

- A network connection failure.
- HTTP `502`, `503`, or `504`.
- Cloudflare origin-connectivity responses `520` through `527`, or `530`.

An authoritative response is never replaced by a cached value. Authentication failures, revoked access, missing secrets, rate limits, and other application responses continue to reach your code normally.

Cache files use AES-256-GCM with a key derived from the machine's Ed25519 identity and vault ID. A file cannot be decrypted without the matching machine identity, and tampered entries are rejected.

### Limit cache age

Set `maxAge` to the maximum age, in seconds, that your application accepts during an outage:

```kotlin
val sikkerKey = SikkerKey("vault_abc123").enableCache(
    maxAge = 3600,
)
```

Without `maxAge`, cached values do not expire automatically. They are still only read when the live service cannot be reached.

### Observe fallback use

Use `onFallback` to record when the SDK serves a cached value:

```kotlin
val sikkerKey = SikkerKey("vault_abc123").enableCache(
    maxAge = 3600,
    onFallback = { secretId, cachedAt ->
        println("Used cached value for $secretId from epoch $cachedAt")
    },
)
```

The SDK does not emit a cache-fallback message unless you supply this callback.

The fallback cache is intended for a host with a persistent, protected identity directory. It is not useful for a memory-only identity that disappears when the process exits.

## Monitor secrets for changes

Use `watch` when your application should react after a secret changes, is deleted, or becomes inaccessible:

```kotlin
import com.sikker.key.sdk.WatchStatus

sikkerKey.watch("sk_db_credentials") { event ->
    when (event.status) {
        WatchStatus.CHANGED -> {
            val username = event.fields?.get("username")
            val password = event.fields?.get("password")
            println("Database credentials changed for ${event.secretId}")
        }

        WatchStatus.DELETED ->
            println("${event.secretId} was deleted")

        WatchStatus.ACCESS_DENIED ->
            println("Access to ${event.secretId} was removed")

        WatchStatus.ERROR ->
            println("Could not retrieve the update: ${event.error}")
    }
}
```

The SDK polls on a background daemon thread every 15 seconds by default. Your callback also runs on that polling thread, so hand off slow or blocking work to your application's executor.

For `CHANGED` events:

- `event.value` contains the new complete value.
- `event.fields` contains parsed fields when the value is a structured JSON object.

Deleted and inaccessible secrets are automatically removed from the watch list.

### Change the polling interval

```kotlin
sikkerKey.setPollInterval(30)
```

The value is in seconds. Values below 10 are raised to 10 seconds.

### Stop monitoring

```kotlin
// Stop one watch.
sikkerKey.unwatch("sk_db_credentials")

// Stop all watches and shut down the polling thread.
sikkerKey.close()
```

`SikkerKey` implements `AutoCloseable`, so you can scope it with `use`:

```kotlin
SikkerKey("vault_abc123").use { sikkerKey ->
    val password = sikkerKey.getField("sk_db_credentials", "password")
    startApplication(password)
}
```

## Work with more than one vault

Create one client per vault:

```kotlin
val production = SikkerKey("vault_production")
val staging = SikkerKey("vault_staging")

val productionKey = production.getSecret("sk_api_key")
val stagingKey = staging.getSecret("sk_api_key")
```

List the vault identities registered under `SIKKERKEY_HOME`:

```kotlin
val vaultIds: List<String> = SikkerKey.listVaults()
```

## Inspect the active machine

The client exposes the identity it is using:

```kotlin
println(sikkerKey.machineId)
println(sikkerKey.machineName)
println(sikkerKey.vaultId)
println(sikkerKey.apiUrl)
```

| Property | Meaning |
|---|---|
| `machineId` | Machine UUID assigned by SikkerKey |
| `machineName` | Machine name assigned during provisioning or enrollment |
| `vaultId` | Vault associated with the machine identity |
| `apiUrl` | Retrieval endpoint stored in the identity |

## Handle errors

The SDK's exception hierarchy starts with the unchecked `SikkerKeyException` type:

```kotlin
import com.sikker.key.sdk.*

try {
    val value = sikkerKey.getSecret("sk_example")
} catch (error: NotFoundException) {
    // The requested secret or resource was not found.
} catch (error: AccessDeniedException) {
    // The machine is not allowed to perform this read.
} catch (error: AuthenticationException) {
    // The machine identity could not be authenticated.
} catch (error: RateLimitedException) {
    // The request remained rate-limited after automatic retries.
} catch (error: ApiException) {
    println("SikkerKey returned HTTP ${error.httpStatus}: ${error.message}")
} catch (error: ConfigurationException) {
    println("The machine identity could not be loaded: ${error.message}")
}
```

### Exception reference

| Exception | When it is used |
|---|---|
| `ConfigurationException` | Identity, key, vault-selection, or bootstrap configuration is invalid |
| `AuthenticationException` | HTTP `401` |
| `AccessDeniedException` | HTTP `403` |
| `NotFoundException` | HTTP `404` |
| `ConflictException` | HTTP `409` |
| `RateLimitedException` | HTTP `429` |
| `ServerSealedException` | HTTP `503` |
| `ApiException` | Another HTTP or network error; inspect `httpStatus` |
| `SecretStructureException` | `getFields` or `getField` received a non-structured value |
| `FieldNotFoundException` | The requested structured field does not exist |

Network failures use an `ApiException` with `httpStatus == 0`.

### Retries and timeouts

The SDK automatically retries network failures and HTTP `429` or `503` responses up to three times. Retries wait 1, 2, and 4 seconds, and every attempt receives a fresh timestamp and nonce.

Connections and responses each use a 15-second timeout. Other HTTP responses are returned immediately as their matching exception.

## Use the SDK from Java

The Kotlin companion methods are available through `SikkerKey.Companion`:

```java
import com.sikker.key.sdk.SikkerKey;

var sikkerKey = SikkerKey.Companion.invoke("vault_abc123");
var apiKey = sikkerKey.getSecret("sk_stripe_key");

var database = sikkerKey.getFields("sk_db_prod");
var host = database.get("host");
```

## Feature-to-API reference

| What you want to do | SDK API | Result |
|---|---|---|
| Create a client from disk | `SikkerKey(vaultOrPath?)` | `SikkerKey` |
| Create a memory-only ephemeral client | `SikkerKey.bootstrapInMemory(vaultId, token, hostname?, name?)` | `SikkerKey` |
| List locally registered vaults | `SikkerKey.listVaults()` | `List<String>` |
| Enable outage fallback | `enableCache(maxAge?, onFallback?)` | The same `SikkerKey` client |
| Read a standard secret | `getSecret(secretId)` | `String` |
| Read every structured field | `getFields(secretId)` | `Map<String, String>` |
| Read one structured field | `getField(secretId, field)` | `String` |
| List accessible secrets | `listSecrets()` | `List<SecretListItem>` |
| List accessible secrets in a project | `listSecretsByProject(projectId)` | `List<SecretListItem>` |
| Export accessible values | `export(projectId?)` | `Map<String, String>` |
| Monitor a secret | `watch(secretId, callback)` | `Unit` |
| Stop monitoring one secret | `unwatch(secretId)` | `Unit` |
| Set the polling interval | `setPollInterval(seconds)` | `Unit` |
| Stop all monitoring | `close()` | `Unit` |

## Runtime footprint

The SDK uses:

- `kotlinx-serialization-json` `1.7.3` for JSON.
- `java.net.HttpURLConnection` for HTTPS requests.
- Java's built-in Ed25519 implementation for request signing.
- Java's built-in AES-GCM and HMAC-SHA256 implementations for the optional cache.

No external HTTP or cryptography client is required.

## Documentation

- [SikkerKey documentation](https://docs.sikkerkey.com)
- [SDK overview](https://docs.sikkerkey.com/docs/sdk/overview)
- [Kotlin SDK reference](https://docs.sikkerkey.com/docs/sdk/kotlin)
- [Machine authentication](https://docs.sikkerkey.com/docs/machines/signatures)

## License

The SikkerKey Kotlin/JVM SDK is available under the [MIT License](LICENSE).
