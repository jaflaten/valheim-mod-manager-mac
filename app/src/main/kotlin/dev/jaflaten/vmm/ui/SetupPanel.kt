package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.AppPaths
import dev.jaflaten.vmm.core.BepInExService
import dev.jaflaten.vmm.core.GameLocator
import dev.jaflaten.vmm.core.GameProcess
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

class SetupPanel(private val frame: MainFrame) : JPanel(BorderLayout()) {
    companion object {
        const val DEVCOMMANDS = "JereKuusela-Server_devcommands"
    }

    private val gamePath = JTextField(50).apply { isEditable = false }
    private val bepStatus = JLabel()
    private val bepButton = JButton("Install BepInEx")
    private val rosetta = JLabel()
    private val steam = JLabel()
    private val devStatus = JLabel()
    private val devButton = JButton("Install Server devcommands")
    private val viaTerminal = JCheckBox("Launch through Terminal.app (fallback if the game does not start)")
    private val showDeprecated = JCheckBox("Show deprecated mods in Browse")

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        content.add(section("1. Valheim folder", form(
            row(gamePath, JButton("Detect").apply { addActionListener { detect() } }, JButton("Choose…").apply { addActionListener { choose() } },
                JButton("Open").apply { addActionListener { frame.ctx.gameRoot?.let(Ui::open) } }),
        )))
        content.add(section("2. BepInEx mod loader", form(
            row(bepStatus),
            row(bepButton, JButton("Open BepInEx folder").apply { addActionListener { frame.ctx.bepinex?.bepinexDir?.let(Ui::open) } }),
            row(JLabel("<html><font color='#999999'>Installs BepInExPack_Valheim ${BepInExService.PINNED_VERSION} from Thunderstore into the game folder. " +
                "Vanilla play is unaffected — BepInEx only loads when you use “Play Modded”.</font></html>")),
        )))
        content.add(section("3. System checks", form(row(rosetta), row(steam))))
        content.add(section("Server devcommands", form(
            row(devStatus, devButton),
            row(JLabel("<html><font color='#bbbbbb'>Server_devcommands lets you use dev/cheat commands (god, fly, spawn, …) in single player <i>and</i> on servers where you are admin.<br>" +
                "In game: press <b>F5</b> to open the console, type <b>devcommands</b> and press Enter. Then e.g. <b>god</b>, <b>fly</b> (Z), <b>spawn Boar 2</b>, <b>pos</b>.<br>" +
                "For a dedicated server you must be in its <b>adminlist.txt</b> (your SteamID64); for full support the server should also run the mod.</font></html>")),
        )))
        content.add(section("Options", form(
            row(viaTerminal), row(showDeprecated),
            row(JButton("Open app data folder").apply { addActionListener { Ui.open(AppPaths.dataDir) } }),
        )))
        content.add(Box.createVerticalGlue())
        add(JScrollPane(content).apply { border = null }, BorderLayout.CENTER)

        bepButton.addActionListener { installBepInEx() }
        devButton.addActionListener { installDevcommands() }
        viaTerminal.addActionListener { frame.ctx.updateSettings(frame.ctx.settings.copy(launchViaTerminal = viaTerminal.isSelected)) }
        showDeprecated.addActionListener {
            frame.ctx.updateSettings(frame.ctx.settings.copy(showDeprecated = showDeprecated.isSelected))
            frame.browsePanel.setPackages(frame.ctx.client.packages)
        }
    }

    private fun section(title: String, body: JComponent) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 0, 12, 0),
            BorderFactory.createTitledBorder(title),
        )
        add(body, BorderLayout.CENTER)
        alignmentX = LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height + 40)
    }

    private fun row(vararg comps: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { comps.forEach { add(it) } }

    private fun form(vararg rows: JComponent) = JPanel(GridBagLayout()).apply {
        rows.forEachIndexed { i, r ->
            add(r, GridBagConstraints().apply {
                gridy = i; gridx = 0; weightx = 1.0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 4, 2, 4)
            })
        }
    }

    fun reload() {
        val ctx = frame.ctx
        gamePath.text = ctx.gameRoot?.toString() ?: "Not found — click Detect or Choose…"
        viaTerminal.isSelected = ctx.settings.launchViaTerminal
        showDeprecated.isSelected = ctx.settings.showDeprecated
        val st = ctx.bepinex?.status()
        bepStatus.text = when {
            st == null -> "—"
            st.healthy -> "<html><font color='#7ccf7c'>✓ Installed</font> ${st.version?.let { "v$it" } ?: ""} · doorstop ${st.doorstopArchs.joinToString()}</html>"
            st.installed -> "<html><font color='#ffcc66'>⚠ ${st.problems.joinToString("; ")}</font></html>"
            else -> "<html><font color='#ff8080'>✗ Not installed</font></html>"
        }
        bepButton.text = if (st?.installed == true) "Repair / Reinstall BepInEx" else "Install BepInEx"
        bepButton.isEnabled = ctx.gameRoot != null

        val dev = runCatching { ctx.mods?.loadState()?.mods?.get(DEVCOMMANDS) }.getOrNull()
        devStatus.text = when {
            dev == null -> "Not installed"
            dev.enabled -> "<html><font color='#7ccf7c'>✓ Installed v${dev.version} (enabled)</font></html>"
            else -> "<html><font color='#ffcc66'>Installed v${dev.version} (disabled — enable it in Installed)</font></html>"
        }
        devButton.isEnabled = st?.installed == true && dev == null
        refreshChecks()
    }

    private fun refreshChecks() {
        Thread {
            val r = GameProcess.isRosettaAvailable()
            val s = GameProcess.isSteamRunning()
            javax.swing.SwingUtilities.invokeLater {
                rosetta.text = if (r) "<html><font color='#7ccf7c'>✓</font> Rosetta 2 available (required: BepInEx's doorstop is x86_64-only)</html>"
                else "<html><font color='#ff8080'>✗ Rosetta 2 missing</font> — run: softwareupdate --install-rosetta --agree-to-license</html>"
                steam.text = if (s) "<html><font color='#7ccf7c'>✓</font> Steam is running</html>"
                else "<html><font color='#ffcc66'>Steam is not running</font> — it will be started when you press Play Modded</html>"
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detect() {
        val root = GameLocator.detect(frame.ctx.settings.copy(gameRootOverride = null))
        if (root == null) {
            Ui.error(frame, "Could not find Valheim in your Steam libraries. Use Choose… to select the folder that contains Valheim.app.")
            return
        }
        setRoot(root, override = null)
    }

    private fun choose() {
        val chooser = JFileChooser(frame.ctx.gameRoot?.toFile()).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Select the Valheim folder (contains Valheim.app)"
        }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
        var p = chooser.selectedFile.toPath()
        if (p.fileName.toString().endsWith(".app", true)) p = p.parent
        if (!GameLocator.isValidGameRoot(p)) {
            Ui.error(frame, "No Valheim.app found in $p")
            return
        }
        setRoot(p, override = p.toString())
    }

    private fun setRoot(root: Path, override: String?) {
        frame.ctx.updateSettings(frame.ctx.settings.copy(gameRootOverride = override))
        frame.ctx.setGameRoot(root)
        frame.refreshAll()
    }

    private fun installBepInEx() {
        val bep = frame.ctx.bepinex ?: return
        frame.runTask("Installing BepInEx…", { report -> bep.install(report) }) {
            frame.status("BepInEx ${it.version} installed")
        }
    }

    private fun installDevcommands() {
        val mods = frame.ctx.mods ?: return
        if (frame.ctx.client.find(DEVCOMMANDS) == null) {
            Ui.error(frame, "Thunderstore list not loaded yet. Wait for it to load (see status bar) and try again.")
            return
        }
        InstalledPanel.installWithConfirm(frame, mods.plan(listOf(DEVCOMMANDS)))
    }
}
