package dev.jaflaten.vmm.core

data class ResolutionPlan(
    /** Packages to install, dependencies before dependents. */
    val toInstall: List<ModPackage>,
    /** Dependency names not found on Thunderstore. */
    val missing: List<String>,
)

object DependencyResolver {
    const val BEPINEX_PACK = "denikson-BepInExPack_Valheim"

    /**
     * Resolves [targets] (always installed at their latest version) and their transitive dependencies.
     * Installed dependencies are kept unless they are older than what something requires, in which case
     * they are upgraded to latest. Never downgrades. BepInExPack is handled by [BepInExService].
     */
    fun resolve(
        targets: List<String>,
        lookup: (String) -> ModPackage?,
        installed: Map<String, String>,
    ): ResolutionPlan {
        val order = ArrayList<ModPackage>()
        val missing = LinkedHashSet<String>()
        val state = HashMap<String, Boolean>() // false = visiting, true = done
        val required = HashMap<String, String>()

        // Collect the highest required version for each dependency first, so the result is order-independent.
        fun collect(name: String, seen: MutableSet<String>) {
            if (!seen.add(name)) return
            val pkg = lookup(name) ?: return
            for (d in pkg.dependencies.mapNotNull(DependencyRef::parse)) {
                if (d.fullName == BEPINEX_PACK) continue
                val prev = required[d.fullName]
                if (prev == null || Versions.isNewer(d.version, prev)) required[d.fullName] = d.version
                collect(d.fullName, seen)
            }
        }
        val seen = HashSet<String>()
        targets.forEach { collect(it, seen) }

        fun needsInstall(name: String, isTarget: Boolean, pkg: ModPackage): Boolean {
            val have = installed[name] ?: return true
            if (isTarget) return Versions.isNewer(pkg.latestVersion, have)
            val need = required[name] ?: return false
            return Versions.isNewer(need, have) && Versions.isNewer(pkg.latestVersion, have)
        }

        fun visit(name: String, isTarget: Boolean) {
            if (name == BEPINEX_PACK) return
            when (state[name]) {
                true, false -> return // done, or cycle
                null -> {}
            }
            val pkg = lookup(name)
            if (pkg == null) {
                missing += name
                state[name] = true
                return
            }
            state[name] = false
            for (d in pkg.dependencies.mapNotNull(DependencyRef::parse)) visit(d.fullName, false)
            state[name] = true
            if (needsInstall(name, isTarget, pkg)) order += pkg
        }

        targets.forEach { visit(it, true) }
        return ResolutionPlan(order, missing.toList())
    }

    /** Names of installed+enabled mods that (directly) depend on [fullName]. */
    fun dependents(fullName: String, mods: Collection<InstalledMod>): List<String> =
        mods.filter { m -> m.enabled && m.dependencies.any { DependencyRef.parse(it)?.fullName == fullName } }
            .map { it.fullName }
}
