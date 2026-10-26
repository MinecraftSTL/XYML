// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/// Stores a complete localized presentation table for top-level shell navigation.
@NotNullByDefault
public final class ShellPagePresentations {
    /// Immutable presentation values keyed by stable destination.
    private final @Unmodifiable Map<ShellPageId, ShellPagePresentation> presentations;

    /// Creates a presentation table after validating that every destination has one value.
    ///
    /// @param presentations one localized presentation for every shell page
    public ShellPagePresentations(Map<ShellPageId, ShellPagePresentation> presentations) {
        Objects.requireNonNull(presentations, "presentations");
        EnumMap<ShellPageId, ShellPagePresentation> copy = new EnumMap<>(ShellPageId.class);
        presentations.forEach((page, presentation) -> copy.put(
                Objects.requireNonNull(page, "presentation page"),
                Objects.requireNonNull(presentation, "page presentation")));

        EnumSet<ShellPageId> missingPages = EnumSet.allOf(ShellPageId.class);
        missingPages.removeAll(copy.keySet());
        if (!missingPages.isEmpty()) {
            throw new IllegalArgumentException("Missing page presentations: " + missingPages);
        }
        this.presentations = Map.copyOf(copy);
    }

    /// Returns the localized presentation for a destination.
    ///
    /// @param page the stable destination
    /// @return the destination presentation
    public ShellPagePresentation get(ShellPageId page) {
        return Objects.requireNonNull(presentations.get(Objects.requireNonNull(page, "page")));
    }

    /// Returns complete built-in English fallback presentations.
    ///
    /// Production startup should supply values from the active launcher locale.
    ///
    /// @return immutable English fallback presentations
    public static ShellPagePresentations englishFallback() {
        return new ShellPagePresentations(Map.of(
                ShellPageId.INSTANCES, new ShellPagePresentation("Instances", KeyEvent.VK_I),
                ShellPageId.DOWNLOADS, new ShellPagePresentation("Downloads", KeyEvent.VK_D),
                ShellPageId.TASKS, new ShellPagePresentation("Tasks", KeyEvent.VK_T),
                ShellPageId.ACCOUNTS, new ShellPagePresentation("Accounts", KeyEvent.VK_A),
                ShellPageId.SETTINGS, new ShellPagePresentation("Settings", KeyEvent.VK_S)));
    }
}
