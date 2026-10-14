package dev.jaflaten.vmm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModManagerTest {
    @TempDir
    lateinit var tmp: Path

    private fun manager(): ModManager = ModManager(TestUtil.fakeGameRoot(Files.createDirectories(tmp.resolve("game"))), ThunderstoreClient(tmp.resolve("cache.gz")))

    private fun install(m: ModManager, name: String, entries: Map<String, String>, version: String = "1.0.0"): List<String> =
        m.installZip(TestUtil.zip(tmp.resolve("$name-$version.zip"), entries), name, version, emptyList())

    @Test
    fun `standard plugins layout with config defaults`() {
        val m = manager()
        install(m, "Jere-Dev", mapOf("manifest.json" to "{}", "icon.png" to "x", "plugins/Dev.dll" to "dll", "config/dev.cfg" to "a=1"))
        assertTrue(Files.exists(m.pluginsDir.resolve("Jere-Dev/Dev.dll")))
        assertFalse(Files.exists(m.pluginsDir.resolve("Jere-Dev/manifest.json")))
        assertEquals("a=1", Files.readString(m.configDir.resolve("dev.cfg")))
        assertEquals(listOf("dev.cfg"), m.loadState().mods["Jere-Dev"]!!.createdConfigs)
    }

    @Test
    fun `root dlls, BepInEx prefix, wrapper dir and patchers`() {
        val m = manager()
        install(m, "A-Root", mapOf("manifest.json" to "{}", "A.dll" to "x", "Assets/a.bundle" to "y"))
        assertTrue(Files.exists(m.pluginsDir.resolve("A-Root/A.dll")))
        assertTrue(Files.exists(m.pluginsDir.resolve("A-Root/Assets/a.bundle")))

        install(m, "B-Prefixed", mapOf("BepInEx/plugins/B/B.dll" to "x", "BepInEx/patchers/BP.dll" to "p", "BepInEx/core/evil.dll" to "no"))
        assertTrue(Files.exists(m.pluginsDir.resolve("B-Prefixed/B/B.dll")))
        assertTrue(Files.exists(m.patchersDir.resolve("B-Prefixed/BP.dll")))
        assertFalse(Files.exists(m.bepinexDir.resolve("core/evil.dll")))

        install(m, "C-Wrapped", mapOf("CWrap/plugins/C.dll" to "x"))
        assertTrue(Files.exists(m.pluginsDir.resolve("C-Wrapped/C.dll")))
    }

    @Test
    fun `existing user config is never overwritten`() {
        val m = manager()
        Files.writeString(m.configDir.resolve("dev.cfg"), "mine")
        install(m, "Jere-Dev", mapOf("plugins/Dev.dll" to "dll", "config/dev.cfg" to "default"))
        assertEquals("mine", Files.readString(m.configDir.resolve("dev.cfg")))
        assertTrue(m.loadState().mods["Jere-Dev"]!!.createdConfigs.isEmpty())
    }

    @Test
    fun `update removes stale files and keeps disabled state`() {
        val m = manager()
        install(m, "A-Mod", mapOf("plugins/Old.dll" to "x"), "1.0.0")
        m.setEnabled("A-Mod", false)
        install(m, "A-Mod", mapOf("plugins/New.dll" to "x"), "2.0.0")
        assertFalse(Files.exists(m.pluginsDisabledDir.resolve("A-Mod/Old.dll")))
        assertTrue(Files.exists(m.pluginsDisabledDir.resolve("A-Mod/New.dll")))
        assertFalse(Files.exists(m.pluginsDir.resolve("A-Mod")))
        assertEquals("2.0.0", m.loadState().mods["A-Mod"]!!.version)
    }

    @Test
    fun `enable disable and uninstall move plugins and patchers together`() {
        val m = manager()
        install(m, "A-Mod", mapOf("plugins/A.dll" to "x", "patchers/AP.dll" to "p", "config/a.cfg" to "c"))
        m.setEnabled("A-Mod", false)
        assertTrue(Files.exists(m.pluginsDisabledDir.resolve("A-Mod/A.dll")))
        assertTrue(Files.exists(m.patchersDisabledDir.resolve("A-Mod/AP.dll")))
        assertFalse(Files.exists(m.pluginsDir.resolve("A-Mod")))
        assertFalse(m.list().single().enabled)

        m.setEnabled("A-Mod", true)
        assertTrue(Files.exists(m.pluginsDir.resolve("A-Mod/A.dll")))
        assertTrue(Files.exists(m.patchersDir.resolve("A-Mod/AP.dll")))

        m.uninstall("A-Mod", removeConfigs = true)
        assertFalse(Files.exists(m.pluginsDir.resolve("A-Mod")))
        assertFalse(Files.exists(m.patchersDir.resolve("A-Mod")))
        assertFalse(Files.exists(m.configDir.resolve("a.cfg")))
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `manual mods are listed and can be toggled`() {
        val m = manager()
        Files.writeString(m.pluginsDir.resolve("Manual.dll"), "x")
        val entry = m.list().single()
        assertFalse(entry.managed)
        m.setEnabled("Manual.dll", false)
        assertTrue(Files.exists(m.pluginsDisabledDir.resolve("Manual.dll")))
        assertFalse(m.list().single().enabled)
    }

    @Test
    fun `zip slip is rejected`() {
        val m = manager()
        assertThrows(Exception::class.java) { install(m, "Evil-Mod", mapOf("../../evil.txt" to "x")) }
        assertFalse(Files.exists(tmp.resolve("evil.txt")))
        assertTrue(m.loadState().mods.isEmpty())
    }

    @Test
    fun `symlinked plugin folder is refused`() {
        val m = manager()
        val outside = Files.createDirectories(tmp.resolve("outside"))
        Files.createSymbolicLink(m.pluginsDisabledDir.also { Files.createDirectories(it.parent) }, outside)
        install(m, "A-Mod", mapOf("plugins/A.dll" to "x"))
        assertThrows(VmmException::class.java) { m.setEnabled("A-Mod", false) }
        assertTrue(Files.exists(m.pluginsDir.resolve("A-Mod/A.dll")))
    }
}
