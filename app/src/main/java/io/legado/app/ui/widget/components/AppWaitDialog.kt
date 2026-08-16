package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 阻塞等待对话框（L2 Dialog 族）：居中圆形进度 + 文案，禁止关闭（无取消按钮）。
 * [show]=false 时不渲染任何内容，由调用方以状态控制显隐。
 */
@Composable
fun AppWaitDialog(
    text: String? = null,
    show: Boolean,
    modifier: Modifier = Modifier
) {
    if (!show) return
    Dialog(onDismissRequest = { }) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
