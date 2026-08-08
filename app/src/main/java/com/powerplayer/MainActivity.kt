package com.powerplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.powerplayer.ui.screens.PlayerScreen
import com.powerplayer.ui.theme.PowerPlayerTheme
import com.powerplayer.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PowerPlayerTheme {
                FolderPickerLauncher(
                    onPickFolder = { viewModel.pickFolder(it) },
                    content = { launch -> PlayerScreen(viewModel = viewModel, onPickFolder = launch) }
                )
            }
        }
    }
}

@Composable
private fun FolderPickerLauncher(
    onPickFolder: (Uri) -> Unit,
    content: @Composable (() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // разрешение не критично — сможем открыть папку в текущей сессии
            }
            onPickFolder(uri)
        }
    }
    content { launcher.launch(null) }
}
