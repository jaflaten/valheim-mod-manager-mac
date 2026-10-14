package dev.jaflaten.vmm.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Simple editor for the .cfg files in BepInEx/config. Optional [hint] pre-filters by mod name. */
class ConfigEditorDialog(owner: JFrame, private val configDir: Path, hint: String?) : JDialog(owner, "BepInEx configs", true) {
    private val all: List<Path> = if (Files.isDirectory(configDir)) Files.walk(configDir).use { s ->
        s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".cfg") }.sorted().toList()
    } else emptyList()
    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val filter = JTextField(18).apply { putClientProperty("JTextField.placeholderText", "Filter…") }
    private val editor = JTextArea().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 12); tabSize = 4 }
    private val save = JButton("Save").apply { isEnabled = false }
    private var current: Path? = null
    private var dirty = false

    init {
        val left = JPanel(BorderLayout(0, 4)).apply {
            add(filter, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(300, 500)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, JScrollPane(editor))
        contentPane.add(split, BorderLayout.CENTER)
        contentPane.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            add(JButton("Open folder").apply { addActionListener { Ui.open(configDir) } })
            add(save)
            add(JButton("Close").apply { addActionListener { if (confirmDiscard()) dispose() } })
        }, BorderLayout.SOUTH)

        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refill()
            override fun removeUpdate(e: DocumentEvent) = refill()
            override fun changedUpdate(e: DocumentEvent) = refill()
        })
        list.addListSelectionListener { if (!it.valueIsAdjusting) load(list.selectedValue) }
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = markDirty()
            override fun removeUpdate(e: DocumentEvent) = markDirty()
            override fun changedUpdate(e: DocumentEvent) = markDirty()
        })
        save.addActionListener { saveCurrent() }

        // Thunderstore names are Owner-Mod; config files are usually named after the plugin GUID, which often contains the mod name.
        hint?.substringAfter('-')?.takeIf { n -> all.any { it.fileName.toString().contains(n, true) } }?.let { filter.text = it }
        refill()
        size = Dimension(1000, 650)
        setLocationRelativeTo(owner)
    }

    private fun refill() {
        val q = filter.text.trim().lowercase()
        listModel.clear()
        all.map { configDir.relativize(it).toString() }.filter { q.isEmpty() || it.lowercase().contains(q) }.forEach(listModel::addElement)
    }

    private fun markDirty() {
        if (current != null) { dirty = true; save.isEnabled = true }
    }

    private fun confirmDiscard() = !dirty || Ui.confirm(this, "Discard unsaved changes to ${current?.fileName}?")

    private fun load(rel: String?) {
        if (rel == null) return
        val p = configDir.resolve(rel)
        if (p == current) return
        if (!confirmDiscard()) return
        current = null
        editor.text = runCatching { Files.readString(p) }.getOrElse { "Could not read: ${it.message}" }
        editor.caretPosition = 0
        current = p
        dirty = false
        save.isEnabled = false
    }

    private fun saveCurrent() {
        val p = current ?: return
        try {
            Files.writeString(p, editor.text)
            dirty = false
            save.isEnabled = false
        } catch (e: Exception) {
            Ui.error(this, "Could not save $p: ${e.message}")
        }
    }
}
