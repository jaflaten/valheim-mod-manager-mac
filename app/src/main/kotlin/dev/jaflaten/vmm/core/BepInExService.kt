package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class BepInExStatus(
    val installed: Boolean,
    val version: String?,
    val doorstop: Path?,
    val doorstopArchs: Set<String>,
    val problems: List<String>,
) {
    val healthy: Boolean get() = installed && problems.isEmpty()
}

class BepInExService(private val gameRoot: Path, private val client: ThunderstoreClient, private val mods: ModManager) {
    companion object {
        /** Version verified to work with the Rosetta/doorstop launch on macOS. */
        const val PINNED_VERSION = "5.4.2351"
        const val OWNER = "denikson"
        const val NAME = "BepInExPack_Valheim"
        fun downloadUrl(version: String) = "https://thunderstore.io/package/download/$OWNER/$NAME/$version/"
    }

    val bepinexDir: Path = gameRoot.resolve("BepInEx")
    val preloader: Path = bepinexDir.resolve("core/BepInEx.Preloader.dll")
    val logFile: Path = bepinexDir.resolve("LogOutput.log")

    fun findDoorstop(): Path? = listOf(
        gameRoot.resolve("doorstop_libs/libdoorstop_x64.dylib"),
        gameRoot.resolve("doorstop_libs/libdoorstop.dylib"),
        gameRoot.resolve("libdoorstop.dylib"),
        gameRoot.resolve("libdoorstop_x64.dylib"),
    ).firstOrNull { Files.isRegularFile(it) }

    fun status(): BepInExStatus {
        val problems = ArrayList<String>()
        val coreExists = Files.isDirectory(bepinexDir.resolve("core"))
        if (!coreExists) return BepInExStatus(false, null, null, emptySet(), listOf("BepInEx is not installed"))
        if (!Files.isRegularFile(preloader)) problems += "Missing BepInEx/core/BepInEx.Preloader.dll"
        val doorstop = findDoorstop()
        val archs = doorstop?.let { runCatching { FileOps.machOArchitectures(it) }.getOrDefault(emptySet()) }.orEmpty()
        if (doorstop == null) problems += "Missing doorstop library (doorstop_libs/libdoorstop_x64.dylib)"
        else if ("x86_64" !in archs) problems += "Doorstop library is not x86_64 (found: $archs)"
        val cfg = bepinexDir.resolve("config/BepInEx.cfg")
        if (Files.exists(cfg)) {
            val text = Files.readString(cfg)
            if (Regex("(?m)^\\s*Type\\s*=\\s*Application\\s*$").containsMatchIn(text)) {
                problems += "BepInEx.cfg entrypoint Type is 'Application' (should be 'GameObject')"
            }
        }
        val version = runCatching { mods.loadState().bepinexVersion }.getOrNull()
        return BepInExStatus(true, version, doorstop, archs, problems)
    }

    /** Installs or repairs BepInEx. Keeps user config, plugins and patchers; replaces core and doorstop files. */
    fun install(progress: (String, Double) -> Unit = { _, _ -> }): BepInExStatus {
        GameProcess.ensureNotRunning()
        FileOps.requireInside(gameRoot, bepinexDir)
        val version = PINNED_VERSION
        val zip = AppPaths.cacheDir.resolve("downloads/$OWNER-$NAME-$version.zip")
        if (!Files.exists(zip)) {
            progress("Downloading BepInEx $version", 0.0)
            val tmp = zip.resolveSibling("$OWNER-$NAME-$version.part")
            client.download(downloadUrl(version), tmp) { f -> progress("Downloading BepInEx $version", f * 0.7) }
            Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        progress("Installing BepInEx $version", 0.8)
        val tmp = Files.createTempDirectory("vmm-bepinex-" + UUID.randomUUID())
        try {
            FileOps.extractZip(zip, tmp)
            val src = tmp.resolve(NAME).takeIf { Files.isDirectory(it) } ?: tmp
            if (!Files.isRegularFile(src.resolve("BepInEx/core/BepInEx.Preloader.dll"))) {
                throw VmmException("Downloaded BepInEx pack has an unexpected layout")
            }
            Files.list(src).use { it.toList() }.forEach { entry ->
                val name = entry.fileName.toString()
                val target = gameRoot.resolve(name)
                FileOps.requireInside(gameRoot, target)
                when {
                    name == "BepInEx" -> Files.list(entry).use { it.toList() }.forEach { sub ->
                        val t = bepinexDir.resolve(sub.fileName.toString())
                        when (sub.fileName.toString()) {
                            "config", "plugins", "patchers" -> FileOps.copyTree(sub, t, overwrite = false)
                            else -> {
                                FileOps.deleteRecursively(t)
                                if (Files.isDirectory(sub)) FileOps.copyTree(sub, t) else Files.copy(sub, t)
                            }
                        }
                    }
                    Files.isDirectory(entry) -> {
                        FileOps.deleteRecursively(target)
                        FileOps.copyTree(entry, target)
                    }
                    else -> Files.copy(entry, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            runCatching { FileOps.deleteRecursively(tmp) }
        }
        Files.createDirectories(bepinexDir.resolve("plugins"))
        Files.list(gameRoot).use { s -> s.filter { it.fileName.toString().endsWith(".sh") }.forEach(FileOps::makeExecutable) }
        listOf(gameRoot.resolve("doorstop_libs"), bepinexDir.resolve("core")).forEach(FileOps::removeQuarantine)
        findDoorstop()?.let(FileOps::removeQuarantine)

        mods.updateState { it.copy(bepinexVersion = version) }
        val status = status()
        if (!status.healthy) throw VmmException("BepInEx installed but validation failed: ${status.problems.joinToString("; ")}")
        progress("BepInEx $version installed", 1.0)
        return status
    }
}
