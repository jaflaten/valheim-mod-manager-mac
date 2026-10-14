package dev.jaflaten.vmm

import com.formdev.flatlaf.themes.FlatMacDarkLaf
import dev.jaflaten.vmm.ui.MainFrame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/** Renders each tab of the main window to PNG files in [args][0] (UI smoke check without screen-capture permission). */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "/tmp")
    FlatMacDarkLaf.setup()
    lateinit var frame: MainFrame
    SwingUtilities.invokeAndWait { frame = MainFrame(); frame.isVisible = true }
    Thread.sleep(8000)
    val tabs = generateSequence(listOf(frame.contentPane as java.awt.Container)) { level ->
        level.flatMap { c -> c.components.filterIsInstance<java.awt.Container>() }.takeIf { it.isNotEmpty() }
    }.flatten().filterIsInstance<JTabbedPane>().first()
    for (i in 0 until tabs.tabCount) {
        SwingUtilities.invokeAndWait {
            tabs.selectedIndex = i
            if (i == 1) {
                val table = generateSequence(listOf(tabs.getComponentAt(1) as java.awt.Container)) { l ->
                    l.flatMap { c -> c.components.filterIsInstance<java.awt.Container>() }.takeIf { it.isNotEmpty() }
                }.flatten().filterIsInstance<javax.swing.JTable>().first()
                if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
            }
        }
        Thread.sleep(1500)
        SwingUtilities.invokeAndWait {
            val root = frame.rootPane
            val img = BufferedImage(root.width, root.height, BufferedImage.TYPE_INT_RGB)
            root.paint(img.graphics)
            ImageIO.write(img, "png", File(out, "vmm-tab$i.png"))
        }
    }
    println("Rendered ${tabs.tabCount} tabs to $out")
    System.exit(0)
}
