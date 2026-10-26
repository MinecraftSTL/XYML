// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.LauncherSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies persistent selector ordering remains independent of management-page source order.
@NotNullByDefault
public final class ShellRecentSelectionsTest {
    /// Confirmed selections move to the front while never-used entries retain source order.
    @Test
    public void ordersDirectoriesByConfirmedRecentUse() {
        ShellRecentSelections selections = ShellRecentSelections.transientSelections();

        selections.recordDirectory("B");
        selections.recordDirectory("C");
        assertEquals(List.of("C", "B", "A"), selections.orderDirectories(List.of("A", "B", "C")));

        selections.recordDirectory("A");
        assertEquals(List.of("A", "C", "B"), selections.orderDirectories(List.of("A", "B", "C")));
    }

    /// Removed IDs are pruned without changing the source order of never-used entries.
    @Test
    public void prunesRemovedAccounts() {
        ShellRecentSelections selections = ShellRecentSelections.transientSelections();
        selections.recordAccount("removed");
        selections.recordAccount("account-2");

        assertEquals(
                List.of("account-2", "account-1", "account-3"),
                selections.orderAccounts(List.of("account-1", "account-2", "account-3")));
    }

    /// Equal instance IDs in separate directories retain independent histories.
    @Test
    public void isolatesInstanceHistoryByDirectory() {
        ShellRecentSelections selections = ShellRecentSelections.transientSelections();
        selections.recordInstance("directory-a", "shared");
        selections.recordInstance("directory-a", "a-only");
        selections.recordInstance("directory-b", "shared");
        selections.recordInstance("directory-b", "b-only");

        assertEquals(
                List.of("a-only", "shared", "unused"),
                selections.orderInstances("directory-a", List.of("shared", "unused", "a-only")));
        assertEquals(
                List.of("b-only", "shared", "unused"),
                selections.orderInstances("directory-b", List.of("shared", "unused", "b-only")));
    }

    /// Selector history survives the launcher's normal JSON persistence round trip.
    @Test
    public void persistsThroughLauncherSettingsJson() {
        LauncherSettings settings = new LauncherSettings();
        ShellRecentSelections selections = new ShellRecentSelections(settings);
        selections.recordDirectory("directory-2");
        selections.recordAccount("account-2");
        selections.recordInstance("directory-2", "instance-2");

        LauncherSettings restored = LauncherSettings.fromJson(
                JsonParser.parseString(settings.toJson()).getAsJsonObject());
        ShellRecentSelections restoredSelections = new ShellRecentSelections(restored);

        assertEquals(
                List.of("directory-2", "directory-1"),
                restoredSelections.orderDirectories(List.of("directory-1", "directory-2")));
        assertEquals(
                List.of("account-2", "account-1"),
                restoredSelections.orderAccounts(List.of("account-1", "account-2")));
        assertEquals(
                List.of("instance-2", "instance-1"),
                restoredSelections.orderInstances(
                        "directory-2",
                        List.of("instance-1", "instance-2")));
    }
}
