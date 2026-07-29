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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.util.i18n.I18n;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Stable localized labels for the NBT editor without extending the shared localization catalog.
@NotNullByDefault
final class NBTEditorStrings {
    /// Shared English bundle for non-Chinese locales.
    private static final NBTEditorStrings ENGLISH = new NBTEditorStrings(
            "NBT Editor",
            "Back",
            "Open NBT file",
            "Reload from disk",
            "Save changes",
            "Choose an NBT file",
            "NBT files (*.dat, *.dat_old, *.mca, *.mcr)",
            "Discard unsaved changes?",
            "The unsaved changes to %s will be discarded.",
            "No NBT file open.",
            "Opening NBT data...",
            "Saving NBT data...",
            "Ready",
            "Modified",
            "The source changed outside the editor. Reload before saving again.",
            "NBT operation failed",
            "Name",
            "Type",
            "Children",
            "Value",
            "Apply",
            "This NBT type is read-only.",
            "Enter a valid value for the selected NBT type.",
            "%d entries");

    /// Shared Simplified Chinese bundle.
    private static final NBTEditorStrings SIMPLIFIED_CHINESE = new NBTEditorStrings(
            "NBT 编辑器",
            "返回",
            "打开 NBT 文件",
            "从磁盘重新加载",
            "保存修改",
            "选择 NBT 文件",
            "NBT 文件（*.dat、*.dat_old、*.mca、*.mcr）",
            "放弃未保存的修改？",
            "%s 的未保存修改将被放弃。",
            "未打开 NBT 文件。",
            "正在打开 NBT 数据...",
            "正在保存 NBT 数据...",
            "已就绪",
            "已修改",
            "源文件已在编辑器外部变化。请重新加载后再保存。",
            "NBT 操作失败",
            "名称",
            "类型",
            "子项",
            "值",
            "应用",
            "此 NBT 类型为只读。",
            "请输入符合所选 NBT 类型的值。",
            "%d 项");

    /// Visible page title.
    private final String title;

    /// Back-command accessible text.
    private final String backTooltip;

    /// Open-command accessible text.
    private final String openTooltip;

    /// Reload-command accessible text.
    private final String reloadTooltip;

    /// Save-command accessible text.
    private final String saveTooltip;

    /// Native file-chooser title.
    private final String chooserTitle;

    /// Native file-filter description.
    private final String fileFilter;

    /// Dirty-document confirmation title.
    private final String discardTitle;

    /// Dirty-document confirmation format.
    private final String discardMessageFormat;

    /// Empty-state instruction.
    private final String emptyText;

    /// Opening-state text.
    private final String openingText;

    /// Saving-state text.
    private final String savingText;

    /// Clean ready-state text.
    private final String readyText;

    /// Dirty ready-state text.
    private final String modifiedText;

    /// Stale-source conflict explanation.
    private final String conflictText;

    /// Generic operation failure label.
    private final String errorText;

    /// Selected-node name label.
    private final String nameLabel;

    /// Selected-node type label.
    private final String typeLabel;

    /// Selected-node child-count label.
    private final String childrenLabel;

    /// Scalar editor label.
    private final String valueLabel;

    /// Typed edit command text.
    private final String applyText;

    /// Read-only detail message.
    private final String readOnlyText;

    /// Invalid scalar input message.
    private final String invalidValueText;

    /// Direct-child count format.
    private final String entriesFormat;

    /// Creates one complete immutable bundle.
    private NBTEditorStrings(
            String title,
            String backTooltip,
            String openTooltip,
            String reloadTooltip,
            String saveTooltip,
            String chooserTitle,
            String fileFilter,
            String discardTitle,
            String discardMessageFormat,
            String emptyText,
            String openingText,
            String savingText,
            String readyText,
            String modifiedText,
            String conflictText,
            String errorText,
            String nameLabel,
            String typeLabel,
            String childrenLabel,
            String valueLabel,
            String applyText,
            String readOnlyText,
            String invalidValueText,
            String entriesFormat) {
        this.title = requireText(title, "title");
        this.backTooltip = requireText(backTooltip, "backTooltip");
        this.openTooltip = requireText(openTooltip, "openTooltip");
        this.reloadTooltip = requireText(reloadTooltip, "reloadTooltip");
        this.saveTooltip = requireText(saveTooltip, "saveTooltip");
        this.chooserTitle = requireText(chooserTitle, "chooserTitle");
        this.fileFilter = requireText(fileFilter, "fileFilter");
        this.discardTitle = requireText(discardTitle, "discardTitle");
        this.discardMessageFormat = requireText(discardMessageFormat, "discardMessageFormat");
        this.emptyText = requireText(emptyText, "emptyText");
        this.openingText = requireText(openingText, "openingText");
        this.savingText = requireText(savingText, "savingText");
        this.readyText = requireText(readyText, "readyText");
        this.modifiedText = requireText(modifiedText, "modifiedText");
        this.conflictText = requireText(conflictText, "conflictText");
        this.errorText = requireText(errorText, "errorText");
        this.nameLabel = requireText(nameLabel, "nameLabel");
        this.typeLabel = requireText(typeLabel, "typeLabel");
        this.childrenLabel = requireText(childrenLabel, "childrenLabel");
        this.valueLabel = requireText(valueLabel, "valueLabel");
        this.applyText = requireText(applyText, "applyText");
        this.readOnlyText = requireText(readOnlyText, "readOnlyText");
        this.invalidValueText = requireText(invalidValueText, "invalidValueText");
        this.entriesFormat = requireText(entriesFormat, "entriesFormat");
    }

    /// Chooses Chinese text for Chinese display locales and English otherwise.
    ///
    /// @return localized shared bundle
    static NBTEditorStrings localized() {
        return I18n.isUseChinese() ? SIMPLIFIED_CHINESE : ENGLISH;
    }

    /// Returns the deterministic English bundle for focused tests.
    ///
    /// @return shared English bundle
    static NBTEditorStrings english() {
        return ENGLISH;
    }

    /// Returns the deterministic Simplified Chinese bundle for focused tests.
    ///
    /// @return shared Simplified Chinese bundle
    static NBTEditorStrings simplifiedChinese() {
        return SIMPLIFIED_CHINESE;
    }

    /// Returns the visible title.
    String title() {
        return title;
    }

    /// Returns the back tooltip.
    String backTooltip() {
        return backTooltip;
    }

    /// Returns the open tooltip.
    String openTooltip() {
        return openTooltip;
    }

    /// Returns the reload tooltip.
    String reloadTooltip() {
        return reloadTooltip;
    }

    /// Returns the save tooltip.
    String saveTooltip() {
        return saveTooltip;
    }

    /// Returns the chooser title.
    String chooserTitle() {
        return chooserTitle;
    }

    /// Returns the file-filter description.
    String fileFilter() {
        return fileFilter;
    }

    /// Returns the discard confirmation title.
    String discardTitle() {
        return discardTitle;
    }

    /// Formats a dirty-document confirmation message.
    ///
    /// @param file dirty source
    /// @return localized confirmation text
    String discardMessage(Path file) {
        return String.format(Locale.ROOT, discardMessageFormat, Objects.requireNonNull(file, "file"));
    }

    /// Returns the empty-state instruction.
    String emptyText() {
        return emptyText;
    }

    /// Returns the opening-state text.
    String openingText() {
        return openingText;
    }

    /// Returns the saving-state text.
    String savingText() {
        return savingText;
    }

    /// Returns the clean ready-state text.
    String readyText() {
        return readyText;
    }

    /// Returns the dirty-state text.
    String modifiedText() {
        return modifiedText;
    }

    /// Returns the stale-source explanation.
    String conflictText() {
        return conflictText;
    }

    /// Returns the generic failure label.
    String errorText() {
        return errorText;
    }

    /// Returns the name detail label.
    String nameLabel() {
        return nameLabel;
    }

    /// Returns the type detail label.
    String typeLabel() {
        return typeLabel;
    }

    /// Returns the child-count detail label.
    String childrenLabel() {
        return childrenLabel;
    }

    /// Returns the value editor label.
    String valueLabel() {
        return valueLabel;
    }

    /// Returns the edit command text.
    String applyText() {
        return applyText;
    }

    /// Returns the read-only detail text.
    String readOnlyText() {
        return readOnlyText;
    }

    /// Returns the invalid-value message.
    String invalidValueText() {
        return invalidValueText;
    }

    /// Formats one direct-child count.
    ///
    /// @param count non-negative child count
    /// @return localized count text
    String entries(int count) {
        return String.format(Locale.ROOT, entriesFormat, count);
    }

    /// Rejects missing or blank bundle entries.
    ///
    /// @param value candidate label
    /// @param name logical field name
    /// @return validated label
    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
