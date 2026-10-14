package dev.jaflaten.vmm.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compact package model; we only keep what the UI and installer need (the raw listing is ~150 MB of JSON). */
data class ModPackage(
    val fullName: String,
    val owner: String,
    val name: String,
    val packageUrl: String,
    val dateUpdated: String,
    val rating: Int,
    val pinned: Boolean,
    val deprecated: Boolean,
    val nsfw: Boolean,
    val categories: List<String>,
    val latestVersion: String,
    val description: String,
    val dependencies: List<String>,
    val totalDownloads: Long,
    val fileSize: Long,
    val websiteUrl: String?,
    val versions: List<String>,
) {
    fun downloadUrl(version: String = latestVersion) =
        "https://thunderstore.io/package/download/$owner/$name/$version/"
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawVersion(
    @JsonProperty("version_number") val versionNumber: String = "",
    val description: String? = null,
    val dependencies: List<String> = emptyList(),
    val downloads: Long = 0,
    @JsonProperty("file_size") val fileSize: Long = 0,
    @JsonProperty("website_url") val websiteUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RawPackage(
    val name: String = "",
    @JsonProperty("full_name") val fullName: String = "",
    val owner: String = "",
    @JsonProperty("package_url") val packageUrl: String = "",
    @JsonProperty("date_updated") val dateUpdated: String = "",
    @JsonProperty("rating_score") val ratingScore: Int = 0,
    @JsonProperty("is_pinned") val isPinned: Boolean = false,
    @JsonProperty("is_deprecated") val isDeprecated: Boolean = false,
    @JsonProperty("has_nsfw_content") val hasNsfw: Boolean = false,
    val categories: List<String> = emptyList(),
    val versions: List<RawVersion> = emptyList(),
) {
    fun toModel(): ModPackage? {
        val latest = versions.maxWithOrNull { a, b -> Versions.compare(a.versionNumber, b.versionNumber) } ?: return null
        return ModPackage(
            fullName = fullName, owner = owner, name = name, packageUrl = packageUrl, dateUpdated = dateUpdated,
            rating = ratingScore, pinned = isPinned, deprecated = isDeprecated, nsfw = hasNsfw, categories = categories,
            latestVersion = latest.versionNumber, description = latest.description.orEmpty(),
            dependencies = latest.dependencies, totalDownloads = versions.sumOf { it.downloads },
            fileSize = latest.fileSize, websiteUrl = latest.websiteUrl,
            versions = versions.map { it.versionNumber }.sortedWith { a, b -> Versions.compare(b, a) },
        )
    }
}

class ThunderstoreClient(
    private val cacheFile: Path = AppPaths.cacheDir.resolve("packages.json.gz"),
    private val cacheMaxAge: Duration = Duration.ofMinutes(30),
) {
    companion object {
        const val INDEX_URL = "https://thunderstore.io/c/valheim/api/v1/package-listing-index/"
        const val USER_AGENT = "ValheimModManagerSwing/1.0 (personal macOS mod manager)"
        const val MAX_DOWNLOAD_BYTES = 1L shl 30

        val http: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        fun request(url: String): HttpRequest {
            val uri = URI.create(url)
            if (uri.scheme != "https") throw VmmException("Refusing non-HTTPS URL: $url")
            return HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET().build()
        }

        fun parsePackages(input: InputStream): List<ModPackage> {
            val result = ArrayList<ModPackage>()
            Json.mapper.factory.createParser(input).use { p ->
                if (p.nextToken() != JsonToken.START_ARRAY) throw VmmException("Unexpected Thunderstore payload")
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    val raw = Json.mapper.readValue(p, RawPackage::class.java)
                    raw.toModel()?.let(result::add)
                }
            }
            return result
        }

        private fun maybeGunzip(bytes: ByteArray): InputStream {
            val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            return if (gz) GZIPInputStream(ByteArrayInputStream(bytes)) else ByteArrayInputStream(bytes)
        }
    }

    @Volatile
    var packages: List<ModPackage> = emptyList()
        private set
    private var byName: Map<String, ModPackage> = emptyMap()

    fun find(fullName: String): ModPackage? = byName[fullName]

    /** Loads from cache when fresh, otherwise from the network. Falls back to stale cache if offline. */
    fun load(forceRefresh: Boolean = false): List<ModPackage> {
        val cacheFresh = Files.exists(cacheFile) &&
            Files.getLastModifiedTime(cacheFile).toInstant().isAfter(Instant.now().minus(cacheMaxAge))
        val list = if (!forceRefresh && cacheFresh) {
            readCache() ?: fetchAndCache()
        } else {
            try {
                fetchAndCache()
            } catch (e: Exception) {
                readCache() ?: throw VmmException("Could not download Thunderstore package list: ${e.message}", e)
            }
        }
        setPackages(list)
        return list
    }

    internal fun setPackages(list: List<ModPackage>) {
        packages = list
        byName = list.associateBy { it.fullName }
    }

    private fun readCache(): List<ModPackage>? = try {
        GZIPInputStream(Files.newInputStream(cacheFile)).use { Json.mapper.readValue<List<ModPackage>>(it) }
    } catch (_: Exception) {
        null
    }

    private fun fetchAndCache(): List<ModPackage> {
        val list = fetchAll()
        Files.createDirectories(cacheFile.parent)
        val tmp = Files.createTempFile(cacheFile.parent, "packages", ".tmp")
        GZIPOutputStream(Files.newOutputStream(tmp)).use { Json.mapper.writer().writeValue(it, list) }
        Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return list
    }

    private fun getBytes(url: String): ByteArray {
        val resp = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) throw VmmException("HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    private fun fetchAll(): List<ModPackage> {
        val chunkUrls: List<String> = maybeGunzip(getBytes(INDEX_URL)).use { Json.mapper.readValue(it) }
        if (chunkUrls.isEmpty() || chunkUrls.size > 256) throw VmmException("Unexpected Thunderstore index (${chunkUrls.size} chunks)")
        val futures = chunkUrls.map { url ->
            CompletableFuture.supplyAsync { maybeGunzip(getBytes(url)).use { parsePackages(it) } }
        }
        return futures.flatMap { it.join() }.distinctBy { it.fullName }
    }

    /** Downloads a zip to [target], reporting progress 0..1. Validates status, size and ZIP signature. */
    fun download(url: String, target: Path, progress: (Double) -> Unit = {}) {
        val resp = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() != 200) {
            resp.body().close()
            throw VmmException("Download failed: HTTP ${resp.statusCode()} for $url")
        }
        val total = resp.headers().firstValueAsLong("Content-Length").orElse(-1)
        Files.createDirectories(target.parent)
        resp.body().use { input ->
            Files.newOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (read > MAX_DOWNLOAD_BYTES) throw VmmException("Download too large: $url")
                    if (total > 0) progress(read.toDouble() / total)
                }
            }
        }
        Files.newInputStream(target).use {
            val sig = it.readNBytes(4)
            if (sig.size < 4 || sig[0] != 'P'.code.toByte() || sig[1] != 'K'.code.toByte()) {
                Files.deleteIfExists(target)
                throw VmmException("Downloaded file is not a ZIP archive: $url")
            }
        }
    }
}
