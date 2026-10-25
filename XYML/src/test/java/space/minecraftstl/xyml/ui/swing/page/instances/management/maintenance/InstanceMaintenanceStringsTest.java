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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Verifies that the maintenance page explains a non-modpack instance instead of reporting an
/// unsupported archive format.
@NotNullByDefault
public final class InstanceMaintenanceStringsTest {
    /// Resolves the unavailable-update explanation from the Simplified Chinese bundle.
    @Test
    public void resolvesSimplifiedChineseNotModpackExplanation() {
        SupportedLocale previousLocale = I18n.getLocale();
        try {
            I18n.setLocale(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE));
            InstanceMaintenanceStrings strings = InstanceMaintenanceStrings.localized();

            assertAll(
                    () -> assertEquals("当前实例不是整合包实例，无法通过整合包方式更新",
                            strings.updateUnavailableStatus()),
                    () -> assertNotEquals(I18n.i18n("modpack.unsupported"),
                            strings.updateUnavailableStatus()));
        } finally {
            I18n.setLocale(previousLocale);
        }
    }

    /// Resolves the same explanation from the English bundle and keeps it aligned with the
    /// component-test text.
    @Test
    public void resolvesEnglishNotModpackExplanation() {
        SupportedLocale previousLocale = I18n.getLocale();
        try {
            I18n.setLocale(SupportedLocale.getLocale(Locale.ENGLISH));
            InstanceMaintenanceStrings strings = InstanceMaintenanceStrings.localized();

            assertAll(
                    () -> assertEquals("Only installed modpacks can be updated from an archive.",
                            strings.updateUnavailableStatus()),
                    () -> assertEquals(InstanceMaintenanceStrings.english().updateUnavailableStatus(),
                            strings.updateUnavailableStatus()),
                    () -> assertNotEquals(I18n.i18n("modpack.unsupported"),
                            strings.updateUnavailableStatus()));
        } finally {
            I18n.setLocale(previousLocale);
        }
    }
}
