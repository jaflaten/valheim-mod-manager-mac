package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.BepInExService
import dev.jaflaten.vmm.core.GameLocator
import dev.jaflaten.vmm.core.Launcher
import dev.jaflaten.vmm.core.ModManager
import dev.jaflaten.vmm.core.Settings
import dev.jaflaten.vmm.core.ThunderstoreClient
import java.nio.file.Path

/** Shared services. Game-specific services are null until a Valheim folder is found. */
class AppContext {
    var settings: Settings = Settings.load()
        private set
    val client = ThunderstoreClient()
    var gameRoot: Path? = null
        private set
    var mods: ModManager? = null
        private set
    var bepinex: BepInExService? = null
        private set
    var launcher: Launcher? = null
        private set

    init {
        setGameRoot(GameLocator.detect(settings))
    }

    fun updateSettings(s: Settings) {
        settings = s
        s.save()
    }

    fun setGameRoot(root: Path?) {
        gameRoot = root
        mods = root?.let { ModManager(it, client) }
        bepinex = root?.let { BepInExService(it, client, mods!!) }
        launcher = root?.let { Launcher(it, bepinex!!) }
    }
}
