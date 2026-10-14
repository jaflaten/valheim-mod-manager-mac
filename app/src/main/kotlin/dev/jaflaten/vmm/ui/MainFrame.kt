package dev.jaflaten.vmm.ui

import dev.jaflaten.vmm.core.GameProcess
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.WindowConstants

class MainFrame : JFrame("Valheim Mod Manager") {
    val ctx = AppContext()
    private val busy = AtomicBoolean(false)
    private val statusLabel = JLabel("Ready")
    private val progressBar = JProgressBar(0, 1000).apply { isVisible = false; preferredSize = Dimension(220, 14) }
    private val gameLabel = JLabel()
    private val playModded = JButton("▶  Play Modded").apply {
        font = font.deriveFont(Font.BOLD, 14f)
        putClientProperty("JButton.buttonType", "default")
    }
    private val playVanilla = JButton("Play Vanilla")

    val installedPanel = InstalledPanel(this)
    val browsePanel = BrowsePanel(this)
    val setupPanel = SetupPanel(this)
    val logsPanel = LogsPanel(this)
    private val tabs = JTabbedPane()

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(1000, 650)
        size = Dimension(1250, 800)
        setLocationRelativeTo(null)

        val top = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(playModded); add(playVanilla) }, BorderLayout.WEST)
            add(gameLabel, BorderLayout.EAST)
        }
        tabs.addTab("Installed", installedPanel)
        tabs.addTab("Browse Thunderstore", browsePanel)
        tabs.addTab("Setup", setupPanel)
        tabs.addTab("Logs", logsPanel)
        tabs.addChangeListener { if (tabs.selectedComponent == logsPanel) logsPanel.reload() }

        val bottom = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 12, 6, 12)
            add(statusLabel, BorderLayout.CENTER)
            add(progressBar, BorderLayout.EAST)
        }
        contentPane.layout = BorderLayout()
        contentPane.add(top, BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(bottom, BorderLayout.SOUTH)

        playModded.addActionListener { launchModded() }
        playVanilla.addActionListener {
            ctx.launcher?.launchVanilla() ?: Ui.error(this, "Valheim folder not found. See Setup.")
            status("Launching vanilla Valheim via Steam…")
        }

        refreshAll()
        if (ctx.gameRoot == null || ctx.bepinex?.status()?.installed != true) tabs.selectedComponent = setupPanel
        loadPackages(false)
    }

    fun status(msg: String) {
        statusLabel.text = msg
    }

    fun refreshAll() {
        val root = ctx.gameRoot
        val bep = ctx.bepinex?.status()
        gameLabel.text = when {
            root == null -> "<html><font color='#ff8080'>Valheim not found — see Setup</font></html>"
            bep?.healthy == true -> "<html>BepInEx ${bep.version ?: ""} ✓ &nbsp; <font color='#999999'>$root</font></html>"
            else -> "<html><font color='#ffcc66'>BepInEx not ready — see Setup</font> &nbsp; <font color='#999999'>$root</font></html>"
        }
        playModded.isEnabled = bep?.healthy == true
        playVanilla.isEnabled = root != null
        installedPanel.reload()
        setupPanel.reload()
        browsePanel.refreshInstalledMarks()
    }

    fun loadPackages(force: Boolean) {
        runTask("Loading Thunderstore mod list…", { _ -> ctx.client.load(force) }) { list ->
            status("Loaded ${list.size} Thunderstore packages")
            browsePanel.setPackages(list)
            installedPanel.reload()
        }
    }

    /**
     * Runs [work] on a background thread with progress reporting, then [onDone] on the EDT.
     * Only one task runs at a time to avoid concurrent file operations.
     */
    fun <T> runTask(title: String, work: ((String, Double) -> Unit) -> T, onDone: (T) -> Unit = {}) {
        if (!busy.compareAndSet(false, true)) {
            Ui.info(this, "Please wait for the current operation to finish.")
            return
        }
        status(title)
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        object : SwingWorker<T, Pair<String, Double>>() {
            override fun doInBackground(): T = work { msg, frac -> publish(msg to frac) }
            override fun process(chunks: List<Pair<String, Double>>) {
                val (msg, frac) = chunks.last()
                status(msg)
                progressBar.isIndeterminate = false
                progressBar.value = (frac.coerceIn(0.0, 1.0) * 1000).toInt()
            }
            override fun done() {
                busy.set(false)
                progressBar.isVisible = false
                try {
                    onDone(get())
                } catch (e: Exception) {
                    val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                    status("Failed: ${cause.message}")
                    Ui.error(this@MainFrame, cause.message ?: cause.toString())
                }
                refreshAll()
            }
        }.execute()
    }

    private fun launchModded() {
        val launcher = ctx.launcher ?: return
        runTask("Launching modded Valheim…", { report ->
            launcher.launchModded(ctx.settings.launchViaTerminal) { report(it, 0.5) }
        }) { gameLog ->
            status("Valheim starting (modded). Game output: $gameLog")
            logsPanel.lastGameLog = gameLog
            // Check that the game survived startup; if not, show why.
            Timer(20_000) {
                if (!GameProcess.isValheimRunning()) {
                    status("Valheim exited early — see Logs tab")
                    tabs.selectedComponent = logsPanel
                    logsPanel.showGameLog()
                }
            }.apply { isRepeats = false; start() }
        }
    }

    fun showTab(panel: JPanel) {
        SwingUtilities.invokeLater { tabs.selectedComponent = panel }
    }

    companion object {
        val GREEN = Color(0x7CCF7C)
        val ORANGE = Color(0xF0B060)
    }
}
