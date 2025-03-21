import groovy.util.*
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.signing.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.tasks.*
import java.net.*

/*
 * Copyright 2017-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

// Configures publishing of Maven artifacts to MavenCentral
plugins {
    `maven-publish`
    signing
}

val isMultiplatform = name in listOf(
    "kotlinx-serialization-core",
    "kotlinx-serialization-json",
    "kotlinx-serialization-json-okio",
    "kotlinx-serialization-json-io",
    "kotlinx-serialization-json-tests",
    "kotlinx-serialization-protobuf",
    "kotlinx-serialization-cbor",
    "kotlinx-serialization-properties"
)

val isBom = name == "kotlinx-serialization-bom"

if (!isBom) {
    tasks.register<Jar>("stubJavadoc") {
        archiveClassifier = "javadoc"
    }
}

tasks.register<Jar>("emptyJar")

afterEvaluate {
    val mainSourcesJar = tasks.register<Jar>("mainSourcesJar") {
        archiveClassifier = "sources"
        if (isMultiplatform) {
            from(kotlinExtension.sourceSets.getByName("commonMain").kotlin)
        } else if (isBom) {
            // no-op: sourceSets is [] for BOM, as it does not have sources.
        } else {
            from(sourceSets.named("main").get().allSource)
        }
    }

    publishing {
        if (!isMultiplatform && !isBom) {
            publications.register<MavenPublication>("maven") {
                artifactId = project.name
                from(components["java"])
                artifact(mainSourcesJar)
                artifact(tasks.named("stubJavadoc"))
            }
        } else {
            // Rename artifacts for backward compatibility
            publications.withType<MavenPublication>().configureEach {
                val type = name
                logger.info("Configuring $type")
                when (type) {
                    "kotlinMultiplatform" -> {
                        // With Kotlin 1.4.0, the root module ID has no suffix, but for compatibility with
                        // the consumers who can't read Gradle module metadata, we publish the JVM artifacts in it
                        artifactId = project.name
                        reconfigureMultiplatformPublication(publications.getByName("jvm") as MavenPublication)
                    }
                    "metadata", "jvm", "js", "native" -> artifactId = "${project.name}-$type"
                }
                logger.info("Artifact id = $artifactId")

                // The 'root' module publishes the JVM module's Javadoc JAR as per reconfigureMultiplatformPublication, and
                // every other module should publish an empty Javadoc JAR. TODO: provide proper documentation artifacts?
                if (name != "kotlinMultiplatform" && !isBom) {
                    artifact(tasks.named("stubJavadoc"))
                }
            }
        }

        publications.withType<MavenPublication>().configureEach {
            pom.configureMavenCentralMetadata()
            signPublicationIfKeyPresent()
        }
    }
}

val testRepositoryDir = project.layout.buildDirectory.dir("testRepository")

publishing {
    repositories {
        addSonatypeRepository()

        /**
         * Maven repository in build directory to check published artifacts.
         */
        maven {
            setUrl(testRepositoryDir)
            name = "test"
        }
    }
}

interface LocalArtifactAttr : Named {
    companion object {
        val ATTRIBUTE = Attribute.of(
            "kotlinx.kover.gradle-plugin",
            LocalArtifactAttr::class.java
        )
    }
}

val testPublicationTask: TaskCollection<*> = tasks.named { name -> name == "publishAllPublicationsToTestRepository" }
configurations.register("testPublication") {
    isVisible = false
    isCanBeResolved = false
    // this configuration produces modules that can be consumed by other projects
    isCanBeConsumed = true
    attributes {
        attribute(Attribute.of("kotlinx.serialization.repository", String::class.java), "test")
    }
    outgoing.artifact(testRepositoryDir) {
        builtBy(testPublicationTask)
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

// NOTE: This is a temporary WA, see KT-61313.
// Task ':compileTestKotlin<platform>' uses this output of task ':sign<platform>Publication' without declaring an explicit or implicit dependency
tasks.withType<KotlinNativeCompile>().matching { it.name.startsWith("compileTestKotlin") }.configureEach {
    val targetName = name.substringAfter("compileTestKotlin")
    mustRunAfter(tasks.withType<Sign>().named { it == "sign${targetName}Publication" })
}

// NOTE: This is a temporary WA, see KT-61313.
// Task ':linkDebugTest<platform>' uses this output of task ':sign<platform>Publication' without declaring an explicit or implicit dependency
tasks.withType<KotlinNativeLink>() {
    val targetName = name.substringAfter("linkDebugTest")
    mustRunAfter(tasks.withType<Sign>().named { it == "sign${targetName}Publication" })
}

fun MavenPom.configureMavenCentralMetadata() {
    name = project.name
    description = "Kotlin multiplatform serialization runtime library"
    url = "https://github.com/Kotlin/kotlinx.serialization"

    licenses {
        license {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            distribution = "repo"
        }
    }

    developers {
        developer {
            id = "JetBrains"
            name = "JetBrains Team"
            organization = "JetBrains"
            organizationUrl = "https://www.jetbrains.com"
        }
    }

    scm {
        url = "https://github.com/Kotlin/kotlinx.serialization"
    }
}

// utility functions

/**
 * Re-configure common publication to depend on JVM artifact only in pom.xml.
 *
 *  Publish the platform JAR and POM so that consumers who depend on this module and can't read Gradle module
 *  metadata can still get the platform artifact and transitive dependencies from the POM.
 *
 *  Taken from https://github.com/Kotlin/kotlinx.coroutines
 */
public fun Project.reconfigureMultiplatformPublication(jvmPublication: MavenPublication) {
    val mavenPublications =
        extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>()
    val kmpPublication = mavenPublications.getByName("kotlinMultiplatform")

    var jvmPublicationXml: XmlProvider? = null
    jvmPublication.pom.withXml { jvmPublicationXml = this }

    kmpPublication.pom.withXml {
        val root = asNode()
        // Remove the original content and add the content from the platform POM:
        root.children().toList().forEach { root.remove(it as Node) }
        jvmPublicationXml!!.asNode().children().forEach { root.append(it as Node) }

        // Adjust the self artifact ID, as it should match the root module's coordinates:
        ((root["artifactId"] as NodeList).first() as Node).setValue(kmpPublication.artifactId)

        // Set packaging to POM to indicate that there's no artifact:
        root.appendNode("packaging", "pom")

        // Remove the original platform dependencies and add a single dependency on the platform module:
        val dependencies = (root["dependencies"] as NodeList).first() as Node
        dependencies.children().toList().forEach { dependencies.remove(it as Node) }
        dependencies.appendNode("dependency").apply {
            appendNode("groupId", jvmPublication.groupId)
            appendNode("artifactId", jvmPublication.artifactId)
            appendNode("version", jvmPublication.version)
            appendNode("scope", "compile")
        }
    }

    // TODO verify if this is still relevant
    tasks.matching { it.name == "generatePomFileForKotlinMultiplatformPublication" }.configureEach {
        @Suppress("DEPRECATION")
        dependsOn("generatePomFileFor${jvmPublication.name.capitalize()}Publication")
    }
}

fun MavenPublication.signPublicationIfKeyPresent() {
    val keyId = getSensitiveProperty("libs.sign.key.id")
    val signingKey = getSensitiveProperty("libs.sign.key.private")
    val signingKeyPassphrase = getSensitiveProperty("libs.sign.passphrase")
    if (!signingKey.isNullOrBlank()) {
        extensions.configure<SigningExtension>("signing") {
            useInMemoryPgpKeys(keyId, signingKey, signingKeyPassphrase)
            sign(this@signPublicationIfKeyPresent)
        }
    }
}

fun RepositoryHandler.addSonatypeRepository() {
    maven {
        url = mavenRepositoryUri()
        credentials {
            username = getSensitiveProperty("libs.sonatype.user")
            password = getSensitiveProperty("libs.sonatype.password")
        }
    }
}

fun mavenRepositoryUri(): URI {
    // TODO -SNAPSHOT detection can be made here as well
    val repositoryId: String? = System.getenv("libs.repository.id")
    return if (repositoryId == null) {
        URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
    } else {
        URI("https://oss.sonatype.org/service/local/staging/deployByRepositoryId/$repositoryId")
    }
}

fun getSensitiveProperty(name: String): String? {
    return findProperty(name) as? String ?: System.getenv(name)
}
