package net.prototux.aare.ui.car

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The car readings' icons from the user's dongle launcher (Icone.java there, drawn with Path on a
 * 24 grid: a filled part and a 2-px stroke), so the GTS car card looks like it did on the dongle.
 * One color: the icon's tint.
 */
internal object ObdIcons {
    private fun icon(name: String, fill: String, stroke: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        if (fill.isNotEmpty()) addPath(pathData = addPathNodes(fill), fill = SolidColor(Color.White))
        addPath(pathData = addPathNodes(stroke), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
    }.build()

    val Coolant = icon("Coolant", fill = "M15.2 17.5A3.2 3.2 0 1 1 8.8 17.5A3.2 3.2 0 1 1 15.2 17.5ZM11.2 8H12.8V16H11.2Z", stroke = "M12 2.5H12A2 2 0 0 1 14 4.5V13A2 2 0 0 1 12 15H12A2 2 0 0 1 10 13V4.5A2 2 0 0 1 12 2.5ZM3 21.5Q5 20 7 21.5Q9 23 11 21.5M13 21.5Q15 20 17 21.5Q19 23 21 21.5")
    val Boost = icon("Boost", fill = "M13.6 12A1.6 1.6 0 1 1 10.4 12A1.6 1.6 0 1 1 13.6 12Z", stroke = "M4.48 9.26A8 8 0 1 1 5.87 17.14M7.5 12A4.5 4.5 0 1 1 12 16.5M4.2 9L2.5 9")
    val Battery = icon("Battery", fill = "M6 4.5H9V7H6ZM15 4.5H18V7H15Z", stroke = "M4.5 7H19.5A1.5 1.5 0 0 1 21 8.5V17.5A1.5 1.5 0 0 1 19.5 19H4.5A1.5 1.5 0 0 1 3 17.5V8.5A1.5 1.5 0 0 1 4.5 7ZM6.5 13L9.5 13M14.5 13L17.5 13M16 11.5L16 14.5")
    val Fuel = icon("Fuel", fill = "M6 6.5H11V10.5H6Z", stroke = "M5.5 4H11.5A1.5 1.5 0 0 1 13 5.5V19.5A1.5 1.5 0 0 1 11.5 21H5.5A1.5 1.5 0 0 1 4 19.5V5.5A1.5 1.5 0 0 1 5.5 4ZM13 9L16.5 9L18.5 11L18.5 17.5Q18.5 19 17 19Q15.5 19 15.5 17.5L15.5 13L13 13M2.5 21L14.5 21")
    val Range = icon("Range", fill = "", stroke = "M8 3L3 21M16 3L21 21M12 4L12 7M12 10.5L12 13.5M12 17L12 20")
    val Economy = icon("Economy", fill = "", stroke = "M12 3C12 3 5 11 5 15C5 19 8.2 21.5 12 21.5C15.8 21.5 19 19 19 15C19 11 12 3 12 3Z")
}
