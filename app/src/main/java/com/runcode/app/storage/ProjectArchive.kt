package com.runcode.app.storage

import android.content.Context
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.NetworkConfig
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.RestartPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moves whole projects in and out of the app as ordinary .zip files.
 *
 * An export carries `runcode.json` with the project's settings next to `source/`, `data/`
 * and `config/`, so importing it gives back the same project. Any other zip — a GitHub
 * "Download ZIP", a folder zipped on a laptop — is imported as a new Python project with
 * its contents as the source code.
 *
 * Unlike a backup (.rcpkg) this is meant to leave the device, so it never contains a secret:
 * the vault stays behind, and a secret variable is exported only as its `${SEC_...}` reference.
 */
class ProjectArchive(
    private val context: Context,
    private val storage: ProjectStorage
) {

    fun export(project: Project, destination: OutputStream) {
        val root = storage.getProjectDir(project.id).canonicalFile
        ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(encodeManifest(project, root).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            EXPORTED_DIRS.forEach { name ->
                val folder = File(root, name)
                if (folder.isDirectory) addFolder(folder, name, zip)
            }
        }
    }

    /** Imports a zip as a brand-new project. Nothing is written into an existing one. */
    fun import(input: InputStream, fallbackName: String): Project {
        val id = storage.newProjectId()
        val staging = File(context.cacheDir, "import_$id")
        staging.deleteRecursively()
        staging.mkdirs()

        try {
            extractSafely(input, staging)

            val manifest = File(staging, MANIFEST_NAME).takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.takeIf { it.optString("format") == MANIFEST_FORMAT }

            // A zip made by zipping a folder, or GitHub's "Download ZIP", wraps everything in
            // one top-level directory. Look inside it.
            var contentRoot = staging
            if (manifest == null && !File(staging, "source").isDirectory) {
                val children = staging.listFiles().orEmpty().filterNot { it.name in IGNORED_ENTRIES }
                if (children.size == 1 && children[0].isDirectory) contentRoot = children[0]
            }
            if (contentRoot.listFiles().orEmpty().none { it.name !in IGNORED_ENTRIES && it.name != MANIFEST_NAME }) {
                throw IllegalArgumentException("The archive is empty")
            }

            storage.initializeProjectDirectories(id)
            val root = storage.getProjectDir(id)
            val isRuncodeLayout = manifest != null || File(contentRoot, "source").isDirectory
            if (isRuncodeLayout) {
                EXPORTED_DIRS.forEach { name ->
                    val from = File(contentRoot, name)
                    if (from.isDirectory) moveInto(from, File(root, name))
                }
            } else {
                val sourceDir = storage.getSourceDir(id)
                contentRoot.listFiles().orEmpty()
                    .filterNot { it.name in IGNORED_ENTRIES }
                    .forEach { moveInto(it, File(sourceDir, it.name)) }
            }

            return buildProject(id, root, manifest, fallbackName)
        } catch (e: Exception) {
            storage.deleteProject(id)
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun buildProject(id: String, root: File, manifest: JSONObject?, fallbackName: String): Project {
        val sourceDir = storage.getSourceDir(id)
        val entryPoint = detectEntryPoint(sourceDir, manifest?.optString("entry_point"))
        val profile = manifest?.optString("profile")
            ?.let { wanted -> ProjectProfile.entries.find { it.id == wanted } }
            ?: if (entryPoint.endsWith(".html") || entryPoint.endsWith(".htm")) ProjectProfile.STATIC_WEB else ProjectProfile.PYTHON_SCRIPT
        val name = manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { "Imported project" }

        val defaultPolicy = if (profile == ProjectProfile.PYTHON_SCRIPT || profile == ProjectProfile.SQLITE_APP) {
            RestartPolicy.NEVER
        } else {
            RestartPolicy.ON_FAILURE
        }

        return Project(
            id = id,
            name = name,
            description = manifest?.optString("description")?.takeIf { it.isNotBlank() } ?: "Imported from $fallbackName.zip",
            profile = profile,
            projectRoot = root.absolutePath,
            entryPoint = entryPoint,
            arguments = manifest?.optJSONArray("arguments")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            } ?: emptyList(),
            environment = manifest?.optJSONArray("environment")?.let { decodeEnvironment(it) } ?: emptyList(),
            workingDirectory = manifest?.optString("working_directory")
                ?.takeIf { it.isNotBlank() }
                ?.let { relative -> runCatching { storage.resolveInProject(id, relative).path }.getOrNull() }
                ?: "",
            restartPolicy = manifest?.optString("restart_policy")
                ?.let { wanted -> RestartPolicy.entries.find { it.name == wanted } }
                ?: defaultPolicy,
            // Never auto-start imported code on boot: the person should run it once first.
            startOnBoot = false,
            maxCpuPercent = manifest?.optInt("max_cpu_percent", 0) ?: 0,
            maxHeapMb = manifest?.optInt("max_heap_mb", 0) ?: 0,
            idleTimeoutMinutes = manifest?.optInt("idle_timeout_minutes", 0) ?: 0,
            network = NetworkConfig(
                port = manifest?.optInt("port", 8080)?.takeIf { it in 1024..65535 } ?: 8080,
                allowLan = manifest?.optBoolean("allow_lan", false) ?: false
            )
        )
    }

    private fun encodeManifest(project: Project, root: File): JSONObject {
        val environment = JSONArray()
        project.environment.forEach { variable ->
            val value = when {
                !variable.isSecret -> variable.value
                SECRET_REFERENCE.matches(variable.value) -> variable.value
                else -> ""
            }
            environment.put(
                JSONObject()
                    .put("key", variable.key)
                    .put("value", value)
                    .put("is_secret", variable.isSecret)
            )
        }
        val arguments = JSONArray()
        project.arguments.forEach { arguments.put(it) }

        // Only a working directory inside the project means anything on another device.
        val workingDirectory = project.workingDirectory.takeIf { it.isNotBlank() }
            ?.let { File(it).canonicalFile }
            ?.takeIf { it.path.startsWith(root.path + File.separator) }
            ?.relativeTo(root)?.path?.replace(File.separatorChar, '/')
            ?: ""

        return JSONObject()
            .put("format", MANIFEST_FORMAT)
            .put("version", 1)
            .put("name", project.name)
            .put("description", project.description)
            .put("profile", project.profile.id)
            .put("entry_point", project.entryPoint)
            .put("arguments", arguments)
            .put("environment", environment)
            .put("working_directory", workingDirectory)
            .put("restart_policy", project.restartPolicy.name)
            .put("port", project.network.port)
            .put("allow_lan", project.network.allowLan)
            .put("max_cpu_percent", project.maxCpuPercent)
            .put("max_heap_mb", project.maxHeapMb)
            .put("idle_timeout_minutes", project.idleTimeoutMinutes)
    }

    private fun decodeEnvironment(array: JSONArray): List<EnvironmentVariable> =
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val key = obj.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EnvironmentVariable(key, obj.optString("value"), obj.optBoolean("is_secret", false))
        }

    private fun detectEntryPoint(sourceDir: File, preferred: String?): String {
        preferred?.takeIf { it.isNotBlank() && File(sourceDir, it).isFile }?.let { return it }
        COMMON_ENTRY_POINTS.firstOrNull { File(sourceDir, it).isFile }?.let { return it }
        val topLevel = sourceDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
        topLevel.firstOrNull { it.extension == "py" && !it.name.startsWith("_") }?.let { return it.name }
        topLevel.firstOrNull { it.extension == "html" || it.extension == "htm" }?.let { return it.name }
        return preferred?.takeIf { it.isNotBlank() } ?: "main.py"
    }

    private fun addFolder(folder: File, parentPath: String, zip: ZipOutputStream) {
        folder.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val entryPath = "$parentPath/${file.name}"
            if (file.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryPath/"))
                zip.closeEntry()
                addFolder(file, entryPath, zip)
            } else if (!file.name.contains(".tmp_")) {
                zip.putNextEntry(ZipEntry(entryPath))
                BufferedInputStream(FileInputStream(file)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Extracts with Zip-Slip protection and hard caps, so a hostile or corrupt archive can
     * neither write outside [destination] nor fill the disk.
     */
    private fun extractSafely(input: InputStream, destination: File) {
        val destCanonical = destination.canonicalFile
        var totalBytes = 0L
        var entries = 0
        val buffer = ByteArray(16 * 1024)

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            if (entry == null) throw IllegalArgumentException("Not a zip archive, or the archive is empty")
            while (entry != null) {
                entries++
                if (entries > MAX_ENTRIES) throw IllegalArgumentException("Archive has more than $MAX_ENTRIES entries")

                // Archives made on Windows sometimes use backslashes as separators.
                val name = entry.name.replace('\\', '/')
                val target = File(destCanonical, name).canonicalFile
                if (target != destCanonical && !target.path.startsWith(destCanonical.path + File.separator)) {
                    throw SecurityException("Refusing archive entry outside the project: ${entry.name}")
                }

                if (entry.isDirectory || name.endsWith("/")) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                            if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw IllegalArgumentException("Archive expands to more than ${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** cacheDir and filesDir share a filesystem, so this is normally a rename, not a copy. */
    private fun moveInto(from: File, to: File) {
        if (to.isDirectory && to.list().isNullOrEmpty()) to.delete()
        if (to.exists() || !from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
        }
    }

    companion object {
        const val MANIFEST_NAME = "runcode.json"
        private const val MANIFEST_FORMAT = "runcode-project"
        private val EXPORTED_DIRS = listOf("source", "data", "config")
        private val IGNORED_ENTRIES = setOf("__MACOSX", ".DS_Store")
        private val COMMON_ENTRY_POINTS = listOf("main.py", "bot.py", "app.py", "server.py", "index.html")
        private val SECRET_REFERENCE = Regex("""\$\{SEC_[A-Za-z0-9_]+\}""")
        private const val MAX_ENTRIES = 20_000
        private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    }
}
