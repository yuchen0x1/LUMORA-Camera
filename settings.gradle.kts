pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Apply advisory fixes to both plugin tooling and app/test configurations. Keeping
// minimums instead of forcing versions lets future upstream upgrades stay newer.
gradle.beforeProject {
    val minimums = mapOf(
        "org.bouncycastle:bcprov-jdk18on" to "1.85",
        "org.bouncycastle:bcpkix-jdk18on" to "1.85",
        "org.bouncycastle:bcutil-jdk18on" to "1.85",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
        "org.jdom:jdom2" to "2.0.6.1",
        "org.apache.commons:commons-lang3" to "3.18.0",
        "org.apache.httpcomponents:httpclient" to "4.5.14",
        "com.google.protobuf:protobuf-java" to "3.25.5",
        "com.google.protobuf:protobuf-kotlin" to "3.25.5"
    )
    val securityFloor = Action<org.gradle.api.artifacts.DependencyResolveDetails> {
        val current = requested.version.orEmpty()
        val minimum = when {
            requested.group == "io.netty" && current.startsWith("4.1.") -> "4.1.137.Final"
            requested.group == "com.google.guava" && requested.name == "guava" ->
                if (current.endsWith("-android")) "33.7.2-android" else "33.7.2-jre"
            else -> minimums["${requested.group}:${requested.name}"]
        }
        if (minimum != null) {
            val existing = Regex("[0-9]+").findAll(current).map { it.value.toInt() }.toList()
            val required = Regex("[0-9]+").findAll(minimum).map { it.value.toInt() }.toList()
            val comparison = (0 until maxOf(existing.size, required.size)).firstNotNullOfOrNull { i ->
                val order = (existing.getOrElse(i) { 0 }).compareTo(required.getOrElse(i) { 0 })
                order.takeIf { it != 0 }
            } ?: 0
            if (comparison < 0) {
                useVersion(minimum)
                because("Patched dependency minimum; see SECURITY_AUDIT.md")
            }
        }
    }
    buildscript.configurations.configureEach {
        resolutionStrategy.eachDependency(securityFloor)
        if (name == "classpath") resolutionStrategy.activateDependencyLocking()
    }
    configurations.configureEach { resolutionStrategy.eachDependency(securityFloor) }
}

rootProject.name = "LUMORA Camera"
include(":app")
