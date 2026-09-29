package com.runcode.app.settings

import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.RestartPolicy
import com.runcode.app.security.SecretReferences
import com.runcode.app.security.SecretVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** A null secret value keeps the existing reference; plaintext never enters Project. */
data class EnvironmentEdit(val key: String, val value: String?, val isSecret: Boolean)

data class ProjectSettings(
    val port: Int,
    val restartPolicy: RestartPolicy,
    val startOnBoot: Boolean,
    val maxCpuPercent: Int,
    val maxHeapMb: Int,
    val idleTimeoutMinutes: Int,
    val environment: List<EnvironmentEdit>
) {
    fun validate() {
        require(port in 1024..65535) { "Port must be between 1024 and 65535" }
        require(maxCpuPercent in 0..100) { "CPU limit must be between 0 and 100" }
        require(maxHeapMb >= 0) { "Heap limit cannot be negative" }
        require(idleTimeoutMinutes in 0..35_791_394) { "Idle timeout is out of range" }
        require(environment.map { it.key }.distinct().size == environment.size) { "Environment names must be unique" }
        environment.forEach {
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it.key)) { "Use letters, digits and underscores for environment names" }
            require(it.key !in setOf("RUNCODE_PORT", "RUNCODE_BIND_ADDRESS")) { "RUNCODE_PORT and RUNCODE_BIND_ADDRESS are managed by the runtime" }
            require(it.value?.contains('\u0000') != true) { "Environment values cannot contain NUL characters" }
            if (!it.isSecret) {
                require(it.value != null && SecretReferences.key(it.value) == null) { "Plain variables cannot reference the secret vault" }
            }
        }
    }

    companion object {
        fun from(project: Project) = ProjectSettings(
            project.network.port, project.restartPolicy, project.startOnBoot,
            project.maxCpuPercent, project.maxHeapMb, project.idleTimeoutMinutes,
            project.environment.map { EnvironmentEdit(it.key, if (it.isSecret) null else it.value, it.isSecret) }
        )
    }
}

/** Writes new vault entries first, then commits references. A failed save keeps old secrets. */
class ProjectSettingsManager(
    private val vault: SecretVault,
    private val findProject: suspend (String) -> Project?,
    private val saveProject: suspend (Project) -> Unit,
    private val isActive: (String) -> Boolean
) {
    private val mutex = Mutex()

    suspend fun update(projectId: String, settings: ProjectSettings, allowSecrets: Boolean = true): Project = mutex.withLock {
        settings.validate()
        val current = requireNotNull(findProject(projectId)) { "Project no longer exists" }
        check(!isActive(projectId)) { "Stop the service before changing its settings" }
        if (!allowSecrets) {
            val oldSecrets = current.environment.filter { it.isSecret }.map { it.key }.toSet()
            val keptSecrets = settings.environment.filter { it.isSecret && it.value == null }.map { it.key }.toSet()
            require(oldSecrets == keptSecrets && settings.environment.none { it.isSecret && it.value != null }) {
                "Manage secret variables in the app's project settings"
            }
        }
        // Once vault writes begin, finish the metadata commit or rollback even if the
        // screen closes. Cancellation after a DB commit must not delete referenced keys.
        withContext(NonCancellable) {
        val newKeys = mutableListOf<String>()
        val prefix = "PROJECT_${projectId.replace('-', '_')}_"
        val updated = try {
            val environment = settings.environment.map { edit ->
                val value = if (edit.isSecret) {
                    if (edit.value == null) {
                        val old = current.environment.find { it.key == edit.key && it.isSecret }
                        require(old != null) { "Enter a value for the new secret" }
                        require(old.value.isEmpty() || SecretReferences.key(old.value) != null) { "Replace this legacy secret to save it securely" }
                        old.value
                    } else {
                        require(edit.value.isNotEmpty()) { "Secret values cannot be empty" }
                        val key = prefix + UUID.randomUUID().toString().replace("-", "")
                        check(vault.setSecret(key, edit.value)) { "Could not save the secret securely. Settings were not changed." }
                        newKeys.add(key)
                        SecretReferences.placeholder(key)
                    }
                } else requireNotNull(edit.value)
                EnvironmentVariable(edit.key, value, edit.isSecret)
            }
            current.copy(
                network = current.network.copy(port = settings.port),
                restartPolicy = settings.restartPolicy,
                startOnBoot = settings.startOnBoot,
                maxCpuPercent = settings.maxCpuPercent,
                maxHeapMb = settings.maxHeapMb,
                idleTimeoutMinutes = settings.idleTimeoutMinutes,
                environment = environment,
                updatedAt = System.currentTimeMillis()
            ).also { saveProject(it) }
        } catch (error: Exception) {
            newKeys.forEach { vault.removeSecret(it) }
            throw error
        }
        val kept = updated.environment.mapNotNull { SecretReferences.key(it.value) }.toSet()
        current.environment.mapNotNull { SecretReferences.key(it.value) }
            .filter { it.startsWith(prefix) && it !in kept }
            .forEach { vault.removeSecret(it) }
        updated
        }
    }
}
