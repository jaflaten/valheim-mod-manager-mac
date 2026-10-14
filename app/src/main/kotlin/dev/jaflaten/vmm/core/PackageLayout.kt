package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path

/** Result of normalising an extracted Thunderstore zip into BepInEx folders. Any of the dirs may be null. */
data class StagedPackage(val plugins: Path?, val patchers: Path?, val config: Path?, val warnings: List<String>)

/**
 * Maps the many Thunderstore zip layouts onto BepInEx folders, similar to r2modman:
 * - top-level metadata (manifest.json, icon.png, README, CHANGELOG, LICENSE) is ignored
 * - single wrapper directories and a leading "BepInEx/" are stripped
 * - plugins/, patchers/, config/ are mapped to their BepInEx counterparts
 * - everything else (root DLLs, asset folders) goes into the mod's plugins folder
 */
object PackageLayout {
    private val metadata = setOf("manifest.json", "icon.png", "readme.md", "changelog.md", "license", "license.md", "license.txt")
    private val known = setOf("plugins", "patchers", "config", "bepinex", "core", "monomod")

    fun stage(extracted: Path, out: Path): StagedPackage {
        val warnings = ArrayList<String>()
        val plugins = out.resolve("plugins")
        val patchers = out.resolve("patchers")
        val config = out.resolve("config")

        var root = extracted
        for (depth in 0 until 3) {
            val single = children(root, topLevel = root == extracted).singleOrNull() ?: break
            val isWrapper = Files.isDirectory(single) && single.fileName.toString().lowercase() !in known &&
                children(single, topLevel = false).any { Files.isDirectory(it) && it.fileName.toString().lowercase() in known }
            if (!isWrapper) break
            root = single
        }

        fun place(entry: Path, insideBepInEx: Boolean) {
            val name = entry.fileName.toString()
            val dir = Files.isDirectory(entry)
            when {
                dir && name.equals("bepinex", true) && !insideBepInEx ->
                    children(entry, topLevel = false).forEach { place(it, true) }
                dir && name.equals("plugins", true) -> FileOps.copyTree(entry, plugins)
                dir && name.equals("patchers", true) -> FileOps.copyTree(entry, patchers)
                dir && name.equals("config", true) -> FileOps.copyTree(entry, config)
                dir && name.equals("core", true) -> warnings += "Ignored '$name/': mods may not replace BepInEx core files"
                dir && name.equals("monomod", true) -> warnings += "Ignored '$name/': MonoMod patches are not supported"
                dir -> FileOps.copyTree(entry, plugins.resolve(name))
                else -> {
                    Files.createDirectories(plugins)
                    Files.copy(entry, plugins.resolve(name))
                }
            }
        }
        children(root, topLevel = root == extracted).forEach { place(it, false) }

        fun nonEmpty(p: Path) = p.takeIf { Files.isDirectory(it) && Files.walk(it).use { s -> s.anyMatch(Files::isRegularFile) } }
        return StagedPackage(nonEmpty(plugins), nonEmpty(patchers), nonEmpty(config), warnings)
    }

    private fun children(dir: Path, topLevel: Boolean): List<Path> = Files.list(dir).use { s ->
        s.filter { p ->
            val n = p.fileName.toString()
            !n.startsWith(".") && n != "__MACOSX" && !(topLevel && n.lowercase() in metadata)
        }.sorted().toList()
    }
}
