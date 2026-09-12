package com.runcode.app.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.NetworkConfig
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppMetaDatabase(context: Context) : SQLiteOpenHelper(context, "runcode_meta.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                profile TEXT NOT NULL,
                project_root TEXT NOT NULL,
                entry_point TEXT NOT NULL,
                port INTEGER DEFAULT 8080,
                bind_address TEXT DEFAULT '127.0.0.1',
                allow_lan INTEGER DEFAULT 0,
                restart_policy TEXT DEFAULT 'ON_FAILURE',
                start_on_boot INTEGER DEFAULT 0,
                created_at INTEGER,
                updated_at INTEGER
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE recent_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                opened_at INTEGER NOT NULL
            );
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }

    suspend fun insertOrUpdateProject(project: Project) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("description", project.description)
            put("profile", project.profile.id)
            put("project_root", project.projectRoot)
            put("entry_point", project.entryPoint)
            put("port", project.network.port)
            put("bind_address", project.network.bindAddress)
            put("allow_lan", if (project.network.allowLan) 1 else 0)
            put("restart_policy", project.restartPolicy.name)
            put("start_on_boot", if (project.startOnBoot) 1 else 0)
            put("created_at", project.createdAt)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("projects", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getAllProjects(): List<Project> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Project>()
        readableDatabase.rawQuery("SELECT * FROM projects ORDER BY updated_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val profileStr = cursor.getString(cursor.getColumnIndexOrThrow("profile"))
                val profile = ProjectProfile.entries.find { it.id == profileStr } ?: ProjectProfile.PYTHON_SCRIPT
                val policyStr = cursor.getString(cursor.getColumnIndexOrThrow("restart_policy"))
                val policy = try { RestartPolicy.valueOf(policyStr) } catch (_: Exception) { RestartPolicy.ON_FAILURE }

                list.add(
                    Project(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        profile = profile,
                        projectRoot = cursor.getString(cursor.getColumnIndexOrThrow("project_root")),
                        entryPoint = cursor.getString(cursor.getColumnIndexOrThrow("entry_point")),
                        network = NetworkConfig(
                            port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
                            bindAddress = cursor.getString(cursor.getColumnIndexOrThrow("bind_address")),
                            allowLan = cursor.getInt(cursor.getColumnIndexOrThrow("allow_lan")) == 1
                        ),
                        restartPolicy = policy,
                        startOnBoot = cursor.getInt(cursor.getColumnIndexOrThrow("start_on_boot")) == 1,
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        list
    }

    suspend fun getProjectById(id: String): Project? = withContext(Dispatchers.IO) {
        getAllProjects().find { it.id == id }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("projects", "id = ?", arrayOf(id))
        writableDatabase.delete("recent_files", "project_id = ?", arrayOf(id))
    }
}
