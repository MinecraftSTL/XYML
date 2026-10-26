// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
