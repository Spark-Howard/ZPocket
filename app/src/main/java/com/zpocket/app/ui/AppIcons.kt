package com.zpocket.app.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 自绘图标（扫码取景框 / 电脑 / 手电筒），避免引入体积很大的 material-icons-extended。
 * 图形在 24x24 网格内绘制。
 */
object AppIcons {

    /** 扫码取景框：四角括号 + 中心方块（中心镂空一个小点）。 */
    val QrScan: ImageVector by lazy {
        materialIcon(name = "Zpocket.QrScan") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                // 左上角
                moveTo(4f, 10f)
                lineTo(4f, 4f)
                lineTo(10f, 4f)
                lineTo(10f, 6f)
                lineTo(6f, 6f)
                lineTo(6f, 10f)
                close()
                // 右上角
                moveTo(14f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 10f)
                lineTo(18f, 10f)
                lineTo(18f, 6f)
                lineTo(14f, 6f)
                close()
                // 左下角
                moveTo(4f, 14f)
                lineTo(6f, 14f)
                lineTo(6f, 18f)
                lineTo(10f, 18f)
                lineTo(10f, 20f)
                lineTo(4f, 20f)
                close()
                // 右下角
                moveTo(20f, 14f)
                lineTo(20f, 20f)
                lineTo(14f, 20f)
                lineTo(14f, 18f)
                lineTo(18f, 18f)
                lineTo(18f, 14f)
                close()
                // 中心方块（带镂空点）
                moveTo(9f, 9f)
                lineTo(15f, 9f)
                lineTo(15f, 15f)
                lineTo(9f, 15f)
                close()
                moveTo(11f, 11f)
                lineTo(13f, 11f)
                lineTo(13f, 13f)
                lineTo(11f, 13f)
                close()
            }
        }
    }

    /** 电脑显示器：屏幕 + 支架。 */
    val Desktop: ImageVector by lazy {
        materialIcon(name = "Zpocket.Desktop") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                // 显示器外框（2,3 - 22,17）
                moveTo(2f, 3f)
                lineTo(22f, 3f)
                lineTo(22f, 17f)
                lineTo(2f, 17f)
                close()
                // 屏幕镂空
                moveTo(4f, 5f)
                lineTo(20f, 5f)
                lineTo(20f, 15f)
                lineTo(4f, 15f)
                close()
                // 支架立柱
                moveTo(11f, 17f)
                lineTo(13f, 17f)
                lineTo(13f, 19f)
                lineTo(11f, 19f)
                close()
                // 底座
                moveTo(8f, 19f)
                lineTo(16f, 19f)
                lineTo(16f, 21f)
                lineTo(8f, 21f)
                close()
            }
        }
    }

    /** 手电筒：灯头 + 灯身（带镂空按钮）。 */
    val Flashlight: ImageVector by lazy {
        materialIcon(name = "Zpocket.Flashlight") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                // 灯头
                moveTo(6f, 2f)
                lineTo(18f, 2f)
                lineTo(18f, 5f)
                lineTo(6f, 5f)
                close()
                // 灯身（梯形收窄后竖直向下）
                moveTo(8f, 6f)
                lineTo(16f, 6f)
                lineTo(16f, 9f)
                lineTo(14f, 11f)
                lineTo(14f, 20f)
                lineTo(10f, 20f)
                lineTo(10f, 11f)
                lineTo(8f, 9f)
                close()
                // 按钮镂空
                moveTo(11f, 10.5f)
                lineTo(13f, 10.5f)
                lineTo(13f, 12.5f)
                lineTo(11f, 12.5f)
                close()
            }
        }
    }
}
