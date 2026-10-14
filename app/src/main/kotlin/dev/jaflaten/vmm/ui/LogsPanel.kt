package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.AppPaths
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class LogsPanel(private val frame: MainFrame) : JPanel(BorderLayout()) {
    var lastGameLog: Path? = null
    private val source = JComboBox(arrayOf("BepInEx log (LogOutput.log)", "Game output (last launch)"))
    private val text = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(source)
            add(JButton("Reload").apply { addActionListener { reload() } })
            add(JButton("Open in Console").apply { addActionListener { currentFile()?.let { f -> runCatching { ProcessBuilder("/usr/bin/open", "-a", "Console", f.toString()).start() } } } })
            add(JButton("Open logs folder").apply { addActionListener { Ui.open(AppPaths.logsDir) } })
        }, BorderLayout.NORTH)
        add(JScrollPane(text), BorderLayout.CENTER)
        source.addActionListener { reload() }
    }

    private fun latestGameLog(): Path? = lastGameLog ?: runCatching {
        Files.list(AppPaths.logsDir).use { s -> s.filter { it.fileName.toString().startsWith("game-") }.max(compareBy { it.fileName.toString() }).orElse(null) }
    }.getOrNull()

    private fun currentFile(): Path? = if (source.selectedIndex == 0) frame.ctx.bepinex?.logFile else latestGameLog()

    fun showGameLog() {
        source.selectedIndex = 1
        reload()
    }

    fun reload() {
        val f = currentFile()
        text.text = when {
            f == null -> "No log yet."
            !Files.exists(f) -> "No log yet ($f). Launch the game with “Play Modded” first."
            else -> tail(f, 512 * 1024)
        }
        text.caretPosition = text.document.length
    }

    private fun tail(f: Path, maxBytes: Long): String = RandomAccessFile(f.toFile(), "r").use { raf ->
        val start = maxOf(0, raf.length() - maxBytes)
        raf.seek(start)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.readFully(bytes)
        (if (start > 0) "… (truncated)\n" else "") + String(bytes, Charsets.UTF_8)
    }
}
