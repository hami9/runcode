package com.runcode.app.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.NetworkConfig
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppMetaDatabase(context: Context) : SQLiteOpenHelper(context, "runcode_meta.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
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
                max_cpu_percent INTEGER DEFAULT 0,
                max_heap_mb INTEGER DEFAULT 0,
                idle_timeout_minutes INTEGER DEFAULT 0,
                environment TEXT DEFAULT '[]',
                arguments TEXT DEFAULT '[]',
                working_directory TEXT DEFAULT '',
                created_at INTEGER,
                updated_at INTEGER
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE recent_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                opened_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 dropped environment/arguments/workingDirectory on the floor, so a bot token
            // configured before a restart was silently lost.
            db.execSQL("ALTER TABLE projects ADD COLUMN environment TEXT DEFAULT '[]'")
            db.execSQL("ALTER TABLE projects ADD COLUMN arguments TEXT DEFAULT '[]'")
            db.execSQL("ALTER TABLE projects ADD COLUMN working_directory TEXT DEFAULT ''")
        }
        if (oldVersion < 3) {
            // Per-service governance limits; 0 keeps the previous unlimited behaviour.
            db.execSQL("ALTER TABLE projects ADD COLUMN max_cpu_percent INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE projects ADD COLUMN max_heap_mb INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE projects ADD COLUMN idle_timeout_minutes INTEGER DEFAULT 0")
        }
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
            put("max_cpu_percent", project.maxCpuPercent)
            put("max_heap_mb", project.maxHeapMb)
            put("idle_timeout_minutes", project.idleTimeoutMinutes)
            put("environment", encodeEnvironment(project.environment))
            put("arguments", encodeArguments(project.arguments))
            put("working_directory", project.workingDirectory)
            put("created_at", project.createdAt)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("projects", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getAllProjects(): List<Project> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Project>()
        readableDatabase.rawQuery("SELECT * FROM projects ORDER BY updated_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(readProject(cursor))
            }
        }
        list
    }

    suspend fun getProjectById(id: String): Project? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM projects WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) readProject(cursor) else null
        }
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("projects", "id = ?", arrayOf(id))
        writableDatabase.delete("recent_files", "project_id = ?", arrayOf(id))
    }

    private fun readProject(cursor: Cursor): Project {
        val profileStr = cursor.getString(cursor.getColumnIndexOrThrow("profile"))
        val profile = ProjectProfile.entries.find { it.id == profileStr } ?: ProjectProfile.PYTHON_SCRIPT
        val policyStr = cursor.getString(cursor.getColumnIndexOrThrow("restart_policy"))
        val policy = try {
            RestartPolicy.valueOf(policyStr)
        } catch (_: Exception) {
            RestartPolicy.ON_FAILURE
        }

        return Project(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
            profile = profile,
            projectRoot = cursor.getString(cursor.getColumnIndexOrThrow("project_root")),
            entryPoint = cursor.getString(cursor.getColumnIndexOrThrow("entry_point")),
            arguments = decodeArguments(cursor.getStringOrNull("arguments")),
            environment = decodeEnvironment(cursor.getStringOrNull("environment")),
            workingDirectory = cursor.getStringOrNull("working_directory") ?: "",
            network = NetworkConfig(
                port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
                bindAddress = cursor.getString(cursor.getColumnIndexOrThrow("bind_address")),
                allowLan = cursor.getInt(cursor.getColumnIndexOrThrow("allow_lan")) == 1
            ),
            restartPolicy = policy,
            startOnBoot = cursor.getInt(cursor.getColumnIndexOrThrow("start_on_boot")) == 1,
            maxCpuPercent = cursor.getIntOrZero("max_cpu_percent"),
            maxHeapMb = cursor.getIntOrZero("max_heap_mb"),
            idleTimeoutMinutes = cursor.getIntOrZero("idle_timeout_minutes"),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    private fun Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun encodeEnvironment(environment: List<EnvironmentVariable>): String {
        val array = JSONArray()
        environment.forEach { variable ->
            array.put(
                JSONObject()
                    .put("key", variable.key)
                    .put("value", variable.value)
                    .put("isSecret", variable.isSecret)
            )
        }
        return array.toString()
    }

    private fun decodeEnvironment(raw: String?): List<EnvironmentVariable> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                EnvironmentVariable(
                    key = obj.optString("key"),
                    value = obj.optString("value"),
                    isSecret = obj.optBoolean("isSecret", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeArguments(arguments: List<String>): String {
        val array = JSONArray()
        arguments.forEach { array.put(it) }
        return array.toString()
    }

    private fun decodeArguments(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val DB_VERSION = 3
    }
}
