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
import space.minecraftstl.xyml.nbt.NBTDocument;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/// Verifies that one asynchronous NBT command still owns the visible Swing state.
@NotNullByDefault
final class NBTEditorAsyncResultGuard {
    /// Prevents utility-class construction.
    private NBTEditorAsyncResultGuard() {
    }

    /// Checks document identity, revision, and selection before a result mutates the view.
    ///
    /// A failed command must still refer to the exact submitted node because no editor revision
    /// changed. A successful command may rebuild the tree, so its returned address or the submitted
    /// address is accepted while the new model is being installed.
    ///
    /// @param submitted node selected when the command was submitted, or `null` for history without a row
    /// @param requestAddress address selected when the command was submitted
    /// @param requestDocument document selected when the command was submitted
    /// @param requestRevision editor revision selected when the command was submitted
    /// @param closed whether the owning panel has begun teardown
    /// @param currentDocument document currently visible in the controller
    /// @param currentSelection row currently selected in the tree, or `null`
    /// @param result completed command result
    /// @return whether the result may update visible controls
    static boolean accepts(
            @Nullable NBTEditorTreeNode submitted,
            NBTAddress requestAddress,
            NBTDocument requestDocument,
            long requestRevision,
            boolean closed,
            @Nullable NBTDocument currentDocument,
            @Nullable NBTEditorTreeNode currentSelection,
            NBTEditResult result) {
        NBTAddress address = Objects.requireNonNull(requestAddress, "requestAddress");
        NBTDocument document = Objects.requireNonNull(requestDocument, "requestDocument");
        NBTEditResult completed = Objects.requireNonNull(result, "result");
        if (closed || currentDocument != document || currentSelection == null
                || !currentSelection.belongsTo(document)) {
            return false;
        }
        if (submitted != null) {
            return acceptsNodeResult(
                    submitted,
                    address,
                    document,
                    requestRevision,
                    currentSelection,
                    completed);
        }
        if (!completed.applied()) {
            return currentSelection.address().equals(address)
                    && currentSelection.node().getRevision() == requestRevision
                    && document.editor().getRevision() == requestRevision;
        }
        @Nullable NBTAddress resultAddress = completed.selection();
        return resultAddress != null
                && document.editor().getRevision() >= requestRevision
                && (resultAddress.equals(currentSelection.address())
                || address.equals(currentSelection.address()));
    }

    /// Checks whether an exceptional completion still belongs to the visible request.
    ///
    /// Exceptional completions do not carry a result address, so they use the same strict
    /// identity and revision checks as a rejected command. This prevents a late cancellation or
    /// worker failure from painting an unrelated selection after the user has moved on.
    ///
    /// @param submitted node selected when the command was submitted, or `null` for history
    /// @param requestAddress address selected when the command was submitted
    /// @param requestDocument document selected when the command was submitted
    /// @param requestRevision editor revision selected when the command was submitted
    /// @param closed whether the owning panel has begun teardown
    /// @param currentDocument document currently visible in the controller
    /// @param currentSelection row currently selected in the tree, or `null`
    /// @return whether the failure may update visible controls
    static boolean acceptsFailure(
            @Nullable NBTEditorTreeNode submitted,
            NBTAddress requestAddress,
            NBTDocument requestDocument,
            long requestRevision,
            boolean closed,
            @Nullable NBTDocument currentDocument,
            @Nullable NBTEditorTreeNode currentSelection) {
        NBTAddress address = Objects.requireNonNull(requestAddress, "requestAddress");
        NBTDocument document = Objects.requireNonNull(requestDocument, "requestDocument");
        if (closed || currentDocument != document || currentSelection == null
                || !currentSelection.belongsTo(document)) {
            return false;
        }
        if (submitted != null) {
            return currentSelection == submitted
                    && submitted.node().getRevision() == requestRevision
                    && document.editor().getRevision() == requestRevision;
        }
        return currentSelection.address().equals(address)
                && document.editor().getRevision() == requestRevision;
    }

    /// Removes completion wrappers while preserving the original failure.
    ///
    /// @param failure exceptional completion
    /// @return unwrapped failure
    static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Returns a concise non-empty detail suitable for a Swing status label.
    ///
    /// @param failure exceptional completion
    /// @return failure detail
    static String detail(Throwable failure) {
        Throwable cause = unwrap(failure);
        @Nullable String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /// Applies the stricter identity rule for a node-bound command.
    ///
    /// @param submitted node selected when the command was submitted
    /// @param requestAddress address selected when the command was submitted
    /// @param document document selected when the command was submitted
    /// @param requestRevision editor revision selected when the command was submitted
    /// @param currentSelection row currently selected in the tree
    /// @param result completed command result
    /// @return whether the result may update visible controls
    private static boolean acceptsNodeResult(
            NBTEditorTreeNode submitted,
            NBTAddress requestAddress,
            NBTDocument document,
            long requestRevision,
            NBTEditorTreeNode currentSelection,
            NBTEditResult result) {
        if (!result.applied()) {
            return currentSelection == submitted
                    && currentSelection.node().getRevision() == requestRevision
                    && document.editor().getRevision() == requestRevision;
        }
        @Nullable NBTAddress resultAddress = result.selection();
        return resultAddress != null
                && (resultAddress.equals(currentSelection.address())
                || requestAddress.equals(currentSelection.address())
                // Tree rebuilding briefly restores the old node's parent before the
                // command callback can select the returned address (notably rename).
                || (!requestAddress.isRoot()
                && requestAddress.parent().equals(currentSelection.address())))
                && document.editor().getRevision() >= requestRevision;
    }
}
