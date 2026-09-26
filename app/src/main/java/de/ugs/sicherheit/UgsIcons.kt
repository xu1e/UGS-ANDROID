package de.ugs.sicherheit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Eigene, schlichte Linien-Symbole für Bereiche ohne passendes Material-Kernsymbol. */
object UgsIcons {
    private fun icon(name: String, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
            .build()

    val Signature by lazy {
        icon("Signature") {
            moveTo(3f, 17f)
            curveTo(5f, 11f, 7f, 7f, 8.5f, 8.5f)
            curveTo(10f, 10f, 6f, 16f, 8f, 16.5f)
            curveTo(10f, 17f, 11f, 12f, 12.5f, 12.5f)
            curveTo(14f, 13f, 13f, 16f, 15f, 16f)
            curveTo(16.5f, 16f, 17f, 14f, 18f, 14f)
            moveTo(3f, 20.5f)
            lineTo(21f, 20.5f)
            moveTo(16f, 4f)
            lineTo(20f, 8f)
            lineTo(14f, 14f)
            lineTo(11f, 14.5f)
            lineTo(11.5f, 11.5f)
            close()
        }
    }

    val Euro by lazy {
        icon("Euro") {
            moveTo(17.5f, 6.5f)
            curveTo(16.3f, 5.3f, 14.7f, 4.5f, 13f, 4.5f)
            curveTo(9f, 4.5f, 6.5f, 7.9f, 6.5f, 12f)
            curveTo(6.5f, 16.1f, 9f, 19.5f, 13f, 19.5f)
            curveTo(14.7f, 19.5f, 16.3f, 18.7f, 17.5f, 17.5f)
            moveTo(4f, 10f)
            lineTo(13f, 10f)
            moveTo(4f, 14f)
            lineTo(12f, 14f)
        }
    }

    val Receipt by lazy {
        icon("Receipt") {
            moveTo(6f, 3f)
            lineTo(18f, 3f)
            lineTo(18f, 21f)
            lineTo(16f, 19.5f)
            lineTo(14f, 21f)
            lineTo(12f, 19.5f)
            lineTo(10f, 21f)
            lineTo(8f, 19.5f)
            lineTo(6f, 21f)
            close()
            moveTo(9f, 8f)
            lineTo(15f, 8f)
            moveTo(9f, 12f)
            lineTo(15f, 12f)
            moveTo(9f, 16f)
            lineTo(12.5f, 16f)
        }
    }

    val Handshake by lazy {
        icon("Handshake") {
            moveTo(2f, 12f)
            lineTo(5f, 9f)
            lineTo(9f, 9f)
            lineTo(12f, 7f)
            lineTo(15f, 9f)
            lineTo(19f, 9f)
            lineTo(22f, 12f)
            moveTo(12f, 7f)
            lineTo(9f, 11f)
            curveTo(10f, 12f, 11.5f, 12f, 12.5f, 11f)
            lineTo(17f, 15f)
            curveTo(17.5f, 15.5f, 17f, 16.5f, 16f, 16f)
            moveTo(16f, 16f)
            curveTo(16.5f, 17f, 15.5f, 17.5f, 15f, 17f)
            moveTo(15f, 17f)
            curveTo(15.3f, 18f, 14.3f, 18.5f, 13.5f, 18f)
            moveTo(13.5f, 18f)
            curveTo(13.5f, 19f, 12.5f, 19.3f, 12f, 18.8f)
            lineTo(7f, 14f)
            moveTo(5f, 9f)
            lineTo(7f, 14f)
            moveTo(19f, 9f)
            lineTo(17f, 15f)
        }
    }

    val Mic by lazy {
        icon("Mic") {
            moveTo(12f, 3f)
            curveTo(10.3f, 3f, 9f, 4.3f, 9f, 6f)
            lineTo(9f, 12f)
            curveTo(9f, 13.7f, 10.3f, 15f, 12f, 15f)
            curveTo(13.7f, 15f, 15f, 13.7f, 15f, 12f)
            lineTo(15f, 6f)
            curveTo(15f, 4.3f, 13.7f, 3f, 12f, 3f)
            close()
            moveTo(5.5f, 11f)
            curveTo(5.5f, 14.6f, 8.4f, 17.5f, 12f, 17.5f)
            curveTo(15.6f, 17.5f, 18.5f, 14.6f, 18.5f, 11f)
            moveTo(12f, 17.5f)
            lineTo(12f, 21f)
            moveTo(9f, 21f)
            lineTo(15f, 21f)
        }
    }

    val Print by lazy {
        icon("Print") {
            moveTo(7f, 8f)
            lineTo(7f, 3f)
            lineTo(17f, 3f)
            lineTo(17f, 8f)
            moveTo(7f, 17f)
            lineTo(4f, 17f)
            lineTo(4f, 9f)
            curveTo(4f, 8.4f, 4.4f, 8f, 5f, 8f)
            lineTo(19f, 8f)
            curveTo(19.6f, 8f, 20f, 8.4f, 20f, 9f)
            lineTo(20f, 17f)
            lineTo(17f, 17f)
            moveTo(7f, 14f)
            lineTo(17f, 14f)
            lineTo(17f, 21f)
            lineTo(7f, 21f)
            close()
        }
    }

    val Moon by lazy {
        icon("Moon") {
            moveTo(20f, 14.5f)
            curveTo(18.9f, 15f, 17.7f, 15.3f, 16.5f, 15.3f)
            curveTo(12.1f, 15.3f, 8.7f, 11.9f, 8.7f, 7.5f)
            curveTo(8.7f, 6.3f, 9f, 5.1f, 9.5f, 4f)
            curveTo(6.2f, 5.3f, 4f, 8.5f, 4f, 12.2f)
            curveTo(4f, 17f, 7.9f, 20.9f, 12.7f, 20.9f)
            curveTo(16.2f, 20.9f, 19.2f, 18.8f, 20f, 14.5f)
            close()
        }
    }

    val Archive by lazy {
        icon("Archive") {
            moveTo(3f, 4f)
            lineTo(21f, 4f)
            lineTo(21f, 8f)
            lineTo(3f, 8f)
            close()
            moveTo(5f, 8f)
            lineTo(5f, 20f)
            lineTo(19f, 20f)
            lineTo(19f, 8f)
            moveTo(10f, 12f)
            lineTo(14f, 12f)
        }
    }

    val Junk by lazy {
        icon("Junk") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(9f, 7f)
            lineTo(9f, 4f)
            lineTo(15f, 4f)
            lineTo(15f, 7f)
            moveTo(6f, 7f)
            lineTo(7f, 20f)
            lineTo(17f, 20f)
            lineTo(18f, 7f)
            moveTo(10f, 11f)
            lineTo(14f, 16f)
            moveTo(14f, 11f)
            lineTo(10f, 16f)
        }
    }

    val Filter by lazy {
        icon("Filter") {
            moveTo(3f, 5f)
            lineTo(21f, 5f)
            lineTo(14f, 13f)
            lineTo(14f, 19f)
            lineTo(10f, 21f)
            lineTo(10f, 13f)
            close()
        }
    }

    val Attachment by lazy {
        icon("Attachment") {
            moveTo(16.5f, 6.5f)
            lineTo(8f, 15f)
            curveTo(7.2f, 15.8f, 7.2f, 17f, 8f, 17.8f)
            curveTo(8.8f, 18.6f, 10f, 18.6f, 10.8f, 17.8f)
            lineTo(19f, 9.6f)
            curveTo(20.6f, 8f, 20.6f, 5.4f, 19f, 3.8f)
            curveTo(17.4f, 2.2f, 14.8f, 2.2f, 13.2f, 3.8f)
            lineTo(4.8f, 12.2f)
            curveTo(2.4f, 14.6f, 2.4f, 18.4f, 4.8f, 20.8f)
            curveTo(7.2f, 23.2f, 11f, 23.2f, 13.4f, 20.8f)
            lineTo(21f, 13.2f)
        }
    }

    val Reply by lazy {
        icon("Reply") {
            moveTo(10f, 5f)
            lineTo(3f, 12f)
            lineTo(10f, 19f)
            moveTo(3f, 12f)
            lineTo(14f, 12f)
            curveTo(18f, 12f, 21f, 15f, 21f, 19f)
        }
    }

    val Forward by lazy {
        icon("Forward") {
            moveTo(14f, 5f)
            lineTo(21f, 12f)
            lineTo(14f, 19f)
            moveTo(21f, 12f)
            lineTo(10f, 12f)
            curveTo(6f, 12f, 3f, 15f, 3f, 19f)
        }
    }

    val Photo by lazy {
        icon("Photo") {
            moveTo(3f, 5f)
            lineTo(21f, 5f)
            lineTo(21f, 19f)
            lineTo(3f, 19f)
            close()
            moveTo(3f, 16f)
            lineTo(8f, 11f)
            lineTo(12f, 15f)
            lineTo(15f, 12f)
            lineTo(21f, 18f)
            moveTo(16f, 8.5f)
            lineTo(16.01f, 8.5f)
        }
    }
}
