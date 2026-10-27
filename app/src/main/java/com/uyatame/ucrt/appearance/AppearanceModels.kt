package com.uyatame.ucrt.appearance

import org.json.JSONObject

enum class AppearancePreset { STANDARD, TRANSLUCENT, LIGHTWEIGHT_GLASS, LIQUID_GLASS, CUSTOM }
enum class BlurLevel { OFF, WEAK, MEDIUM, STRONG }
enum class BorderLevel { NONE, SUBTLE, STANDARD, STRONG }
enum class GlossLevel { OFF, SUBTLE, STANDARD }
enum class CornerLevel { SUBTLE, STANDARD, LARGE }
enum class DepthLevel { FLAT, SUBTLE, STANDARD, STRONG }
enum class AnimationLevel { OFF, LIGHT, STANDARD }
enum class PowerSaveBehavior { KEEP, DISABLE_BLUR, LIGHTWEIGHT_GLASS }
enum class ComponentGroup { CARD_LIST, CONTROLS, OVERLAYS, NAVIGATION, PLAYER, VISUALIZATION }

data class CommonAppearanceSettings(
    val backgroundTransparency: Int = 0,
    val surfaceTransparency: Int = 0,
    val popupTransparency: Int = 0,
    val blurLevel: BlurLevel = BlurLevel.OFF,
    val borderLevel: BorderLevel = BorderLevel.SUBTLE,
    val glossLevel: GlossLevel = GlossLevel.OFF,
    val cornerLevel: CornerLevel = CornerLevel.STANDARD,
    val depthLevel: DepthLevel = DepthLevel.STANDARD,
)

data class AppearancePerformanceSettings(
    val animationLevel: AnimationLevel = AnimationLevel.STANDARD,
    val powerSaveBehavior: PowerSaveBehavior = PowerSaveBehavior.DISABLE_BLUR,
    val reduceBlurWhileScrolling: Boolean = true,
    val disableBlurOnLowPerformance: Boolean = true,
)

data class ComponentStyleOverride(
    val enabled: Boolean = false,
    val transparency: Int? = null,
    val blurLevel: BlurLevel? = null,
    val borderLevel: BorderLevel? = null,
    val cornerLevel: CornerLevel? = null,
    val glossLevel: GlossLevel? = null,
)

data class ComponentAppearanceOverrides(
    val cardAndList: ComponentStyleOverride = ComponentStyleOverride(),
    val controls: ComponentStyleOverride = ComponentStyleOverride(),
    val overlays: ComponentStyleOverride = ComponentStyleOverride(),
    val navigation: ComponentStyleOverride = ComponentStyleOverride(),
    val player: ComponentStyleOverride = ComponentStyleOverride(),
    val visualization: ComponentStyleOverride = ComponentStyleOverride(),
) {
    fun get(group: ComponentGroup): ComponentStyleOverride = when (group) {
        ComponentGroup.CARD_LIST -> cardAndList
        ComponentGroup.CONTROLS -> controls
        ComponentGroup.OVERLAYS -> overlays
        ComponentGroup.NAVIGATION -> navigation
        ComponentGroup.PLAYER -> player
        ComponentGroup.VISUALIZATION -> visualization
    }

    fun with(group: ComponentGroup, value: ComponentStyleOverride): ComponentAppearanceOverrides = when (group) {
        ComponentGroup.CARD_LIST -> copy(cardAndList = value)
        ComponentGroup.CONTROLS -> copy(controls = value)
        ComponentGroup.OVERLAYS -> copy(overlays = value)
        ComponentGroup.NAVIGATION -> copy(navigation = value)
        ComponentGroup.PLAYER -> copy(player = value)
        ComponentGroup.VISUALIZATION -> copy(visualization = value)
    }
}

data class AppearanceSettings(
    val preset: AppearancePreset = AppearancePreset.STANDARD,
    val common: CommonAppearanceSettings = presetCommon(AppearancePreset.STANDARD),
    val components: ComponentAppearanceOverrides = ComponentAppearanceOverrides(),
    val performance: AppearancePerformanceSettings = AppearancePerformanceSettings(),
) {
    fun normalized(): AppearanceSettings = copy(
        common = common.copy(
            backgroundTransparency = common.backgroundTransparency.coerceIn(0, 90),
            surfaceTransparency = common.surfaceTransparency.coerceIn(0, 90),
            popupTransparency = common.popupTransparency.coerceIn(0, 90),
        ),
        components = ComponentAppearanceOverrides(
            cardAndList = components.cardAndList.normalized(),
            controls = components.controls.normalized(),
            overlays = components.overlays.normalized(),
            navigation = components.navigation.normalized(),
            player = components.player.normalized(),
            visualization = components.visualization.normalized(),
        ),
    )
}

private fun ComponentStyleOverride.normalized() = copy(
    transparency = transparency?.coerceIn(0, 90),
)

fun presetCommon(preset: AppearancePreset): CommonAppearanceSettings = when (preset) {
    AppearancePreset.STANDARD -> CommonAppearanceSettings()
    AppearancePreset.TRANSLUCENT -> CommonAppearanceSettings(
        backgroundTransparency = 10, surfaceTransparency = 15, popupTransparency = 8,
        blurLevel = BlurLevel.OFF, borderLevel = BorderLevel.STANDARD,
        glossLevel = GlossLevel.SUBTLE, cornerLevel = CornerLevel.STANDARD,
        depthLevel = DepthLevel.SUBTLE,
    )
    AppearancePreset.LIGHTWEIGHT_GLASS -> CommonAppearanceSettings(
        backgroundTransparency = 16, surfaceTransparency = 22, popupTransparency = 12,
        blurLevel = BlurLevel.OFF, borderLevel = BorderLevel.STANDARD,
        glossLevel = GlossLevel.SUBTLE, cornerLevel = CornerLevel.LARGE,
        depthLevel = DepthLevel.SUBTLE,
    )
    AppearancePreset.LIQUID_GLASS -> CommonAppearanceSettings(
        backgroundTransparency = 20, surfaceTransparency = 28, popupTransparency = 16,
        blurLevel = BlurLevel.MEDIUM, borderLevel = BorderLevel.STANDARD,
        glossLevel = GlossLevel.STANDARD, cornerLevel = CornerLevel.LARGE,
        depthLevel = DepthLevel.STANDARD,
    )
    AppearancePreset.CUSTOM -> CommonAppearanceSettings()
}

fun AppearanceSettings.selectPreset(preset: AppearancePreset): AppearanceSettings = if (preset == AppearancePreset.CUSTOM) {
    copy(preset = AppearancePreset.CUSTOM)
} else {
    copy(preset = preset, common = presetCommon(preset), components = ComponentAppearanceOverrides()).normalized()
}

fun AppearanceSettings.toJson(): String = JSONObject().apply {
    put("version", 1)
    put("preset", preset.name)
    put("common", common.toJson())
    put("components", JSONObject().apply {
        ComponentGroup.entries.forEach { put(it.name, components.get(it).toJson()) }
    })
    put("performance", JSONObject().apply {
        put("animation", performance.animationLevel.name)
        put("powerSave", performance.powerSaveBehavior.name)
        put("reduceBlurWhileScrolling", performance.reduceBlurWhileScrolling)
        put("disableBlurOnLowPerformance", performance.disableBlurOnLowPerformance)
    })
}.toString()

private fun CommonAppearanceSettings.toJson() = JSONObject().apply {
    put("backgroundTransparency", backgroundTransparency)
    put("surfaceTransparency", surfaceTransparency)
    put("popupTransparency", popupTransparency)
    put("blur", blurLevel.name)
    put("border", borderLevel.name)
    put("gloss", glossLevel.name)
    put("corner", cornerLevel.name)
    put("depth", depthLevel.name)
}

private fun ComponentStyleOverride.toJson() = JSONObject().apply {
    put("enabled", enabled)
    transparency?.let { put("transparency", it) }
    blurLevel?.let { put("blur", it.name) }
    borderLevel?.let { put("border", it.name) }
    cornerLevel?.let { put("corner", it.name) }
    glossLevel?.let { put("gloss", it.name) }
}

fun appearanceFromJson(raw: String): AppearanceSettings = runCatching {
    if (raw.isBlank()) return@runCatching AppearanceSettings()
    val root = JSONObject(raw)
    val preset = enumValue(root.optString("preset"), AppearancePreset.STANDARD)
    val commonObject = root.optJSONObject("common") ?: JSONObject()
    val common = CommonAppearanceSettings(
        backgroundTransparency = commonObject.optInt("backgroundTransparency", 0),
        surfaceTransparency = commonObject.optInt("surfaceTransparency", 0),
        popupTransparency = commonObject.optInt("popupTransparency", 0),
        blurLevel = enumValue(commonObject.optString("blur"), BlurLevel.OFF),
        borderLevel = enumValue(commonObject.optString("border"), BorderLevel.SUBTLE),
        glossLevel = enumValue(commonObject.optString("gloss"), GlossLevel.OFF),
        cornerLevel = enumValue(commonObject.optString("corner"), CornerLevel.STANDARD),
        depthLevel = enumValue(commonObject.optString("depth"), DepthLevel.STANDARD),
    )
    val componentsObject = root.optJSONObject("components") ?: JSONObject()
    var components = ComponentAppearanceOverrides()
    ComponentGroup.entries.forEach { group ->
        val item = componentsObject.optJSONObject(group.name) ?: JSONObject()
        components = components.with(group, ComponentStyleOverride(
            enabled = item.optBoolean("enabled", false),
            transparency = if (item.has("transparency")) item.optInt("transparency") else null,
            blurLevel = optionalEnum(item, "blur", BlurLevel.entries),
            borderLevel = optionalEnum(item, "border", BorderLevel.entries),
            cornerLevel = optionalEnum(item, "corner", CornerLevel.entries),
            glossLevel = optionalEnum(item, "gloss", GlossLevel.entries),
        ))
    }
    val performanceObject = root.optJSONObject("performance") ?: JSONObject()
    AppearanceSettings(
        preset = preset,
        common = common,
        components = components,
        performance = AppearancePerformanceSettings(
            animationLevel = enumValue(performanceObject.optString("animation"), AnimationLevel.STANDARD),
            powerSaveBehavior = enumValue(performanceObject.optString("powerSave"), PowerSaveBehavior.DISABLE_BLUR),
            reduceBlurWhileScrolling = performanceObject.optBoolean("reduceBlurWhileScrolling", true),
            disableBlurOnLowPerformance = performanceObject.optBoolean("disableBlurOnLowPerformance", true),
        ),
    ).normalized()
}.getOrElse { AppearanceSettings() }

private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

private fun <T : Enum<T>> optionalEnum(json: JSONObject, key: String, values: List<T>): T? {
    if (!json.has(key)) return null
    val value = json.optString(key)
    return values.firstOrNull { it.name == value }
}
