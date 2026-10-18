package com.qingsongreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.qingsongreader.dict.DictEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(
    word: String,
    entry: DictEntry?,
    loading: Boolean,
    error: String?,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = word, style = MaterialTheme.typography.titleLarge)
            entry?.phonetic?.let { phonetic ->
                if (phonetic.isNotBlank()) {
                    Text(text = phonetic, style = MaterialTheme.typography.bodySmall)
                }
            }

            when {
                loading -> Text("查询中…", style = MaterialTheme.typography.bodyMedium)
                error != null -> Text(error, style = MaterialTheme.typography.bodyMedium)
                entry == null -> Text("没有查到释义。", style = MaterialTheme.typography.bodyMedium)
                else -> entry.senses.forEach { sense ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        if (sense.partOfSpeech.isNotBlank()) {
                            Text(
                                text = sense.partOfSpeech,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(text = sense.definition, style = MaterialTheme.typography.bodyMedium)
                        if (sense.example.isNotBlank()) {
                            Text(
                                text = "例：${sense.example}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSpeak) { Text("朗读") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry?.plainDefinition ?: word))
                }) { Text("复制释义") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
