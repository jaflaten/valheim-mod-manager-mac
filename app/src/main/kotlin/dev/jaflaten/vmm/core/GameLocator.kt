package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path

object GameLocator {
    const val APP_ID = "892970"

    private val steamRoot: Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Steam")

    fun isValidGameRoot(root: Path): Boolean = findAppBundle(root) != null

    /** Valheim ships as "Valheim.app" but older installs used "valheim.app". */
    fun findAppBundle(root: Path): Path? {
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { s ->
            s.filter { it.fileName.toString().equals("valheim.app", ignoreCase = true) && Files.isDirectory(it) }
                .findFirst().orElse(null)
        }
    }

    fun detect(settings: Settings): Path? {
        settings.gameRootOverride?.let { o ->
            val p = Path.of(o)
            if (isValidGameRoot(p)) return p
        }
        return candidateLibraries().map { it.resolve("steamapps/common/Valheim") }.firstOrNull(::isValidGameRoot)
    }

    fun candidateLibraries(): List<Path> {
        val libs = linkedSetOf(steamRoot)
        val vdf = steamRoot.resolve("steamapps/libraryfolders.vdf")
        if (Files.exists(vdf)) libs += parseLibraryFolders(Files.readString(vdf))
        return libs.toList()
    }

    fun parseLibraryFolders(vdf: String): List<Path> =
        Regex("\"path\"\\s+\"([^\"]+)\"").findAll(vdf).map { Path.of(it.groupValues[1].replace("\\\\", "\\")) }.toList()

    /** CFBundleExecutable from Info.plist (XML plist), defaulting to "Valheim". */
    fun bundleExecutable(appBundle: Path): String {
        val plist = appBundle.resolve("Contents/Info.plist")
        if (!Files.exists(plist)) return "Valheim"
        val text = Files.readString(plist)
        return Regex("<key>CFBundleExecutable</key>\\s*<string>([^<]+)</string>").find(text)?.groupValues?.get(1) ?: "Valheim"
    }
}
