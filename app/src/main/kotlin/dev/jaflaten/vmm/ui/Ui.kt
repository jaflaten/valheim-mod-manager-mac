package dev.jaflaten.vmm.ui

import java.awt.Component
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea

object Ui {
    fun open(path: Path) {
        runCatching { ProcessBuilder("/usr/bin/open", path.toString()).start() }
    }

    fun openUrl(url: String) {
        if (url.startsWith("https://")) runCatching { ProcessBuilder("/usr/bin/open", url).start() }
    }

    fun error(parent: Component?, message: String) {
        val area = JTextArea(message).apply { lineWrap = true; wrapStyleWord = true; isEditable = false; columns = 60; rows = minOf(12, message.lines().size + 2) }
        JOptionPane.showMessageDialog(parent, JScrollPane(area), "Error", JOptionPane.ERROR_MESSAGE)
    }

    fun info(parent: Component?, message: String, title: String = "Valheim Mod Manager") =
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE)

    fun confirm(parent: Component?, message: String, title: String = "Confirm"): Boolean =
        JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION

    fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }
}
