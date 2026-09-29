package com.runcode.app.settings

import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.security.SecretReferences
import com.runcode.app.security.SecretVault
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ProjectSettingsTest {
    private var stored = Project("p1", "Project", "", ProjectProfile.PYTHON_SCRIPT, "/projects/p1", "main.py")
    private val secrets = mutableMapOf<String, String>()
    private var vaultFails = false
    private var databaseFails = false
    private var active = false
    private var afterSave: (suspend () -> Unit)? = null
    private val manager = ProjectSettingsManager(object : SecretVault {
        override fun setSecret(key: String, plainText: String): Boolean {
            if (vaultFails) return false
            secrets[key] = plainText
            return true
        }
        override fun getSecret(key: String) = secrets[key]
        override fun removeSecret(key: String) { secrets.remove(key) }
    }, { stored }, {
        check(!databaseFails) { "Database unavailable" }
        stored = it
        afterSave?.invoke()
    }, { active })

    private fun settings(vararg edits: EnvironmentEdit) = ProjectSettings.from(stored).copy(environment = edits.toList())

    @Test fun `secret plaintext never enters project metadata`() = runBlocking {
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "test-secret-value", true)))
        val variable = stored.environment.single()
        assertNotEquals("test-secret-value", variable.value)
        assertEquals("test-secret-value", secrets[SecretReferences.key(variable.value)])
    }

    @Test fun `blank existing secret edit keeps its reference`() = runBlocking {
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "original-value", true)))
        val before = stored.environment
        manager.update("p1", ProjectSettings.from(stored).copy(port = 9090))
        assertEquals(before, stored.environment)
        assertEquals(9090, stored.network.port)
    }

    @Test fun `replacement removes only the old project secret`() = runBlocking {
        secrets["MCP_BRIDGE_TOKEN"] = "bridge-value"
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "old-value", true)))
        val oldKey = SecretReferences.key(stored.environment.single().value)
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "new-value", true)))
        assertFalse(secrets.containsKey(oldKey))
        assertEquals("bridge-value", secrets["MCP_BRIDGE_TOKEN"])
        assertTrue(secrets.containsValue("new-value"))
    }

    @Test fun `database failure rolls back newly written secrets`() = runBlocking {
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "old-value", true)))
        val before = stored
        val oldSecrets = secrets.toMap()
        databaseFails = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.update("p1", settings(EnvironmentEdit("TOKEN", "new-value", true))) }
        }
        assertEquals(before, stored)
        assertEquals(oldSecrets, secrets)
    }

    @Test fun `vault failure does not save plaintext or settings`() {
        vaultFails = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.update("p1", settings(EnvironmentEdit("TOKEN", "secret-value", true))) }
        }
        assertTrue(stored.environment.isEmpty())
        assertTrue(secrets.isEmpty())
    }

    @Test fun `mcp cannot replace or remove secrets`() = runBlocking {
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "secret-value", true)))
        listOf(settings(), settings(EnvironmentEdit("TOKEN", "plain", false)),
            settings(EnvironmentEdit("TOKEN", "new-secret", true))).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { manager.update("p1", candidate, allowSecrets = false) }
            }
        }
        manager.update("p1", ProjectSettings.from(stored).copy(port = 9091), allowSecrets = false)
        assertEquals(9091, stored.network.port)
    }

    @Test fun `mcp cannot add a secret reference as plain text`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.update("p1", settings(EnvironmentEdit("TOKEN", "\${SEC_MCP_BRIDGE_TOKEN}", false)), false) }
        }
    }

    @Test fun `invalid numbers duplicate keys and reserved variables are rejected`() {
        listOf(settings().copy(port = 0), settings().copy(port = 65536),
            settings().copy(maxCpuPercent = 101), settings().copy(maxHeapMb = -1),
            settings().copy(idleTimeoutMinutes = Int.MAX_VALUE),
            settings(EnvironmentEdit("A", "1", false), EnvironmentEdit("A", "2", false)),
            settings(EnvironmentEdit("1INVALID", "1", false)),
            settings(EnvironmentEdit("RUNCODE_PORT", "5", false)),
            settings(EnvironmentEdit("OK", "nul\u0000value", false))).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { candidate.validate() }
        }
    }

    @Test fun `active service settings cannot change`() {
        active = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.update("p1", settings().copy(port = 9000)) }
        }
        assertEquals(8080, stored.network.port)
    }

    @Test fun `removing a variable deletes its owned secret`() = runBlocking {
        manager.update("p1", settings(EnvironmentEdit("TOKEN", "secret-value", true)))
        manager.update("p1", settings())
        assertTrue(secrets.isEmpty())
        assertTrue(stored.environment.isEmpty())
    }

    @Test fun `new secret without a value is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.update("p1", settings(EnvironmentEdit("TOKEN", null, true))) }
        }
    }

    @Test fun `cancellation after metadata commit keeps referenced vault entries`() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        afterSave = {
            committed.complete(Unit)
            release.await()
        }
        val save = launch { manager.update("p1", settings(EnvironmentEdit("TOKEN", "saved-value", true))) }
        committed.await()
        save.cancel()
        release.complete(Unit)
        save.join()
        assertEquals("saved-value", secrets[SecretReferences.key(stored.environment.single().value)])
    }
}
