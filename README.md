# SikkerKey Kotlin/JVM SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sikkerkeyofficial/sikkerkey-sdk?color=green)](https://central.sonatype.com/artifact/io.github.sikkerkeyofficial/sikkerkey-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-17+-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)

The official Kotlin/JVM SDK for [SikkerKey](https://sikkerkey.com). Read-only access to secrets using Ed25519 machine authentication. Single dependency: `kotlinx-serialization-json`.

## Installation

### Gradle

```kotlin
dependencies {
    implementation("io.github.sikkerkeyofficial:sikkerkey-sdk:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.sikkerkeyofficial</groupId>
    <artifactId>sikkerkey-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Requires Java 17+.

## Quick Start

```kotlin
import com.sikker.key.sdk.SikkerKey

val sk = SikkerKey("vault_abc123")
val apiKey = sk.getSecret("sk_stripe_key")
```

The SDK reads the machine identity from `~/.sikkerkey/vaults/<vault-id>/identity.json`, signs every request with the machine's Ed25519 private key, and returns the decrypted value. All methods are synchronous (blocking).

## Client Creation

```kotlin
// Explicit vault ID
val sk = SikkerKey("vault_abc123")

// Direct path to identity file
val sk = SikkerKey("/etc/sikkerkey/vaults/vault_abc123/identity.json")

// Auto-detect from SIKKERKEY_IDENTITY env or single vault on disk
val sk = SikkerKey()
```

Throws `ConfigurationException` if the identity is missing, the key can't be loaded, or multiple vaults exist without a specified vault ID.

## Reading Secrets

### Single Value

```kotlin
val apiKey = sk.getSecret("sk_stripe_prod")
```

### Structured (Multiple Fields)

```kotlin
val fields = sk.getFields("sk_db_prod")
val host = fields["host"]       // "db.example.com"
val password = fields["password"] // "hunter2"
```

Throws `SecretStructureException` if the secret value is not a JSON object.

### Single Field

```kotlin
val password = sk.getField("sk_db_prod", "password")
```

Throws `FieldNotFoundException` if the field doesn't exist. The error message includes available field names.

## Listing Secrets

```kotlin
// All secrets this machine can access
val secrets = sk.listSecrets()
for (s in secrets) {
    println("${s.id}: ${s.name}")
}

// Secrets in a specific project
val projectSecrets = sk.listSecretsByProject("proj_production")
```

Returns `List<SecretListItem>` with `id`, `name`, `fieldNames` (nullable), and `projectId` (nullable).

## Export

```kotlin
// All secrets as a flat map
val env = sk.export()
// {API_KEY=sk-live-..., DB_CREDS_HOST=db.example.com, DB_CREDS_PASSWORD=s3cret}

// Scoped to a project
val env = sk.export("proj_production")

// Inject into system properties
sk.export().forEach { (k, v) -> System.setProperty(k, v) }
```

Structured secrets are flattened: `SECRET_NAME_FIELD_NAME`.

## Watching for Changes

Watch secrets for real-time updates. When a secret is rotated, updated, or deleted, the callback fires with the new value. Polling happens on a background daemon thread - your application is never blocked.

```kotlin
sk.watch("sk_db_password") { event ->
    when (event.status) {
        WatchStatus.CHANGED -> {
            println("New value: ${event.value}")
            // Structured secrets include parsed fields
            println("Fields: ${event.fields}")
        }
        WatchStatus.DELETED -> println("Secret was deleted")
        WatchStatus.ACCESS_DENIED -> println("Access revoked")
        WatchStatus.ERROR -> println("Error: ${event.error}")
    }
}
```

### Practical Example

```kotlin
// Auto-rotate database credentials
sk.watch("sk_db_credentials") { event ->
    if (event.status == WatchStatus.CHANGED) {
        Database.configureCredentials(
            event.fields!!["username"]!!,
            event.fields["password"]!!
        )
    }
}
```

### Poll Interval

The default poll interval is 15 seconds. The server enforces a minimum of 10 seconds.

```kotlin
sk.setPollInterval(30) // seconds
```

### Stop Watching

```kotlin
// Stop watching a specific secret
sk.unwatch("sk_db_password")

// Stop all watches and shut down polling
sk.close()
```

`SikkerKey` implements `AutoCloseable`, so it can be used with Kotlin's `use` block or Java's try-with-resources.

## Multi-Vault

```kotlin
val prod = SikkerKey("vault_a1b2c3")
val staging = SikkerKey("vault_x9y8z7")

val prodKey = prod.getSecret("sk_api_key")
val stagingKey = staging.getSecret("sk_api_key")
```

### List Registered Vaults

```kotlin
val vaults = SikkerKey.listVaults()
// ["vault_a1b2c3", "vault_x9y8z7"]
```

Companion object function.

## Java Interop

The SDK works from Java with no additional configuration:

```java
import com.sikker.key.sdk.SikkerKey;

var sk = SikkerKey.Companion.invoke("vault_abc123");
var secret = sk.getSecret("sk_stripe_key");

var fields = sk.getFields("sk_db_prod");
var host = fields.get("host");
```

## Machine Info

```kotlin
sk.machineId    // "550e8400-e29b-41d4-a716-446655440000"
sk.machineName  // "api-server-1"
sk.vaultId      // "vault_abc123"
sk.apiUrl       // "https://api.sikkerkey.com"
```

## Error Handling

```kotlin
import com.sikker.key.sdk.*

try {
    val secret = sk.getSecret("sk_nonexistent")
} catch (e: NotFoundException) {
    // 404 - secret doesn't exist
} catch (e: AccessDeniedException) {
    // 403 - machine not approved or no grant
} catch (e: AuthenticationException) {
    // 401 - invalid signature or unknown machine
} catch (e: ApiException) {
    // any other HTTP error
    println(e.httpStatus)
}
```

### Exception Hierarchy

```
SikkerKeyException (extends RuntimeException)
├── ConfigurationException      - identity/key issues
├── SecretStructureException    - secret is not a JSON object (getFields)
├── FieldNotFoundException      - field not in structured secret (getField)
└── ApiException                - HTTP error (has httpStatus property)
    ├── AuthenticationException - 401
    ├── AccessDeniedException   - 403
    ├── NotFoundException       - 404
    ├── ConflictException       - 409
    ├── RateLimitedException    - 429
    └── ServerSealedException   - 503
```

All exceptions extend `RuntimeException` (unchecked).

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `machineId` | `String` | Machine UUID |
| `machineName` | `String` | Hostname from bootstrap |
| `vaultId` | `String` | Vault this identity belongs to |
| `apiUrl` | `String` | SikkerKey API URL |

## Method Reference

| Method | Returns | Description |
|--------|---------|-------------|
| `SikkerKey(vaultOrPath?)` | `SikkerKey` | Create client (companion `invoke`) |
| `SikkerKey.listVaults()` | `List<String>` | List registered vault IDs (companion) |
| `getSecret(secretId)` | `String` | Read a secret value |
| `getFields(secretId)` | `Map<String, String>` | Read structured secret |
| `getField(secretId, field)` | `String` | Read single field |
| `listSecrets()` | `List<SecretListItem>` | List all accessible secrets |
| `listSecretsByProject(projectId)` | `List<SecretListItem>` | List secrets in a project |
| `export(projectId?)` | `Map<String, String>` | Export as env map |
| `watch(secretId, callback)` | `Unit` | Watch a secret for changes |
| `unwatch(secretId)` | `Unit` | Stop watching a secret |
| `setPollInterval(seconds)` | `Unit` | Set poll interval (min 10s) |
| `close()` | `Unit` | Stop all watches, shut down polling |

## Identity Resolution

1. **Explicit path** - starts with `/` or contains `identity.json`
2. **Vault ID** - looks up `~/.sikkerkey/vaults/{vaultId}/identity.json`
3. **`SIKKERKEY_IDENTITY` env** - path to identity file
4. **Auto-detect** - single vault on disk

The `vault_` prefix is added automatically if not present. Override base directory with `SIKKERKEY_HOME`.

## Retry Behavior

429 and 503 responses are retried up to 3 times with exponential backoff (1s, 2s, 4s). Each retry uses a fresh timestamp and nonce. Network errors (`IOException`) are also retried.

## Environment Variables

| Variable | Description |
|----------|-------------|
| `SIKKERKEY_IDENTITY` | Path to `identity.json` - overrides vault lookup |
| `SIKKERKEY_HOME` | Base config directory (default: `~/.sikkerkey`) |

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `kotlinx-serialization-json` | >=1.7.3 | JSON parsing |

Ed25519 signing uses `java.security.Signature` (JDK built-in, Java 17+). HTTP uses `java.net.HttpURLConnection`. No external HTTP client.

## Documentation

- [SDK Overview](https://docs.sikkerkey.com/docs/sdk/overview)
- [Kotlin SDK Reference](https://docs.sikkerkey.com/docs/sdk/kotlin)
- [Machine Authentication](https://docs.sikkerkey.com/docs/machines/signatures)

## License

MIT - see [LICENSE](LICENSE) for details.
