package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class InstalledMod(
    val fullName: String,
    val version: String,
    val enabled: Boolean = true,
    val dependencies: List<String> = emptyList(),
    val hasPlugins: Boolean = false,
    val hasPatchers: Boolean = false,
    /** Config files (relative to BepInEx/config) that this mod created; never pre-existing user files. */
    val createdConfigs: List<String> = emptyList(),
    val installedAt: String = Instant.now().toString(),
)

data class ModState(
    val mods: Map<String, InstalledMod> = emptyMap(),
    val bepinexVersion: String? = null,
)

/** Row shown in the "Installed" table. Unmanaged entries are files/folders the user dropped in manually. */
data class ModEntry(
    val name: String,
    val version: String?,
    val enabled: Boolean,
    val managed: Boolean,
    val latestVersion: String?,
    val path: Path,
) {
    val updateAvailable: Boolean get() = managed && version != null && latestVersion != null && Versions.isNewer(latestVersion, version)
}

class ModManager(val gameRoot: Path, private val client: ThunderstoreClient) {
    val bepinexDir: Path = gameRoot.resolve("BepInEx")
    val pluginsDir: Path = bepinexDir.resolve("plugins")
    val pluginsDisabledDir: Path = bepinexDir.resolve("plugins_disabled")
    val patchersDir: Path = bepinexDir.resolve("patchers")
    val patchersDisabledDir: Path = bepinexDir.resolve("patchers_disabled")
    val configDir: Path = bepinexDir.resolve("config")
    private val stateFile: Path = bepinexDir.resolve("vmm-state.json")
    private val downloadsDir: Path get() = AppPaths.cacheDir.resolve("downloads")

    @Synchronized
    fun loadState(): ModState = try {
        if (Files.exists(stateFile)) Json.mapper.readValue(stateFile.toFile(), ModState::class.java) else ModState()
    } catch (e: Exception) {
        throw VmmException("Could not read $stateFile: ${e.message}", e)
    }

    @Synchronized
    fun saveState(state: ModState) = Json.writeAtomic(stateFile, state)

    @Synchronized
    fun updateState(f: (ModState) -> ModState) = saveState(f(loadState()))

    fun list(): List<ModEntry> {
        val state = loadState()
        val result = ArrayList<ModEntry>()
        for (m in state.mods.values) {
            val dir = if (m.enabled) pluginsDir else pluginsDisabledDir
            result += ModEntry(m.fullName, m.version, m.enabled, true, client.find(m.fullName)?.latestVersion, dir.resolve(m.fullName))
        }
        val managedNames = state.mods.keys
        for ((dir, enabled) in listOf(pluginsDir to true, pluginsDisabledDir to false)) {
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { s ->
                s.filter { !it.fileName.toString().startsWith(".") && it.fileName.toString() !in managedNames }
                    .sorted().forEach { result += ModEntry(it.fileName.toString(), null, enabled, false, null, it) }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun ownedPaths(m: InstalledMod, enabled: Boolean): List<Path> = buildList {
        if (m.hasPlugins) add((if (enabled) pluginsDir else pluginsDisabledDir).resolve(m.fullName))
        if (m.hasPatchers) add((if (enabled) patchersDir else patchersDisabledDir).resolve(m.fullName))
    }

    /** Moves all [moves] or none: rolls back completed moves on failure. */
    private fun moveAll(moves: List<Pair<Path, Path>>) {
        for ((src, dst) in moves) {
            FileOps.requireInside(bepinexDir, src)
            FileOps.requireInside(bepinexDir, dst)
            if (Files.exists(dst)) throw VmmException("Cannot move: target already exists: $dst")
        }
        val done = ArrayList<Pair<Path, Path>>()
        try {
            for ((src, dst) in moves) {
                FileOps.move(src, dst)
                done += src to dst
            }
        } catch (e: Exception) {
            done.asReversed().forEach { (src, dst) -> runCatching { FileOps.move(dst, src) } }
            throw VmmException("Failed to move mod files: ${e.message}", e)
        }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        GameProcess.ensureNotRunning()
        val state = loadState()
        val m = state.mods[name]
        if (m == null) {
            val (from, to) = if (enabled) pluginsDisabledDir to pluginsDir else pluginsDir to pluginsDisabledDir
            val src = from.resolve(name)
            if (!Files.exists(src)) throw VmmException("Not found: $src")
            moveAll(listOf(src to to.resolve(name)))
            return
        }
        if (m.enabled == enabled) return
        val moves = ownedPaths(m, m.enabled).zip(ownedPaths(m, enabled)).filter { Files.exists(it.first) }
        moveAll(moves)
        saveState(state.copy(mods = state.mods + (name to m.copy(enabled = enabled))))
    }

    fun uninstall(name: String, removeConfigs: Boolean = false) {
        GameProcess.ensureNotRunning()
        val state = loadState()
        val m = state.mods[name]
        if (m == null) {
            for (dir in listOf(pluginsDir, pluginsDisabledDir)) {
                val p = dir.resolve(name)
                if (Files.exists(p)) {
                    FileOps.requireInside(bepinexDir, p)
                    FileOps.deleteRecursively(p)
                }
            }
            return
        }
        for (p in ownedPaths(m, true) + ownedPaths(m, false)) {
            if (Files.exists(p)) {
                FileOps.requireInside(bepinexDir, p)
                FileOps.deleteRecursively(p)
            }
        }
        if (removeConfigs) {
            for (rel in m.createdConfigs) {
                val p = configDir.resolve(rel)
                FileOps.requireInside(configDir, p)
                Files.deleteIfExists(p)
            }
        }
        saveState(state.copy(mods = state.mods - name))
    }

    fun installedVersions(): Map<String, String> = loadState().mods.mapValues { it.value.version }

    fun plan(targets: List<String>): ResolutionPlan =
        DependencyResolver.resolve(targets, client::find, installedVersions())

    /** Installs every package of [plan] in order. [progress] receives (message, fraction 0..1). Returns warnings. */
    fun install(plan: ResolutionPlan, progress: (String, Double) -> Unit = { _, _ -> }): List<String> {
        GameProcess.ensureNotRunning()
        if (!Files.isDirectory(bepinexDir)) throw VmmException("Install BepInEx first (Setup tab).")
        val warnings = ArrayList<String>()
        plan.toInstall.forEachIndexed { i, pkg ->
            val base = i.toDouble() / plan.toInstall.size
            val step = 1.0 / plan.toInstall.size
            progress("Downloading ${pkg.fullName} ${pkg.latestVersion}", base)
            val zip = downloadsDir.resolve("${pkg.fullName}-${pkg.latestVersion}.zip")
            if (!Files.exists(zip)) {
                val tmp = downloadsDir.resolve("${pkg.fullName}-${pkg.latestVersion}.part")
                client.download(pkg.downloadUrl(), tmp) { f -> progress("Downloading ${pkg.fullName} ${pkg.latestVersion}", base + step * 0.8 * f) }
                Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            progress("Installing ${pkg.fullName}", base + step * 0.9)
            warnings += installZip(zip, pkg.fullName, pkg.latestVersion, pkg.dependencies).map { "${pkg.fullName}: $it" }
        }
        progress("Done", 1.0)
        return warnings
    }

    /** Installs or replaces one package from a local zip. Old owned files are removed, so updates leave no stale DLLs. */
    fun installZip(zip: Path, fullName: String, version: String, dependencies: List<String>): List<String> {
        require(Regex("[A-Za-z0-9_.]+-[A-Za-z0-9_.]+").matches(fullName)) { "Invalid package name: $fullName" }
        FileOps.requireInside(gameRoot, bepinexDir)
        val staging = bepinexDir.resolve(".vmm-staging").resolve(UUID.randomUUID().toString())
        try {
            val extracted = staging.resolve("extracted")
            FileOps.extractZip(zip, extracted)
            val staged = PackageLayout.stage(extracted, staging.resolve("out"))
            if (staged.plugins == null && staged.patchers == null && staged.config == null) {
                throw VmmException("$fullName contains no installable files")
            }

            val state = loadState()
            val previous = state.mods[fullName]
            val enabled = previous?.enabled ?: true
            val next = InstalledMod(
                fullName = fullName, version = version, enabled = enabled, dependencies = dependencies,
                hasPlugins = staged.plugins != null, hasPatchers = staged.patchers != null,
                createdConfigs = previous?.createdConfigs.orEmpty(),
            )

            // Back up whatever currently occupies the package's slots (managed or a same-named manual folder).
            val slots = listOf(pluginsDir, pluginsDisabledDir, patchersDir, patchersDisabledDir).map { it.resolve(fullName) }
            val backup = staging.resolve("backup")
            val backedUp = ArrayList<Pair<Path, Path>>()
            val placed = ArrayList<Path>()
            try {
                slots.filter { Files.exists(it) }.forEachIndexed { i, p ->
                    FileOps.requireInside(bepinexDir, p)
                    val b = backup.resolve("$i")
                    FileOps.move(p, b)
                    backedUp += p to b
                }
                staged.plugins?.let { val t = ownedPaths(next, enabled).first(); FileOps.move(it, t); placed.add(t) }
                staged.patchers?.let { val t = ownedPaths(next, enabled).last(); FileOps.move(it, t); placed.add(t) }
            } catch (e: Exception) {
                placed.forEach { runCatching { FileOps.deleteRecursively(it) } }
                backedUp.forEach { (orig, b) -> runCatching { FileOps.move(b, orig) } }
                throw VmmException("Failed to install $fullName: ${e.message}", e)
            }

            val created = staged.config?.let { src ->
                FileOps.copyTree(src, configDir, overwrite = false).map { configDir.relativize(it).toString() }
            }.orEmpty()
            saveState(state.copy(mods = state.mods + (fullName to next.copy(createdConfigs = (next.createdConfigs + created).distinct()))))
            return staged.warnings
        } finally {
            runCatching { FileOps.deleteRecursively(staging) }
            runCatching {
                val parent = bepinexDir.resolve(".vmm-staging")
                Files.list(parent).use { s -> if (s.findAny().isEmpty) Files.delete(parent) }
            }
        }
    }
}
