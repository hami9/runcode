package com.runcode.app.backup

import android.content.Context
import com.runcode.app.domain.models.BackupManifest
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.storage.ProjectStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupPreview(
    val isValid: Boolean,
    val manifest: BackupManifest?,
    val fileList: List<String>,
    val error: String? = null
)

class BackupManager(
    private val context: Context,
    private val projectStorage: ProjectStorage
) {

    suspend fun createProjectBackup(project: Project): File = withContext(Dispatchers.IO) {
        val backupsDir = projectStorage.getBackupsDir(project.id)
        if (!backupsDir.exists()) backupsDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val backupFile = File(backupsDir, "backup_${project.id}_$timestamp.rcpkg")
        val projectRoot = projectStorage.getProjectDir(project.id).canonicalFile

        // Gather files to include (source/ and data/, exclude logs/ and cache/)
        val filesToInclude = mutableListOf<Pair<File, String>>() // file -> zipRelativePath
        listOf("source", "data").forEach { subName ->
            val subFolder = File(projectRoot, subName)
            if (subFolder.exists()) {
                subFolder.walkTopDown().forEach { f ->
                    if (f.isFile && !f.name.endsWith("-wal") && !f.name.endsWith("-shm")) {
                        val relPath = f.relativeTo(projectRoot).path
                        filesToInclude.add(f to relPath)
                    }
                }
            }
        }

        // Calculate SHA-256 of file contents
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        filesToInclude.forEach { (file, _) ->
            totalBytes += file.length()
            FileInputStream(file).use { fis ->
                val buf = ByteArray(4096)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }

        val manifestContent = """
{
  "version": 1,
  "projectId": "${project.id}",
  "projectName": "${project.name}",
  "profileId": "${project.profile.id}",
  "createdAt": $timestamp,
  "fileCount": ${filesToInclude.size},
  "totalSizeBytes": $totalBytes,
  "checksum": "$checksum"
}
""".trimIndent()

        // Create the zip archive
        ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { zos ->
            // 1. Write manifest.json
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Write project payload files
            filesToInclude.forEach { (f, relPath) ->
                zos.putNextEntry(ZipEntry("payload/$relPath"))
                BufferedInputStream(FileInputStream(f)).use { bis ->
                    bis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }

        backupFile
    }

    suspend fun verifyBackup(backupFile: File): BackupPreview = withContext(Dispatchers.IO) {
        if (!backupFile.exists() || backupFile.length() == 0L) {
            return@withContext BackupPreview(isValid = false, manifest = null, fileList = emptyList(), error = "File does not exist or is empty")
        }

        try {
            ZipFile(backupFile).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                    ?: return@withContext BackupPreview(isValid = false, manifest = null, fileList = emptyList(), error = "Missing manifest.json in backup archive")

                val manifestJson = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val manifest = parseManifest(manifestJson)

                val fileList = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.contains("..")) {
                        return@withContext BackupPreview(isValid = false, manifest = null, fileList = emptyList(), error = "Zip-Slip attempt detected: ${e.name}")
                    }
                    if (!e.isDirectory && e.name != "manifest.json") {
                        fileList.add(e.name.removePrefix("payload/"))
                    }
                }

                BackupPreview(
                    isValid = true,
                    manifest = manifest,
                    fileList = fileList
                )
            }
        } catch (e: Exception) {
            BackupPreview(isValid = false, manifest = null, fileList = emptyList(), error = "Verification failed: ${e.message}")
        }
    }

    suspend fun restoreProjectFromBackup(backupFile: File, overrideName: String? = null): Project = withContext(Dispatchers.IO) {
        val preview = verifyBackup(backupFile)
        if (!preview.isValid || preview.manifest == null) {
            throw IllegalArgumentException(preview.error ?: "Invalid backup file")
        }

        val newId = UUID.randomUUID().toString().substring(0, 8)
        val stagingDir = File(context.cacheDir, "restore_staging_$newId")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        try {
            // Extract payload to staging directory
            ZipFile(backupFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith("payload/") && !e.isDirectory) {
                        val relPath = e.name.removePrefix("payload/")
                        val outFile = File(stagingDir, relPath).canonicalFile
                        if (!outFile.path.startsWith(stagingDir.canonicalPath)) {
                            throw SecurityException("Zip-Slip detected in restore")
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zip.getInputStream(e).copyTo(fos)
                        }
                    }
                }
            }

            // Move from staging to project storage
            projectStorage.initializeProjectDirectories(newId)
            val projectRoot = projectStorage.getProjectDir(newId)

            stagingDir.listFiles()?.forEach { child ->
                val targetDir = File(projectRoot, child.name)
                child.copyRecursively(targetDir, overwrite = true)
            }

            val finalName = overrideName ?: preview.manifest.projectName
            val profile = ProjectProfile.entries.find { it.id == preview.manifest.profileId } ?: ProjectProfile.PYTHON_SCRIPT

            // Determine entry point from restored source directory
            val sourceDir = projectStorage.getSourceDir(newId)
            val entryPoint = if (File(sourceDir, "main.py").exists()) {
                "main.py"
            } else if (File(sourceDir, "bot.py").exists()) {
                "bot.py"
            } else if (File(sourceDir, "index.html").exists()) {
                "index.html"
            } else if (File(sourceDir, "server.py").exists()) {
                "server.py"
            } else if (File(sourceDir, "app.py").exists()) {
                "app.py"
            } else {
                sourceDir.listFiles()?.firstOrNull { it.isFile }?.name ?: "main.py"
            }

            Project(
                id = newId,
                name = finalName,
                description = "Restored from backup (${preview.manifest.projectName})",
                profile = profile,
                projectRoot = projectRoot.absolutePath,
                entryPoint = entryPoint
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun parseManifest(json: String): BackupManifest {
        // Simple robust JSON extractor without external dependency
        val projectId = json.substringAfter("\"projectId\": \"").substringBefore("\"")
        val projectName = json.substringAfter("\"projectName\": \"").substringBefore("\"")
        val profileId = json.substringAfter("\"profileId\": \"").substringBefore("\"")
        val createdAt = json.substringAfter("\"createdAt\": ").substringBefore(",").trim().toLongOrNull() ?: System.currentTimeMillis()
        val fileCount = json.substringAfter("\"fileCount\": ").substringBefore(",").trim().toIntOrNull() ?: 0
        val totalSizeBytes = json.substringAfter("\"totalSizeBytes\": ").substringBefore(",").trim().toLongOrNull() ?: 0L
        val checksum = json.substringAfter("\"checksum\": \"").substringBefore("\"")

        return BackupManifest(
            version = 1,
            projectId = projectId,
            projectName = projectName,
            profileId = profileId,
            createdAt = createdAt,
            fileCount = fileCount,
            totalSizeBytes = totalSizeBytes,
            checksum = checksum
        )
    }
}
