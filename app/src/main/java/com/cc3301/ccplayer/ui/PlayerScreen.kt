package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.PlaybackState

@Composable
fun PlayerScreen(
    state: PlaybackState,
    surfaceCallback: MpvSurfaceView.Callback,
    onTogglePlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) {
        AndroidView(
            factory = { context ->
                MpvSurfaceView(context).also { view -> view.callback = surfaceCallback }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { controlsVisible = !controlsVisible },
        )
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(onClick = onTogglePlayPause) {
                    Text(
                        text = stringResource(
                            if (state == PlaybackState.Playing) R.string.player_pause else R.string.player_resume,
                        ),
                    )
                }
                Button(onClick = onClose) {
                    Text(text = stringResource(R.string.player_exit))
                }
            }
        }
    }
}
