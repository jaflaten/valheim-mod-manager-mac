package dev.jaflaten.vmm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionsAndResolverTest {
    @Test
    fun `compares versions numerically`() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("5.4.2351", "5.4.2350"))
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
    }

    @Test
    fun `parses dependency strings`() {
        assertEquals(DependencyRef("denikson-BepInExPack_Valheim", "5.4.2351"), DependencyRef.parse("denikson-BepInExPack_Valheim-5.4.2351"))
        assertEquals(DependencyRef("ValheimModding-Jotunn", "2.30.0"), DependencyRef.parse("ValheimModding-Jotunn-2.30.0"))
        assertNull(DependencyRef.parse("garbage"))
    }

    private val bep = "denikson-BepInExPack_Valheim-5.4.2351"

    @Test
    fun `resolves dependencies first and skips BepInEx`() {
        val pkgs = listOf(
            TestUtil.pkg("A-Mod", "1.0.0", listOf(bep, "B-Lib-1.0.0", "C-Lib-1.0.0")),
            TestUtil.pkg("B-Lib", "1.2.0", listOf("C-Lib-1.0.0")),
            TestUtil.pkg("C-Lib", "2.0.0"),
        ).associateBy { it.fullName }
        val plan = DependencyResolver.resolve(listOf("A-Mod"), pkgs::get, emptyMap())
        assertEquals(listOf("C-Lib", "B-Lib", "A-Mod"), plan.toInstall.map { it.fullName })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `handles cycles and missing packages`() {
        val pkgs = listOf(
            TestUtil.pkg("A-Mod", "1.0.0", listOf("B-Mod-1.0.0", "X-Gone-1.0.0")),
            TestUtil.pkg("B-Mod", "1.0.0", listOf("A-Mod-1.0.0")),
        ).associateBy { it.fullName }
        val plan = DependencyResolver.resolve(listOf("A-Mod"), pkgs::get, emptyMap())
        assertEquals(setOf("A-Mod", "B-Mod"), plan.toInstall.map { it.fullName }.toSet())
        assertEquals(listOf("X-Gone"), plan.missing)
    }

    @Test
    fun `keeps satisfied installed dependencies and upgrades outdated ones`() {
        val pkgs = listOf(
            TestUtil.pkg("A-Mod", "1.0.0", listOf("B-Lib-1.5.0", "C-Lib-1.0.0")),
            TestUtil.pkg("B-Lib", "2.0.0"),
            TestUtil.pkg("C-Lib", "3.0.0"),
        ).associateBy { it.fullName }
        val plan = DependencyResolver.resolve(listOf("A-Mod"), pkgs::get, mapOf("B-Lib" to "1.0.0", "C-Lib" to "1.0.0"))
        assertEquals(listOf("B-Lib", "A-Mod"), plan.toInstall.map { it.fullName })
    }

    @Test
    fun `installed up-to-date target is not reinstalled`() {
        val pkgs = mapOf("A-Mod" to TestUtil.pkg("A-Mod", "1.0.0"))
        assertTrue(DependencyResolver.resolve(listOf("A-Mod"), pkgs::get, mapOf("A-Mod" to "1.0.0")).toInstall.isEmpty())
        assertEquals(1, DependencyResolver.resolve(listOf("A-Mod"), pkgs::get, mapOf("A-Mod" to "0.9.0")).toInstall.size)
    }
}
