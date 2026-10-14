package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.DependencyRef
import dev.jaflaten.vmm.core.DependencyResolver
import dev.jaflaten.vmm.core.ModPackage
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.table.AbstractTableModel

class BrowsePanel(private val frame: MainFrame) : JPanel(BorderLayout()) {
    /** Thunderstore entries that are desktop apps, not installable game mods. */
    private val notMods = setOf("ebkr-r2modman", "Thunderstore-Thunderstore_Mod_Manager")
    private var all: List<ModPackage> = emptyList()
    private var rows: List<ModPackage> = emptyList()
    private var installed: Map<String, String> = emptyMap()

    private val search = JTextField(28).apply { putClientProperty("JTextField.placeholderText", "Search mods (name, author, description)…") }
    private val sort = JComboBox(arrayOf("Most downloaded", "Top rated", "Recently updated", "Name"))
    private val category = JComboBox<String>()
    private val count = JLabel()
    private val details = JEditorPane("text/html", "").apply {
        isEditable = false
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) Ui.openUrl(it.url.toString()) }
    }
    private val installButton = JButton("Install").apply { isEnabled = false; putClientProperty("JButton.buttonType", "default") }
    private val debounce = Timer(250) { applyFilter() }.apply { isRepeats = false }

    private val model = object : AbstractTableModel() {
        val cols = arrayOf("Mod", "Author", "Version", "Downloads", "Rating", "Updated", "")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getValueAt(r: Int, c: Int): Any {
            val p = rows[r]
            return when (c) {
                0 -> p.name.replace('_', ' ')
                1 -> p.owner
                2 -> p.latestVersion
                3 -> Ui.formatCount(p.totalDownloads)
                4 -> p.rating.toString()
                5 -> p.dateUpdated.take(10)
                else -> installed[p.fullName]?.let { if (it == p.latestVersion) "Installed" else "Update" } ?: ""
            }
        }
    }
    private val table = JTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 24
        columnModel.getColumn(0).preferredWidth = 240
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).preferredWidth = 70
        columnModel.getColumn(3).preferredWidth = 85
        columnModel.getColumn(4).preferredWidth = 55
        columnModel.getColumn(5).preferredWidth = 90
        columnModel.getColumn(6).preferredWidth = 70
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(search); add(JLabel("Sort:")); add(sort); add(JLabel("Category:")); add(category)
            add(JButton("Refresh from Thunderstore").apply { addActionListener { frame.loadPackages(true) } })
            add(count)
        }
        val right = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
            add(JScrollPane(details), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(installButton) }, BorderLayout.SOUTH)
            preferredSize = Dimension(380, 400)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(table), right).apply { resizeWeight = 0.65 }
        add(bar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: DocumentEvent) = debounce.restart()
        })
        sort.addActionListener { applyFilter() }
        category.addActionListener { applyFilter() }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        installButton.addActionListener { installSelected() }
        details.text = "<html><body style='font-family:sans-serif'><p>Loading Thunderstore mod list…</p></body></html>"
    }

    fun setPackages(list: List<ModPackage>) {
        all = list.filter { !it.nsfw && it.fullName != DependencyResolver.BEPINEX_PACK && it.fullName !in notMods }
        val cats = all.flatMap { it.categories }.distinct().sorted()
        val current = category.selectedItem
        category.removeAllItems()
        category.addItem("All")
        cats.forEach { category.addItem(it) }
        if (current != null && current in cats) category.selectedItem = current
        refreshInstalledMarks()
        applyFilter()
    }

    fun refreshInstalledMarks() {
        installed = runCatching { frame.ctx.mods?.installedVersions() }.getOrNull().orEmpty()
        model.fireTableRowsUpdated(0, maxOf(0, rows.size - 1))
        showDetails()
    }

    private fun applyFilter() {
        val terms = search.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cat = category.selectedItem as String?
        val showDeprecated = frame.ctx.settings.showDeprecated
        var list = all.asSequence()
            .filter { showDeprecated || !it.deprecated }
            .filter { cat == null || cat == "All" || cat in it.categories }
            .filter { p ->
                terms.isEmpty() || terms.all { t ->
                    p.name.lowercase().replace('_', ' ').contains(t) || p.name.lowercase().contains(t) ||
                        p.owner.lowercase().contains(t) || p.description.lowercase().contains(t)
                }
            }.toList()
        list = when (sort.selectedIndex) {
            1 -> list.sortedByDescending { it.rating }
            2 -> list.sortedByDescending { it.dateUpdated }
            3 -> list.sortedBy { it.name.lowercase() }
            else -> list.sortedByDescending { it.totalDownloads }
        }
        rows = list
        model.fireTableDataChanged()
        count.text = "${rows.size} of ${all.size} mods"
    }

    private fun selectedPackage(): ModPackage? = table.selectedRow.takeIf { it >= 0 }?.let { rows.getOrNull(it) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun showDetails() {
        val p = selectedPackage()
        if (p == null) {
            installButton.isEnabled = false
            if (all.isNotEmpty()) details.text = "<html><body style='font-family:sans-serif'><p>Select a mod to see details.</p></body></html>"
            return
        }
        val have = installed[p.fullName]
        installButton.text = when {
            have == null -> "Install"
            have != p.latestVersion -> "Update to ${p.latestVersion}"
            else -> "Reinstall"
        }
        installButton.isEnabled = true
        val deps = p.dependencies.mapNotNull(DependencyRef::parse)
        details.text = buildString {
            append("<html><body style='font-family:sans-serif;padding:6px'>")
            append("<h2>${esc(p.name.replace('_', ' '))}</h2>")
            append("<p>by <b>${esc(p.owner)}</b> · v${esc(p.latestVersion)} · ${Ui.formatCount(p.totalDownloads)} downloads · rating ${p.rating}</p>")
            if (p.deprecated) append("<p style='color:#ff8080'><b>Deprecated</b></p>")
            if (have != null) append("<p style='color:#7ccf7c'>Installed: v${esc(have)}</p>")
            append("<p>${esc(p.description)}</p>")
            if (p.categories.isNotEmpty()) append("<p><i>${esc(p.categories.joinToString(", "))}</i></p>")
            append("<h3>Dependencies</h3>")
            if (deps.isEmpty()) append("<p>None</p>") else {
                append("<ul>")
                deps.forEach { d ->
                    val mark = when {
                        d.fullName == DependencyResolver.BEPINEX_PACK -> " (BepInEx)"
                        installed.containsKey(d.fullName) -> " ✓"
                        else -> ""
                    }
                    append("<li>${esc(d.fullName)} ${esc(d.version)}$mark</li>")
                }
                append("</ul>")
            }
            append("<p><a href='${esc(p.packageUrl)}'>Open on Thunderstore</a>")
            p.websiteUrl?.takeIf { it.startsWith("https://") }?.let { append(" · <a href='${esc(it)}'>Website</a>") }
            append("</p></body></html>")
        }
        details.caretPosition = 0
    }

    private fun installSelected() {
        val p = selectedPackage() ?: return
        val mods = frame.ctx.mods ?: return Ui.error(frame, "Valheim folder not found. See Setup.")
        val plan = if (installed[p.fullName] == p.latestVersion) {
            // Reinstall: force the target, keep resolution for dependencies.
            val base = mods.plan(listOf(p.fullName))
            base.copy(toInstall = base.toInstall + p)
        } else mods.plan(listOf(p.fullName))
        InstalledPanel.installWithConfirm(frame, plan)
    }
}
