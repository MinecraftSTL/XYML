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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Stable localized text for the theme-pack management surface.
///
/// @param title section title
/// @param searchLabel search field label and placeholder
/// @param refreshTooltip refresh command tooltip
/// @param importTooltip import command tooltip
/// @param applyTooltip apply command tooltip
/// @param locateTooltip locate command tooltip
/// @param deleteTooltip delete command tooltip
/// @param builtInLabel embedded origin label
/// @param installedLabel installed origin label
/// @param appliedLabel active theme label
/// @param loadingText inventory loading status
/// @param importingText import status
/// @param applyingText application status
/// @param deletingText deletion status
/// @param locatingText directory opening status
/// @param emptyText empty inventory status
/// @param noResultsText empty search result status
/// @param countFormat ready item-count format with one integer placeholder
/// @param failureFormat failure format with one string placeholder
/// @param confirmDeleteTitle deletion confirmation title
/// @param confirmDeleteFormat deletion confirmation format with one string placeholder
/// @param chooserTitle import chooser title
/// @param chooserFilter import chooser extension description
@NotNullByDefault
public record ThemePackManagementStrings(
        String title,
        String searchLabel,
        String refreshTooltip,
        String importTooltip,
        String applyTooltip,
        String locateTooltip,
        String deleteTooltip,
        String builtInLabel,
        String installedLabel,
        String appliedLabel,
        String loadingText,
        String importingText,
        String applyingText,
        String deletingText,
        String locatingText,
        String emptyText,
        String noResultsText,
        String countFormat,
        String failureFormat,
        String confirmDeleteTitle,
        String confirmDeleteFormat,
        String chooserTitle,
        String chooserFilter) {
    /// Rejects missing strings and invalid status formats.
    public ThemePackManagementStrings {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(searchLabel, "searchLabel");
        Objects.requireNonNull(refreshTooltip, "refreshTooltip");
        Objects.requireNonNull(importTooltip, "importTooltip");
        Objects.requireNonNull(applyTooltip, "applyTooltip");
        Objects.requireNonNull(locateTooltip, "locateTooltip");
        Objects.requireNonNull(deleteTooltip, "deleteTooltip");
        Objects.requireNonNull(builtInLabel, "builtInLabel");
        Objects.requireNonNull(installedLabel, "installedLabel");
        Objects.requireNonNull(appliedLabel, "appliedLabel");
        Objects.requireNonNull(loadingText, "loadingText");
        Objects.requireNonNull(importingText, "importingText");
        Objects.requireNonNull(applyingText, "applyingText");
        Objects.requireNonNull(deletingText, "deletingText");
        Objects.requireNonNull(locatingText, "locatingText");
        Objects.requireNonNull(emptyText, "emptyText");
        Objects.requireNonNull(noResultsText, "noResultsText");
        requireSinglePlaceholder(countFormat, "countFormat");
        requireSinglePlaceholder(failureFormat, "failureFormat");
        Objects.requireNonNull(confirmDeleteTitle, "confirmDeleteTitle");
        requireSinglePlaceholder(confirmDeleteFormat, "confirmDeleteFormat");
        Objects.requireNonNull(chooserTitle, "chooserTitle");
        Objects.requireNonNull(chooserFilter, "chooserFilter");
    }

    /// Returns concise English text for production fallback and tests.
    ///
    /// @return English strings
    public static ThemePackManagementStrings english() {
        return new ThemePackManagementStrings(
                "Theme packs",
                "Search themes",
                "Refresh theme packs",
                "Import a local theme pack",
                "Apply selected theme",
                "Show installed theme pack",
                "Delete installed theme pack",
                "Built in",
                "Installed",
                "Applied",
                "Loading theme packs...",
                "Importing theme pack...",
                "Applying theme...",
                "Deleting theme pack...",
                "Opening theme pack...",
                "No theme packs are available.",
                "No themes match the search.",
                "%d themes",
                "Theme-pack operation failed: %s",
                "Delete theme pack",
                "Delete the installed package containing \"%s\"?",
                "Import theme pack",
                "HMCL theme packs (*.hmcl-theme)");
    }

    /// Returns concise Simplified Chinese text.
    ///
    /// @return Simplified Chinese strings
    public static ThemePackManagementStrings simplifiedChinese() {
        return new ThemePackManagementStrings(
                "主题包",
                "搜索主题",
                "刷新主题包",
                "导入本地主题包",
                "应用所选主题",
                "在文件管理器中显示主题包",
                "删除已安装主题包",
                "内置",
                "已安装",
                "已应用",
                "正在加载主题包...",
                "正在导入主题包...",
                "正在应用主题...",
                "正在删除主题包...",
                "正在打开主题包...",
                "没有可用的主题包。",
                "没有符合搜索条件的主题。",
                "%d 个主题",
                "主题包操作失败：%s",
                "删除主题包",
                "删除包含“%s”的已安装主题包？",
                "导入主题包",
                "HMCL 主题包 (*.hmcl-theme)");
    }

    /// Requires exactly one standard formatter placeholder.
    private static void requireSinglePlaceholder(String format, String name) {
        String checked = Objects.requireNonNull(format, name);
        int first = checked.indexOf('%');
        if (first < 0 || checked.indexOf('%', first + 1) >= 0) {
            throw new IllegalArgumentException(name + " must contain exactly one placeholder");
        }
    }
}
