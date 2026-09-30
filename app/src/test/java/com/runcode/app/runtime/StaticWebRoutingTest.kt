package com.runcode.app.runtime

import android.app.Application
import com.runcode.app.network.PortManager
import com.runcode.app.system.ProcessMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StaticWebRoutingTest {

    private lateinit var engine: StaticWebEngine
    private lateinit var sourceDir: File

    @Before fun setup() {
        engine = StaticWebEngine(PortManager(), ProcessMonitor())
        sourceDir = Files.createTempDirectory("static-web").toFile()
        File(sourceDir, "index.html").writeText("<html></html>")
        File(sourceDir, "style.css").writeText("body{}")
        File(sourceDir, "assets").mkdirs()
        File(sourceDir, "assets/app.js").writeText("// app")
    }

    private fun resolve(path: String) = engine.resolveTarget(sourceDir, path, "index.html")

    @Test fun `root serves the entrypoint`() {
        assertEquals(File(sourceDir, "index.html").canonicalFile, resolve(""))
    }

    @Test fun `existing files are served`() {
        assertEquals(File(sourceDir, "style.css").canonicalFile, resolve("style.css"))
        assertEquals(File(sourceDir, "assets/app.js").canonicalFile, resolve("assets/app.js"))
    }

    @Test fun `a route with no extension falls back to the entrypoint`() {
        assertEquals(File(sourceDir, "index.html").canonicalFile, resolve("dashboard"))
        assertEquals(File(sourceDir, "index.html").canonicalFile, resolve("users/42/"))
    }

    @Test fun `a missing file is not answered with the entrypoint`() {
        // It used to return index.html with a 200, so a typo in an asset path broke the page
        // quietly. The caller turns a file that does not exist into a 404.
        val missing = resolve("missing.css")
        assertEquals(File(sourceDir, "missing.css").canonicalFile, missing)
        assertFalse(missing!!.isFile)

        val missingNested = resolve("assets/nope.js")
        assertEquals(File(sourceDir, "assets/nope.js").canonicalFile, missingNested)
        assertFalse(missingNested!!.isFile)
    }

    @Test fun `paths that escape the source directory are refused`() {
        assertNull(resolve("../secrets.txt"))
        assertNull(resolve("assets/../../secrets.txt"))
    }
}
