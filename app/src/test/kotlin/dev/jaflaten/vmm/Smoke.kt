package dev.jaflaten.vmm

import dev.jaflaten.vmm.core.BepInExService
import dev.jaflaten.vmm.core.GameLocator
import dev.jaflaten.vmm.core.GameProcess
import dev.jaflaten.vmm.core.Launcher
import dev.jaflaten.vmm.core.ModManager
import dev.jaflaten.vmm.core.Settings
import dev.jaflaten.vmm.core.ThunderstoreClient
import java.nio.file.Files

/** Manual end-to-end check against the real Valheim install: ./gradlew smoke --args="install [Owner-Name...]|launch" */
fun main(args: Array<String>) {
    val root = GameLocator.detect(Settings.load()) ?: error("Valheim not found")
    println("Game root: $root")
    val client = ThunderstoreClient()
    val mods = ModManager(root, client)
    val bep = BepInExService(root, client, mods)
    when (args.firstOrNull()) {
        "install" -> {
            val t = System.currentTimeMillis()
            val list = client.load()
            println("Thunderstore: ${list.size} packages in ${System.currentTimeMillis() - t} ms")
            println("BepInEx: " + bep.install { m, f -> println("  [%3.0f%%] %s".format(f * 100, m)) })
            val plan = mods.plan(args.drop(1).ifEmpty { listOf("JereKuusela-Server_devcommands") })
            println("Plan: ${plan.toInstall.map { "${it.fullName} ${it.latestVersion}" }} missing=${plan.missing}")
            println("Warnings: " + mods.install(plan) { m, _ -> println("  $m") })
            mods.list().forEach { println("  ${it.name} ${it.version} enabled=${it.enabled}") }
        }
        "launch" -> {
            val started = System.currentTimeMillis()
            val log = Launcher(root, bep).launchModded(viaTerminal = false) { println("  $it") }
            println("Game output → $log")
            repeat(24) {
                Thread.sleep(5000)
                val running = GameProcess.isValheimRunning()
                val fresh = Files.exists(bep.logFile) && Files.getLastModifiedTime(bep.logFile).toMillis() >= started
                val bepLog = if (fresh) Files.readString(bep.logFile) else ""
                println("t=${(it + 1) * 5}s running=$running chainloaderDone=${bepLog.contains("Chainloader startup complete")}")
                if (bepLog.contains("Chainloader startup complete")) {
                    bepLog.lines().filter { l -> "Loading [" in l || "Chainloader" in l }.forEach { l -> println("  $l") }
                    return
                }
                if (!running && it > 2) {
                    println("Game exited early. Output:\n" + Files.readString(log).takeLast(4000))
                    return
                }
            }
        }
        else -> println("usage: install | launch")
    }
}
