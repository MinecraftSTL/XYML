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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import javax.swing.JTabbedPane;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies that content-category tabs use content labels rather than instance-management labels.
@NotNullByDefault
final class DownloadCategoryPanelTest {
    /// Locale active before this test changes the process-wide launcher locale.
    private final SupportedLocale originalLocale = I18n.getLocale();

    /// Restores the process-wide locale after the tab-label assertion.
    @AfterEach
    void restoreLocale() {
        I18n.setLocale(originalLocale);
    }

    /// Keeps the Mods download tab distinct from the installed-mod management page.
    @Test
    void labelsModsTabAsContentInsteadOfManagement() {
        I18n.setLocale(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE));
        AtomicReference<@Nullable DownloadCategoryPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> panelReference.set(new DownloadCategoryPanel(
                TaskProgressStrings.english(),
                null,
                Duration.ZERO)));

        DownloadCategoryPanel panel = Objects.requireNonNull(panelReference.get());
        try {
            EdtDispatcher.executeAndWait(() -> {
                JTabbedPane tabs = panel.categoryTabs();
                String modsTabTitle = tabs.getTitleAt(1);
                assertEquals(i18n("mods"), modsTabTitle);
                assertNotEquals(i18n("mods.manage"), modsTabTitle);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }
}
