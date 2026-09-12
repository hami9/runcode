package com.runcode.app

import android.app.Application
import com.runcode.app.backup.BackupManager
import com.runcode.app.database.AppMetaDatabase
import com.runcode.app.database.ProjectDatabaseManager
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.logging.LogManager
import com.runcode.app.network.PortManager
import com.runcode.app.runtime.PythonEngine
import com.runcode.app.runtime.RuntimeRegistry
import com.runcode.app.runtime.StaticWebEngine
import com.runcode.app.security.SecretStore
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.supervisor.ServiceSupervisor
import com.runcode.app.system.CompatibilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RuncodeApp : Application() {

    lateinit var projectStorage: ProjectStorage
        private set
    lateinit var portManager: PortManager
        private set
    lateinit var secretStore: SecretStore
        private set
    lateinit var logManager: LogManager
        private set
    lateinit var appMetaDatabase: AppMetaDatabase
        private set
    lateinit var projectDatabaseManager: ProjectDatabaseManager
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var compatibilityManager: CompatibilityManager
        private set
    lateinit var runtimeRegistry: RuntimeRegistry
        private set
    lateinit var serviceSupervisor: ServiceSupervisor
        private set

    override fun onCreate() {
        super.onCreate()

        projectStorage = ProjectStorage(this)
        portManager = PortManager()
        secretStore = SecretStore(this)
        logManager = LogManager(this)
        appMetaDatabase = AppMetaDatabase(this)
        projectDatabaseManager = ProjectDatabaseManager()
        backupManager = BackupManager(this, projectStorage)
        compatibilityManager = CompatibilityManager(this)

        val pythonEngine = PythonEngine(this, secretStore)
        val staticWebEngine = StaticWebEngine(portManager)
        runtimeRegistry = RuntimeRegistry(pythonEngine, staticWebEngine)

        serviceSupervisor = ServiceSupervisor(
            context = this,
            runtimeRegistry = runtimeRegistry,
            logManager = logManager,
            portManager = portManager
        )

        // Seed initial starter projects on first launch if empty
        CoroutineScope(Dispatchers.IO).launch {
            val existing = appMetaDatabase.getAllProjects()
            if (existing.isEmpty()) {
                val p1 = projectStorage.createProjectFromTemplate("Python Quickstart", ProjectProfile.PYTHON_SCRIPT)
                val p2 = projectStorage.createProjectFromTemplate("Telegram Bot Starter", ProjectProfile.TELEGRAM_BOT)
                val p3 = projectStorage.createProjectFromTemplate("Local Web Dashboard", ProjectProfile.STATIC_WEB, customPort = 8080)
                val p4 = projectStorage.createProjectFromTemplate("Tasks SQLite App", ProjectProfile.SQLITE_APP)

                appMetaDatabase.insertOrUpdateProject(p1)
                appMetaDatabase.insertOrUpdateProject(p2)
                appMetaDatabase.insertOrUpdateProject(p3)
                appMetaDatabase.insertOrUpdateProject(p4)
            }
        }
    }
}
