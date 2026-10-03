package com.runcode.app.ui

import android.os.Looper
import com.runcode.app.RuncodeApp
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.git.GitIdentity
import com.runcode.app.git.HostPythonGitBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Git screen's view model against real git (dulwich in the host's Python). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class GitViewModelTest {

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for: $what", condition())
    }

    @Test fun `a view model clears only the unsaved-file marker it published`() {
        val app = RuntimeEnvironment.getApplication() as RuncodeApp
        val project = app.projectStorage.createProjectFromTemplate("VM marker", ProjectProfile.PYTHON_SCRIPT)
        runBlocking { app.appMetaDatabase.insertOrUpdateProject(project) }
        val model = MainViewModel(app)
        await("projects") { model.projects.value.isNotEmpty() }
        model.selectProject(project)
        await("selection") { model.selectedProject.value?.id == project.id && model.editorContent.value.isNotEmpty() }
        val onCleared = MainViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }

        model.updateEditorContent("unsaved")
        await("published") { app.unsavedEditorFile?.first == project.id }
        // Another activity's view model published since: not ours to clear.
        val other = "another-project" to "main.py"
        app.unsavedEditorFile = other
        onCleared.invoke(model)
        assertSame(other, app.unsavedEditorFile)

        model.saveCurrentFile()
        await("saved") { !model.isDirty.value && app.unsavedEditorFile == null }
        model.updateEditorContent("unsaved again")
        await("published again") { app.unsavedEditorFile?.first == project.id }
        onCleared.invoke(model)
        assertNull("its own marker is cleared", app.unsavedEditorFile)
    }

    @Test fun `initialise, commit, and refuse a pull over unsaved editor changes`() {
        val python = HostPythonGitBackend.find()
        assumeTrue("No Python with dulwich", python != null)
        val app = RuntimeEnvironment.getApplication() as RuncodeApp
        app.gitBackend = HostPythonGitBackend(python!!)
        app.git.identity = GitIdentity("Ada", "ada@example.com")

        val project = app.projectStorage.createProjectFromTemplate("VM git", ProjectProfile.PYTHON_SCRIPT)
        runBlocking { app.appMetaDatabase.insertOrUpdateProject(project) }
        val model = MainViewModel(app)
        await("projects") { model.projects.value.isNotEmpty() }
        model.selectProject(project)
        await("selection") { model.selectedProject.value?.id == project.id && model.editorContent.value.isNotEmpty() }

        model.refreshGit()
        await("first refresh") { model.git.value.busy == null && model.git.value.projectId == project.id }
        assertFalse(model.git.value.isRepository)

        model.gitInit()
        await("init") { model.git.value.isRepository && model.git.value.busy == null }
        assertEquals("main", model.git.value.status!!.branch)
        assertFalse(model.git.value.status!!.isClean)

        model.gitCommit("First", stageAll = true)
        await("commit") { model.git.value.log.size == 1 && model.git.value.busy == null }
        assertTrue(model.git.value.status!!.isClean)
        assertEquals("First", model.git.value.log.single().message)

        // Unsaved edits: a pull would rewrite files underneath the editor.
        model.updateEditorContent("unsaved change")
        model.gitPull()
        assertEquals("Save the file open in the editor first.", model.git.value.error)
        // The MCP bridge sees the same unsaved file, so its git_pull refuses too.
        await("unsaved file published") { app.unsavedEditorFile == project.id to model.activeTab.value }

        // A failing operation surfaces its reason and leaves the screen usable.
        model.saveCurrentFile()
        await("save") { !model.isDirty.value }
        await("unsaved file cleared") { app.unsavedEditorFile == null }
        model.gitPush()
        await("push error") { model.git.value.busy == null && model.git.value.error != null }
        assertTrue(model.git.value.error!!.contains("remote"))
        assertTrue(model.git.value.isRepository)
    }
}
