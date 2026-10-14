package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestUtil {
    fun zip(target: Path, entries: Map<String, String>): Path {
        ZipOutputStream(Files.newOutputStream(target)).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return target
    }

    fun pkg(fullName: String, version: String = "1.0.0", deps: List<String> = emptyList()): ModPackage {
        val (owner, name) = fullName.split('-', limit = 2)
        return ModPackage(
            fullName, owner, name, "https://thunderstore.io/c/valheim/p/$owner/$name/", "2026-01-01", 0, false, false, false,
            emptyList(), version, "", deps, 0, 0, null, listOf(version),
        )
    }

    fun fakeGameRoot(dir: Path): Path {
        val app = dir.resolve("Valheim.app/Contents")
        Files.createDirectories(app.resolve("MacOS"))
        Files.writeString(
            app.resolve("Info.plist"),
            "<plist><dict><key>CFBundleExecutable</key>\n  <string>Valheim</string></dict></plist>",
        )
        Files.createDirectories(dir.resolve("BepInEx/core"))
        Files.createDirectories(dir.resolve("BepInEx/plugins"))
        Files.createDirectories(dir.resolve("BepInEx/config"))
        return dir
    }
}
