package com.runcode.app.ui

import android.os.Looper
import com.runcode.app.RuncodeApp
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class EditorSessionTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Editor operation did not finish", condition())
    }

    private fun create(app: RuncodeApp, name: String): Project {
        val project = app.projectStorage.createProjectFromTemplate(name, ProjectProfile.PYTHON_SCRIPT)
        app.projectStorage.writeFileAtomically(project.id, "source/main.py", name)
        runBlocking { app.appMetaDatabase.insertOrUpdateProject(project) }
        return project
    }

    @Test fun `switching projects saves to the old owner and clears its tabs`() {
        val app = RuntimeEnvironment.getApplication<RuncodeApp>()
        val a = create(app, "project-a")
        val b = create(app, "project-b")
        val model = MainViewModel(app)
        await { model.projects.value.isNotEmpty() }
        model.selectProject(a)
        await { model.selectedProject.value?.id == a.id && model.editorContent.value == "project-a" }
        model.updateEditorContent("unsaved-a")
        model.selectProject(b)
        await { model.selectedProject.value?.id == b.id && model.editorContent.value == "project-b" }
        assertEquals("unsaved-a", app.projectStorage.readFile(a.id, "source/main.py"))
        assertEquals("project-b", app.projectStorage.readFile(b.id, "source/main.py"))
        assertEquals(listOf("source/main.py"), model.openTabs.value)
        assertFalse(model.isDirty.value)
    }

    @Test fun `failed save prevents project switch and keeps the buffer`() {
        val app = RuntimeEnvironment.getApplication<RuncodeApp>()
        val a = create(app, "save-failure-a")
        val b = create(app, "save-failure-b")
        val model = MainViewModel(app)
        await { model.projects.value.isNotEmpty() }
        model.selectProject(a)
        await { model.editorContent.value == "save-failure-a" }
        model.updateEditorContent("must-survive")
        val entry = File(a.projectRoot, "source/main.py")
        assertTrue(entry.delete())
        assertTrue(entry.mkdir())
        model.selectProject(b)
        await { model.userMessage.value?.startsWith("Save error:") == true }
        assertEquals(a.id, model.selectedProject.value?.id)
        assertEquals("must-survive", model.editorContent.value)
        assertTrue(model.isDirty.value)
    }
}
