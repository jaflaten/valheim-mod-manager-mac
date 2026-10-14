package dev.jaflaten.vmm.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object AppPaths {
    val dataDir: Path by lazy {
        val override = System.getProperty("vmm.dataDir")
        val dir = if (override != null) Path.of(override)
        else Path.of(System.getProperty("user.home"), "Library", "Application Support", "ValheimModManagerSwing")
        Files.createDirectories(dir)
        dir
    }
    val cacheDir: Path get() = dataDir.resolve("cache").also { Files.createDirectories(it) }
    val logsDir: Path get() = dataDir.resolve("logs").also { Files.createDirectories(it) }
    val settingsFile: Path get() = dataDir.resolve("settings.json")
}

object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT)

    /** Writes via temp file + atomic move so a crash never leaves a half-written file. */
    fun writeAtomic(target: Path, value: Any) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        try {
            mapper.writeValue(tmp.toFile(), value)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

data class Settings(
    val gameRootOverride: String? = null,
    val launchViaTerminal: Boolean = false,
    val showDeprecated: Boolean = false,
) {
    fun save() = Json.writeAtomic(AppPaths.settingsFile, this)

    companion object {
        fun load(): Settings = try {
            if (Files.exists(AppPaths.settingsFile)) Json.mapper.readValue(AppPaths.settingsFile.toFile(), Settings::class.java)
            else Settings()
        } catch (_: Exception) {
            Settings()
        }
    }
}

class VmmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
