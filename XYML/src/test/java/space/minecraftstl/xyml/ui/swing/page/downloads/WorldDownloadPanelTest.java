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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the download center renders remote and local world workflows without eager network work.
@NotNullByDefault
final class WorldDownloadPanelTest {
    /// Temporary visual-report fallback when no shared report directory is configured.
    @TempDir
    private Path temporaryDirectory;

    /// Renders the real Worlds category and checks its remote catalog geometry and source selection.
    @Test
    void rendersRemoteAndLocalWorldWorkflowsInsideDownloadCenter() throws IOException {
        DownloadCategoryPanel panel = onEdt(() -> new DownloadCategoryPanel(
                TaskProgressStrings.english(),
                null,
                Duration.ZERO));
        try {
            BufferedImage image = onEdt(() -> {
                panel.setSize(1024, 720);
                JTabbedPane categories = panel.categoryTabs();
                categories.setSelectedIndex(categories.getTabCount() - 1);
                layoutRecursively(panel);

                JTabbedPane workflows = findNamed(
                        panel,
                        "downloadsWorldWorkflowTabs",
                        JTabbedPane.class);
                assertEquals(2, workflows.getTabCount());
                assertEquals(0, workflows.getSelectedIndex());
                Container remoteWorkflow = (Container) Objects.requireNonNull(
                        workflows.getSelectedComponent(),
                        "selected remote workflow");

                JComboBox<?> source = findNamed(remoteWorkflow, "remoteAddonSource", JComboBox.class);
                assertEquals(1, source.getItemCount());
                assertEquals(RemoteAddonCatalogSource.CURSEFORGE, source.getSelectedItem());

                JComponent results = findNamed(remoteWorkflow, "remoteAddonResults", JComponent.class);
                JComponent search = findNamed(remoteWorkflow, "remoteAddonSearch", JComponent.class);
                JButton searchAction = findNamed(remoteWorkflow, "remoteAddonSearchAction", JButton.class);
                JComponent gameVersion = findNamed(remoteWorkflow, "remoteAddonGameVersion", JComponent.class);
                JButton previousPage = findNamed(remoteWorkflow, "remoteAddonPreviousPage", JButton.class);
                JButton nextPage = findNamed(remoteWorkflow, "remoteAddonNextPage", JButton.class);
                JComponent version = findNamed(remoteWorkflow, "remoteAddonVersion", JComponent.class);
                JButton saveAs = findNamed(remoteWorkflow, "remoteAddonInstall", JButton.class);

                Rectangle workflowBounds = bounds(panel, workflows);
                Rectangle searchBounds = bounds(panel, search);
                Rectangle searchActionBounds = bounds(panel, searchAction);
                Rectangle gameVersionBounds = bounds(panel, gameVersion);
                Rectangle previousPageBounds = bounds(panel, previousPage);
                Rectangle nextPageBounds = bounds(panel, nextPage);
                Rectangle resultBounds = bounds(panel, results);
                Rectangle versionBounds = bounds(panel, version);
                Rectangle saveAsBounds = bounds(panel, saveAs);
                assertTrue(workflowBounds.width >= 900);
                assertTrue(resultBounds.width >= 850);
                assertTrue(resultBounds.height >= 300);
                assertFalse(searchBounds.intersects(searchActionBounds));
                assertFalse(gameVersionBounds.intersects(previousPageBounds));
                assertFalse(previousPageBounds.intersects(nextPageBounds));
                assertEquals(120, previousPageBounds.width);
                assertEquals(120, nextPageBounds.width);
                assertTrue(previousPageBounds.y >= gameVersionBounds.y + gameVersionBounds.height);
                assertEquals(previousPageBounds.y, nextPageBounds.y);
                assertTrue(resultBounds.y >= previousPageBounds.y + previousPageBounds.height);
                assertFalse(versionBounds.intersects(saveAsBounds));
                assertTrue(versionBounds.y >= resultBounds.y + resultBounds.height);

                BufferedImage rendered = new BufferedImage(1024, 720, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = rendered.createGraphics();
                try {
                    panel.printAll(graphics);
                } finally {
                    graphics.dispose();
                }
                assertTrue(countOpaquePixels(rendered) > 500_000L);
                assertTrue(distinctColors(rendered).size() > 12);
                return rendered;
            });

            Path reportRoot = visualReportRoot();
            Files.createDirectories(reportRoot);
            assertTrue(ImageIO.write(image, "PNG", reportRoot.resolve("download-world-catalog.png").toFile()));
        } finally {
            onEdt(panel::close);
        }
    }

    /// Resolves the configured shared screenshot directory or this test's temporary fallback.
    ///
    /// @return normalized report directory
    private Path visualReportRoot() {
        String configuredRoot = Objects.toString(System.getenv("XYML_VISUAL_REPORT_DIR"), "");
        return configuredRoot.isBlank()
                ? temporaryDirectory.resolve("visual-reports")
                : Path.of(configuredRoot).toAbsolutePath().normalize();
    }

    /// Converts one descendant rectangle into root-panel coordinates.
    ///
    /// @param root rendered root component
    /// @param component descendant component
    /// @return descendant bounds in root coordinates
    private static Rectangle bounds(JComponent root, JComponent component) {
        @Nullable Container parent = component.getParent();
        if (parent == null) {
            throw new AssertionError("Detached component: " + component.getName());
        }
        return javax.swing.SwingUtilities.convertRectangle(parent, component.getBounds(), root);
    }

    /// Recursively performs layout for an offscreen Swing hierarchy.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /// Finds one required named descendant.
    ///
    /// @param root component hierarchy root
    /// @param name stable component name
    /// @param type requested component type
    /// @param <T> component subtype
    /// @return matching descendant
    private static <T extends JComponent> T findNamed(
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
        throw new AssertionError("Missing component: " + name);
    }

    /// Finds one optional named descendant.
    ///
    /// @param root component hierarchy root
    /// @param name stable component name
    /// @param type requested component type
    /// @param <T> component subtype
    /// @return matching descendant, or null when absent
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

    /// Counts nontransparent pixels in the rendered report.
    ///
    /// @param image rendered panel image
    /// @return opaque or translucent pixel count
    private static long countOpaquePixels(BufferedImage image) {
        long count = 0L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Collects distinct painted colors for a coarse nonblank-surface assertion.
    ///
    /// @param image rendered panel image
    /// @return mutable set of rendered ARGB values
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Runs one value operation synchronously on the Swing EDT.
    ///
    /// @param operation non-null EDT operation
    /// @param <T> returned value type
    /// @return non-null operation result
    private static <T extends Object> T onEdt(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(operation, "operation").get()));
        return Objects.requireNonNull(result.get(), "EDT operation returned null");
    }

    /// Runs one void operation synchronously on the Swing EDT.
    ///
    /// @param operation non-null EDT operation
    private static void onEdt(Runnable operation) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(operation, "operation"));
    }
}
