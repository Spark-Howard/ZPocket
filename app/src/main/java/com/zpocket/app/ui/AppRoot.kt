package com.zpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zpocket.app.data.Connection
import com.zpocket.app.data.ConnectionStore

sealed interface Screen {
    data object Home : Screen
    data object Scanner : Screen
    data class Remote(val connection: Connection) : Screen
}

/** 首次进入的页面：开了自动重连且有历史记录时，直接进上次的远程界面。 */
private fun initialScreen(store: ConnectionStore): Screen {
    if (!store.isAutoConnectEnabled()) return Screen.Home
    return store.load().firstOrNull()?.let { Screen.Remote(it) } ?: Screen.Home
}

@Composable
fun AppRoot(store: ConnectionStore, incomingUrl: MutableState<String?>) {
    var screen by remember { mutableStateOf(initialScreen(store)) }
    var history by remember { mutableStateOf(store.load()) }
    var autoConnect by remember { mutableStateOf(store.isAutoConnectEnabled()) }

    fun refresh() {
        history = store.load()
    }

    fun connect(url: String): Boolean {
        val conn = store.record(url) ?: return false
        refresh()
        screen = Screen.Remote(conn)
        return true
    }

    LaunchedEffect(incomingUrl.value) {
        incomingUrl.value?.let {
            connect(it)
            incomingUrl.value = null
        }
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            history = history,
            autoConnect = autoConnect,
            onAutoConnectChange = {
                autoConnect = it
                store.setAutoConnectEnabled(it)
            },
            onScan = { screen = Screen.Scanner },
            onOpen = { connect(it.url) },
            onDelete = {
                store.delete(it.id)
                refresh()
            },
        )

        Screen.Scanner -> ScannerScreen(
            onBack = {
                refresh()
                screen = Screen.Home
            },
            onScanned = { url -> connect(url) },
        )

        is Screen.Remote -> RemoteScreen(
            connection = current.connection,
            onBack = {
                refresh()
                screen = Screen.Home
            },
            onRescan = { screen = Screen.Scanner },
            onForget = {
                store.delete(current.connection.id)
                refresh()
                screen = Screen.Home
            },
        )
    }
}
