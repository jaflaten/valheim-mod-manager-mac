package dev.jaflaten.vmm.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

object FileOps {
    private const val MAX_EXTRACTED_BYTES = 2L shl 30

    /** Throws if [path] (or any existing ancestor between [root] and it) is a symlink or lies outside [root]. */
    fun requireInside(root: Path, path: Path) {
        val r = root.toAbsolutePath().normalize()
        val p = path.toAbsolutePath().normalize()
        if (!p.startsWith(r)) throw VmmException("Path escapes ${r}: $p")
        var cur = p
        while (cur != r && cur.startsWith(r)) {
            if (Files.isSymbolicLink(cur)) throw VmmException("Refusing to operate through symlink: $cur")
            cur = cur.parent ?: break
        }
    }

    /** Extracts [zip] into [dest] with zip-slip and size protection. */
    fun extractZip(zip: Path, dest: Path) {
        Files.createDirectories(dest)
        val root = dest.toAbsolutePath().normalize()
        var total = 0L
        ZipFile(zip.toFile()).use { zf ->
            for (entry in zf.entries()) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) throw VmmException("Unsafe zip entry: ${entry.name}")
                val out = root.resolve(name).normalize()
                if (!out.startsWith(root)) throw VmmException("Unsafe zip entry: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                    continue
                }
                Files.createDirectories(out.parent)
                zf.getInputStream(entry).use { input ->
                    Files.newOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_EXTRACTED_BYTES) throw VmmException("Archive expands to more than 2 GB")
                            output.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    /** Copies the tree [src] into [dst], merging directories. When [overwrite] is false existing files are kept. Returns files written. */
    fun copyTree(src: Path, dst: Path, overwrite: Boolean = true): List<Path> {
        val written = ArrayList<Path>()
        Files.walk(src).use { stream ->
            for (p in stream.toList()) {
                val rel = src.relativize(p)
                val target = dst.resolve(rel.toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    if (!overwrite && Files.exists(target)) continue
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                    written.add(target)
                }
            }
        }
        return written
    }

    /** Moves a file or directory; falls back to copy+delete across file systems. */
    fun move(src: Path, dst: Path) {
        Files.createDirectories(dst.parent)
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (Files.isDirectory(src)) {
                copyTree(src, dst)
                deleteRecursively(src)
            } else {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun removeQuarantine(path: Path) {
        if (!Files.exists(path)) return
        runCatching {
            ProcessBuilder("/usr/bin/xattr", "-dr", "com.apple.quarantine", path.toString())
                .redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor() }
        }
    }

    fun makeExecutable(path: Path) {
        runCatching { path.toFile().setExecutable(true, false) }
    }

    /** Reads Mach-O headers and returns the contained architectures, e.g. ["x86_64"]. */
    fun machOArchitectures(file: Path): Set<String> {
        val bytes = Files.newInputStream(file).use { it.readNBytes(4096) }
        if (bytes.size < 8) return emptySet()
        fun cpu(type: Int) = when (type) {
            0x01000007 -> "x86_64"
            0x0100000C -> "arm64"
            7 -> "i386"
            else -> "cpu$type"
        }
        val be = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (be.getInt(0)) {
            0xCAFEBABE.toInt() -> {
                val n = be.getInt(4)
                (0 until minOf(n, 16)).map { cpu(be.getInt(8 + it * 20)) }.toSet()
            }
            0xCFFAEDFE.toInt(), 0xCEFAEDFE.toInt() -> setOf(cpu(le.getInt(4)))
            0xFEEDFACF.toInt(), 0xFEEDFACE.toInt() -> setOf(cpu(be.getInt(4)))
            else -> emptySet()
        }
    }
}
