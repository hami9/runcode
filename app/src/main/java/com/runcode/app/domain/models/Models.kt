package com.runcode.app.domain.models

enum class ProjectProfile(val id: String, val displayName: String, val defaultExtension: String) {
    PYTHON_SCRIPT("python_script", "Python Script", "py"),
    TELEGRAM_BOT("telegram_bot", "Telegram Bot", "py"),
    PYTHON_HTTP("python_http", "Python HTTP API", "py"),
    STATIC_WEB("static_web", "Static Website", "html"),
    SQLITE_APP("sqlite_app", "SQLite Database App", "py")
}

enum class RestartPolicy {
    NEVER,
    ON_FAILURE,
    ALWAYS
}

enum class ServiceState {
    STOPPED,
    PREPARING,
    STARTING,
    RUNNING,
    STOPPING,
    RESTARTING,
    DEGRADED,
    FAILED
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    STDOUT,
    STDERR,
    SYSTEM
}

data class EnvironmentVariable(
    val key: String,
    val value: String,
    val isSecret: Boolean = false
)

data class NetworkConfig(
    val port: Int = 8080,
    val bindAddress: String = "127.0.0.1",
    val allowLan: Boolean = false
)

data class Project(
    val id: String,
    val name: String,
    val description: String,
    val profile: ProjectProfile,
    val projectRoot: String,
    val entryPoint: String,
    val arguments: List<String> = emptyList(),
    val environment: List<EnvironmentVariable> = emptyList(),
    val workingDirectory: String = "",
    val restartPolicy: RestartPolicy = RestartPolicy.ON_FAILURE,
    val startOnBoot: Boolean = false,
    val network: NetworkConfig = NetworkConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RuntimeInstance(
    val serviceId: String,
    val projectId: String,
    val projectName: String,
    val profile: ProjectProfile,
    val state: ServiceState = ServiceState.STOPPED,
    val startTime: Long = 0L,
    val restartCount: Int = 0,
    val port: Int = 0,
    val boundAddress: String = "127.0.0.1",
    val lastError: String? = null,
    val lastHealthCheck: Long = 0L,
    val memoryEstimateMb: Double = 0.0,
    val cpuEstimatePercent: Int = 0
) {
    val isRunning: Boolean get() = state == ServiceState.RUNNING || state == ServiceState.DEGRADED
    val uptimeSeconds: Long
        get() = if (startTime > 0 && isRunning) (System.currentTimeMillis() - startTime) / 1000 else 0L
}

data class RuntimeEvent(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val projectId: String,
    val serviceName: String,
    val level: LogLevel,
    val message: String
)

data class DeviceCapabilities(
    val androidApi: Int,
    val releaseVersion: String,
    val supportedAbis: List<String>,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val isLowMemory: Boolean,
    val freeStorageMb: Long,
    val notificationsAllowed: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val wakeLockActive: Boolean,
    val runningServicesCount: Int
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Boolean
)

data class TableInfo(
    val name: String,
    val columns: List<ColumnInfo>,
    val rowCount: Long
)

data class QueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val affectedRows: Int = 0,
    val durationMs: Long = 0L,
    val isQuery: Boolean = true,
    val error: String? = null
)

data class BackupManifest(
    val version: Int = 1,
    val projectId: String,
    val projectName: String,
    val profileId: String,
    val createdAt: Long,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val checksum: String
)
