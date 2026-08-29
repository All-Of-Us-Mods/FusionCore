package dev.allofus.fusioncore.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val folder_special: ImageVector
  get() {
    if (_folder_special != null) {
      return _folder_special!!
    }
    _folder_special =
      ImageVector.Builder(
          name = "folder_special",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero,
          ) {
            moveTo(12.6f, 16.7f)
            lineToRelative(2.3f, -1.75f)
            lineToRelative(2.3f, 1.75f)
            lineTo(16.35f, 13.85f)
            lineTo(18.65f, 12f)
            horizontalLineTo(15.8f)
            lineTo(14.9f, 9.2f)
            lineTo(14f, 12f)
            horizontalLineTo(11.15f)
            lineToRelative(2.3f, 1.85f)
            lineTo(12.6f, 16.7f)
            close()
            moveTo(4f, 20f)
            quadTo(3.18f, 20f, 2.59f, 19.41f)
            reflectiveQuadTo(2f, 18f)
            verticalLineTo(6f)
            quadTo(2f, 5.18f, 2.59f, 4.59f)
            reflectiveQuadTo(4f, 4f)
            horizontalLineToRelative(6f)
            lineToRelative(2f, 2f)
            horizontalLineToRelative(8f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(22f, 7.18f, 22f, 8f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 20f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 18f)
            horizontalLineTo(20f)
            verticalLineTo(8f)
            horizontalLineTo(11.18f)
            lineToRelative(-2f, -2f)
            horizontalLineTo(4f)
            verticalLineTo(18f)
            close()
            moveToRelative(0f, 0f)
            verticalLineTo(6f)
            verticalLineTo(8f)
            verticalLineTo(18f)
            close()
          }
        }
        .build()
    return _folder_special!!
  }

private var _folder_special: ImageVector? = null
