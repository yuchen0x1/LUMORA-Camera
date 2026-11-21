plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

allprojects {
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
    configurations.configureEach {
        // UTP's private configurations are not a stable project API; androidJdkImage
        // comes from the installed SDK. Their external artifacts are still verified.
        if (!name.startsWith("_internal-") && name != "androidJdkImage") {
            resolutionStrategy.activateDependencyLocking()
        }
    }
}

// Refresh the reviewed graph explicitly; normal builds must not rewrite lock state.
// Run with --write-locks and review verification metadata against official artifacts.
tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves lockable dependency graphs without requiring compiled Android artifacts."
    notCompatibleWithConfigurationCache("Resolves project configurations at execution time")
    doFirst {
        check(gradle.startParameter.isWriteDependencyLocks) { "Pass --write-locks to refresh locks" }
    }
    doLast {
        project.allprojects.forEach { target ->
            target.configurations.filter {
                it.isCanBeResolved && !it.name.startsWith("_internal-") && it.name != "androidJdkImage"
            }.forEach { configuration ->
                logger.lifecycle("Locking ${target.path}:${configuration.name}")
                // Android test configurations also reference this app's own variants.
                // Resolving raw files is ambiguous before AGP selects an artifact type.
                configuration.incoming.resolutionResult.allDependencies.forEach { dependency ->
                    if (dependency is org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                        throw dependency.failure
                    }
                }
            }
        }
    }
}
