// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies repeatable Microsoft authorization-link commands without rendering the URL.
@NotNullByDefault
final class MicrosoftAuthorizationActionsTest {
    /// A long OAuth URL remains available to both commands without changing the action row's preferred size.
    @Test
    void reopensAndCopiesLocationWithoutRenderingIt() {
        AtomicReference<@Nullable String> opened = new AtomicReference<>();
        AtomicReference<@Nullable String> copied = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            MicrosoftAuthorizationActions actions = new MicrosoftAuthorizationActions(opened::set, copied::set);
            JButton reopen = button(actions, "microsoftAuthorizationReopen");
            JButton copy = button(actions, "microsoftAuthorizationCopyLink");
            Dimension preferredBeforeLocation = actions.getPreferredSize();
            String location = "https://login.live.com/oauth20_authorize.srf?" + "state=x".repeat(500);

            assertAll(
                    () -> assertFalse(actions.isVisible()),
                    () -> assertFalse(reopen.isEnabled()),
                    () -> assertFalse(copy.isEnabled()),
                    () -> assertInstanceOf(FlatSVGIcon.class, reopen.getIcon()),
                    () -> assertInstanceOf(FlatSVGIcon.class, copy.getIcon()));

            actions.showLocation(location);
            assertAll(
                    () -> assertTrue(actions.isVisible()),
                    () -> assertTrue(reopen.isEnabled()),
                    () -> assertTrue(copy.isEnabled()),
                    () -> assertEquals(preferredBeforeLocation, actions.getPreferredSize()),
                    () -> assertFalse(Arrays.stream(actions.getComponents())
                            .filter(JButton.class::isInstance)
                            .map(JButton.class::cast)
                            .map(JButton::getText)
                            .anyMatch(location::equals)));

            reopen.doClick();
            copy.doClick();
            assertAll(
                    () -> assertEquals(location, opened.get()),
                    () -> assertEquals(location, copied.get()));

            opened.set(null);
            copied.set(null);
            actions.clearLocation();
            reopen.doClick();
            copy.doClick();
            assertAll(
                    () -> assertFalse(actions.isVisible()),
                    () -> assertFalse(reopen.isEnabled()),
                    () -> assertFalse(copy.isEnabled()),
                    () -> assertNull(opened.get()),
                    () -> assertNull(copied.get()));
        });
    }

    /// Finds one stable named button in an authorization action row.
    ///
    /// @param actions action row
    /// @param name stable component name
    /// @return matching button
    private static JButton button(MicrosoftAuthorizationActions actions, String name) {
        return Arrays.stream(actions.getComponents())
                .filter(JButton.class::isInstance)
                .map(JButton.class::cast)
                .filter(component -> name.equals(component.getName()))
                .findFirst()
                .orElseThrow();
    }
}
