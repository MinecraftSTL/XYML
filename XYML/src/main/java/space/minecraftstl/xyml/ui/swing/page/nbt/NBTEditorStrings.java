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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.io.NBTReadIssue;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocaleUtils;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// Resource wrapper and deterministic locale injection point for the Swing NBT editor.
@NotNullByDefault
final class NBTEditorStrings {
    /// Shared English resources for deterministic tests and non-Chinese locales.
    private static final NBTEditorStrings ENGLISH =
            new NBTEditorStrings(SupportedLocale.getLocale(Locale.ENGLISH));

    /// Shared Simplified Chinese resources for deterministic tests.
    private static final NBTEditorStrings SIMPLIFIED_CHINESE =
            new NBTEditorStrings(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE));

    /// Shared Traditional Chinese resources for deterministic tests.
    private static final NBTEditorStrings TRADITIONAL_CHINESE =
            new NBTEditorStrings(SupportedLocale.getLocale(Locale.TRADITIONAL_CHINESE));

    /// Explicit resource locale used by every lookup.
    private final SupportedLocale locale;

    /// Creates one lightweight wrapper over an explicit resource locale.
    ///
    /// @param locale resource locale
    private NBTEditorStrings(SupportedLocale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /// Chooses resources matching the current display script.
    ///
    /// @return localized shared wrapper
    static NBTEditorStrings localized() {
        SupportedLocale current = I18n.getLocale();
        if (!LocaleUtils.isChinese(current.getDisplayLocale())) {
            return ENGLISH;
        }
        return "Hant".equals(LocaleUtils.getScript(current.getDisplayLocale()))
                ? TRADITIONAL_CHINESE
                : SIMPLIFIED_CHINESE;
    }

    /// Returns deterministic English resources.
    ///
    /// @return English wrapper
    static NBTEditorStrings english() {
        return ENGLISH;
    }

    /// Returns deterministic Simplified Chinese resources.
    ///
    /// @return Simplified Chinese wrapper
    static NBTEditorStrings simplifiedChinese() {
        return SIMPLIFIED_CHINESE;
    }

    /// Returns deterministic Traditional Chinese resources.
    ///
    /// @return Traditional Chinese wrapper
    static NBTEditorStrings traditionalChinese() {
        return TRADITIONAL_CHINESE;
    }

    /// Returns the editor title.
    String title() {
        return locale.i18n("swing.nbt_editor.title");
    }

    /// Returns the back command tooltip.
    String backTooltip() {
        return locale.i18n("swing.nbt_editor.back");
    }

    /// Returns the new-document command tooltip.
    String newTooltip() {
        return locale.i18n("swing.nbt_editor.new");
    }

    /// Returns the open command tooltip.
    String openTooltip() {
        return locale.i18n("swing.nbt_editor.open");
    }

    /// Returns the reload command tooltip.
    String reloadTooltip() {
        return locale.i18n("swing.nbt_editor.reload");
    }

    /// Returns the save command tooltip.
    String saveTooltip() {
        return locale.i18n("swing.nbt_editor.save");
    }

    /// Returns the undo command tooltip.
    String undoTooltip() {
        return locale.i18n("swing.nbt_editor.undo");
    }

    /// Returns the redo command tooltip.
    String redoTooltip() {
        return locale.i18n("swing.nbt_editor.redo");
    }

    /// Returns the add command text.
    String addText() {
        return locale.i18n("swing.nbt_editor.add");
    }

    /// Returns the copy command text.
    String copyText() {
        return locale.i18n("swing.nbt_editor.copy");
    }

    /// Returns the paste command text.
    String pasteText() {
        return locale.i18n("swing.nbt_editor.paste");
    }

    /// Returns the delete command text.
    String deleteText() {
        return locale.i18n("swing.nbt_editor.delete");
    }

    /// Returns the move-up command text.
    String moveUpText() {
        return locale.i18n("swing.nbt_editor.move_up");
    }

    /// Returns the move-down command text.
    String moveDownText() {
        return locale.i18n("swing.nbt_editor.move_down");
    }

    /// Returns the chooser title.
    String chooserTitle() {
        return locale.i18n("swing.nbt_editor.chooser_title");
    }

    /// Returns the new-document chooser title.
    String newChooserTitle() {
        return locale.i18n("swing.nbt_editor.new_chooser_title");
    }

    /// Returns the file-filter description.
    String fileFilter() {
        return locale.i18n("swing.nbt_editor.file_filter");
    }

    /// Returns the dirty-document confirmation title.
    String discardTitle() {
        return locale.i18n("swing.nbt_editor.discard_title");
    }

    /// Formats the dirty-document confirmation message.
    ///
    /// @param file dirty source
    /// @return localized confirmation text
    String discardMessage(Path file) {
        return locale.i18n("swing.nbt_editor.discard_message", Objects.requireNonNull(file, "file"));
    }

    /// Returns the destructive chunk-clear title.
    String clearChunkTitle() {
        return locale.i18n("swing.nbt_editor.clear_chunk_title");
    }

    /// Formats the destructive chunk-clear message.
    ///
    /// @param localIndex fixed region slot
    /// @return localized confirmation text
    String clearChunkMessage(int localIndex) {
        return locale.i18n("swing.nbt_editor.clear_chunk_message", localIndex);
    }

    /// Returns the warning shown when tolerant reading recovered a source without losing data.
    String recoveredReadWarning() {
        return locale.i18n("swing.nbt_editor.read_recovered_warning");
    }

    /// Returns the stronger warning shown when tolerant reading could not reconstruct all data.
    String partialReadWarning() {
        return locale.i18n("swing.nbt_editor.read_partial_warning");
    }

    /// Returns the warning shown when a supported XoyzNBT extension may not be readable by the official game.
    String extensionReadWarning() {
        return locale.i18n("swing.nbt_editor.read_extension_warning");
    }

    /// Returns the command which expands diagnostic details.
    String showReadDetailsText() {
        return locale.i18n("swing.nbt_editor.read_show_details");
    }

    /// Returns the command which collapses diagnostic details.
    String hideReadDetailsText() {
        return locale.i18n("swing.nbt_editor.read_hide_details");
    }

    /// Returns the label for the detected outer storage algorithm in diagnostics.
    String readDetailsEncodingLabel() {
        return locale.i18n("swing.nbt_editor.read_details_encoding");
    }

    /// Returns the label for strict-envelope validity in diagnostics.
    String readDetailsStrictLabel() {
        return locale.i18n("swing.nbt_editor.read_details_strict");
    }

    /// Formats one parser diagnostic without exposing library-local prose that may not match the UI locale.
    ///
    /// The stable code remains visible for support and log correlation.  Detailed parser text stays in the immutable
    /// report and is not copied into the localized Swing surface.
    ///
    /// @param issue immutable parser diagnostic
    /// @return localized diagnostic summary
    String readDetailsIssue(NBTReadIssue issue) {
        NBTReadIssue checked = Objects.requireNonNull(issue, "issue");
        String severity = switch (checked.severity()) {
            case INFORMATIONAL -> locale.i18n("swing.nbt_editor.read_issue_severity_informational");
            case RECOVERED -> locale.i18n("swing.nbt_editor.read_issue_severity_recovered");
            case PARTIAL_DATA_LOSS -> locale.i18n("swing.nbt_editor.read_issue_severity_partial");
            case ERROR -> locale.i18n("swing.nbt_editor.read_issue_severity_error");
        };
        return locale.i18n("swing.nbt_editor.read_details_issue", severity, checked.code());
    }

    /// Formats one region-slot storage profile for the diagnostic details view.
    ///
    /// @param x local chunk X coordinate
    /// @param z local chunk Z coordinate
    /// @param marker raw low-seven-bit compression marker
    /// @param external whether the slot uses an external companion
    /// @param occupied whether its location entry is occupied
    /// @return localized slot metadata
    String readDetailsRegionSlot(int x, int z, int marker, boolean external, boolean occupied) {
        return locale.i18n("swing.nbt_editor.read_details_region_slot", x, z, marker, external, occupied);
    }

    /// Returns the repair-save confirmation title.
    String repairSaveTitle() {
        return locale.i18n("swing.nbt_editor.repair_save_title");
    }

    /// Formats a repair-save confirmation message for a recovered source.
    ///
    /// @param file source being rewritten
    /// @return localized confirmation text
    String repairSaveMessage(Path file) {
        return locale.i18n("swing.nbt_editor.repair_save_message", Objects.requireNonNull(file, "file"));
    }

    /// Formats a repair-save confirmation message for a source with partial data loss.
    ///
    /// @param file source being rewritten
    /// @return localized confirmation text
    String partialRepairSaveMessage(Path file) {
        return locale.i18n("swing.nbt_editor.repair_save_partial_message", Objects.requireNonNull(file, "file"));
    }

    /// Returns the empty-state text.
    String emptyText() {
        return locale.i18n("swing.nbt_editor.empty");
    }

    /// Returns the opening-state text.
    String openingText() {
        return locale.i18n("swing.nbt_editor.opening");
    }

    /// Returns the transactional-edit state text.
    String editingText() {
        return locale.i18n("swing.nbt_editor.editing");
    }

    /// Returns the fatal-edit recovery text.
    String editUncertainText() {
        return locale.i18n("swing.nbt_editor.edit_uncertain");
    }

    /// Returns the saving-state text.
    String savingText() {
        return locale.i18n("swing.nbt_editor.saving");
    }

    /// Returns the clean ready-state text.
    String readyText() {
        return locale.i18n("swing.nbt_editor.ready");
    }

    /// Returns the dirty ready-state text.
    String modifiedText() {
        return locale.i18n("swing.nbt_editor.modified");
    }

    /// Returns the partial-save recovery text.
    String partialSaveText() {
        return locale.i18n("swing.nbt_editor.partial_save");
    }

    /// Returns the uncertain-commit recovery text.
    String commitUncertainText() {
        return locale.i18n("swing.nbt_editor.commit_uncertain");
    }

    /// Returns the generic operation-failure text.
    String errorText() {
        return locale.i18n("swing.nbt_editor.error");
    }

    /// Returns the name label.
    String nameLabel() {
        return locale.i18n("swing.nbt_editor.name");
    }

    /// Returns the type label.
    String typeLabel() {
        return locale.i18n("swing.nbt_editor.type");
    }

    /// Returns the child-count label.
    String childrenLabel() {
        return locale.i18n("swing.nbt_editor.children");
    }

    /// Returns the value label.
    String valueLabel() {
        return locale.i18n("swing.nbt_editor.value");
    }

    /// Returns the Minecraft formatting-preview toggle label.
    String formattingPreviewText() {
        return locale.i18n("swing.nbt_editor.formatting_preview");
    }

    /// Returns the numeric-radix selector's accessible label.
    String numberRadixText() {
        return locale.i18n("swing.nbt_editor.number_radix");
    }

    /// Returns the decimal-radix option.
    String decimalRadixText() {
        return locale.i18n("swing.nbt_editor.radix_decimal");
    }

    /// Returns the hexadecimal-radix option.
    String hexadecimalRadixText() {
        return locale.i18n("swing.nbt_editor.radix_hexadecimal");
    }

    /// Returns the primitive-array load-failure text.
    String arrayLoadFailedText() {
        return locale.i18n("swing.nbt_editor.array_load_failed");
    }

    /// Returns the apply command text.
    String applyText() {
        return locale.i18n("swing.nbt_editor.apply");
    }

    /// Returns the read-only selection text.
    String readOnlyText() {
        return locale.i18n("swing.nbt_editor.read_only");
    }

    /// Returns the invalid-edit text.
    String invalidValueText() {
        return locale.i18n("swing.nbt_editor.invalid_value");
    }

    /// Formats one direct-child count.
    ///
    /// @param count non-negative child count
    /// @return localized count text
    String entries(int count) {
        return locale.i18n("swing.nbt_editor.entries", count);
    }

    /// Formats one scalar tree row.
    ///
    /// @param name contextual row name
    /// @param value scalar value
    /// @return localized complete row text
    String treeValue(String name, String value) {
        return locale.i18n(
                "swing.nbt_editor.tree_value",
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(value, "value"));
    }

    /// Formats one container tree row.
    ///
    /// @param name contextual row name
    /// @param count direct-child count
    /// @return localized complete row text
    String treeEntries(String name, int count) {
        return locale.i18n(
                "swing.nbt_editor.tree_entries",
                Objects.requireNonNull(name, "name"),
                count);
    }

    /// Returns the structured-form tab title.
    String structuredTab() {
        return locale.i18n("swing.nbt_editor.structured_tab");
    }

    /// Returns the subtree-SNBT tab title.
    String snbtTab() {
        return locale.i18n("swing.nbt_editor.snbt_tab");
    }

    /// Returns the asynchronous subtree-loading status.
    String loadingSnbtText() {
        return locale.i18n("swing.nbt_editor.loading_snbt");
    }

    /// Returns the asynchronous primitive-array loading status.
    String loadingValueText() {
        return locale.i18n("swing.nbt_editor.loading_value");
    }

    /// Returns the subtree-load failure status.
    String snbtLoadFailedText() {
        return locale.i18n("swing.nbt_editor.snbt_load_failed");
    }

    /// Returns the SNBT replacement command text.
    String replaceText() {
        return locale.i18n("swing.nbt_editor.replace");
    }

    /// Returns the new-tag dialog title.
    String addTitle() {
        return locale.i18n("swing.nbt_editor.add_title");
    }

    /// Returns the insert command text.
    String insertText() {
        return locale.i18n("swing.nbt_editor.insert");
    }

    /// Returns the cancel command text.
    String cancelText() {
        return locale.i18n("swing.nbt_editor.cancel");
    }

    /// Returns the empty-List element-type label.
    String listTypeLabel() {
        return locale.i18n("swing.nbt_editor.list_type");
    }

    /// Returns the TAG_End display label.
    String tagEndText() {
        return locale.i18n("swing.nbt_editor.tag_end");
    }

    /// Returns the copy-success status text.
    String copiedText() {
        return locale.i18n("swing.nbt_editor.copied");
    }

    /// Returns the localized display name for one immutable tree row.
    ///
    /// @param node tree row
    /// @return localized contextual name
    String nodeName(NBTEditorTreeNode node) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        var segments = selected.address().segments();
        if (!segments.isEmpty()) {
            NBTAddress.Segment segment = segments.get(segments.size() - 1);
            if (segment instanceof NBTAddress.RegionChunkSegment chunk) {
                int localIndex = chunk.localIndex();
                return locale.i18n(
                        "swing.nbt_editor.chunk",
                        localIndex & 31,
                        localIndex >>> 5);
            }
            if (segment instanceof NBTAddress.ChunkRootSegment) {
                return locale.i18n("swing.nbt_editor.chunk_root");
            }
        }
        return selected.presentation().displayName();
    }

    /// Returns a localized type label for one immutable tree row.
    ///
    /// Standard tag names retain their format identity while Region and Chunk container names use
    /// launcher resources instead of exposing internal enum constants.
    ///
    /// @param node tree row
    /// @return localized or format-defined type label
    String nodeType(NBTEditorTreeNode node) {
        NBTEditorTreeNode selected = Objects.requireNonNull(node, "node");
        @Nullable TagType<?> tagType = selected.node().getType();
        if (tagType != null) {
            return tagType.name();
        }
        NBTNodeType nodeType = selected.presentation().type();
        if (nodeType == NBTNodeType.CHUNK_REGION) {
            return locale.i18n("swing.nbt_editor.type_region");
        }
        if (nodeType == NBTNodeType.CHUNK) {
            return locale.i18n("swing.nbt_editor.type_chunk");
        }
        return locale.i18n("swing.nbt_editor.type_unknown");
    }

    /// Returns the stable default Compound child name.
    String defaultTagName() {
        return locale.i18n("swing.nbt_editor.default_tag_name");
    }

    /// Returns a concise localized rejection for one stable editor reason.
    ///
    /// @param reason stable library reason, or `null` for parser and UI-state failures
    /// @return localized user-facing rejection
    String editFailureText(@Nullable NBTEditException.Reason reason) {
        if (reason == null) {
            return invalidValueText();
        }
        return switch (reason) {
            case STALE_NODE, FOREIGN_NODE, NOT_FOUND ->
                locale.i18n("swing.nbt_editor.edit_stale");
            case INVALID_NAME, DUPLICATE_NAME ->
                locale.i18n("swing.nbt_editor.edit_name");
            case TYPE_MISMATCH -> locale.i18n("swing.nbt_editor.edit_type");
            case CYCLE -> locale.i18n("swing.nbt_editor.edit_cycle");
            case ROOT_OPERATION -> locale.i18n("swing.nbt_editor.edit_root");
            case INVALID_INDEX, INVALID_TARGET, INVALID_FORMAT, NO_UNDO, NO_REDO ->
                locale.i18n("swing.nbt_editor.edit_invalid");
        };
    }
}
