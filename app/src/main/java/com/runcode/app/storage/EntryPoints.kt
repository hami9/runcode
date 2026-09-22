package com.runcode.app.storage

import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile

/** What a rename, move or delete did to a project's entry point. */
sealed interface EntryPointEffect {
    data object Unaffected : EntryPointEffect

    /** The entry point moved and is still inside `source/`; [project] points at it again. */
    data class Moved(val project: Project) : EntryPointEffect

    /** The entry point was deleted or moved out of `source/`; the project cannot start as-is. */
    data object Lost : EntryPointEffect
}

object EntryPoints {

    /** Why [relativePath] cannot be [project]'s entry point, or null if it can. */
    fun problemWith(project: Project, relativePath: String): String? = when {
        !relativePath.startsWith("source/") -> "The entry point has to be inside source/"
        project.profile != ProjectProfile.STATIC_WEB && !relativePath.endsWith(".py") ->
            "${project.profile.displayName} projects run a .py file"
        else -> null
    }

    /**
     * Follows the entry point when [from] (a project-relative path) moves to [to], or is deleted
     * when [to] is null. Without this, renaming `main.py` left a project that failed to start
     * with "Entrypoint script does not exist".
     */
    fun follow(project: Project, from: String, to: String?): EntryPointEffect {
        val entry = "source/${project.entryPoint}"
        val moved = when {
            entry == from -> to
            entry.startsWith("$from/") -> to?.let { it + entry.removePrefix(from) }
            else -> return EntryPointEffect.Unaffected
        }
        if (moved == null || !moved.startsWith("source/")) return EntryPointEffect.Lost
        return EntryPointEffect.Moved(
            project.copy(entryPoint = moved.removePrefix("source/"), updatedAt = System.currentTimeMillis())
        )
    }
}
