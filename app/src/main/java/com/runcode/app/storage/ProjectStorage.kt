package com.runcode.app.storage

import android.content.Context
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.NetworkConfig
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectStorage(private val context: Context) {

    private val baseProjectsDir = File(context.filesDir, "projects")

    init {
        if (!baseProjectsDir.exists()) {
            baseProjectsDir.mkdirs()
        }
    }

    fun getProjectDir(projectId: String): File {
        return File(baseProjectsDir, projectId)
    }

    fun getSourceDir(projectId: String): File {
        return File(getProjectDir(projectId), "source")
    }

    fun getDataDir(projectId: String): File {
        return File(getProjectDir(projectId), "data")
    }

    fun getBackupsDir(projectId: String): File {
        return File(getProjectDir(projectId), "backups")
    }

    fun initializeProjectDirectories(projectId: String) {
        val root = getProjectDir(projectId)
        listOf("source", "data", "config", "logs", "cache", "backups").forEach { sub ->
            val dir = File(root, sub)
            if (!dir.exists()) dir.mkdirs()
        }
    }

    fun createProjectFromTemplate(
        name: String,
        profile: ProjectProfile,
        customPort: Int = 8080
    ): Project {
        val id = UUID.randomUUID().toString().substring(0, 8)
        initializeProjectDirectories(id)
        val sourceDir = getSourceDir(id)
        val dataDir = getDataDir(id)

        var entryPoint = "main.py"
        val envList = mutableListOf<EnvironmentVariable>()

        when (profile) {
            ProjectProfile.PYTHON_SCRIPT -> {
                entryPoint = "main.py"
                val script = """
# Python Script Template in runcode
import sys
import time
import math

print("=== runcode Local Runtime ===")
print("Python script starting up...")
print(f"Platform: Android runtime")

# Example calculation
for i in range(1, 6):
    sq = math.sqrt(i * 10)
    print(f"Step {i}: computed sqrt({i * 10}) = {sq:.3f}")
    time.sleep(0.5)

print("Batch calculations completed successfully!")
""".trimIndent()
                File(sourceDir, entryPoint).writeText(script)
            }

            ProjectProfile.TELEGRAM_BOT -> {
                entryPoint = "bot.py"
                envList.add(EnvironmentVariable("TELEGRAM_BOT_TOKEN", "\${SEC_BOT_TOKEN}", isSecret = true))
                val botCode = """
# Telegram Bot Profile for runcode
import os
import time
import sys

token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
print("Initializing Telegram Bot Service...")
print(f"Configured Bot Token: {token}")

if not token or token == "${'$'}{SEC_BOT_TOKEN}":
    print("[WARN] No real bot token provided. Operating in Bot Emulation Mode.")
    print("Add your Telegram Bot Token in Project Settings -> Environment Secrets.")

print("Starting long-polling event loop...")

# Emulated or real webhook / polling event loop
step = 0
while True:
    step += 1
    if step % 5 == 0:
        print(f"[Bot Polling] Listening for updates... (heartbeat #{step})")
    time.sleep(2)
""".trimIndent()
                File(sourceDir, entryPoint).writeText(botCode)
            }

            ProjectProfile.PYTHON_HTTP -> {
                entryPoint = "server.py"
                val serverCode = """
# Lightweight Python HTTP API for runcode
import json
import time

port = $customPort
print(f"Starting lightweight HTTP service on port {port}...")
print("Endpoints available:")
print("  GET  /        - Welcome & service info")
print("  GET  /status  - Runtime health stats")
print("  GET  /api     - JSON API response")

# Simulated / embedded HTTP listener loop
while True:
    time.sleep(5)
    print(f"[HTTP Monitor] Service listening on 127.0.0.1:{port} - Active")
""".trimIndent()
                File(sourceDir, entryPoint).writeText(serverCode)
            }

            ProjectProfile.STATIC_WEB -> {
                entryPoint = "index.html"
                val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$name - runcode</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="card">
        <div class="badge">RUNCODE LOCAL WEB</div>
        <h1>$name</h1>
        <p>This web project is hosted locally on your Android device via runcode.</p>
        <div class="stats">
            <span class="status-dot"></span> Server active on <strong>127.0.0.1:$customPort</strong>
        </div>
        <button onclick="ping()">Test API Ping</button>
        <div id="output"></div>
    </div>
    <script src="app.js"></script>
</body>
</html>
""".trimIndent()
                val css = """
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    background: #0B0F17;
    color: #E2E8F0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
}
.card {
    background: #161F2E;
    border: 1px solid #233147;
    border-radius: 16px;
    padding: 32px;
    max-width: 480px;
    width: 100%;
    box-shadow: 0 10px 30px rgba(0,0,0,0.5);
}
.badge {
    background: #00E5FF22;
    color: #00E5FF;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 1px;
    padding: 4px 10px;
    border-radius: 6px;
    display: inline-block;
    margin-bottom: 16px;
}
h1 { font-size: 24px; margin-bottom: 12px; color: #FFFFFF; }
p { color: #94A3B8; font-size: 14px; line-height: 1.6; margin-bottom: 20px; }
.stats {
    background: #0D131D;
    padding: 12px 16px;
    border-radius: 8px;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 24px;
}
.status-dot {
    width: 8px; height: 8px;
    background: #00E676;
    border-radius: 50%;
    box-shadow: 0 0 8px #00E676;
}
button {
    background: #00E5FF;
    color: #0B0F17;
    border: none;
    padding: 12px 20px;
    border-radius: 8px;
    font-weight: 600;
    cursor: pointer;
    width: 100%;
    font-size: 14px;
}
#output { margin-top: 16px; font-family: monospace; font-size: 12px; color: #00E676; }
""".trimIndent()
                val js = """
function ping() {
    const el = document.getElementById('output');
    el.innerText = 'Ping response from runcode at ' + new Date().toLocaleTimeString();
}
""".trimIndent()
                File(sourceDir, "index.html").writeText(html)
                File(sourceDir, "style.css").writeText(css)
                File(sourceDir, "app.js").writeText(js)
            }

            ProjectProfile.SQLITE_APP -> {
                entryPoint = "app.py"
                // Seed sample SQLite database in data directory
                val dbFile = File(dataDir, "tasks.sqlite")
                createSampleSqliteDb(dbFile)

                val pythonDbScript = """
# SQLite Application Demo in runcode
import sqlite3
import time

db_path = "data/tasks.sqlite"
print(f"Connecting to database: {db_path}")

conn = sqlite3.connect(db_path)
cursor = conn.cursor()

# Query existing tasks
cursor.execute("SELECT id, title, status, priority FROM tasks")
rows = cursor.fetchall()
print(f"Found {len(rows)} tasks in database:")
for r in rows:
    print(f"  [{r[0]}] {r[1]} - status: {r[2]} (priority {r[3]})")

conn.close()
print("Database operations complete.")
""".trimIndent()
                File(sourceDir, entryPoint).writeText(pythonDbScript)
            }
        }

        return Project(
            id = id,
            name = name,
            description = "Created with ${profile.displayName} template",
            profile = profile,
            projectRoot = getProjectDir(id).absolutePath,
            entryPoint = entryPoint,
            network = NetworkConfig(port = customPort),
            environment = envList,
            restartPolicy = if (profile == ProjectProfile.TELEGRAM_BOT || profile == ProjectProfile.PYTHON_HTTP || profile == ProjectProfile.STATIC_WEB) {
                RestartPolicy.ON_FAILURE
            } else {
                RestartPolicy.NEVER
            }
        )
    }

    private fun createSampleSqliteDb(file: File) {
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        status TEXT DEFAULT 'pending',
                        priority INTEGER DEFAULT 1,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                """.trimIndent())
                db.execSQL("INSERT INTO tasks (title, status, priority) VALUES ('Initialize local runtime', 'completed', 1);")
                db.execSQL("INSERT INTO tasks (title, status, priority) VALUES ('Configure Telegram bot webhook', 'in_progress', 2);")
                db.execSQL("INSERT INTO tasks (title, status, priority) VALUES ('Host local documentation API', 'pending', 3);")
            }
        } catch (_: Exception) {}
    }

    /**
     * Safely reads text from a file inside project source or data, preventing path traversal.
     */
    fun readFile(projectId: String, relativePath: String): String {
        val root = getProjectDir(projectId).canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw SecurityException("Access denied: path traversal attempted: $relativePath")
        }
        if (!target.exists()) return ""
        return target.readText()
    }

    /**
     * Atomic write to file: writes to sibling tmp file, syncs, then renames.
     */
    fun writeFileAtomically(projectId: String, relativePath: String, content: String) {
        val root = getProjectDir(projectId).canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw SecurityException("Access denied: path traversal attempted: $relativePath")
        }
        target.parentFile?.mkdirs()

        val tempFile = File(target.parentFile, "${target.name}.tmp_${System.nanoTime()}")
        FileOutputStream(tempFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
    }

    fun listProjectFiles(projectId: String): List<FileNode> {
        val root = getSourceDir(projectId)
        if (!root.exists()) return emptyList()
        return scanDir(root, "")
    }

    private fun scanDir(dir: File, currentRelPath: String): List<FileNode> {
        val list = mutableListOf<FileNode>()
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
            val relPath = if (currentRelPath.isEmpty()) child.name else "$currentRelPath/${child.name}"
            if (child.isDirectory) {
                list.add(
                    FileNode(
                        name = child.name,
                        relativePath = "source/$relPath",
                        isDirectory = true,
                        size = 0L,
                        children = scanDir(child, relPath)
                    )
                )
            } else {
                list.add(
                    FileNode(
                        name = child.name,
                        relativePath = "source/$relPath",
                        isDirectory = false,
                        size = child.length(),
                        children = emptyList()
                    )
                )
            }
        }
        return list
    }

    fun deleteFile(projectId: String, relativePath: String): Boolean {
        val root = getProjectDir(projectId).canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw SecurityException("Access denied: path traversal attempted")
        }
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    fun deleteProject(projectId: String): Boolean {
        return getProjectDir(projectId).deleteRecursively()
    }

    fun exportProjectZip(projectId: String, destStream: OutputStream) {
        val root = getProjectDir(projectId).canonicalFile
        ZipOutputStream(BufferedOutputStream(destStream)).use { zos ->
            // Include source, data, config
            listOf("source", "data", "config").forEach { folderName ->
                val folder = File(root, folderName)
                if (folder.exists()) {
                    addFolderToZip(folder, folderName, zos)
                }
            }
        }
    }

    private fun addFolderToZip(folder: File, parentPath: String, zos: ZipOutputStream) {
        folder.listFiles()?.forEach { file ->
            val entryPath = "$parentPath/${file.name}"
            if (file.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryPath/"))
                zos.closeEntry()
                addFolderToZip(file, entryPath, zos)
            } else {
                zos.putNextEntry(ZipEntry(entryPath))
                BufferedInputStream(FileInputStream(file)).use { bis ->
                    bis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * Imports ZIP into a project, safeguarding against Zip-Slip vulnerabilities.
     */
    fun extractZipSafely(inputStream: InputStream, destinationDir: File) {
        val destCanonical = destinationDir.canonicalFile
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destCanonical, entry.name).canonicalFile
                if (!newFile.path.startsWith(destCanonical.path)) {
                    throw SecurityException("Zip Slip vulnerability detected in entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val children: List<FileNode>
)
