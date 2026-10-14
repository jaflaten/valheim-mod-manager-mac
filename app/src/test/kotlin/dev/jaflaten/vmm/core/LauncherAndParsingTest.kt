package dev.jaflaten.vmm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LauncherAndParsingTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `modded command passes DYLD vars as env arguments under Rosetta`() {
        val root = TestUtil.fakeGameRoot(Files.createDirectories(tmp.resolve("Alice's Games/Valheim")))
        val client = ThunderstoreClient(tmp.resolve("c.gz"))
        val mods = ModManager(root, client)
        val bep = BepInExService(root, client, mods)
        val doorstop = root.resolve("doorstop_libs/libdoorstop_x64.dylib")
        val cmd = Launcher(root, bep).moddedCommand(doorstop)

        assertEquals(listOf("/usr/bin/arch", "-x86_64", "/usr/bin/env"), cmd.take(3))
        assertTrue("SteamAppId=892970" in cmd)
        assertTrue("DOORSTOP_ENABLED=1" in cmd)
        assertTrue("DYLD_INSERT_LIBRARIES=$doorstop" in cmd)
        assertTrue("DOORSTOP_TARGET_ASSEMBLY=${root.resolve("BepInEx/core/BepInEx.Preloader.dll")}" in cmd)
        assertEquals(root.resolve("Valheim.app/Contents/MacOS/Valheim").toString(), cmd[cmd.size - 2])
        assertEquals("-console", cmd.last())

        val script = Launcher(root, bep).terminalScript(cmd, tmp.resolve("log.txt"))
        assertTrue(script.contains("Alice'\\''s Games"))
    }

    @Test
    fun `parses thunderstore listing and picks latest version`() {
        val json = """
            [{"name":"Server_devcommands","full_name":"JereKuusela-Server_devcommands","owner":"JereKuusela",
              "package_url":"https://thunderstore.io/c/valheim/p/JereKuusela/Server_devcommands/","date_updated":"2026-09-01",
              "rating_score":500,"is_pinned":false,"is_deprecated":false,"has_nsfw_content":false,"categories":["Tools"],
              "versions":[
                {"version_number":"1.115.0","description":"new","dependencies":["denikson-BepInExPack_Valheim-5.4.2351"],"downloads":10,"file_size":100},
                {"version_number":"1.9.0","description":"old","dependencies":[],"downloads":5,"file_size":50}
              ], "unknown_field": 1}]
        """.trimIndent()
        val list = ThunderstoreClient.parsePackages(json.byteInputStream())
        val p = list.single()
        assertEquals("1.115.0", p.latestVersion)
        assertEquals("new", p.description)
        assertEquals(15, p.totalDownloads)
        assertEquals("https://thunderstore.io/package/download/JereKuusela/Server_devcommands/1.115.0/", p.downloadUrl())
    }

    @Test
    fun `reads mach-o architectures`() {
        val f = tmp.resolve("lib.dylib")
        // 64-bit little-endian Mach-O header, cputype x86_64
        Files.write(f, byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(), 7, 0, 0, 1, 3, 0, 0, 0))
        assertEquals(setOf("x86_64"), FileOps.machOArchitectures(f))
    }

    @Test
    fun `parses steam library folders`() {
        val vdf = """
            "libraryfolders" { "0" { "path" "/Users/me/Library/Application Support/Steam" } "1" { "path" "/Volumes/Games/SteamLibrary" } }
        """.trimIndent()
        assertEquals(listOf(Path.of("/Users/me/Library/Application Support/Steam"), Path.of("/Volumes/Games/SteamLibrary")), GameLocator.parseLibraryFolders(vdf))
    }
}
