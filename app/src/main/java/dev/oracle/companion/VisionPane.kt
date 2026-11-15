package dev.oracle.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun VisionPane(
    api: CompanionApi?,
    online: Boolean,
    onNavigateChat: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        LiveCameraPane(
            api = api,
            online = online,
            onNavigateChat = onNavigateChat,
            onCapture = {}
        )
    }
}
