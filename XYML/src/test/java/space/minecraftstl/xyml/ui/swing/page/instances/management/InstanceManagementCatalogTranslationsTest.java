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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Ensures navigation and page headings name the same management function in all three maintained languages.
@NotNullByDefault
final class InstanceManagementCatalogTranslationsTest {
    /// Navigation uses the management label rather than a bare server noun.
    @Test
    void navigationMatchesManagementHeading() {
        assertEquals(i18n("server.management.title"), InstanceManagementPageId.SERVERS.localizedLabel());
    }

    /// Three-language resources contain matching unique management labels and local-list explanations.
    ///
    /// @throws IOException when the bundled language resources cannot be read
    @Test
    void synchronizesThreeLanguageManagementCopy() throws IOException {
        for (String language : List.of("I18N.properties", "I18N_zh_CN.properties", "I18N_zh.properties")) {
            String text;
            try (InputStream input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                    "assets/lang/" + language))) {
                text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Properties properties = new Properties();
            properties.load(new StringReader(text));
            String expected = switch (language) {
                case "I18N_zh_CN.properties" -> "服务器管理";
                case "I18N_zh.properties" -> "伺服器管理";
                default -> "Server Management";
            };
            assertEquals(expected, properties.getProperty("server.manage"), language);
            assertEquals(expected, properties.getProperty("server.management.title"), language);
            assertFalse(properties.getProperty("server.management.description", "").isBlank(), language);
            assertFalse(properties.getProperty("server.reorder", "").isBlank(), language);
            assertFalse(properties.getProperty("button.enabled", "").isBlank(), language);
            assertFalse(properties.getProperty("button.disabled", "").isBlank(), language);
            assertFalse(properties.getProperty("button.enable").equals(properties.getProperty("button.enabled")),
                    language + ": action/state wording must stay distinct");
            assertFalse(properties.getProperty("button.disable").equals(properties.getProperty("button.disabled")),
                    language + ": action/state wording must stay distinct");
            for (String key : List.of(
                    "server.manage",
                    "server.management.title",
                    "server.management.description",
                    "server.reorder",
                    "button.enable",
                    "button.disable",
                    "button.enabled",
                    "button.disabled")) {
                assertEquals(1L, Pattern.compile("^" + Pattern.quote(key) + "=", Pattern.MULTILINE)
                        .matcher(text).results().count(), language + ": " + key);
            }
            for (String obsolete : List.of(
                    "swing.mods.enabled",
                    "swing.mods.filter.enabled",
                    "swing.mods.filter.disabled",
                    "swing.resourcepacks.enabled",
                    "swing.shaderpacks.enabled")) {
                assertFalse(text.contains(obsolete + "="), language + ": obsolete key " + obsolete);
            }
        }
    }
}
