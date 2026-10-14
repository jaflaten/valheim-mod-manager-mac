package dev.jaflaten.vmm.core

object Versions {
    /** Compares dotted numeric versions ("1.10.0" > "1.9.3"). Non-numeric parts compare as 0. */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}

/** A Thunderstore dependency string such as "denikson-BepInExPack_Valheim-5.4.2351". */
data class DependencyRef(val fullName: String, val version: String) {
    companion object {
        fun parse(s: String): DependencyRef? {
            val idx = s.lastIndexOf('-')
            if (idx <= 0 || idx == s.length - 1) return null
            val fullName = s.substring(0, idx)
            if (!fullName.contains('-')) return null
            return DependencyRef(fullName, s.substring(idx + 1))
        }
    }
}
