package dev.jaflaten.vmm

import com.formdev.flatlaf.themes.FlatMacDarkLaf
import dev.jaflaten.vmm.ui.MainFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    System.setProperty("apple.awt.application.name", "Valheim Mod Manager")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    FlatMacDarkLaf.setup()
    UIManager.put("Table.showHorizontalLines", true)
    UIManager.put("Component.arc", 8)
    SwingUtilities.invokeLater { MainFrame().isVisible = true }
}
