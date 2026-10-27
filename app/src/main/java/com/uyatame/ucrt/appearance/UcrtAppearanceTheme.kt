package com.uyatame.ucrt.appearance

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

@Immutable
data class UcrtSurfaceToken(
    val containerColor: Color,
    val contentColor: Color,
    val alpha: Float,
    val blurRadius: Dp,
    val borderColor: Color,
    val borderWidth: Dp,
    val cornerRadius: Dp,
    val highlightAlpha: Float,
    val shadowElevation: Dp,
)

@Immutable
data class UcrtMotionToken(
    val enabled: Boolean,
    val durationScale: Float,
)

@Immutable
data class UcrtVisualTokens(
    val background: UcrtSurfaceToken,
    val card: UcrtSurfaceToken,
    val listItem: UcrtSurfaceToken,
    val controls: UcrtSurfaceToken,
    val overlay: UcrtSurfaceToken,
    val navigation: UcrtSurfaceToken,
    val player: UcrtSurfaceToken,
    val visualization: UcrtSurfaceToken,
    val motion: UcrtMotionToken,
) {
    companion object {
        fun standard(): UcrtVisualTokens {
            val fallback = UcrtSurfaceToken(
                containerColor = Color.Unspecified,
                contentColor = Color.Unspecified,
                alpha = 1f,
                blurRadius = 0.dp,
                borderColor = Color.Transparent,
                borderWidth = 0.dp,
                cornerRadius = 12.dp,
                highlightAlpha = 0f,
                shadowElevation = 0.dp,
            )
            return UcrtVisualTokens(fallback, fallback, fallback, fallback, fallback, fallback, fallback, fallback, UcrtMotionToken(true, 1f))
        }
    }
}

val LocalUcrtVisualTokens = staticCompositionLocalOf { UcrtVisualTokens.standard() }
val UcrtVisuals: UcrtVisualTokens
    @Composable get() = LocalUcrtVisualTokens.current

enum class UcrtSurfaceRole {
    BACKGROUND, CARD, LIST_ITEM, CONTROLS, OVERLAY, NAVIGATION, PLAYER, VISUALIZATION,
}

private fun UcrtVisualTokens.token(role: UcrtSurfaceRole): UcrtSurfaceToken = when (role) {
    UcrtSurfaceRole.BACKGROUND -> background
    UcrtSurfaceRole.CARD -> card
    UcrtSurfaceRole.LIST_ITEM -> listItem
    UcrtSurfaceRole.CONTROLS -> controls
    UcrtSurfaceRole.OVERLAY -> overlay
    UcrtSurfaceRole.NAVIGATION -> navigation
    UcrtSurfaceRole.PLAYER -> player
    UcrtSurfaceRole.VISUALIZATION -> visualization
}

@Composable
fun ProvideUcrtAppearance(
    appearanceJson: String,
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configured = appearanceFromJson(appearanceJson)
    val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    val effective = resolveEffectiveAppearance(configured, powerSave)
    val glassScheme = resolveGlassColorScheme(effective, colorScheme)
    val tokens = resolveVisualTokens(effective, glassScheme, powerSave = false)
    val baseTypography = MaterialTheme.typography
    val baseShapes = MaterialTheme.shapes
    val corner = when (effective.common.cornerLevel) {
        CornerLevel.SUBTLE -> 8.dp
        CornerLevel.STANDARD -> 14.dp
        CornerLevel.LARGE -> 22.dp
    }
    val glassShapes = baseShapes.copy(
        extraSmall = RoundedCornerShape((corner.value * 0.55f).dp),
        small = RoundedCornerShape((corner.value * 0.72f).dp),
        medium = RoundedCornerShape(corner),
        large = RoundedCornerShape((corner.value * 1.25f).dp),
        extraLarge = RoundedCornerShape((corner.value * 1.55f).dp),
    )
    MaterialTheme(
        colorScheme = glassScheme,
        typography = baseTypography,
        shapes = glassShapes,
    ) {
        CompositionLocalProvider(
            LocalUcrtVisualTokens provides tokens,
            LocalContentColor provides glassScheme.onBackground,
            content = content,
        )
    }
}

private fun resolveEffectiveAppearance(
    configured: AppearanceSettings,
    powerSave: Boolean,
): AppearanceSettings {
    var settings = configured.normalized()
    if (!powerSave) return settings
    settings = when (settings.performance.powerSaveBehavior) {
        PowerSaveBehavior.KEEP -> settings
        PowerSaveBehavior.DISABLE_BLUR -> settings.copy(common = settings.common.copy(blurLevel = BlurLevel.OFF))
        PowerSaveBehavior.LIGHTWEIGHT_GLASS -> settings.selectPreset(AppearancePreset.LIGHTWEIGHT_GLASS)
    }
    return settings
}

private fun resolveGlassColorScheme(
    settings: AppearanceSettings,
    base: ColorScheme,
): ColorScheme {
    val common = settings.common
    fun alpha(percent: Int, minimum: Float = 0.10f): Float =
        (1f - percent.coerceIn(0, 90) / 100f).coerceIn(minimum, 1f)

    fun componentAlpha(group: ComponentGroup, fallback: Int, minimum: Float = 0.10f): Float {
        val override = settings.components.get(group).takeIf { it.enabled }
        return alpha(override?.transparency ?: fallback, minimum)
    }

    val backgroundAlpha = alpha(common.backgroundTransparency)
    val surfaceAlpha = alpha(common.surfaceTransparency)
    val popupAlpha = alpha(common.popupTransparency.coerceAtMost(55), 0.45f)
    val controlAlpha = componentAlpha(ComponentGroup.CONTROLS, common.surfaceTransparency, 0.30f)
    val cardAlpha = componentAlpha(ComponentGroup.CARD_LIST, common.surfaceTransparency)
    val navigationAlpha = componentAlpha(ComponentGroup.NAVIGATION, common.surfaceTransparency, 0.30f)
    val playerAlpha = componentAlpha(ComponentGroup.PLAYER, common.surfaceTransparency, 0.30f)
    val overlayAlpha = componentAlpha(ComponentGroup.OVERLAYS, common.popupTransparency.coerceAtMost(55), 0.45f)
    val visualizationAlpha = componentAlpha(ComponentGroup.VISUALIZATION, common.surfaceTransparency)

    return base.copy(
        background = base.background.copy(alpha = backgroundAlpha),
        surface = base.surface.copy(alpha = surfaceAlpha),
        surfaceVariant = base.surfaceVariant.copy(alpha = cardAlpha),
        surfaceDim = base.surfaceDim.copy(alpha = surfaceAlpha),
        surfaceBright = base.surfaceBright.copy(alpha = surfaceAlpha),
        surfaceContainerLowest = base.surfaceContainerLowest.copy(alpha = backgroundAlpha),
        surfaceContainerLow = base.surfaceContainerLow.copy(alpha = visualizationAlpha),
        surfaceContainer = base.surfaceContainer.copy(alpha = cardAlpha),
        surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = playerAlpha),
        surfaceContainerHighest = base.surfaceContainerHighest.copy(alpha = overlayAlpha),
        primaryContainer = base.primaryContainer.copy(alpha = controlAlpha),
        secondaryContainer = base.secondaryContainer.copy(alpha = controlAlpha),
        tertiaryContainer = base.tertiaryContainer.copy(alpha = controlAlpha),
        inverseSurface = base.inverseSurface.copy(alpha = popupAlpha),
        scrim = base.scrim.copy(alpha = when (common.depthLevel) {
            DepthLevel.FLAT -> 0.20f
            DepthLevel.SUBTLE -> 0.28f
            DepthLevel.STANDARD -> 0.36f
            DepthLevel.STRONG -> 0.46f
        }),
        surfaceTint = base.surfaceTint.copy(alpha = navigationAlpha),
    )
}

fun resolveVisualTokens(
    configured: AppearanceSettings,
    colors: ColorScheme,
    powerSave: Boolean,
): UcrtVisualTokens {
    val settings = resolveEffectiveAppearance(configured, powerSave)
    val common = settings.common
    fun make(
        group: ComponentGroup?,
        baseColor: Color,
        defaultTransparency: Int,
        popup: Boolean = false,
    ): UcrtSurfaceToken {
        val override = group?.let { settings.components.get(it) }?.takeIf { it.enabled }
        val transparency = (override?.transparency ?: defaultTransparency).coerceIn(0, if (popup) 55 else 90)
        val blur = override?.blurLevel ?: common.blurLevel
        val border = override?.borderLevel ?: common.borderLevel
        val corner = override?.cornerLevel ?: common.cornerLevel
        val gloss = override?.glossLevel ?: common.glossLevel
        val alpha = (1f - transparency / 100f).coerceIn(if (popup) 0.45f else 0.1f, 1f)
        return UcrtSurfaceToken(
            containerColor = baseColor.copy(alpha = alpha),
            contentColor = colors.onSurface,
            alpha = alpha,
            blurRadius = when (blur) { BlurLevel.OFF -> 0.dp; BlurLevel.WEAK -> 8.dp; BlurLevel.MEDIUM -> 18.dp; BlurLevel.STRONG -> 32.dp },
            borderColor = when (border) {
                BorderLevel.NONE -> Color.Transparent
                BorderLevel.SUBTLE -> lerp(colors.outlineVariant, Color.White, 0.12f).copy(alpha = 0.34f)
                BorderLevel.STANDARD -> lerp(colors.outline, Color.White, 0.18f).copy(alpha = 0.52f)
                BorderLevel.STRONG -> lerp(colors.primary, Color.White, 0.22f).copy(alpha = 0.72f)
            },
            borderWidth = when (border) { BorderLevel.NONE -> 0.dp; BorderLevel.SUBTLE -> 0.5.dp; BorderLevel.STANDARD -> 1.dp; BorderLevel.STRONG -> 1.5.dp },
            cornerRadius = when (corner) { CornerLevel.SUBTLE -> 8.dp; CornerLevel.STANDARD -> 14.dp; CornerLevel.LARGE -> 22.dp },
            highlightAlpha = when (gloss) { GlossLevel.OFF -> 0f; GlossLevel.SUBTLE -> 0.10f; GlossLevel.STANDARD -> 0.19f },
            shadowElevation = when (common.depthLevel) { DepthLevel.FLAT -> 0.dp; DepthLevel.SUBTLE -> 1.dp; DepthLevel.STANDARD -> 3.dp; DepthLevel.STRONG -> 7.dp },
        )
    }
    val background = make(null, colors.background, common.backgroundTransparency)
    val card = make(ComponentGroup.CARD_LIST, colors.surfaceContainer, common.surfaceTransparency)
    val list = make(ComponentGroup.CARD_LIST, colors.surfaceContainerLow, common.surfaceTransparency)
    val controls = make(ComponentGroup.CONTROLS, colors.secondaryContainer, common.surfaceTransparency)
    val overlay = make(ComponentGroup.OVERLAYS, colors.surfaceContainerHigh, common.popupTransparency, popup = true)
    val navigation = make(ComponentGroup.NAVIGATION, colors.surfaceContainer, common.surfaceTransparency)
    val player = make(ComponentGroup.PLAYER, colors.surfaceContainerHigh, common.surfaceTransparency)
    val visualization = make(ComponentGroup.VISUALIZATION, colors.surfaceContainerLow, common.surfaceTransparency)
    val motion = when (settings.performance.animationLevel) {
        AnimationLevel.OFF -> UcrtMotionToken(false, 0f)
        AnimationLevel.LIGHT -> UcrtMotionToken(true, 0.65f)
        AnimationLevel.STANDARD -> UcrtMotionToken(true, 1f)
    }
    return UcrtVisualTokens(background, card, list, controls, overlay, navigation, player, visualization, motion)
}

@Composable
fun UcrtSurface(
    modifier: Modifier = Modifier,
    role: UcrtSurfaceRole = UcrtSurfaceRole.CARD,
    content: @Composable BoxScope.() -> Unit,
) {
    val token = UcrtVisuals.token(role)
    val shape = RoundedCornerShape(token.cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = token.containerColor,
        contentColor = token.contentColor,
        border = token.borderWidth.takeIf { it > 0.dp }?.let { BorderStroke(it, token.borderColor) },
        shadowElevation = token.shadowElevation,
    ) {
        Box(Modifier.clip(shape)) {
            if (role == UcrtSurfaceRole.BACKGROUND && token.highlightAlpha > 0f) {
                Box(
                    Modifier.size(360.dp).offset(x = (-80).dp, y = (-120).dp).blur(token.blurRadius.coerceAtLeast(24.dp))
                        .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = token.highlightAlpha * 1.5f), Color.Transparent)))
                )
                Box(
                    Modifier.size(320.dp).align(androidx.compose.ui.Alignment.BottomEnd).offset(x = 90.dp, y = 100.dp)
                        .blur(token.blurRadius.coerceAtLeast(24.dp))
                        .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.tertiary.copy(alpha = token.highlightAlpha), Color.Transparent)))
                )
            }
            content()
            if (token.highlightAlpha > 0f) {
                Box(
                    Modifier.matchParentSize().blur(token.blurRadius / 4).background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = token.highlightAlpha),
                                Color.Transparent,
                                Color.White.copy(alpha = token.highlightAlpha * 0.25f),
                            )
                        )
                    )
                )
            }
        }
    }
}

@Composable
fun UcrtBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    UcrtSurface(modifier = modifier.fillMaxSize(), role = UcrtSurfaceRole.BACKGROUND, content = content)
}
