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
import space.minecraftstl.xyml.library.nbt.tag.TagType;

import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/// Preserves and asynchronously reformats the visible numeric draft when its radix changes.
@NotNullByDefault
final class NBTNumberRadixEditor {
    /// Backend used only for detached parsing and formatting.
    private final NBTEditorController controller;

    /// Visible decimal or hexadecimal selector.
    private final JComboBox<String> selector;

    /// Complete structured-value draft.
    private final JTextComponent valueEditor;

    /// Cancellable background text pipeline.
    private final NBTAsyncTextLoader textLoader;

    /// Supplies the current revision-bound row.
    private final Supplier<@Nullable NBTEditorTreeNode> selection;

    /// Rejects late results after selection, revision, or radix changes.
    private final Predicate<ValueLoadKey> acceptance;

    /// Reports whether the owning panel remains active.
    private final BooleanSupplier active;

    /// Disables value controls while conversion runs.
    private final Runnable started;

    /// Restores value controls after successful conversion.
    private final Consumer<ValueLoadKey> succeeded;

    /// Restores value controls and displays validation after rejection.
    private final Consumer<String> failed;

    /// Radix represented by the complete visible draft.
    private NBTNumberRadix displayedRadix = NBTNumberRadix.DECIMAL;

    /// Prevents selector rollback from recursively starting another conversion.
    private boolean changingSelection;

    /// Creates a coordinator over caller-owned Swing controls and callbacks.
    ///
    /// @param controller detached snapshot and formatting backend
    /// @param selector visible radix selector
    /// @param valueEditor complete structured-value editor
    /// @param textLoader cancellable asynchronous text loader
    /// @param selection current selected row supplier
    /// @param acceptance late-result acceptance predicate
    /// @param active whether the owner remains active
    /// @param started conversion-start callback
    /// @param succeeded successful-conversion callback
    /// @param failed rejected-conversion callback
    NBTNumberRadixEditor(
            NBTEditorController controller,
            JComboBox<String> selector,
            JTextComponent valueEditor,
            NBTAsyncTextLoader textLoader,
            Supplier<@Nullable NBTEditorTreeNode> selection,
            Predicate<ValueLoadKey> acceptance,
            BooleanSupplier active,
            Runnable started,
            Consumer<ValueLoadKey> succeeded,
            Consumer<String> failed) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.valueEditor = Objects.requireNonNull(valueEditor, "valueEditor");
        this.textLoader = Objects.requireNonNull(textLoader, "textLoader");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.active = Objects.requireNonNull(active, "active");
        this.started = Objects.requireNonNull(started, "started");
        this.succeeded = Objects.requireNonNull(succeeded, "succeeded");
        this.failed = Objects.requireNonNull(failed, "failed");
    }

    /// Returns the radix selected by the visible combo box.
    ///
    /// @return hexadecimal for the second option, otherwise decimal
    NBTNumberRadix selectedRadix() {
        return selector.getSelectedIndex() == 1
                ? NBTNumberRadix.HEXADECIMAL
                : NBTNumberRadix.DECIMAL;
    }

    /// Records that a complete backend value was rendered in one radix.
    ///
    /// @param radix radix represented by the visible text
    void markDisplayed(NBTNumberRadix radix) {
        displayedRadix = Objects.requireNonNull(radix, "radix");
    }

    /// Converts the current complete draft after a user changes the selector.
    void selectionChanged() {
        if (!active.getAsBoolean() || changingSelection) {
            return;
        }
        NBTNumberRadix sourceRadix = displayedRadix;
        NBTNumberRadix targetRadix = selectedRadix();
        @Nullable NBTEditorTreeNode selected = selection.get();
        if (sourceRadix == targetRadix || selected == null || !isNumeric(selected)) {
            return;
        }

        ValueLoadKey sourceKey = new ValueLoadKey(selected, sourceRadix);
        ValueLoadKey targetKey = new ValueLoadKey(selected, targetRadix);
        String draft = valueEditor.getText();
        textLoader.prepare(targetKey);
        textLoader.load(
                targetKey,
                () -> controller.reformatStructuredValue(selected, draft, sourceRadix, targetRadix),
                () -> acceptance.test(targetKey),
                started,
                () -> finish(targetKey),
                detail -> reject(sourceKey, sourceRadix, detail));
    }

    /// Returns whether the selected row has a numeric scalar or aggregate value.
    ///
    /// @param selected current row
    /// @return whether radix conversion applies
    private boolean isNumeric(NBTEditorTreeNode selected) {
        @Nullable TagType<?> type = selected.node().getType();
        @Nullable TagType<?> elementType = type == TagType.LIST
                ? controller.listElementType(selected)
                : null;
        return NBTStructuredValueCodec.isNumericScalar(type)
                || NBTStructuredValueCodec.isEditableAggregate(type, elementType);
    }

    /// Publishes one accepted target representation.
    ///
    /// @param targetKey accepted target key
    private void finish(ValueLoadKey targetKey) {
        displayedRadix = targetKey.radix();
        succeeded.accept(targetKey);
    }

    /// Restores the untouched source representation after conversion rejection.
    ///
    /// @param sourceKey original value key
    /// @param sourceRadix original visible radix
    /// @param detail technical rejection detail
    private void reject(ValueLoadKey sourceKey, NBTNumberRadix sourceRadix, String detail) {
        displayedRadix = Objects.requireNonNull(sourceRadix, "sourceRadix");
        changingSelection = true;
        try {
            selector.setSelectedIndex(sourceRadix == NBTNumberRadix.HEXADECIMAL ? 1 : 0);
        } finally {
            changingSelection = false;
        }
        textLoader.retain(Objects.requireNonNull(sourceKey, "sourceKey"));
        failed.accept(Objects.requireNonNull(detail, "detail"));
    }
}
