// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies page-owned asynchronous environment work without probing actual hardware.
@NotNullByDefault
public final class AboutPanelEnvironmentTest {
    /// Panels owned by the current test and closed on the EDT after every outcome.
    private final List<AboutPanel> panels = new ArrayList<>();

    /// Releases pending page work after successful and failed test assertions.
    @AfterEach
    public void closePages() {
        EdtDispatcher.executeAndWait(() -> panels.forEach(AboutPanel::close));
    }

    /// Loading is deferred to the provided executor and publishes an immutable defensive snapshot.
    @Test
    public void defersLoadingAndCopiesResults() {
        AtomicReference<@Nullable Runnable> pending = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean();
        List<AboutPanel.AboutEntry> source = new ArrayList<>();
        source.add(row("CPU", "test processor"));
        AboutPanel panel = create(pending::set, () -> {
            started.set(true);
            return source;
        });
        assertFalse(started.get());
        Objects.requireNonNull(pending.get()).run();
        source.add(row("GPU", "late mutation"));
        EdtDispatcher.executeAndWait(() -> {
            assertEquals(1, panel.environmentSnapshot().size());
            assertEquals("test processor", panel.environmentSnapshot().get(0).subtitle());
            assertThrows(UnsupportedOperationException.class,
                    () -> panel.environmentSnapshot().add(row("Other", "not allowed")));
        });
    }

    /// Closing before queued work runs prevents probing and suppresses later completion UI.
    @Test
    public void closeBeforeWorkPreventsPublication() {
        AtomicReference<@Nullable Runnable> pending = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean();
        AboutPanel panel = create(pending::set, () -> {
            started.set(true);
            return List.of(row("CPU", "unused"));
        });
        EdtDispatcher.executeAndWait(panel::close);
        Objects.requireNonNull(pending.get()).run();
        EdtDispatcher.executeAndWait(() -> assertTrue(panel.environmentSnapshot().isEmpty()));
        assertFalse(started.get());
    }

    /// A running supplier that finishes after close cannot repopulate the disposed page.
    @Test
    public void lateResultAfterCloseIsIgnored() {
        AtomicReference<@Nullable Runnable> pending = new AtomicReference<>();
        AtomicReference<@Nullable AboutPanel> owner = new AtomicReference<>();
        AboutPanel panel = create(pending::set, () -> {
            Objects.requireNonNull(owner.get()).close();
            return List.of(row("CPU", "late result"));
        });
        owner.set(panel);
        Objects.requireNonNull(pending.get()).run();
        EdtDispatcher.executeAndWait(() -> assertTrue(panel.environmentSnapshot().isEmpty()));
    }

    /// Executor rejection leaves a usable page and a localized unavailable message instead of escaping construction.
    @Test
    public void rejectedExecutorShowsLocalizedFailure() {
        AboutPanel panel = create(command -> {
            throw new RejectedExecutionException("test executor rejection");
        }, () -> List.of(row("CPU", "unreachable")));
        EdtDispatcher.executeAndWait(() -> {
            assertTrue(panel.environmentSnapshot().isEmpty());
            assertTrue(containsLabel(panel, i18n("about.environment.failed")));
        });
    }

    /// Creates a controlled panel on the EDT and registers its page-owned cleanup.
    ///
    /// @param executor deterministic task scheduler
    /// @param source synthetic environment source, snapshotted by the page
    /// @return constructed page
    private AboutPanel create(Executor executor, Supplier<List<AboutPanel.AboutEntry>> source) {
        AtomicReference<@Nullable AboutPanel> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            AboutPanel panel = new AboutPanel(uri -> { }, executor, source);
            panels.add(panel);
            result.set(panel);
        });
        return Objects.requireNonNull(result.get());
    }

    /// Creates a synthetic environment entry without external resources or links.
    ///
    /// @param title row title
    /// @param value row detail
    /// @return immutable row value
    private static AboutPanel.AboutEntry row(String title, String value) {
        return new AboutPanel.AboutEntry(null, null, title, value, null);
    }

    /// Searches the constructed component tree for one exact localized label.
    ///
    /// @param root root component
    /// @param expected exact visible text
    /// @return true when the label exists
    private static boolean containsLabel(Component root, String expected) {
        if (root instanceof JLabel label && expected.equals(label.getText()))
            return true;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsLabel(child, expected))
                    return true;
            }
        }
        return false;
    }
}
