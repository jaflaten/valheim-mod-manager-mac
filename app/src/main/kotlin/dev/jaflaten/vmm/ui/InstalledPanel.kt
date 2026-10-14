package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.DependencyResolver
import dev.jaflaten.vmm.core.ModEntry
import dev.jaflaten.vmm.core.ResolutionPlan
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

class InstalledPanel(private val frame: MainFrame) : JPanel(BorderLayout()) {
    private var all: List<ModEntry> = emptyList()
    private var rows: List<ModEntry> = emptyList()
    private val filter = JTextField(20).apply { putClientProperty("JTextField.placeholderText", "Filter installed mods…") }
    private val summary = JLabel()

    private val model = object : AbstractTableModel() {
        val cols = arrayOf("Enabled", "Mod", "Installed", "Latest", "Source")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int) = if (c == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(r: Int, c: Int) = c == 0
        override fun getValueAt(r: Int, c: Int): Any? {
            val e = rows[r]
            return when (c) {
                0 -> e.enabled
                1 -> e.name
                2 -> e.version ?: "—"
                3 -> when {
                    !e.managed -> ""
                    e.updateAvailable -> "⬆ ${e.latestVersion}"
                    e.latestVersion != null -> "✓"
                    else -> "?"
                }
                else -> if (e.managed) "Thunderstore" else "Manual"
            }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            if (c == 0) toggle(rows[r], v as Boolean)
        }
    }
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        autoCreateRowSorter = false
        rowHeight = 26
        columnModel.getColumn(0).maxWidth = 70
        columnModel.getColumn(2).preferredWidth = 90
        columnModel.getColumn(3).preferredWidth = 90
        columnModel.getColumn(4).preferredWidth = 100
        columnModel.getColumn(1).preferredWidth = 420
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        bar.add(filter)
        bar.add(button("Refresh") { reload() })
        bar.add(button("Update selected") { update(selected().filter { it.updateAvailable }) })
        bar.add(button("Update all") { update(all.filter { it.updateAvailable }) })
        bar.add(button("Uninstall") { uninstall() })
        bar.add(button("Open folder") { selected().firstOrNull()?.let { Ui.open(it.path) } ?: frame.ctx.mods?.let { Ui.open(it.pluginsDir) } })
        bar.add(button("Edit configs…") { frame.ctx.mods?.let { ConfigEditorDialog(frame, it.configDir, selected().firstOrNull()?.name).isVisible = true } })
        add(bar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() } }

    private fun selected(): List<ModEntry> = table.selectedRows.map { rows[it] }

    fun reload() {
        all = try {
            frame.ctx.mods?.list().orEmpty()
        } catch (e: Exception) {
            frame.status("Could not list mods: ${e.message}")
            emptyList()
        }
        applyFilter()
    }

    private fun applyFilter() {
        val q = filter.text.trim().lowercase()
        rows = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
        model.fireTableDataChanged()
        val updates = all.count { it.updateAvailable }
        summary.text = " ${all.size} installed · ${all.count { it.enabled }} enabled" + if (updates > 0) " · $updates update(s) available" else ""
    }

    private fun toggle(entry: ModEntry, enable: Boolean) {
        val mods = frame.ctx.mods ?: return
        if (!enable && entry.managed) {
            val deps = DependencyResolver.dependents(entry.name, mods.loadState().mods.values)
            if (deps.isNotEmpty() && !Ui.confirm(frame, "These enabled mods depend on ${entry.name}:\n\n${deps.joinToString("\n")}\n\nDisable anyway?")) return
        }
        if (enable && entry.managed) {
            val state = mods.loadState()
            val disabledDeps = state.mods[entry.name]?.dependencies.orEmpty()
                .mapNotNull { dev.jaflaten.vmm.core.DependencyRef.parse(it)?.fullName }
                .filter { state.mods[it]?.enabled == false }
            if (disabledDeps.isNotEmpty() && Ui.confirm(frame, "${entry.name} needs these disabled mods:\n\n${disabledDeps.joinToString("\n")}\n\nEnable them too?")) {
                frame.runTask("Enabling…", { _ -> (disabledDeps + entry.name).forEach { mods.setEnabled(it, true) } }) { frame.status("Enabled ${entry.name}") }
                return
            }
        }
        frame.runTask(if (enable) "Enabling ${entry.name}…" else "Disabling ${entry.name}…", { _ -> mods.setEnabled(entry.name, enable) }) {
            frame.status("${if (enable) "Enabled" else "Disabled"} ${entry.name}")
        }
    }

    private fun update(entries: List<ModEntry>) {
        val mods = frame.ctx.mods ?: return
        if (entries.isEmpty()) {
            Ui.info(frame, "No updates available for the selection.")
            return
        }
        val plan = mods.plan(entries.map { it.name })
        installWithConfirm(frame, plan, "Update")
    }

    private fun uninstall() {
        val mods = frame.ctx.mods ?: return
        val sel = selected()
        if (sel.isEmpty()) return
        val state = mods.loadState()
        val dependents = sel.flatMap { DependencyResolver.dependents(it.name, state.mods.values) }.distinct() - sel.map { it.name }.toSet()
        val removeConfigs = JCheckBox("Also delete config files these mods created")
        val msg = buildString {
            append("Uninstall ${sel.size} mod(s)?\n\n")
            sel.forEach { append("• ${it.name}\n") }
            if (dependents.isNotEmpty()) append("\nWarning: still needed by:\n${dependents.joinToString("\n") { "• $it" }}\n")
        }
        val ok = JOptionPane.showConfirmDialog(frame, arrayOf<Any>(msg, removeConfigs), "Uninstall", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
        if (!ok) return
        frame.runTask("Uninstalling…", { _ -> sel.forEach { mods.uninstall(it.name, removeConfigs.isSelected) } }) {
            frame.status("Uninstalled ${sel.size} mod(s)")
        }
    }

    companion object {
        fun installWithConfirm(frame: MainFrame, plan: ResolutionPlan, verb: String = "Install") {
            val mods = frame.ctx.mods ?: return Ui.error(frame, "Valheim folder not found. See Setup.")
            if (frame.ctx.bepinex?.status()?.installed != true) return Ui.error(frame, "Install BepInEx first (Setup tab).")
            if (plan.toInstall.isEmpty() && plan.missing.isEmpty()) return Ui.info(frame, "Everything is already installed and up to date.")
            val msg = buildString {
                if (plan.toInstall.isNotEmpty()) {
                    append("$verb these packages?\n\n")
                    plan.toInstall.forEach { append("• ${it.fullName} ${it.latestVersion}\n") }
                }
                if (plan.missing.isNotEmpty()) {
                    append("\nNot found on Thunderstore (skipped):\n")
                    plan.missing.forEach { append("• $it\n") }
                }
            }
            if (plan.toInstall.isEmpty()) return Ui.info(frame, msg)
            if (!Ui.confirm(frame, msg, verb)) return
            frame.runTask("${verb}ing…", { report -> mods.install(plan, report) }) { warnings ->
                frame.status("$verb complete: ${plan.toInstall.size} package(s)")
                if (warnings.isNotEmpty()) Ui.info(frame, "Installed with warnings:\n\n" + warnings.joinToString("\n"))
            }
        }
    }
}
