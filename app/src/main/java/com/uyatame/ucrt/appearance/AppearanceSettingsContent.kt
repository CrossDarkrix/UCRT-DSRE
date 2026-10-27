package com.uyatame.ucrt.appearance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uyatame.ucrt.AppIcons
import com.uyatame.ucrt.ChoiceList
import com.uyatame.ucrt.IntSlider
import com.uyatame.ucrt.MainViewModel
import com.uyatame.ucrt.Section
import com.uyatame.ucrt.SwitchRow
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject

private fun l(ja: String, en: String) = if (Locale.getDefault().language == "ja") ja else en

@Composable
fun AppearanceSettingsContent(vm: MainViewModel, s: AppSettings) {
    val appearance = remember(s.appearanceConfig) { appearanceFromJson(s.appearanceConfig) }
    val context = LocalContext.current
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var transferError by remember { mutableStateOf<String?>(null) }
    val exportAppearance = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val payload = encodeAppearanceExport(s, appearance)
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(payload.toByteArray(StandardCharsets.UTF_8))
                } ?: throw IOException("Unable to open export destination")
            }.onSuccess {
                transferMessage = l("外観プリセットをエクスポートしました", "Appearance preset exported")
                transferError = null
            }.onFailure {
                transferError = l("外観プリセットのエクスポートに失敗しました", "Appearance preset export failed") + ": " +
                    (it.message ?: it.javaClass.simpleName)
            }
        }
    }
    val importAppearance = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: throw IOException("Unable to open appearance preset")
                decodeAppearanceExport(raw)
            }.onSuccess { imported ->
                vm.setAppearanceBundle(
                    imported.themeMode,
                    imported.dynamicColor,
                    imported.themeColor,
                    imported.textColor,
                    imported.appearance.toJson(),
                )
                transferMessage = l("外観プリセットをインポートしました", "Appearance preset imported")
                transferError = null
            }.onFailure {
                transferError = l("外観プリセットのインポートに失敗しました", "Appearance preset import failed") + ": " +
                    (it.message ?: it.javaClass.simpleName)
            }
        }
    }
    fun save(updated: AppearanceSettings) {
        vm.set(Keys.appearanceConfig, updated.normalized().toJson())
    }
    fun customize(block: (AppearanceSettings) -> AppearanceSettings) {
        save(block(appearance).copy(preset = AppearancePreset.CUSTOM))
    }

    Section(l("外観プレビュー", "Appearance preview")) {
        AppearancePreview()
    }

    Section(l("外観プリセット", "Appearance preset")) {
        val presets = listOf(
            AppearancePreset.STANDARD,
            AppearancePreset.TRANSLUCENT,
            AppearancePreset.LIGHTWEIGHT_GLASS,
            AppearancePreset.LIQUID_GLASS,
            AppearancePreset.CUSTOM,
        )
        val names = listOf(
            l("標準", "Standard"),
            l("半透明", "Translucent"),
            l("軽量Glass", "Lightweight Glass"),
            "Liquid Glass",
            l("カスタム", "Custom"),
        )
        ChoiceList(
            l("プリセット", "Preset"),
            names,
            presets.indexOf(appearance.preset).coerceAtLeast(0),
        ) { index ->
            val selected = presets[index]
            if (selected != AppearancePreset.CUSTOM) save(appearance.selectPreset(selected))
        }
        Text(
            l(
                "個別値を変更すると自動的にカスタムへ切り替わります。標準へ戻せるため、Glass効果に問題がある場合も復旧できます。",
                "Changing an individual value switches to Custom automatically. Standard remains available as a safe fallback.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Section(l("外観プリセットの共有", "Appearance preset transfer")) {
        Text(
            l("外観設定、テーマモード、ダイナミックカラー、テーマ色、文字色をJSONで保存・復元します。",
                "Save and restore appearance settings, theme mode, dynamic color, theme color, and text color as JSON."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportAppearance.launch("UCRT-Appearance-Preset.json") },
                modifier = Modifier.weight(1f),
            ) { Text(l("エクスポート", "Export")) }
            OutlinedButton(
                onClick = { importAppearance.launch(arrayOf("application/json", "text/json", "text/plain")) },
                modifier = Modifier.weight(1f),
            ) { Text(l("インポート", "Import")) }
        }
        transferMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        transferError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    Section(l("共通設定", "Common settings")) {
        IntSlider(l("背景透明度", "Background transparency"), appearance.common.backgroundTransparency, 0..90, { "$it%" }, null) { value ->
            customize { current -> current.copy(common = current.common.copy(backgroundTransparency = value)) }
        }
        IntSlider(l("Surface透明度", "Surface transparency"), appearance.common.surfaceTransparency, 0..90, { "$it%" }, null) {
            customize { current -> current.copy(common = current.common.copy(surfaceTransparency = it)) }
        }
        IntSlider(l("ポップアップ透明度", "Popup transparency"), appearance.common.popupTransparency, 0..55, { "$it%" }, l("可読性確保のため最大55%です。", "Limited to 55% to preserve readability.")) {
            customize { current -> current.copy(common = current.common.copy(popupTransparency = it)) }
        }
        EnumChoice(l("ぼかし", "Blur"), BlurLevel.entries, appearance.common.blurLevel, listOf(l("オフ", "Off"), l("弱", "Weak"), l("中", "Medium"), l("強", "Strong"))) {
            customize { current -> current.copy(common = current.common.copy(blurLevel = it)) }
        }
        EnumChoice(l("境界線", "Border"), BorderLevel.entries, appearance.common.borderLevel, listOf(l("なし", "None"), l("控えめ", "Subtle"), l("標準", "Standard"), l("強調", "Strong"))) {
            customize { current -> current.copy(common = current.common.copy(borderLevel = it)) }
        }
        EnumChoice(l("光沢", "Gloss"), GlossLevel.entries, appearance.common.glossLevel, listOf(l("オフ", "Off"), l("控えめ", "Subtle"), l("標準", "Standard"))) {
            customize { current -> current.copy(common = current.common.copy(glossLevel = it)) }
        }
        EnumChoice(l("角の丸み", "Corner radius"), CornerLevel.entries, appearance.common.cornerLevel, listOf(l("控えめ", "Subtle"), l("標準", "Standard"), l("大きめ", "Large"))) {
            customize { current -> current.copy(common = current.common.copy(cornerLevel = it)) }
        }
        EnumChoice(l("奥行き", "Depth"), DepthLevel.entries, appearance.common.depthLevel, listOf(l("フラット", "Flat"), l("控えめ", "Subtle"), l("標準", "Standard"), l("強調", "Strong"))) {
            customize { current -> current.copy(common = current.common.copy(depthLevel = it)) }
        }
    }

    Section(l("部品ごとの詳細設定", "Per-component settings")) {
        ComponentGroup.entries.forEach { group ->
            ComponentOverrideEditor(group, appearance) { updated -> save(updated.copy(preset = AppearancePreset.CUSTOM)) }
        }
    }

    Section(l("アニメーションとパフォーマンス", "Animation and performance")) {
        EnumChoice(l("アニメーション", "Animation"), AnimationLevel.entries, appearance.performance.animationLevel, listOf(l("なし", "Off"), l("軽量", "Light"), l("標準", "Standard"))) {
            customize { current -> current.copy(performance = current.performance.copy(animationLevel = it)) }
        }
        EnumChoice(l("省電力時の動作", "Power saver behavior"), PowerSaveBehavior.entries, appearance.performance.powerSaveBehavior, listOf(l("維持", "Keep effects"), l("ぼかしを無効化", "Disable blur"), l("軽量Glassへ変更", "Use Lightweight Glass"))) {
            customize { current -> current.copy(performance = current.performance.copy(powerSaveBehavior = it)) }
        }
        SwitchRow(l("スクロール中はぼかしを弱める", "Reduce blur while scrolling"), null, appearance.performance.reduceBlurWhileScrolling) {
            customize { current -> current.copy(performance = current.performance.copy(reduceBlurWhileScrolling = it)) }
        }
        SwitchRow(l("低性能時はぼかしを無効化", "Disable blur on low performance"), null, appearance.performance.disableBlurOnLowPerformance) {
            customize { current -> current.copy(performance = current.performance.copy(disableBlurOnLowPerformance = it)) }
        }
    }

    Button(
        onClick = { save(AppearanceSettings()) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(l("外観設定を初期化", "Reset appearance settings"))
    }
}

@Composable
private fun <T : Enum<T>> EnumChoice(title: String, values: List<T>, selected: T, labels: List<String>, onSelect: (T) -> Unit) {
    ChoiceList(title, labels, values.indexOf(selected).coerceAtLeast(0)) { onSelect(values[it]) }
}

@Composable
private fun ComponentOverrideEditor(
    group: ComponentGroup,
    appearance: AppearanceSettings,
    onChange: (AppearanceSettings) -> Unit,
) {
    val current = appearance.components.get(group)
    val title = when (group) {
        ComponentGroup.CARD_LIST -> l("カード・リスト", "Cards and lists")
        ComponentGroup.CONTROLS -> l("操作部品", "Controls")
        ComponentGroup.OVERLAYS -> l("オーバーレイ", "Overlays")
        ComponentGroup.NAVIGATION -> l("ナビゲーション", "Navigation")
        ComponentGroup.PLAYER -> l("再生画面", "Player")
        ComponentGroup.VISUALIZATION -> l("可視化パネル", "Visualization panels")
    }
    SwitchRow(title, l("共通設定を上書き", "Override common settings"), current.enabled) { enabled ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(enabled = enabled))))
    }
    if (!current.enabled) return
    IntSlider(l("$title の透明度", "$title transparency"), current.transparency ?: appearance.common.surfaceTransparency, 0..90, { "$it%" }, null) { value ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(transparency = value))))
    }
    EnumChoice(l("$title のぼかし", "$title blur"), BlurLevel.entries, current.blurLevel ?: appearance.common.blurLevel, listOf(l("オフ", "Off"), l("弱", "Weak"), l("中", "Medium"), l("強", "Strong"))) { value ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(blurLevel = value))))
    }
    EnumChoice(l("$title の境界線", "$title border"), BorderLevel.entries, current.borderLevel ?: appearance.common.borderLevel, listOf(l("なし", "None"), l("控えめ", "Subtle"), l("標準", "Standard"), l("強調", "Strong"))) { value ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(borderLevel = value))))
    }
    EnumChoice(l("$title の角丸", "$title corners"), CornerLevel.entries, current.cornerLevel ?: appearance.common.cornerLevel, listOf(l("控えめ", "Subtle"), l("標準", "Standard"), l("大きめ", "Large"))) { value ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(cornerLevel = value))))
    }
    EnumChoice(l("$title の光沢", "$title gloss"), GlossLevel.entries, current.glossLevel ?: appearance.common.glossLevel, listOf(l("オフ", "Off"), l("控えめ", "Subtle"), l("標準", "Standard"))) { value ->
        onChange(appearance.copy(components = appearance.components.with(group, current.copy(glossLevel = value))))
    }
}

@Composable
private fun AppearancePreview() {
    UcrtSurface(Modifier.fillMaxWidth(), UcrtSurfaceRole.CARD) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l("サンプル楽曲", "Sample track"), style = MaterialTheme.typography.titleMedium)
            Text(l("サンプルアーティスト", "Sample artist"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { 0.58f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton({}) { Icon(AppIcons.Pause, null) }
                IconButton({}) { Icon(Icons.Filled.PlayArrow, null) }
                IconButton({}) { Icon(AppIcons.SkipNext, null) }
            }
            UcrtSurface(Modifier.fillMaxWidth(), UcrtSurfaceRole.VISUALIZATION) {
                Column(Modifier.padding(12.dp)) {
                    Text(l("出力経路プレビュー", "Output path preview"), style = MaterialTheme.typography.labelLarge)
                    Text("FLAC  →  Upsampling  →  USB DAC", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


private data class ImportedAppearanceBundle(
    val themeMode: Int,
    val dynamicColor: Boolean,
    val themeColor: Int,
    val textColor: Int,
    val appearance: AppearanceSettings,
)

private fun encodeAppearanceExport(settings: AppSettings, appearance: AppearanceSettings): String = JSONObject()
    .put("schema", "ucrt-appearance-preset")
    .put("version", 1)
    .put("exportedBy", "UCRT")
    .put("theme", JSONObject()
        .put("themeMode", settings.themeMode.coerceIn(0, 2))
        .put("dynamicColor", settings.dynamicColor)
        .put("themeColor", settings.themeColor)
        .put("textColor", settings.textColor))
    .put("appearance", JSONObject(appearance.normalized().toJson()))
    .toString(2)

private fun decodeAppearanceExport(raw: String): ImportedAppearanceBundle {
    val root = JSONObject(raw)
    require(root.optString("schema") == "ucrt-appearance-preset") { "Unsupported appearance schema" }
    require(root.optInt("version", -1) == 1) { "Unsupported appearance version" }
    val theme = root.optJSONObject("theme") ?: throw IllegalArgumentException("Missing theme object")
    val appearanceJson = root.optJSONObject("appearance") ?: throw IllegalArgumentException("Missing appearance object")
    val themeMode = theme.getInt("themeMode")
    require(themeMode in 0..2) { "Invalid theme mode" }
    val dynamicColor = theme.getBoolean("dynamicColor")
    val themeColor = theme.getInt("themeColor")
    val textColor = theme.getInt("textColor")
    val appearance = appearanceFromJson(appearanceJson.toString()).normalized()
    return ImportedAppearanceBundle(themeMode, dynamicColor, themeColor, textColor, appearance)
}
