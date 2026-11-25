package local.jiege.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun vec(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply(block).build()

private val Black = SolidColor(Color.Black)

/** Tab 1 (状态): pulse / shield house icon (Outlined + Filled). */
val StatusTabOutlined: ImageVector = vec("StatusTabOutlined") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 3f)
        lineTo(19.5f, 6.2f)
        verticalLineTo(11.6f)
        curveTo(19.5f, 16.4f, 16.3f, 20.1f, 12f, 21.5f)
        curveTo(7.7f, 20.1f, 4.5f, 16.4f, 4.5f, 11.6f)
        verticalLineTo(6.2f)
        close()
    }
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7.8f, 12.2f)
        horizontalLineTo(10.1f)
        lineTo(11.4f, 9.4f)
        lineTo(13.1f, 14.8f)
        lineTo(14.3f, 12.2f)
        horizontalLineTo(16.2f)
    }
}

val StatusTabFilled: ImageVector = vec("StatusTabFilled") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 2.5f)
        lineTo(20f, 5.9f)
        verticalLineTo(11.6f)
        curveTo(20f, 16.8f, 16.6f, 20.7f, 12f, 22f)
        curveTo(7.4f, 20.7f, 4f, 16.8f, 4f, 11.6f)
        verticalLineTo(5.9f)
        close()
        moveTo(7.4f, 11.4f)
        horizontalLineTo(9.6f)
        lineTo(11.4f, 7.8f)
        lineTo(13.1f, 13.4f)
        lineTo(14.1f, 11.4f)
        horizontalLineTo(16.6f)
        verticalLineTo(13f)
        horizontalLineTo(15.1f)
        lineTo(13.4f, 16.4f)
        curveTo(13.1f, 16.9f, 12.4f, 16.8f, 12.2f, 16.2f)
        lineTo(11.2f, 12.7f)
        lineTo(10.5f, 14.1f)
        horizontalLineTo(7.4f)
        close()
    }
}

/** Tab 2 (色彩引擎): artist palette icon (Outlined + Filled). */
val ColorEngineTabOutlined: ImageVector = vec("ColorEngineTabOutlined") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 3f)
        curveTo(6.9f, 3f, 3f, 6.9f, 3f, 12f)
        reflectiveCurveTo(6.9f, 21f, 12f, 21f)
        curveTo(12.9f, 21f, 13.5f, 20.4f, 13.5f, 19.6f)
        curveTo(13.5f, 19.2f, 13.3f, 18.8f, 13.1f, 18.5f)
        curveTo(12.8f, 18.2f, 12.7f, 17.8f, 12.7f, 17.4f)
        curveTo(12.7f, 16.5f, 13.4f, 15.8f, 14.3f, 15.8f)
        horizontalLineTo(16.6f)
        curveTo(19.2f, 15.8f, 21f, 13.8f, 21f, 11.2f)
        curveTo(21f, 6.6f, 17.1f, 3f, 12f, 3f)
        close()
    }
    path(fill = Black) {
        moveTo(7.4f, 11.5f); arcToRelative(1.3f, 1.3f, 0f, true, true, 2.6f, 0f); arcToRelative(1.3f, 1.3f, 0f, true, true, -2.6f, 0f); close()
        moveTo(10.2f, 7.6f); arcToRelative(1.3f, 1.3f, 0f, true, true, 2.6f, 0f); arcToRelative(1.3f, 1.3f, 0f, true, true, -2.6f, 0f); close()
        moveTo(14.8f, 8.8f); arcToRelative(1.3f, 1.3f, 0f, true, true, 2.6f, 0f); arcToRelative(1.3f, 1.3f, 0f, true, true, -2.6f, 0f); close()
    }
}

val ColorEngineTabFilled: ImageVector = vec("ColorEngineTabFilled") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 2.5f)
        curveTo(6.6f, 2.5f, 2.5f, 6.6f, 2.5f, 12f)
        reflectiveCurveTo(6.6f, 21.5f, 12f, 21.5f)
        curveTo(13.1f, 21.5f, 13.8f, 20.7f, 13.8f, 19.7f)
        curveTo(13.8f, 19.2f, 13.6f, 18.8f, 13.3f, 18.4f)
        curveTo(13f, 18.1f, 12.8f, 17.7f, 12.8f, 17.2f)
        curveTo(12.8f, 16.2f, 13.6f, 15.5f, 14.6f, 15.5f)
        horizontalLineTo(16.7f)
        curveTo(19.6f, 15.5f, 21.5f, 13.4f, 21.5f, 10.6f)
        curveTo(21.5f, 6f, 17.3f, 2.5f, 12f, 2.5f)
        close()
        moveTo(6.8f, 11.8f); arcToRelative(1.4f, 1.4f, 0f, true, true, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, true, -2.8f, 0f); close()
        moveTo(9.8f, 7.6f); arcToRelative(1.4f, 1.4f, 0f, true, true, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, true, -2.8f, 0f); close()
        moveTo(14.5f, 8.7f); arcToRelative(1.4f, 1.4f, 0f, true, true, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, true, -2.8f, 0f); close()
    }
}

/** Tab 3 (滤镜): 3 concentric occluding hollow rings (color-only highlight on selection). */
val FilterTabOutlined: ImageVector = vec("FilterTabOutlined") {
    // Top full ring: center (12.0, 8.85), Ro=5.55, Ri=3.85
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 3.30f)
        arcTo(5.55f, 5.55f, 0f, false, true, 12f, 14.40f)
        arcTo(5.55f, 5.55f, 0f, false, true, 12f, 3.30f)
        close()
        moveTo(12f, 5.00f)
        arcTo(3.85f, 3.85f, 0f, false, true, 12f, 12.70f)
        arcTo(3.85f, 3.85f, 0f, false, true, 12f, 5.00f)
        close()
    }
    // Bottom-left crescent ring: center (8.35, 15.15), clipped outside Rg=7.20 of Top
    path(fill = Black) {
        moveTo(13.861f, 15.805f)
        arcTo(5.55f, 5.55f, 0f, true, true, 5.040f, 10.695f)
        arcTo(7.20f, 7.20f, 0f, false, false, 5.708f, 12.350f)
        arcTo(3.85f, 3.85f, 0f, true, false, 12.093f, 16.049f)
        arcTo(7.20f, 7.20f, 0f, false, false, 13.861f, 15.805f)
        close()
    }
    // Bottom-right crescent ring: center (15.65, 15.15), clipped outside Rg=7.20 of Top & BL
    path(fill = Black) {
        moveTo(18.960f, 10.695f)
        arcTo(5.55f, 5.55f, 0f, false, true, 13.441f, 20.241f)
        arcTo(7.20f, 7.20f, 0f, false, false, 14.535f, 18.835f)
        arcTo(3.85f, 3.85f, 0f, false, false, 18.292f, 12.350f)
        arcTo(7.20f, 7.20f, 0f, false, false, 18.960f, 10.695f)
        close()
    }
}

val FilterTabFilled: ImageVector = FilterTabOutlined

/** Tab 4 (其他模式): camera shutter / mode aperture (Outlined + Filled). */
val ModesTabOutlined: ImageVector = vec("ModesTabOutlined") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.2f, 4.2f); horizontalLineTo(10.5f); verticalLineTo(10.5f); horizontalLineTo(4.2f); close()
        moveTo(13.5f, 4.2f); horizontalLineTo(19.8f); verticalLineTo(10.5f); horizontalLineTo(13.5f); close()
        moveTo(4.2f, 13.5f); horizontalLineTo(10.5f); verticalLineTo(19.8f); horizontalLineTo(4.2f); close()
        moveTo(16.65f, 13.3f); arcToRelative(3.35f, 3.35f, 0f, true, true, -0.01f, 0f); close()
    }
}

val ModesTabFilled: ImageVector = vec("ModesTabFilled") {
    path(fill = Black) {
        moveTo(4f, 4f); horizontalLineTo(10.6f); verticalLineTo(10.6f); horizontalLineTo(4f); close()
        moveTo(13.4f, 4f); horizontalLineTo(20f); verticalLineTo(10.6f); horizontalLineTo(13.4f); close()
        moveTo(4f, 13.4f); horizontalLineTo(10.6f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(16.7f, 13f); arcToRelative(3.6f, 3.6f, 0f, true, true, -0.01f, 0f); close()
    }
}

/** Tab 5 (交互): slider / touch toggle controls (Outlined + Filled). */
val InteractionTabOutlined: ImageVector = vec("InteractionTabOutlined") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(4f, 7.5f); horizontalLineTo(13.5f)
        moveTo(18.5f, 7.5f); horizontalLineTo(20f)
        moveTo(16f, 5.2f); arcToRelative(2.3f, 2.3f, 0f, true, true, -0.01f, 0f); close()
        moveTo(4f, 16.5f); horizontalLineTo(6.5f)
        moveTo(11.5f, 16.5f); horizontalLineTo(20f)
        moveTo(9f, 14.2f); arcToRelative(2.3f, 2.3f, 0f, true, true, -0.01f, 0f); close()
    }
}

val InteractionTabFilled: ImageVector = vec("InteractionTabFilled") {
    path(fill = Black) {
        moveTo(4f, 6.6f); horizontalLineTo(12.2f); verticalLineTo(8.4f); horizontalLineTo(4f); close()
        moveTo(18.8f, 6.6f); horizontalLineTo(20f); verticalLineTo(8.4f); horizontalLineTo(18.8f); close()
        moveTo(15.5f, 4.7f); arcToRelative(2.8f, 2.8f, 0f, true, true, -0.01f, 0f); close()
        moveTo(4f, 15.6f); horizontalLineTo(5.8f); verticalLineTo(17.4f); horizontalLineTo(4f); close()
        moveTo(12.2f, 15.6f); horizontalLineTo(20f); verticalLineTo(17.4f); horizontalLineTo(12.2f); close()
        moveTo(9f, 13.7f); arcToRelative(2.8f, 2.8f, 0f, true, true, -0.01f, 0f); close()
    }
}

/** Shared camera / device / drag / delete icons used inside cards. */
val CameraIcon: ImageVector = vec("Camera") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(8.5f, 4f); horizontalLineTo(15.5f); lineTo(17f, 6.5f); horizontalLineTo(20f)
        curveTo(21.1f, 6.5f, 22f, 7.4f, 22f, 8.5f); verticalLineTo(18.5f)
        curveTo(22f, 19.6f, 21.1f, 20.5f, 20f, 20.5f); horizontalLineTo(4f)
        curveTo(2.9f, 20.5f, 2f, 19.6f, 2f, 18.5f); verticalLineTo(8.5f)
        curveTo(2f, 7.4f, 2.9f, 6.5f, 4f, 6.5f); horizontalLineTo(7f); close()
        moveTo(12f, 9f); curveTo(9.8f, 9f, 8f, 10.8f, 8f, 13f); curveTo(8f, 15.2f, 9.8f, 17f, 12f, 17f)
        curveTo(14.2f, 17f, 16f, 15.2f, 16f, 13f); curveTo(16f, 10.8f, 14.2f, 9f, 12f, 9f); close()
    }
}

val InfoModuleIcon: ImageVector = vec("InfoModule") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(7f, 7f); verticalLineTo(17f); horizontalLineTo(17f); verticalLineTo(7f); close()
    }
}

val GalleryIcon: ImageVector = vec("Gallery") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(4f, 4f); horizontalLineTo(20f); curveTo(21.1f, 4f, 22f, 4.9f, 22f, 6f)
        verticalLineTo(18f); curveTo(22f, 19.1f, 21.1f, 20f, 20f, 20f); horizontalLineTo(4f)
        curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f); verticalLineTo(6f); curveTo(2f, 4.9f, 2.9f, 4f, 4f, 4f); close()
        moveTo(4.5f, 6.5f); verticalLineTo(16.2f); lineTo(9f, 11.7f); lineTo(12.2f, 15f)
        lineTo(15.5f, 11f); lineTo(19.5f, 16.2f); verticalLineTo(6.5f); close()
    }
}

val PhoneIcon: ImageVector = vec("Phone") {
    path(fill = Black, pathFillType = PathFillType.EvenOdd) {
        moveTo(7f, 2f); horizontalLineTo(17f); curveTo(18.1f, 2f, 19f, 2.9f, 19f, 4f)
        verticalLineTo(20f); curveTo(19f, 21.1f, 18.1f, 22f, 17f, 22f); horizontalLineTo(7f)
        curveTo(5.9f, 22f, 5f, 21.1f, 5f, 20f); verticalLineTo(4f); curveTo(5f, 2.9f, 5.9f, 2f, 7f, 2f); close()
        moveTo(7f, 5f); verticalLineTo(17f); horizontalLineTo(17f); verticalLineTo(5f); close()
    }
}

val DragHandleIcon: ImageVector = vec("DragHandle") {
    path(fill = Black) {
        moveTo(5f, 8.2f); horizontalLineTo(19f); verticalLineTo(9.8f); horizontalLineTo(5f); close()
        moveTo(5f, 14.2f); horizontalLineTo(19f); verticalLineTo(15.8f); horizontalLineTo(5f); close()
    }
}

val DeleteIcon: ImageVector = vec("Delete") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 7f); horizontalLineTo(19f)
        moveTo(9f, 4.5f); horizontalLineTo(15f)
        moveTo(7f, 7f); lineTo(8f, 19.2f); horizontalLineTo(16f); lineTo(17f, 7f)
    }
}

val InfoIcon: ImageVector = vec("Info") {
    path(stroke = Black, strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, false, true, 12f, 21f)
        arcTo(9f, 9f, 0f, false, true, 12f, 3f)
        close()
        moveTo(12f, 11.2f); verticalLineTo(16.1f)
    }
    path(fill = Black) {
        moveTo(12f, 7.05f)
        arcTo(1.05f, 1.05f, 0f, false, true, 12f, 9.15f)
        arcTo(1.05f, 1.05f, 0f, false, true, 12f, 7.05f)
        close()
    }
}

val EditIcon: ImageVector = vec("Edit") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.5f, 19.5f); lineTo(5.4f, 15.6f); lineTo(16.2f, 4.8f)
        curveTo(16.9f, 4.1f, 18.0f, 4.1f, 18.7f, 4.8f)
        lineTo(19.2f, 5.3f)
        curveTo(19.9f, 6.0f, 19.9f, 7.1f, 19.2f, 7.8f)
        lineTo(8.4f, 18.6f)
        close()
        moveTo(14.1f, 6.9f); lineTo(17.1f, 9.9f)
        moveTo(13.5f, 19.5f); horizontalLineTo(19.8f)
    }
}

val ApertureIcon: ImageVector = vec("Aperture") {
    path(stroke = Black, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 2.8f)
        arcTo(9.2f, 9.2f, 0f, false, true, 12f, 21.2f)
        arcTo(9.2f, 9.2f, 0f, false, true, 12f, 2.8f)
        close()
        moveTo(16.10f, 12.00f); lineTo(10.83f, 21.13f)
        moveTo(14.05f, 15.55f); lineTo(3.51f, 15.55f)
        moveTo(9.95f, 15.55f); lineTo(4.68f, 6.43f)
        moveTo(7.90f, 12.00f); lineTo(13.17f, 2.87f)
        moveTo(9.95f, 8.45f); lineTo(20.49f, 8.45f)
        moveTo(14.05f, 8.45f); lineTo(19.32f, 17.57f)
    }
}

val SaveIcon: ImageVector = vec("Save") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7f, 4.5f); horizontalLineTo(17f); verticalLineTo(19.5f); lineTo(12f, 16f); lineTo(7f, 19.5f); close()
    }
}

val ShareIcon: ImageVector = vec("Share") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 15f); verticalLineTo(4.5f)
        moveTo(8.2f, 8.2f); lineTo(12f, 4.5f); lineTo(15.8f, 8.2f)
        moveTo(6f, 12f); verticalLineTo(19f); horizontalLineTo(18f); verticalLineTo(12f)
    }
}

val ImportIcon: ImageVector = vec("Import") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 4.5f); verticalLineTo(15f)
        moveTo(8.2f, 11.3f); lineTo(12f, 15f); lineTo(15.8f, 11.3f)
        moveTo(6f, 12f); verticalLineTo(19f); horizontalLineTo(18f); verticalLineTo(12f)
    }
}

val RecipeIcon: ImageVector = vec("Recipe") {
    path(stroke = Black, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6.5f, 4.5f); horizontalLineTo(17.5f); verticalLineTo(19.5f); horizontalLineTo(6.5f); close()
        moveTo(9.5f, 9f); horizontalLineTo(14.5f)
        moveTo(9.5f, 12f); horizontalLineTo(14.5f)
        moveTo(9.5f, 15f); horizontalLineTo(12.5f)
    }
}
