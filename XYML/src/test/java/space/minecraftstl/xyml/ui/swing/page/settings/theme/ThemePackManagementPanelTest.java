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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.ThemeMode;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsPanel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsSnapshot;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsStrings;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies viewport-sized loading, exact application, stable geometry, icon loading, and light/dark rendering.
@NotNullByDefault
public final class ThemePackManagementPanelTest {
    /// Temporary installed paths and fallback screenshot directory.
    @TempDir
    private Path temporaryDirectory;

    /// The panel loads only its measured viewport, filters locally, and applies the exact selected reference.
    @Test
    public void usesMeasuredViewportAndRoutesExactSelection() {
        @Unmodifiable List<ThemePackItem> items = items(160);
        ManualExecutor iconExecutor = new ManualExecutor();
        RecordingApplication application = new RecordingApplication();
        ThemePackManagementModel model = new ThemePackManagementModel(
                new ImmediateBackend(items),
                application,
                Runnable::run,
                items.get(0).reference());
        ThemePackManagementPanel panel = onEdt(() -> new ThemePackManagementPanel(
                model,
                ThemePackManagementStrings.english(),
                new RecordingInteractions(),
                iconExecutor));
        iconExecutor.runAll();
        flushEdt();

        onEdt(() -> {
            panel.setSize(820, 620);
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            int loaded = loadedCount(panel);
            assertTrue(loaded > 0);
            assertTrue(loaded < items.size());
            assertEquals(items.size(), panel.choiceList().getChoiceModel().getSize());

            JTextField search = findNamed(panel, "themePacksSearch", JTextField.class);
            search.setText("needle");
            assertEquals(1, panel.displayedSnapshot().items().size());
            assertEquals(items.size(), panel.displayedSnapshot().totalItemCount());
            search.setText("");
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            panel.choiceList().getList().setSelectedIndex(1);
            findNamed(panel, "themePacksApply", AbstractButton.class).doClick();
            assertEquals(items.get(1).reference(), panel.displayedSnapshot().appliedTheme());
            assertEquals(List.of(items.get(1).reference()), application.references);
            assertNotNull(findNamed(panel, "themePacksRefresh", AbstractButton.class).getIcon());
            panel.close();
        });
    }

    /// Renders nonblank, non-overlapping desktop surfaces under both production FlatLaf modes.
    @Test
    public void writesLightAndDarkVisualReports() throws IOException {
        renderVisualReport(false, "theme-packs-light.png");
        renderVisualReport(true, "theme-packs-dark.png");
        onEdt(() -> {
            FlatLightLaf.setup();
        });
    }

    /// Renders the production appearance-and-theme nesting under both look-and-feel modes.
    @Test
    public void writesIntegratedAppearanceVisualReports() throws IOException {
        renderIntegratedVisualReport(false, "appearance-theme-packs-light.png");
        renderIntegratedVisualReport(true, "appearance-theme-packs-dark.png");
        onEdt(() -> {
            FlatLightLaf.setup();
        });
    }

    /// Creates one deterministic visual report under a requested production look and feel.
    private void renderVisualReport(boolean dark, String fileName) throws IOException {
        onEdt(() -> {
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        });
        @Unmodifiable List<ThemePackItem> items = items(36);
        ManualExecutor iconExecutor = new ManualExecutor();
        ThemePackManagementModel model = new ThemePackManagementModel(
                new ImmediateBackend(items),
                new RecordingApplication(),
                Runnable::run,
                items.get(0).reference());
        ThemePackManagementPanel panel = onEdt(() -> new ThemePackManagementPanel(
                model,
                ThemePackManagementStrings.english(),
                new RecordingInteractions(),
                iconExecutor));
        try {
            iconExecutor.runAll();
            flushEdt();
            BufferedImage rendered = onEdt(() -> renderPanel(panel));
            String configuredRoot = Objects.toString(System.getenv("XYML_VISUAL_REPORT_DIR"), "");
            Path root = configuredRoot.isBlank()
                    ? temporaryDirectory.resolve("visual-reports")
                    : Path.of(configuredRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            assertTrue(ImageIO.write(rendered, "PNG", root.resolve(fileName).toFile()));
        } finally {
            panel.close();
            flushEdt();
        }
    }

    /// Creates one integrated appearance page report using the actual embedded theme-pack panel.
    ///
    /// @param dark whether to install the production dark look and feel
    /// @param fileName report file name
    /// @throws IOException when the report cannot be written
    private void renderIntegratedVisualReport(boolean dark, String fileName) throws IOException {
        onEdt(() -> {
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        });
        @Unmodifiable List<ThemePackItem> items = items(36);
        ManualExecutor iconExecutor = new ManualExecutor();
        ThemePackManagementModel themeModel = new ThemePackManagementModel(
                new ImmediateBackend(items),
                new RecordingApplication(),
                Runnable::run,
                items.get(0).reference());
        ThemePackManagementPanel themePanel = onEdt(() -> new ThemePackManagementPanel(
                themeModel,
                ThemePackManagementStrings.simplifiedChinese(),
                new RecordingInteractions(),
                iconExecutor));
        AppearanceSettingsPanel appearancePanel = onEdt(() -> new AppearanceSettingsPanel(
                new StaticAppearanceSettingsModel(),
                new AppearanceSettingsStrings(
                        "外观",
                        "明暗模式",
                        "跟随主题",
                        "跟随系统",
                        "浅色",
                        "深色",
                        "圆角半径",
                        "启用动画"),
                themePanel));
        try {
            iconExecutor.runAll();
            flushEdt();
            onEdt(() -> prepareIntegratedPanel(appearancePanel, themePanel));
            flushEdt();
            BufferedImage rendered = onEdt(() -> renderIntegratedPanel(appearancePanel, themePanel));
            String configuredRoot = Objects.toString(System.getenv("XYML_VISUAL_REPORT_DIR"), "");
            Path root = configuredRoot.isBlank()
                    ? temporaryDirectory.resolve("visual-reports")
                    : Path.of(configuredRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            assertTrue(ImageIO.write(rendered, "PNG", root.resolve(fileName).toFile()));
        } finally {
            appearancePanel.close();
            flushEdt();
        }
    }

    /// Lays out and paints the integrated page while checking its vertical ownership boundaries.
    ///
    /// @param appearancePanel complete appearance settings page
    /// @param themePanel embedded theme-pack management surface
    /// @return painted desktop report
    private static BufferedImage renderIntegratedPanel(
            AppearanceSettingsPanel appearancePanel,
            ThemePackManagementPanel themePanel) {
        layoutRecursively(appearancePanel);

        Rectangle animations = bounds(
                appearancePanel,
                findNamed(appearancePanel, "appearanceAnimations", JComponent.class));
        Rectangle search = bounds(
                appearancePanel,
                findNamed(themePanel, "themePacksSearch", JComponent.class));
        Rectangle list = bounds(appearancePanel, themePanel.choiceList());
        assertFalse(animations.intersects(search));
        assertTrue(search.y >= animations.y + animations.height);
        assertTrue(list.y >= search.y + search.height);
        assertTrue(list.y + list.height <= appearancePanel.getHeight());
        assertTrue(themePanel.choiceList().getWidth() >= 700);
        assertTrue(loadedCount(themePanel) > 0);
        assertLongRowIsClipped(themePanel);

        BufferedImage image = new BufferedImage(920, 820, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            appearancePanel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue(distinctColors(image).size() > 20);
        assertTrue((image.getRGB(460, 410) >>> 24) != 0);
        return image;
    }

    /// Measures the integrated hierarchy and requests the resulting adaptive list slice.
    ///
    /// @param appearancePanel complete appearance settings page
    /// @param themePanel embedded theme-pack management surface
    private static void prepareIntegratedPanel(
            AppearanceSettingsPanel appearancePanel,
            ThemePackManagementPanel themePanel) {
        appearancePanel.setSize(920, 820);
        layoutRecursively(appearancePanel);
        themePanel.choiceList().refreshLoadPlan();
    }

    /// Lays out, validates, and paints one loaded panel.
    private static BufferedImage renderPanel(ThemePackManagementPanel panel) {
        panel.setSize(920, 620);
        layoutRecursively(panel);
        panel.choiceList().refreshLoadPlan();
        layoutRecursively(panel);

        Rectangle search = bounds(panel, findNamed(panel, "themePacksSearch", JComponent.class));
        Rectangle refresh = bounds(panel, findNamed(panel, "themePacksRefresh", JComponent.class));
        Rectangle importButton = bounds(panel, findNamed(panel, "themePacksImport", JComponent.class));
        Rectangle apply = bounds(panel, findNamed(panel, "themePacksApply", JComponent.class));
        Rectangle locate = bounds(panel, findNamed(panel, "themePacksLocate", JComponent.class));
        Rectangle delete = bounds(panel, findNamed(panel, "themePacksDelete", JComponent.class));
        assertFalse(search.intersects(refresh));
        assertFalse(refresh.intersects(importButton));
        assertFalse(apply.intersects(locate));
        assertFalse(locate.intersects(delete));
        assertEquals(36, refresh.width);
        assertEquals(36, refresh.height);
        assertTrue(panel.choiceList().getWidth() >= 700);

        assertLongRowIsClipped(panel);
        BufferedImage image = new BufferedImage(920, 620, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue(distinctColors(image).size() > 20);
        assertTrue((image.getRGB(460, 310) >>> 24) != 0);
        return image;
    }

    /// Verifies the reusable renderer keeps an intentionally long theme name inside its assigned column.
    private static void assertLongRowIsClipped(ThemePackManagementPanel panel) {
        JList<?> rawList = panel.choiceList().getList();
        @SuppressWarnings("unchecked")
        JList<space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry<ThemePackItem>> list =
                (JList<space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry<ThemePackItem>>) rawList;
        @Nullable ThemePackItem item = panel.choiceList().getChoiceModel().loadedValueAt(1);
        assertNotNull(item);
        ListCellRenderer<? super space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry<ThemePackItem>> renderer =
                list.getCellRenderer();
        Component component = renderer.getListCellRendererComponent(
                list,
                space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry.loaded(1, item),
                1,
                false,
                false);
        component.setSize(list.getWidth(), list.getFixedCellHeight());
        if (component instanceof Container container) {
            layoutRecursively(container);
            JRadioButton primary = findComponent(container, JRadioButton.class);
            JComponent secondary = findNamed(container, "themePackRowSecondary", JComponent.class);
            JComponent badge = findNamed(container, "themePackRowBadge", JComponent.class);
            assertTrue(primary.getFontMetrics(primary.getFont()).stringWidth(primary.getText())
                    <= Math.max(1, primary.getWidth() - 20));
            assertTrue(primary.getWidth() < list.getWidth());
            assertTrue(secondary.getWidth() > 0 && secondary.getHeight() > 0);
            assertTrue(badge.getWidth() > 0 && badge.getHeight() > 0);
        } else {
            throw new AssertionError("Theme renderer did not return a container");
        }
    }

    /// Counts sparse loaded rows without forcing the complete inventory to materialize in Swing.
    private static int loadedCount(ThemePackManagementPanel panel) {
        int count = 0;
        for (int index = 0; index < panel.choiceList().getChoiceModel().getSize(); index++) {
            if (panel.choiceList().getChoiceModel().loadedValueAt(index) != null) {
                count++;
            }
        }
        return count;
    }

    /// Creates deterministic mixed-origin items with one intentionally long row and one searchable marker.
    private @Unmodifiable List<ThemePackItem> items(int count) {
        List<ThemePackItem> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boolean builtIn = index == 0;
            String packageId = builtIn ? "hmcl.default" : "example.pack" + index;
            String name = index == 1
                    ? "A very long local theme name that must be clipped before it reaches the fixed origin column"
                    : index == count - 1 ? "Needle theme" : "Theme " + index;
            result.add(new ThemePackItem(
                    new ThemeReference(packageId, builtIn ? null : "theme" + index),
                    name,
                    builtIn ? "HMCL Default" : "Local package with descriptive metadata " + index,
                    "1." + index + ".0",
                    "Theme author " + index,
                    "Theme description " + index,
                    builtIn,
                    builtIn ? null : temporaryDirectory.resolve(packageId)));
        }
        return List.copyOf(result);
    }

    /// Converts one descendant rectangle into root-panel coordinates.
    private static Rectangle bounds(JComponent panel, JComponent component) {
        @Nullable Container parent = component.getParent();
        if (parent == null) {
            throw new AssertionError("Detached component: " + component.getName());
        }
        return SwingUtilities.convertRectangle(parent, component.getBounds(), panel);
    }

    /// Recursively lays out an offscreen Swing hierarchy.
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /// Finds one required named component.
    private static <T extends JComponent> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedOrNull(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one optional named component.
    private static <T extends JComponent> @Nullable T findNamedOrNull(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamedOrNull(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Finds the first descendant of a requested component type.
    private static <T extends Component> T findComponent(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findComponentOrNull(child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Missing component type: " + type.getSimpleName());
    }

    /// Finds an optional descendant of a requested component type.
    private static <T extends Component> @Nullable T findComponentOrNull(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findComponentOrNull(child, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Collects all painted pixel colors.
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Runs a value operation synchronously on the EDT.
    private static <T extends Object> T onEdt(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(operation, "operation").get()));
        return Objects.requireNonNull(result.get(), "EDT operation returned null");
    }

    /// Runs a void operation synchronously on the EDT.
    private static void onEdt(Runnable operation) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(operation, "operation"));
    }

    /// Flushes every previously queued EDT callback.
    private static void flushEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Immediate metadata backend that performs no file or image work.
    @NotNullByDefault
    private static final class ImmediateBackend implements ThemePackManagementBackend {
        /// Immutable inventory returned by every refresh.
        private final @Unmodifiable List<ThemePackItem> items;

        /// Creates an immediate backend.
        private ImmediateBackend(@Unmodifiable List<ThemePackItem> items) {
            this.items = List.copyOf(items);
        }

        /// Returns the immutable inventory.
        @Override
        public CompletionStage<@Unmodifiable List<ThemePackItem>> loadAll(Executor executor) {
            return CompletableFuture.completedFuture(items);
        }

        /// Rejects unused imports in this panel-focused backend.
        @Override
        public CompletionStage<@Unmodifiable List<ThemePackItem>> importArchive(Path archive, Executor executor) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Import is not configured"));
        }

        /// Completes unused deletion immediately.
        @Override
        public CompletionStage<@Nullable Void> deleteInstalled(ThemePackItem item, Executor executor) {
            return CompletableFuture.completedFuture(null);
        }

        /// Returns the installed test path.
        @Override
        public CompletionStage<Path> locateInstalled(ThemePackItem item, Executor executor) {
            return CompletableFuture.completedFuture(Objects.requireNonNull(
                    item.installedDirectory(),
                    "installedDirectory"));
        }
    }

    /// Records exact application references.
    @NotNullByDefault
    private static final class RecordingApplication implements ThemePackApplication {
        /// Applied references in call order.
        private final List<ThemeReference> references = new ArrayList<>();

        /// Records and immediately completes one exact application.
        @Override
        public CompletionStage<@Nullable Void> apply(ThemeReference reference) {
            references.add(Objects.requireNonNull(reference, "reference"));
            return CompletableFuture.completedFuture(null);
        }
    }

    /// Deterministic background executor used to prove toolbar SVG work is deferred.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO pending operations.
        private final Queue<Runnable> operations = new ArrayDeque<>();

        /// Enqueues one operation.
        @Override
        public void execute(Runnable command) {
            operations.add(Objects.requireNonNull(command, "command"));
        }

        /// Runs all queued operations on the calling non-EDT test thread.
        private void runAll() {
            @Nullable Runnable operation;
            while ((operation = operations.poll()) != null) {
                operation.run();
            }
        }
    }

    /// Dialog and desktop fake with no external side effects.
    @NotNullByDefault
    private static final class RecordingInteractions implements ThemePackManagementInteractions {
        /// Cancels archive selection.
        @Override
        public @Nullable Path chooseImportArchive(Component owner) {
            return null;
        }

        /// Confirms deterministic test deletion.
        @Override
        public boolean confirmDelete(Component owner, ThemePackItem item) {
            return true;
        }

        /// Completes deterministic directory opening.
        @Override
        public CompletionStage<@Nullable Void> revealInstalledDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /// Immutable appearance model used only to exercise the production panel nesting in visual tests.
    @NotNullByDefault
    private static final class StaticAppearanceSettingsModel implements AppearanceSettingsModel {
        /// Stable four-state appearance snapshot rendered by the integrated page.
        private static final AppearanceSettingsSnapshot SNAPSHOT = new AppearanceSettingsSnapshot(
                ThemeMode.SYSTEM,
                9,
                0,
                18,
                3,
                true,
                true,
                ThemeBrightnessPreference.THEME);

        /// Returns the stable visual-test snapshot.
        @Override
        public AppearanceSettingsSnapshot snapshot() {
            return SNAPSHOT;
        }

        /// Returns a no-op registration because this immutable model never publishes transitions.
        @Override
        public Subscription subscribe(ValueChangeListener<AppearanceSettingsSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Rejects unexpected mode writes from the noninteractive visual test.
        @Override
        public void setThemeMode(ThemeMode themeMode) {
            throw new UnsupportedOperationException("Static visual model does not accept writes");
        }

        /// Rejects unexpected four-state writes from the noninteractive visual test.
        @Override
        public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
            throw new UnsupportedOperationException("Static visual model does not accept writes");
        }

        /// Rejects unexpected radius writes from the noninteractive visual test.
        @Override
        public void setCornerRadius(int cornerRadius) {
            throw new UnsupportedOperationException("Static visual model does not accept writes");
        }

        /// Rejects unexpected animation writes from the noninteractive visual test.
        @Override
        public void setAnimationsEnabled(boolean enabled) {
            throw new UnsupportedOperationException("Static visual model does not accept writes");
        }
    }
}
