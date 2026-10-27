package com.uyatame.ucrt.appearance

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import java.util.Locale

private fun l(ja: String, en: String) = if (Locale.getDefault().language == "ja") ja else en

@Composable
fun AppearanceSettingsContent(vm: MainViewModel, s: AppSettings) {
    val appearance = remember(s.appearanceConfig) { appearanceFromJson(s.appearanceConfig) }
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
