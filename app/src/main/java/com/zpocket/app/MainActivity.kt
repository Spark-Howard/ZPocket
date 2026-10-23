package com.zpocket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.zpocket.app.data.ConnectionStore
import com.zpocket.app.data.RemoteUrl
import com.zpocket.app.ui.AppRoot

class MainActivity : ComponentActivity() {

    private lateinit var store: ConnectionStore

    /** 通过 VIEW intent 从外部（系统分享/浏览器）进来的连接链接。 */
    private val incomingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不做 edge-to-edge：系统栏由主题着色，WebView 不延伸到系统栏底下，
        // 避免三键导航遮挡网页底部按钮和输入法误弹出的问题
        store = ConnectionStore(this)
        setContent {
            ZPocketTheme {
                AppRoot(store, incomingUrl)
            }
        }
        extractUrl(intent)?.let { incomingUrl.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrl(intent)?.let { incomingUrl.value = it }
    }

    private fun extractUrl(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data?.toString()
            ?.takeIf { RemoteUrl.isValid(it) }
}

private val ZPocketColors = darkColorScheme(
    primary = Color(0xFF7C8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3358),
    onPrimaryContainer = Color(0xFFDCE1FF),
    background = Color(0xFF0E1122),
    onBackground = Color(0xFFE7E9F5),
    surface = Color(0xFF181C30),
    onSurface = Color(0xFFE7E9F5),
    surfaceVariant = Color(0xFF232842),
    onSurfaceVariant = Color(0xFFA9AFCC),
    outline = Color(0xFF3A4066),
    error = Color(0xFFFF7B7B),
)

@Composable
fun ZPocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZPocketColors, content = content)
}
