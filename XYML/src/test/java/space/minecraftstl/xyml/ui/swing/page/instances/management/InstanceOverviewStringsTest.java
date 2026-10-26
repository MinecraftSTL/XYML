// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that production instance-overview text follows the launcher locale catalog.
@NotNullByDefault
public final class InstanceOverviewStringsTest {
    /// Resolves every screenshot-visible overview label from the Simplified Chinese bundle.
    @Test
    public void resolvesSimplifiedChineseOverviewLabels() {
        SupportedLocale previousLocale = I18n.getLocale();
        try {
            I18n.setLocale(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE));
            InstanceOverviewStrings strings = InstanceOverviewStrings.localized();

            assertAll(
                    () -> assertEquals("概览", strings.title()),
                    () -> assertEquals("游戏实例名称", strings.instanceNameLabel()),
                    () -> assertEquals("实例文件夹", strings.instanceRootLabel()),
                    () -> assertEquals("实例运行文件夹", strings.gameDirectoryLabel()),
                    () -> assertEquals("正在加载……", strings.loadingValue()),
                    () -> assertEquals("打开实例文件夹", strings.openInstanceDirectoryTooltip()));
        } finally {
            I18n.setLocale(previousLocale);
        }
    }
}
