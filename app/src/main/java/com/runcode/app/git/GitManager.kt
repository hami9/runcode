package com.runcode.app.git

import android.content.SharedPreferences
import androidx.core.content.edit
import com.runcode.app.domain.models.Project
import com.runcode.app.security.SecretRedactor
import com.runcode.app.security.SecretVault
import com.runcode.app.storage.ProjectArchive
import com.runcode.app.storage.ProjectStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Calls one function of `runcode_git.py` and returns its JSON reply. */
fun interface GitBackend {
    fun call(function: String, vararg args: Any): String
}

data class GitStatus(
    val branch: String?,
    val head: String?,
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val unstaged: List<String>,
    val untracked: List<String>,
    /** Commits not on the remote yet; null until the branch has been pushed or pulled once. */
    val ahead: Int?,
    val behind: Int?,
    val remote: String?
) {
    val staged: List<String> get() = added + modified + deleted
    val isClean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()
}

data class GitCommit(val sha: String, val author: String, val time: Long, val message: String)

data class GitIdentity(val name: String, val email: String)

class GitException(message: String) : Exception(message)

/**
 * Git for projects, backed by dulwich in the embedded Python. The repository is the project's
 * `source/` folder, so data, logs and caches never end up in commits.
 *
 * The GitHub token lives in the Keystore-backed vault and is only ever handed to the Python
 * call that needs it; the Python side scrubs it from every error message.
 */
class GitManager(
    private val backend: GitBackend,
    private val storage: ProjectStorage,
    private val archive: ProjectArchive,
    private val vault: SecretVault,
    private val prefs: SharedPreferences
) {
    // dulwich is not safe to run twice at once on one repository; one at a time keeps it simple.
    private val mutex = Mutex()

    fun repoDir(project: Project): File = storage.getSourceDir(project.id)

    fun isRepository(project: Project): Boolean = File(repoDir(project), ".git").isDirectory

    // ---------------------------------------------------------------- settings

    var identity: GitIdentity
        get() = GitIdentity(prefs.getString(KEY_NAME, "") ?: "", prefs.getString(KEY_EMAIL, "") ?: "")
        set(value) = prefs.edit { putString(KEY_NAME, value.name.trim()).putString(KEY_EMAIL, value.email.trim()) }

    val hasToken: Boolean get() = vault.getSecret(TOKEN_KEY) != null

    fun setToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) vault.removeSecret(TOKEN_KEY)
        else if (!vault.setSecret(TOKEN_KEY, trimmed)) throw GitException("The token could not be stored in the vault.")
    }

    /**
     * Username and token for [url], or empty ones. The token is a GitHub token, so it only
     * goes to GitHub over HTTPS: a mistyped or hostile remote, or plain HTTP, never sees it.
     */
    private fun credentialsFor(url: String?): Pair<String, String> {
        if (url == null || !sendsToken(url)) return "" to ""
        val token = vault.getSecret(TOKEN_KEY)?.also { SecretRedactor.register(it) } ?: return "" to ""
        // GitHub accepts any user name with a token; this is the one its docs use.
        return TOKEN_USER to token
    }

    // ---------------------------------------------------------------- local

    suspend fun init(project: Project) = run { call("init", repoDir(project).path) }

    suspend fun status(project: Project): GitStatus = run {
        val r = call("status", repoDir(project).path)
        val staged = r.getJSONObject("staged")
        GitStatus(
            branch = r.optStringOrNull("branch"),
            head = r.optStringOrNull("head"),
            added = staged.getJSONArray("added").strings(),
            modified = staged.getJSONArray("modified").strings(),
            deleted = staged.getJSONArray("deleted").strings(),
            unstaged = r.getJSONArray("unstaged").strings(),
            untracked = r.getJSONArray("untracked").strings(),
            ahead = r.optIntOrNull("ahead"),
            behind = r.optIntOrNull("behind"),
            remote = r.optStringOrNull("remote")
        )
    }

    /** Stages [paths], or everything including deletions when empty. */
    suspend fun stage(project: Project, paths: List<String> = emptyList()) = run {
        call("add", repoDir(project).path, JSONArray(paths).toString())
    }

    suspend fun commit(project: Project, message: String): String = run {
        val who = identity
        call("commit", repoDir(project).path, message, who.name, who.email).getString("sha")
    }

    suspend fun log(project: Project, limit: Int = 30): List<GitCommit> = run {
        val commits = call("log", repoDir(project).path, limit.toString()).getJSONArray("commits")
        (0 until commits.length()).map { i ->
            val c = commits.getJSONObject(i)
            GitCommit(c.getString("sha"), c.getString("author"), c.getLong("time"), c.getString("message"))
        }
    }

    suspend fun branches(project: Project): Pair<String?, List<String>> = run {
        val r = call("branches", repoDir(project).path)
        r.optStringOrNull("current") to r.getJSONArray("branches").strings()
    }

    suspend fun checkout(project: Project, branch: String, create: Boolean) = run {
        require(BRANCH_NAME.matches(branch)) { "Branch names use letters, digits, '.', '_', '-' and '/'." }
        call("checkout", repoDir(project).path, branch, create)
    }

    suspend fun setRemote(project: Project, url: String) = run {
        call("set_remote", repoDir(project).path, url.trim())
    }

    // ---------------------------------------------------------------- network

    suspend fun push(project: Project) = run {
        val branch = currentBranch(project)
        val (user, token) = credentialsFor(remoteOf(project))
        call("push", repoDir(project).path, branch, user, token)
    }

    /** Fast-forward only. Returns the number of new commits. */
    suspend fun pull(project: Project): Int = run {
        val branch = currentBranch(project)
        val (user, token) = credentialsFor(remoteOf(project))
        call("pull", repoDir(project).path, branch, user, token).getInt("new_commits")
    }

    /**
     * Clones [url] into a new project and returns it, unsaved. The repository becomes the
     * project's source/ folder and the entry point is detected like a zip import.
     */
    suspend fun cloneProject(url: String): Project = run {
        val name = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "Cloned project" }
        val id = storage.newProjectId()
        storage.initializeProjectDirectories(id)
        val source = storage.getSourceDir(id)
        try {
            // dulwich creates the folder itself and refuses an existing one.
            source.deleteRecursively()
            val (user, token) = credentialsFor(url.trim())
            call("clone", url.trim(), source.path, user, token)
            archive.projectForSource(id, name, "Cloned from ${url.trim()}")
        } catch (e: Exception) {
            storage.deleteProject(id)
            throw e
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun remoteOf(project: Project): String? =
        call("status", repoDir(project).path).optStringOrNull("remote")

    private fun currentBranch(project: Project): String {
        val r = call("branches", repoDir(project).path)
        return r.optStringOrNull("current") ?: throw GitException("Check out a branch first.")
    }

    private suspend fun <T> run(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun call(function: String, vararg args: Any): JSONObject {
        val reply = JSONObject(backend.call(function, *args))
        if (!reply.optBoolean("ok")) throw GitException(SecretRedactor.redact(reply.optString("error", "git failed")))
        return reply
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key) || !has(key)) null else getString(key)
    private fun JSONObject.optIntOrNull(key: String) = if (isNull(key) || !has(key)) null else getInt(key)

    companion object {
        const val TOKEN_KEY = "GIT_GITHUB_TOKEN"
        const val TOKEN_USER = "x-access-token"
        private const val KEY_NAME = "author_name"
        private const val KEY_EMAIL = "author_email"
        private val BRANCH_NAME = Regex("[A-Za-z0-9._/-]+")
        private val TOKEN_HOSTS = setOf("github.com")

        /** Whether [url] is an HTTPS URL on a host the GitHub token belongs to. */
        fun sendsToken(url: String): Boolean {
            val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.userInfo == null &&
                uri.host?.lowercase() in TOKEN_HOSTS
        }
    }
}
