package dev.jaflaten.vmm.core

import java.util.concurrent.TimeUnit

object GameProcess {
    private fun pgrep(name: String): Boolean = try {
        val p = ProcessBuilder("/usr/bin/pgrep", "-x", name).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    fun isValheimRunning(): Boolean = pgrep("Valheim") || pgrep("valheim")

    fun isSteamRunning(): Boolean = pgrep("steam_osx")

    fun ensureNotRunning() {
        if (isValheimRunning()) throw VmmException("Quit Valheim before changing mods.")
    }

    fun isRosettaAvailable(): Boolean = try {
        val p = ProcessBuilder("/usr/bin/arch", "-x86_64", "/usr/bin/true").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}
