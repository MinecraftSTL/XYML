// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JComponent;
import java.util.Objects;

/// Couples one lazily created management component to its selection and cleanup actions.
@NotNullByDefault
final class InstanceManagementPage {
    /// Root component installed in the page deck.
    private final JComponent component;

    /// Idempotent action executed whenever the component becomes selected after another page.
    private final Runnable activation;

    /// Idempotent owner-supplied resource cleanup action.
    private final Runnable cleanup;

    /// Creates one owned page lifecycle.
    ///
    /// @param component root component installed in the deck
    /// @param activation idempotent action executed when the page becomes selected
    /// @param cleanup action releasing page-owned resources
    InstanceManagementPage(JComponent component, Runnable activation, Runnable cleanup) {
        this.component = Objects.requireNonNull(component, "component");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    /// Creates a page that needs no explicit first-display work.
    ///
    /// @param component root component installed in the deck
    /// @param cleanup action releasing page-owned resources
    /// @return configured passive page lifecycle
    static InstanceManagementPage passive(JComponent component, Runnable cleanup) {
        return new InstanceManagementPage(component, () -> { }, cleanup);
    }

    /// Returns the root component installed in the page deck.
    ///
    /// @return owned page root
    JComponent component() {
        return component;
    }

    /// Executes the page's selection action.
    void activate() {
        activation.run();
    }

    /// Releases resources owned by this page.
    void close() {
        cleanup.run();
    }
}
