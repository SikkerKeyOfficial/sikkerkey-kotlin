plugins {
    kotlin("plugin.serialization")
    `maven-publish`
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.sikker"
            artifactId = "sikkerkey-sdk"
            version = project.version.toString()
            from(components["kotlin"])

            pom {
                name.set("SikkerKey SDK")
                description.set("Kotlin SDK for reading and rotating secrets from a SikkerKey vault.")
                url.set("https://github.com/sikkerkey/sikkerkey-sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
