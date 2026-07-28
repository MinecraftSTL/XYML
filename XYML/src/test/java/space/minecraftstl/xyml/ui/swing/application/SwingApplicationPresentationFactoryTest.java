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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentation;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.awt.event.KeyEvent;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests production resource mapping, locale fallback, mnemonics, formats, and timing policy.
@NotNullByDefault
public final class SwingApplicationPresentationFactoryTest {
    /// Locale active before each test changes the process-wide launcher locale.
    private final SupportedLocale originalLocale = I18n.getLocale();

    /// Restores the process-wide locale after each focused factory test.
    @AfterEach
    public void restoreLocale() {
        I18n.setLocale(originalLocale);
    }

    /// English production resources populate every page and preserve explicit duration instances.
    @Test
    public void createsCompleteEnglishPresentationWithExplicitPolicies() throws ReflectiveOperationException {
        I18n.setLocale(SupportedLocale.getLocale(Locale.ENGLISH));
        Duration pageTransition = Duration.ofMillis(173L);
        Duration taskAnimation = Duration.ofMillis(287L);

        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                "Explicit XYML title",
                pageTransition,
                taskAnimation);
        @Unmodifiable List<String> texts = allPresentationText(presentation);

        assertAll(
                () -> assertEquals("Explicit XYML title", presentation.windowTitle()),
                () -> assertSame(pageTransition, presentation.pageTransitionDuration()),
                () -> assertSame(taskAnimation, presentation.taskProgressAnimationDuration()),
                () -> assertEquals("Home", presentation.home().pageTitle()),
                () -> assertEquals("All Instances", presentation.instances().pageTitle()),
                () -> assertEquals("Schematics", presentation.schematics().pageTitle()),
                () -> assertEquals("New Game", presentation.gameVersions().pageTitle()),
                () -> assertEquals("Accounts", presentation.accounts().pageTitle()),
                () -> assertEquals("Appearance", presentation.appearance().pageTitle()),
                () -> assertEquals(
                        "Choose schematic file you want to import",
                        presentation.schematics().actions().importDialogTitle()),
                () -> assertEquals(
                        "Operation Failed",
                        presentation.schematics().actions().operationFailedTitle()),
                () -> assertEquals("Task progress", presentation.taskProgress().progressAccessibleName()),
                () -> assertPresentationTextResolved(texts));
    }

    /// Simplified Chinese resources localize every shell page while retaining stable mnemonic keys.
    @Test
    public void createsLocalizedChineseShellWithStableMnemonics() {
        I18n.setLocale(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE));

        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                Duration.ZERO,
                Duration.ofMillis(1L));

        assertAll(
                () -> assertEquals(Metadata.TITLE, presentation.windowTitle()),
                () -> assertShellPage(presentation, ShellPageId.INSTANCES, "实例列表", KeyEvent.VK_I),
                () -> assertShellPage(presentation, ShellPageId.DOWNLOADS, "下载", KeyEvent.VK_D),
                () -> assertShellPage(presentation, ShellPageId.ACCOUNTS, "账户", KeyEvent.VK_A),
                () -> assertShellPage(presentation, ShellPageId.SETTINGS, "设置", KeyEvent.VK_S),
                () -> assertEquals("正在刷新", presentation.instances().refreshingAction()),
                () -> assertEquals("任务进度", presentation.taskProgress().progressAccessibleName()));
    }

    /// Schematic formatter resources retain their exact integer placeholder contracts.
    @Test
    public void formatsSchematicMetadataWithoutLosingPlaceholders() {
        I18n.setLocale(SupportedLocale.getLocale(Locale.ENGLISH));
        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                Duration.ZERO,
                Duration.ZERO);

        assertAll(
                () -> assertEquals(
                        "2 x 3 x 5",
                        String.format(
                                Locale.ROOT,
                                presentation.schematics().metadata().enclosingSizeFormat(),
                                2,
                                3,
                                5)),
                () -> assertEquals(
                        "16 x 9 pixels",
                        String.format(
                                Locale.ROOT,
                                presentation.schematics().metadata().previewDimensionsFormat(),
                                16,
                                9)),
                () -> assertEquals(
                        "144 pixels",
                        String.format(
                                Locale.ROOT,
                                presentation.schematics().metadata().previewPixelCountFormat(),
                                144)),
                () -> assertEquals(
                        "Delete \"castle.litematic\"? This action cannot be undone.",
                        String.format(
                                Locale.ROOT,
                                presentation.schematics().actions().deleteConfirmationFormat(),
                                "castle.litematic")));
    }

    /// Shared labels retain their locale while missing Swing-only entries fall back without exposing keys.
    @Test
    public void fallsBackToBaseResourcesWithoutExposingRawKeys() throws ReflectiveOperationException {
        I18n.setLocale(SupportedLocale.getLocale(Locale.forLanguageTag("es")));

        SwingApplicationPresentation presentation = SwingApplicationPresentationFactory.create(
                Duration.ofMillis(1L),
                Duration.ofMillis(2L));
        @Unmodifiable List<String> texts = allPresentationText(presentation);

        assertAll(
                () -> assertEquals(
                        "Todas las instancias",
                        presentation.shellPages().get(ShellPageId.INSTANCES).label()),
                () -> assertEquals("Task progress", presentation.taskProgress().progressAccessibleName()),
                () -> assertPresentationTextResolved(texts));
    }

    /// Invalid titles and negative timing policies are rejected without invented substitutes.
    @Test
    public void rejectsInvalidExplicitPresentationPolicy() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SwingApplicationPresentationFactory.create(
                                " ",
                                Duration.ZERO,
                                Duration.ZERO)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SwingApplicationPresentationFactory.create(
                                "XYML",
                                Duration.ofMillis(-1L),
                                Duration.ZERO)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SwingApplicationPresentationFactory.create(
                                "XYML",
                                Duration.ZERO,
                                Duration.ofMillis(-1L))),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> SwingApplicationPresentationFactory.create(
                                "XYML",
                                null,
                                Duration.ZERO)));
    }

    /// Verifies one localized shell label and its stable mnemonic.
    ///
    /// @param presentation complete production presentation
    /// @param page shell destination
    /// @param expectedLabel expected localized label
    /// @param expectedMnemonic expected Swing mnemonic key
    private static void assertShellPage(
            SwingApplicationPresentation presentation,
            ShellPageId page,
            String expectedLabel,
            int expectedMnemonic) {
        ShellPagePresentation pagePresentation = presentation.shellPages().get(page);
        assertAll(
                () -> assertEquals(expectedLabel, pagePresentation.label()),
                () -> assertEquals(expectedMnemonic, pagePresentation.mnemonic()));
    }

    /// Collects every visible or accessible string held by the presentation and nested records.
    ///
    /// @param presentation complete application presentation
    /// @return immutable text values including all shell pages
    private static @Unmodifiable List<String> allPresentationText(
            SwingApplicationPresentation presentation) throws ReflectiveOperationException {
        List<String> texts = new ArrayList<>();
        collectRecordText(presentation, texts);
        for (ShellPageId page : ShellPageId.values()) {
            texts.add(presentation.shellPages().get(page).label());
        }
        return List.copyOf(texts);
    }

    /// Recursively extracts String components from one non-null Java record.
    ///
    /// @param value record to inspect
    /// @param texts mutable destination text list
    private static void collectRecordText(
            Object value,
            List<String> texts) throws ReflectiveOperationException {
        Class<?> recordType = value.getClass();
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException("Expected record value: " + recordType.getName());
        }
        RecordComponent @Nullable [] nullableComponents = recordType.getRecordComponents();
        RecordComponent[] components = Objects.requireNonNull(
                nullableComponents,
                "record components were unavailable");
        for (RecordComponent component : components) {
            @Nullable Object componentValue = component.getAccessor().invoke(value);
            if (componentValue instanceof String text) {
                texts.add(text);
            } else if (componentValue != null && componentValue.getClass().isRecord()) {
                collectRecordText(componentValue, texts);
            }
        }
    }

    /// Returns whether text has the shape of one of the factory's untranslated resource keys.
    ///
    /// @param text presentation text to inspect
    /// @return whether the text resembles a raw key
    private static boolean resemblesRawResourceKey(String text) {
        return text.equals("search") || text.matches(
                "(?:account|button|download|extension|install|message|reveal|schematics|settings|swing|version)"
                        + "(?:\\..*)?");
    }

    /// Verifies that every collected value is visible text rather than a missing resource key.
    ///
    /// @param texts immutable presentation text values
    private static void assertPresentationTextResolved(@Unmodifiable List<String> texts) {
        assertAll(
                () -> assertTrue(texts.stream().allMatch(text -> !text.isBlank())),
                () -> assertFalse(
                        texts.stream().anyMatch(SwingApplicationPresentationFactoryTest::resemblesRawResourceKey),
                        () -> "Raw resource key in presentation: " + texts));
    }
}
