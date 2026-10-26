@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.uyatame.ucrt

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.uyatame.ucrt.dsre.DsreNativeEncoder
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MetadataEditorScreen() {
    val context = LocalContext.current
    val app = context.applicationContext
    val japanese = remember { Locale.getDefault().language == "ja" }
    fun tr(ja: String, en: String) = if (japanese) ja else en
    val scope = rememberCoroutineScope()
    var inputUri by rememberSaveable { mutableStateOf<String?>(null) }
    var inputName by rememberSaveable { mutableStateOf("") }
    var outputTree by rememberSaveable { mutableStateOf("") }
    var coverUri by rememberSaveable { mutableStateOf<String?>(null) }
    var coverMode by rememberSaveable { mutableStateOf("KEEP") }
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var album by rememberSaveable { mutableStateOf("") }
    var albumArtist by rememberSaveable { mutableStateOf("") }
    var composer by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var track by rememberSaveable { mutableStateOf("") }
    var disc by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var copyright by rememberSaveable { mutableStateOf("") }
    var lyrics by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            fun get(key: Int) = retriever.extractMetadata(key).orEmpty()
            title = get(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            albumArtist = get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            composer = get(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE)
            date = get(MediaMetadataRetriever.METADATA_KEY_DATE).ifBlank { get(MediaMetadataRetriever.METADATA_KEY_YEAR) }
            track = get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            disc = get(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            coverMode = "KEEP"
            coverUri = null
        } finally { retriever.release() }
    }

    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            inputUri = uri.toString()
            inputName = DocumentFile.fromSingleUri(app, uri)?.name ?: "audio"
            runCatching { loadMetadata(uri) }.onFailure { error = it.message }
        }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
            outputTree = uri.toString()
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { coverUri = uri.toString(); coverMode = "REPLACE" }
    }

    @Composable
    fun field(label: String, value: String, set: (String) -> Unit, lines: Int = 1) {
        OutlinedTextField(value, set, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = if (lines > 1) 6 else 1)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("音声は再エンコードせず、新しいファイルへタグとカバーだけを書き込みます。", "Audio is stream-copied; only tags and cover are written to a new file."))
        Button({ inputPicker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth(), enabled = !running) { Text(tr("音声ファイルを選択", "Select audio file")) }
        if (inputName.isNotBlank()) Text(inputName)
        OutlinedButton({ outputPicker.launch(null) }, Modifier.fillMaxWidth(), enabled = !running) { Text(tr("保存先フォルダーを選択", "Select output folder")) }
        field(tr("曲名", "Title"), title, { title = it })
        field(tr("アーティスト", "Artist"), artist, { artist = it })
        field(tr("アルバム", "Album"), album, { album = it })
        field(tr("アルバムアーティスト", "Album artist"), albumArtist, { albumArtist = it })
        field(tr("作曲者", "Composer"), composer, { composer = it })
        field(tr("ジャンル", "Genre"), genre, { genre = it })
        field(tr("日付／年", "Date / year"), date, { date = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { field(tr("トラック", "Track"), track, { track = it }) }
            Box(Modifier.weight(1f)) { field(tr("ディスク", "Disc"), disc, { disc = it }) }
        }
        field(tr("コメント", "Comment"), comment, { comment = it }, 2)
        field(tr("著作権", "Copyright"), copyright, { copyright = it })
        field(tr("歌詞", "Lyrics"), lyrics, { lyrics = it }, 4)
        Text(tr("カバー画像", "Cover image"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(coverMode == "KEEP", { coverMode = "KEEP" }, { Text(tr("維持", "Keep")) })
            FilterChip(coverMode == "REPLACE", { coverPicker.launch(arrayOf("image/*")) }, { Text(tr("変更", "Replace")) })
            FilterChip(coverMode == "REMOVE", { coverMode = "REMOVE"; coverUri = null }, { Text(tr("削除", "Remove")) })
        }
        if (coverMode == "REPLACE") Text(coverUri ?: tr("画像を選択してください", "Select an image"))
        Button({
            val source = inputUri?.let(Uri::parse)
            val tree = outputTree.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (source == null || tree == null) { error = tr("入力ファイルと保存先を選択してください", "Select input and output folder"); return@Button }
            running = true; error = null; message = tr("編集中…", "Editing…")
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val directory = DocumentFile.fromTreeUri(app, tree) ?: throw IOException("Unable to open output folder")
                        val sourceDoc = DocumentFile.fromSingleUri(app, source) ?: throw IOException("Unable to open input")
                        val extension = inputName.substringAfterLast('.', "m4a")
                        val stem = inputName.substringBeforeLast('.').ifBlank { "audio" }
                        val output = directory.createFile(sourceDoc.type ?: "audio/*", "$stem-edited.$extension") ?: throw IOException("Unable to create output")
                        val inputTemp = File.createTempFile("metadata_input_", ".$extension", app.cacheDir)
                        val outputTemp = File.createTempFile("metadata_output_", ".$extension", app.cacheDir)
                        val coverTemp = if (coverMode == "REPLACE") File.createTempFile("metadata_cover_", ".image", app.cacheDir) else null
                        try {
                            app.contentResolver.openInputStream(source)?.use { src -> inputTemp.outputStream().use { src.copyTo(it) } } ?: throw IOException("Unable to read input")
                            if (coverTemp != null) app.contentResolver.openInputStream(Uri.parse(coverUri))?.use { src -> coverTemp.outputStream().use { src.copyTo(it) } } ?: throw IOException("Unable to read cover")
                            val code = DsreNativeEncoder.remuxMetadata(inputTemp.absolutePath, outputTemp.absolutePath,
                                title, artist, album, albumArtist, composer, genre, date, track, disc, comment, copyright, lyrics,
                                coverMode, coverTemp?.absolutePath.orEmpty())
                            if (code < 0) throw IOException(DsreNativeEncoder.lastError().ifBlank { "Metadata remux failed: $code" })
                            app.contentResolver.openOutputStream(output.uri, "w")?.use { dst -> outputTemp.inputStream().use { it.copyTo(dst) } } ?: throw IOException("Unable to save output")
                        } catch (e: Throwable) { runCatching { app.contentResolver.delete(output.uri, null, null) }; throw e }
                        finally { inputTemp.delete(); outputTemp.delete(); coverTemp?.delete() }
                    }
                }.onSuccess { message = tr("メタデータ編集が完了しました", "Metadata editing completed") }
                 .onFailure { error = it.message ?: it.javaClass.simpleName }
                running = false
            }
        }, Modifier.fillMaxWidth(), enabled = !running && inputUri != null && outputTree.isNotBlank()) { Text(tr("新しいファイルへ保存", "Save as new file")) }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
