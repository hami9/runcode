package com.runcode.app.storage

import android.content.Context
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.NetworkConfig
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

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

    fun newProjectId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun initializeProjectDirectories(projectId: String) {
        val root = getProjectDir(projectId)
        STRUCTURAL_DIRS.forEach { sub ->
            val dir = File(root, sub)
            if (!dir.exists()) dir.mkdirs()
        }
    }

    fun createProjectFromTemplate(
        name: String,
        profile: ProjectProfile,
        customPort: Int = 8080
    ): Project {
        val id = newProjectId()
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

    // ---------------------------------------------------------------- file access
    //
    // Every path the file manager, the editor and the MCP bridge hand in goes through
    // resolveInProject, so there is exactly one containment check to get right.

    /**
     * Resolves [relativePath] against the project root and refuses anything that escapes it.
     *
     * The trailing separator matters: a bare prefix test lets `projects/abc` accept
     * `projects/abcd/...`, which is a different project.
     */
    fun resolveInProject(projectId: String, relativePath: String): File {
        val root = getProjectDir(projectId).canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Access denied: '$relativePath' is outside the project")
        }
        return target
    }

    /** Canonical project-relative form of [relativePath], e.g. `./source//a.py` -> `source/a.py`. */
    fun normalize(projectId: String, relativePath: String): String =
        relativePathOf(projectId, resolveInProject(projectId, relativePath))

    /** Path of [file] relative to the project root, always with forward slashes. */
    fun relativePathOf(projectId: String, file: File): String =
        file.canonicalFile.relativeTo(getProjectDir(projectId).canonicalFile).path
            .replace(File.separatorChar, '/')

    /** What a path holds, so callers can refuse to load a binary or huge file as text. */
    fun inspect(projectId: String, relativePath: String, textLimitBytes: Long = MAX_TEXT_BYTES): FileInfo {
        val target = resolveInProject(projectId, relativePath)
        return when {
            !target.exists() -> FileInfo(FileKind.MISSING, 0)
            target.isDirectory -> FileInfo(FileKind.DIRECTORY, 0)
            looksBinary(target) -> FileInfo(FileKind.BINARY, target.length())
            target.length() > textLimitBytes -> FileInfo(FileKind.TOO_LARGE, target.length())
            else -> FileInfo(FileKind.TEXT, target.length())
        }
    }

    /** Reads a text file; a file that does not exist yet reads as empty. */
    fun readFile(projectId: String, relativePath: String): String {
        val target = resolveInProject(projectId, relativePath)
        if (!target.exists()) return ""
        return target.readText()
    }

    /**
     * Atomic write to file: writes to sibling tmp file, syncs, then renames.
     */
    fun writeFileAtomically(projectId: String, relativePath: String, content: String) {
        val target = resolveInProject(projectId, relativePath)
        if (target.isDirectory) {
            throw IllegalArgumentException("'$relativePath' is a folder, not a file")
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

    /**
     * The whole project tree, not just `source/`: scripts write their output next to their
     * data, and a file manager that cannot show that output is not much use.
     */
    fun listProjectFiles(projectId: String): List<FileNode> {
        val root = getProjectDir(projectId)
        if (!root.exists()) return emptyList()
        return scanDir(root, "").sortedWith(compareBy({ topLevelRank(it) }, { it.name.lowercase() }))
    }

    private fun topLevelRank(node: FileNode): Int {
        val structural = STRUCTURAL_DIRS.indexOf(node.name)
        return when {
            node.isDirectory && structural >= 0 -> structural
            node.isDirectory -> STRUCTURAL_DIRS.size
            else -> STRUCTURAL_DIRS.size + 1
        }
    }

    private fun scanDir(dir: File, currentRelPath: String): List<FileNode> {
        val list = mutableListOf<FileNode>()
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
            val relPath = if (currentRelPath.isEmpty()) child.name else "$currentRelPath/${child.name}"
            if (child.isDirectory) {
                list.add(
                    FileNode(
                        name = child.name,
                        relativePath = relPath,
                        isDirectory = true,
                        size = 0L,
                        children = scanDir(child, relPath)
                    )
                )
            } else {
                list.add(
                    FileNode(
                        name = child.name,
                        relativePath = relPath,
                        isDirectory = false,
                        size = child.length(),
                        children = emptyList()
                    )
                )
            }
        }
        return list
    }

    /** Creates a folder (and any missing parents). Returns its project-relative path. */
    fun createDirectory(projectId: String, relativePath: String): String {
        val target = resolveInProject(projectId, relativePath)
        if (target.exists() && !target.isDirectory) {
            throw IllegalArgumentException("A file named '${target.name}' already exists there")
        }
        if (!target.exists() && !target.mkdirs()) {
            throw IllegalStateException("Could not create folder '$relativePath'")
        }
        return relativePathOf(projectId, target)
    }

    /**
     * Renames or moves a file or folder. [toRelativePath] is the full new path, so a rename
     * is just a move within the same folder. Returns the new project-relative path.
     */
    fun movePath(projectId: String, fromRelativePath: String, toRelativePath: String): String {
        val source = resolveInProject(projectId, fromRelativePath)
        val target = resolveInProject(projectId, toRelativePath)
        requireMutable(projectId, source)
        if (!source.exists()) throw IllegalArgumentException("'$fromRelativePath' does not exist")
        if (target == source) return relativePathOf(projectId, target)
        if (target.exists()) throw IllegalArgumentException("'${relativePathOf(projectId, target)}' already exists")
        if (source.isDirectory && target.path.startsWith(source.path + File.separator)) {
            throw IllegalArgumentException("A folder cannot be moved into itself")
        }
        if (target.parentFile == getProjectDir(projectId).canonicalFile && target.name in STRUCTURAL_DIRS) {
            throw IllegalArgumentException("'${target.name}' is reserved for the project layout")
        }
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = false)
            source.deleteRecursively()
        }
        return relativePathOf(projectId, target)
    }

    /** Deletes a file, or a folder and everything in it. The project's own layout is protected. */
    fun deleteFile(projectId: String, relativePath: String): Boolean {
        val target = resolveInProject(projectId, relativePath)
        requireMutable(projectId, target)
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    /**
     * Copies an incoming stream (a file picked through the system picker) into [destDirRelativePath].
     * An existing file is never overwritten; the copy gets a " (1)" suffix instead.
     */
    fun importFile(projectId: String, destDirRelativePath: String, displayName: String, input: InputStream): String {
        val destDir = resolveInProject(projectId, destDirRelativePath)
        if (!destDir.isDirectory) throw IllegalArgumentException("'$destDirRelativePath' is not a folder")
        val target = uniqueChild(destDir, sanitizeName(displayName))
        // resolveInProject already contains destDir, and a sanitized name has no separators,
        // but check the final path anyway rather than reason about it.
        resolveInProject(projectId, relativePathOf(projectId, target))

        val tempFile = File(destDir, ".${target.name}.importing_${System.nanoTime()}")
        try {
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = false)
            }
        } finally {
            tempFile.delete()
        }
        return relativePathOf(projectId, target)
    }

    /** Opens a project file for streaming out, e.g. to a document the user picked. */
    fun openForExport(projectId: String, relativePath: String): InputStream {
        val target = resolveInProject(projectId, relativePath)
        if (!target.isFile) throw IllegalArgumentException("'$relativePath' is not a file")
        return BufferedInputStream(FileInputStream(target))
    }

    private fun requireMutable(projectId: String, target: File) {
        val root = getProjectDir(projectId).canonicalFile
        if (target == root) throw SecurityException("The project folder itself cannot be changed")
        if (target.parentFile == root && target.name in STRUCTURAL_DIRS) {
            throw SecurityException("'${target.name}' is part of the project layout and cannot be moved or deleted")
        }
    }

    fun deleteProject(projectId: String): Boolean {
        return getProjectDir(projectId).deleteRecursively()
    }

    companion object {
        /** Folders every project has. Their contents are editable; the folders themselves are not. */
        val STRUCTURAL_DIRS = listOf("source", "data", "config", "logs", "cache", "backups")

        /** Largest file handed to a text consumer (the MCP bridge). The editor uses a lower limit. */
        const val MAX_TEXT_BYTES = 1L * 1024 * 1024

        /**
         * Rejects names that could smuggle a path: separators, `.`/`..`, NUL, or nothing at all.
         * Used for every name a person or a picker supplies.
         */
        fun validateName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Name cannot be empty" }
            require(trimmed != "." && trimmed != "..") { "'$trimmed' is not a valid name" }
            require(trimmed.none { it == '/' || it == '\\' || it == Char(0) }) { "Names cannot contain / or \\" }
            require(trimmed.length <= 255) { "Name is too long" }
            return trimmed
        }

        /** Like [validateName], but repairs a picker-supplied name instead of refusing it. */
        fun sanitizeName(name: String): String {
            val cleaned = name.map { if (it == '/' || it == '\\' || it == Char(0)) '_' else it }
                .joinToString("").trim().take(255)
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "imported_file" else cleaned
        }

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        fun uniqueChild(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (candidate.exists()) {
                candidate = File(dir, "$stem ($n)$ext")
                n++
            }
            return candidate
        }

        /** A NUL byte in the first 8 KB: images, archives, SQLite files and UTF-16 all have one. */
        fun looksBinary(file: File): Boolean {
            if (file.length() == 0L) return false
            val buffer = ByteArray(8192)
            val read = FileInputStream(file).use { it.read(buffer) }
            for (i in 0 until read) if (buffer[i] == 0.toByte()) return true
            return false
        }
    }
}

enum class FileKind { MISSING, DIRECTORY, TEXT, BINARY, TOO_LARGE }

data class FileInfo(val kind: FileKind, val size: Long)

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val children: List<FileNode>
)
