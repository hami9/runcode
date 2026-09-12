package com.runcode.app.runtime

import com.runcode.app.domain.models.ProjectProfile

class RuntimeRegistry(
    val pythonEngine: PythonEngine,
    val staticWebEngine: StaticWebEngine
) {
    private val engines = listOf(pythonEngine, staticWebEngine)

    fun getEngineForProfile(profile: ProjectProfile): RuntimeEngine {
        return when (profile) {
            ProjectProfile.STATIC_WEB -> staticWebEngine
            ProjectProfile.PYTHON_SCRIPT,
            ProjectProfile.TELEGRAM_BOT,
            ProjectProfile.PYTHON_HTTP,
            ProjectProfile.SQLITE_APP -> pythonEngine
        }
    }

    fun getAllEngines(): List<RuntimeEngine> = engines
}
