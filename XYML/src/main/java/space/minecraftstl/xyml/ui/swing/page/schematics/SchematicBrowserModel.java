// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/// Supplies a shallow, viewport-driven schematic directory without exposing a desktop toolkit.
@NotNullByDefault
public interface SchematicBrowserModel extends ViewportChoiceDataSource<SchematicBrowserItem>, AutoCloseable {
    /// Returns the latest immutable browser state.
    ///
    /// @return current state
    SchematicBrowserSnapshot snapshot();

    /// Registers for future browser state changes on the publishing thread.
    ///
    /// @param listener state transition listener
    /// @return independently cancellable registration
    Subscription subscribe(ValueChangeListener<SchematicBrowserSnapshot> listener);

    /// Schedules the first shallow scan only while the model remains idle.
    ///
    /// @return completion of the current or newly scheduled state
    CompletionStage<SchematicBrowserSnapshot> loadIfNeeded();

    /// Schedules a replacement scan of the current directory.
    ///
    /// @return completion of the replacement scan
    CompletionStage<SchematicBrowserSnapshot> refresh();

    /// Schedules navigation to a child directory present in the current stable listing.
    ///
    /// @param directory exact known child path
    /// @return completion of the child scan
    CompletionStage<SchematicBrowserSnapshot> openDirectory(Path directory);

    /// Schedules navigation to the parent without crossing the configured root boundary.
    ///
    /// @return completion of the parent scan or the unchanged root state
    CompletionStage<SchematicBrowserSnapshot> returnToParent();

    /// Imports regular no-follow Litematic files into the current stable directory without overwriting.
    ///
    /// The returned stage completes only after the resulting shallow listing is committed. The
    /// implementation accepts no concurrent write operation.
    ///
    /// @param sourceFiles source files defensively captured when the command starts
    /// @return completion of the write and its resulting stable browser state
    CompletionStage<SchematicBrowserSnapshot> importFiles(List<Path> sourceFiles);

    /// Creates one direct child directory in the current stable directory.
    ///
    /// @param directoryName one non-blank legal path component
    /// @return completion of the write and its resulting stable browser state
    CompletionStage<SchematicBrowserSnapshot> createDirectory(String directoryName);

    /// Recursively deletes one exact direct child from the current stable listing without following links.
    ///
    /// Once deletion begins, an I/O failure may leave an isolated partially deleted tree that cannot
    /// be rolled back. The returned failure and write snapshot make that outcome explicit.
    ///
    /// @param target exact path captured from the current stable listing
    /// @return completion of the write and its resulting stable browser state
    CompletionStage<SchematicBrowserSnapshot> delete(Path target);

    /// Deletes one exact direct child using the selected recycle-bin or permanent mode.
    ///
    /// @param target exact path captured from the current stable listing
    /// @param mode selected deletion behavior
    /// @return completion of the write and its resulting stable browser state
    default CompletionStage<SchematicBrowserSnapshot> delete(Path target, DeletionMode mode) {
        return delete(target);
    }

    /// Cancels public completions, requests cooperative operation cancellation, and prevents late publication.
    ///
    /// A deletion that already moved its target to a private isolation path finishes that committed
    /// cleanup after closure so an internal partially deleted tree is not abandoned unnecessarily.
    @Override
    void close();
}
