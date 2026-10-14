package dev.jaflaten.vmm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Starts Valheim with BepInEx on macOS.
 *
 * The doorstop library is x86_64-only, so the game runs under Rosetta. DYLD_* variables are passed as
 * arguments to /usr/bin/env (not via the parent environment), because SIP strips DYLD_* when launching
 * protected system binaries such as /usr/bin/arch. This mirrors the approach used by Macheim.
 */
class Launcher(private val gameRoot: Path, private val bepinex: BepInExService) {

    fun executable(): Path {
        val app = GameLocator.findAppBundle(gameRoot) ?: throw VmmException("Valheim.app not found in $gameRoot")
        return app.resolve("Contents/MacOS").resolve(GameLocator.bundleExecutable(app))
    }

    fun moddedCommand(doorstop: Path, extraArgs: List<String> = emptyList()): List<String> = listOf(
        "/usr/bin/arch", "-x86_64", "/usr/bin/env",
        "SteamAppId=${GameLocator.APP_ID}",
        "SteamGameId=${GameLocator.APP_ID}",
        "DOORSTOP_ENABLED=1",
        "DOORSTOP_TARGET_ASSEMBLY=${bepinex.preloader.toAbsolutePath()}",
        "DYLD_LIBRARY_PATH=${gameRoot.toAbsolutePath()}",
        "DYLD_INSERT_LIBRARIES=${doorstop.toAbsolutePath()}",
        executable().toAbsolutePath().toString(),
        "-console",
    ) + extraArgs

    /** Ensures Steam is running (Valheim needs it for SteamAppId init); starts it and waits otherwise. */
    fun ensureSteam(log: (String) -> Unit) {
        if (GameProcess.isSteamRunning()) return
        log("Starting Steam…")
        ProcessBuilder("/usr/bin/open", "-a", "Steam").start().waitFor()
        val deadline = System.currentTimeMillis() + 90_000
        while (!GameProcess.isSteamRunning()) {
            if (System.currentTimeMillis() > deadline) throw VmmException("Steam did not start. Start Steam and log in, then try again.")
            Thread.sleep(1000)
        }
        log("Waiting for Steam to finish starting…")
        Thread.sleep(15_000)
    }

    /** Launches modded Valheim fully detached from this JVM. Returns the file receiving game stdout/stderr. */
    fun launchModded(viaTerminal: Boolean, log: (String) -> Unit = {}): Path {
        val status = bepinex.status()
        if (!status.healthy) throw VmmException("BepInEx is not ready: ${status.problems.joinToString("; ")}")
        if (GameProcess.isValheimRunning()) throw VmmException("Valheim is already running.")
        if (!GameProcess.isRosettaAvailable()) {
            throw VmmException("Rosetta 2 is required. Install it with: softwareupdate --install-rosetta --agree-to-license")
        }
        ensureSteam(log)
        FileOps.removeQuarantine(status.doorstop!!)

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val gameLog = AppPaths.logsDir.resolve("game-$stamp.log")
        val cmd = moddedCommand(status.doorstop)
        log("Launching: ${cmd.joinToString(" ")}")

        if (viaTerminal) {
            val script = AppPaths.dataDir.resolve("launch-modded.command")
            Files.writeString(script, terminalScript(cmd, gameLog))
            FileOps.makeExecutable(script)
            ProcessBuilder("/usr/bin/open", "-a", "Terminal", script.toString()).start().waitFor()
        } else {
            // `set -m` gives the background job its own process group, so Ctrl-C/closing the manager won't kill the game.
            val pb = ProcessBuilder(listOf("/bin/sh", "-c", "set -m; nohup \"$@\" >\"\$VMM_LOG\" 2>&1 </dev/null &", "vmm-launch") + cmd)
                .directory(gameRoot.toFile())
                .redirectErrorStream(true)
            pb.environment()["VMM_LOG"] = gameLog.toString()
            val p = pb.start()
            p.inputStream.readAllBytes()
            if (p.waitFor() != 0) throw VmmException("Failed to start Valheim (exit ${p.exitValue()})")
        }
        return gameLog
    }

    fun launchVanilla() {
        ProcessBuilder("/usr/bin/open", "steam://rungameid/${GameLocator.APP_ID}").start().waitFor()
    }

    internal fun terminalScript(cmd: List<String>, gameLog: Path): String = buildString {
        appendLine("#!/bin/bash")
        appendLine("cd ${quote(gameRoot.toString())}")
        appendLine(cmd.joinToString(" ") { quote(it) } + " 2>&1 | tee " + quote(gameLog.toString()))
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
