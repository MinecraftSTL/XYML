/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;

import java.util.Objects;

/// Localizable visible and accessible text for complete launcher-background settings.
///
/// @param sectionTitle background section heading
/// @param sourceTypeLabel primary source field label
/// @param sourceOverrideLabel launcher-source override toggle label
/// @param defaultSourceLabel default local-discovery source label
/// @param builtinSourceLabel bundled-wallpaper source label
/// @param localSourceLabel local file-or-directory source label
/// @param networkSourceLabel network-image source label
/// @param paintSourceLabel solid-paint source label
/// @param themeColorSourceLabel active-theme-color source label
/// @param builtinSelectionLabel bundled-wallpaper selection label
/// @param localPathLabel local image file-or-directory path label
/// @param browseLabel local path browser action label
/// @param networkUrlLabel network image URL label
/// @param paintValueLabel solid-color or gradient expression label
/// @param chooseColorLabel color chooser action label
/// @param opacityLabel background opacity label
/// @param opacityOverrideLabel launcher-opacity override toggle label
/// @param networkCacheLabel network cache policy label
/// @param cacheEnabledLabel enabled cache policy label
/// @param cacheDisabledLabel disabled cache policy label
/// @param windowTransparentLabel native window transparency toggle label
@NotNullByDefault
public record AppearanceBackgroundStrings(
        String sectionTitle,
        String sourceTypeLabel,
        String sourceOverrideLabel,
        String defaultSourceLabel,
        String builtinSourceLabel,
        String localSourceLabel,
        String networkSourceLabel,
        String paintSourceLabel,
        String themeColorSourceLabel,
        String builtinSelectionLabel,
        String localPathLabel,
        String browseLabel,
        String networkUrlLabel,
        String paintValueLabel,
        String chooseColorLabel,
        String opacityLabel,
        String opacityOverrideLabel,
        String networkCacheLabel,
        String cacheEnabledLabel,
        String cacheDisabledLabel,
        String windowTransparentLabel) {
    /// Validates every background settings label.
    public AppearanceBackgroundStrings {
        Objects.requireNonNull(sectionTitle, "sectionTitle");
        Objects.requireNonNull(sourceTypeLabel, "sourceTypeLabel");
        Objects.requireNonNull(sourceOverrideLabel, "sourceOverrideLabel");
        Objects.requireNonNull(defaultSourceLabel, "defaultSourceLabel");
        Objects.requireNonNull(builtinSourceLabel, "builtinSourceLabel");
        Objects.requireNonNull(localSourceLabel, "localSourceLabel");
        Objects.requireNonNull(networkSourceLabel, "networkSourceLabel");
        Objects.requireNonNull(paintSourceLabel, "paintSourceLabel");
        Objects.requireNonNull(themeColorSourceLabel, "themeColorSourceLabel");
        Objects.requireNonNull(builtinSelectionLabel, "builtinSelectionLabel");
        Objects.requireNonNull(localPathLabel, "localPathLabel");
        Objects.requireNonNull(browseLabel, "browseLabel");
        Objects.requireNonNull(networkUrlLabel, "networkUrlLabel");
        Objects.requireNonNull(paintValueLabel, "paintValueLabel");
        Objects.requireNonNull(chooseColorLabel, "chooseColorLabel");
        Objects.requireNonNull(opacityLabel, "opacityLabel");
        Objects.requireNonNull(opacityOverrideLabel, "opacityOverrideLabel");
        Objects.requireNonNull(networkCacheLabel, "networkCacheLabel");
        Objects.requireNonNull(cacheEnabledLabel, "cacheEnabledLabel");
        Objects.requireNonNull(cacheDisabledLabel, "cacheDisabledLabel");
        Objects.requireNonNull(windowTransparentLabel, "windowTransparentLabel");
    }

    /// Returns the localized label for one primary background source.
    ///
    /// @param type primary background source
    /// @return localized source label
    public String sourceLabel(BackgroundType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case DEFAULT -> defaultSourceLabel;
            case BUILTIN -> builtinSourceLabel;
            case CUSTOM -> localSourceLabel;
            case NETWORK -> networkSourceLabel;
            case PAINT -> paintSourceLabel;
            case THEME_COLOR -> themeColorSourceLabel;
        };
    }

    /// Returns the localized label for one network image cache policy.
    ///
    /// @param policy network cache policy
    /// @return localized policy label
    public String networkCachePolicyLabel(NetworkBackgroundImageCachePolicy policy) {
        return switch (Objects.requireNonNull(policy, "policy")) {
            case ENABLED -> cacheEnabledLabel;
            case DISABLED -> cacheDisabledLabel;
        };
    }

    /// Returns complete built-in English labels for deterministic tests and toolkit previews.
    ///
    /// @return complete English background settings text
    public static AppearanceBackgroundStrings englishFallback() {
        return new AppearanceBackgroundStrings(
                "Background",
                "Background source",
                "Use launcher background setting",
                "Default",
                "Built-in",
                "Local image or folder",
                "From URL",
                "Solid color",
                "Follow theme color",
                "Built-in wallpaper",
                "Local image or folder",
                "Browse",
                "Image URL",
                "Solid color",
                "Choose color",
                "Opacity",
                "Use launcher opacity setting",
                "Cache URL images",
                "Enabled",
                "Disabled",
                "Transparent window");
    }

    /// Returns complete built-in Simplified Chinese labels for deterministic visual tests.
    ///
    /// @return complete Simplified Chinese background settings text
    public static AppearanceBackgroundStrings simplifiedChinese() {
        return new AppearanceBackgroundStrings(
                "背景",
                "背景来源",
                "使用启动器背景设置",
                "默认",
                "内置",
                "本地图片或文件夹",
                "网络图片",
                "纯色",
                "跟随主题色",
                "内置壁纸",
                "本地图片或文件夹",
                "浏览",
                "图片链接",
                "纯色",
                "选择颜色",
                "不透明度",
                "使用启动器不透明度设置",
                "缓存网络图片",
                "启用",
                "禁用",
                "窗口透明");
    }
}
