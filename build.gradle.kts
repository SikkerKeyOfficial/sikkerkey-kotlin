plugins {
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "io.github.sikkerkeyofficial"
version = "1.2.1"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.github.sikkerkeyofficial", "sikkerkey-sdk", project.version.toString())

    pom {
        name.set("SikkerKey SDK")
        description.set("Kotlin SDK for reading secrets from a SikkerKey vault. Ed25519 machine authentication.")
        url.set("https://github.com/SikkerKeyOfficial/sikkerkey-kotlin")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("sikkerkeyofficial")
                name.set("SikkerKey")
                email.set("contact@sikkerkey.com")
            }
        }

        scm {
            url.set("https://github.com/SikkerKeyOfficial/sikkerkey-kotlin")
            connection.set("scm:git:git://github.com/SikkerKeyOfficial/sikkerkey-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/SikkerKeyOfficial/sikkerkey-kotlin.git")
        }
    }
}
