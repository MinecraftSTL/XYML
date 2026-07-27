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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.nbt.NBTFileType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies chooser and drop routing, lazy tree state, typed controls, painting, and terminal closure.
@NotNullByDefault
final class NBTEditorPanelTest {
    /// Temporary source directory for real backend fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// Keeps empty-state labels factual rather than embedding interaction instructions.
    @Test
    void usesPureEmptyStateLabels() {
        assertEquals("No NBT file open.", NBTEditorStrings.english().emptyText());
        assertEquals("未打开 NBT 文件。", NBTEditorStrings.simplifiedChinese().emptyText());
    }

    /// Exercises the complete headless page workflow without performing NBT I/O on the EDT.
    @Test
    void rendersAndRoutesTheCompleteEditorWorkflow() throws Exception {
        Path first = temporaryDirectory.resolve("first.dat");
        Path second = temporaryDirectory.resolve("second.dat");
        writeTag(first, new CompoundTag().addInt("value", 1).addString("name", "first"));
        writeTag(second, new CompoundTag().addInt("value", 5).addString("name", "second"));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor iconExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        RecordingInteractions interactions = new RecordingInteractions(first);
        AtomicInteger closeRequests = new AtomicInteger();
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                interactions,
                closeRequests::incrementAndGet,
                iconExecutor));
        assertEquals(1, iconExecutor.pendingCount());
        iconExecutor.runNext();
        flushEdt();

        onEdt(() -> {
            AbstractButton open = findNamed(panel, "nbtEditorOpen", AbstractButton.class);
            assertNotNull(open.getIcon());
            assertEquals("toolBarButton", open.getClientProperty("JButton.buttonType"));
            open.doClick();
            assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
            assertTrue(findNamed(panel, "nbtEditorProgress", JProgressBar.class).isVisible());
            assertFalse(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
        });
        assertEquals(1, ioExecutor.pendingCount());
        ioExecutor.runNext();
        flushEdt();

        onEdt(() -> {
            assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
            JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
            NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
            assertEquals(0, model.getRoot().materializedChildCount());
            tree.setSelectionPath(model.pathForAddress(List.of(0)));
            Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(
                    tree,
                    tree.getLastSelectedPathComponent(),
                    true,
                    false,
                    true,
                    1,
                    false);
            assertTrue(((JLabel) rendered).getIcon() instanceof ImageIcon);
            assertEquals(16, ((JLabel) rendered).getIcon().getIconWidth());
            assertEquals(16, ((JLabel) rendered).getIcon().getIconHeight());
            JTextArea value = findNamed(panel, "nbtEditorValue", JTextArea.class);
            assertTrue(value.isEnabled());
            assertEquals("1", value.getText());
            value.setText("41");
            findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            assertTrue(controller.snapshot().dirty());
            assertTrue(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
            assertEquals("41", value.getText());
            assertPaintsOpaqueContent(panel);
        });

        interactions.confirmDiscard = false;
        assertFalse(onEdt(() -> panel.openDroppedPaths(List.of(second))));
        assertEquals(0, ioExecutor.pendingCount());
        interactions.confirmDiscard = true;
        assertTrue(onEdt(() -> panel.openDroppedPaths(List.of(second))));
        assertEquals(1, ioExecutor.pendingCount());
        ioExecutor.runNext();
        flushEdt();
        assertEquals(second.toAbsolutePath().normalize(), controller.snapshot().file());
        assertFalse(controller.snapshot().dirty());

        onEdt(() -> {
            JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
            NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
            tree.setSelectionPath(model.pathForAddress(List.of(0)));
            JTextArea value = findNamed(panel, "nbtEditorValue", JTextArea.class);
            value.setText("6");
            findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
        });
        interactions.confirmDiscard = false;
        onEdt(() -> findNamed(panel, "nbtEditorBack", AbstractButton.class).doClick());
        assertEquals(0, closeRequests.get());
        interactions.confirmDiscard = true;
        onEdt(() -> findNamed(panel, "nbtEditorBack", AbstractButton.class).doClick());
        assertEquals(1, closeRequests.get());

        interactions.chosenFile = first;
        onEdt(() -> findNamed(panel, "nbtEditorOpen", AbstractButton.class).doClick());
        assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
        panel.close();
        ioExecutor.runAll();
        flushEdt();
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
    }

    /// Prevents accidental classpath image reads from being moved back onto the EDT.
    @Test
    void rejectsSynchronousNbtIconDecodingOnTheEdt() {
        assertThrows(IllegalStateException.class, () -> onEdt(NBTTreeCellRenderer::loadIcons));
    }

    /// Renders the real editor under both production FlatLaf modes for visual regression review.
    @Test
    void writesLightAndDarkVisualReports() throws Exception {
        Path source = temporaryDirectory.resolve("visual-level.dat");
        CompoundTag player = new CompoundTag()
                .addString("Name", "Alex")
                .addInt("Health", 20)
                .addInt("FoodLevel", 18);
        writeTag(source, new CompoundTag()
                .addInt("DataVersion", 3955)
                .addString("LevelName", "Visual regression world")
                .addTag("Player", player));

        renderVisualReport(source, false, "nbt-editor-light.png");
        renderVisualReport(source, true, "nbt-editor-dark.png");
        onEdt(() -> {
            FlatLightLaf.setup();
        });
    }

    /// Requires reload after a stale save and never queues a repeated doomed save.
    @Test
    void disablesSaveAfterAStaleSourceConflict() throws Exception {
        Path source = temporaryDirectory.resolve("conflict.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualExecutor ioExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        RecordingInteractions interactions = new RecordingInteractions(source);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                interactions,
                () -> { }));
        try {
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                findNamed(panel, "nbtEditorValue", JTextArea.class).setText("2");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            });
            writeTag(source, new CompoundTag().addInt("value", 99));
            onEdt(() -> findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick());
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                assertEquals(NBTEditorStatus.CONFLICT, controller.snapshot().status());
                assertFalse(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
                assertTrue(findNamed(panel, "nbtEditorReload", AbstractButton.class).isEnabled());
                findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick();
            });
            assertEquals(0, ioExecutor.pendingCount());
        } finally {
            panel.close();
        }
    }

    /// Paints a stable desktop-sized surface and verifies that the panel is not blank.
    ///
    /// @param panel panel to render
    private static void assertPaintsOpaqueContent(NBTEditorPanel panel) {
        panel.setSize(1000, 700);
        layoutRecursively(panel);
        BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue((image.getRGB(500, 350) >>> 24) != 0);
        assertTrue((image.getRGB(20, 20) >>> 24) != 0);
    }

    /// Opens and renders one deterministic editor fixture under a requested production theme.
    ///
    /// @param source prepared NBT source
    /// @param dark whether to install the dark production look and feel
    /// @param fileName report filename
    /// @throws IOException when the PNG report cannot be written
    private static void renderVisualReport(Path source, boolean dark, String fileName) throws IOException {
        onEdt(() -> {
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        });
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor iconExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { },
                iconExecutor));
        try {
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            iconExecutor.runAll();
            flushEdt();

            BufferedImage image = onEdt(() -> renderVisualImage(panel));
            Path report = Path.of("build", "reports", "swing-nbt", fileName).toAbsolutePath();
            Files.createDirectories(report.getParent());
            assertTrue(ImageIO.write(image, "PNG", report.toFile()));
        } finally {
            panel.close();
        }
    }

    /// Lays out, checks, and paints one loaded editor on the EDT.
    ///
    /// @param panel loaded editor panel
    /// @return rendered ARGB image
    private static BufferedImage renderVisualImage(NBTEditorPanel panel) {
        JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
        NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
        tree.setSelectionPath(model.pathForAddress(List.of(2, 0)));
        panel.setSize(1000, 700);
        layoutRecursively(panel);

        Rectangle back = componentBounds(panel, findNamed(panel, "nbtEditorBack", AbstractButton.class));
        Rectangle open = componentBounds(panel, findNamed(panel, "nbtEditorOpen", AbstractButton.class));
        Rectangle reload = componentBounds(panel, findNamed(panel, "nbtEditorReload", AbstractButton.class));
        Rectangle save = componentBounds(panel, findNamed(panel, "nbtEditorSave", AbstractButton.class));
        Rectangle value = componentBounds(panel, findNamed(panel, "nbtEditorValueScroll", JScrollPane.class));
        Rectangle apply = componentBounds(panel, findNamed(panel, "nbtEditorApply", AbstractButton.class));
        assertFalse(back.intersects(open));
        assertFalse(open.intersects(reload));
        assertFalse(reload.intersects(save));
        assertTrue(apply.y - value.getMaxY() <= 16.0D);
        assertTrue(tree.getWidth() >= 300);
        assertTrue(findNamed(panel, "nbtEditorValue", JTextArea.class).getWidth() >= 200);

        BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue((image.getRGB(500, 350) >>> 24) != 0);
        return image;
    }

    /// Converts one descendant's bounds into the root panel coordinate system.
    ///
    /// @param panel root editor panel
    /// @param component descendant component
    /// @return converted bounds
    private static Rectangle componentBounds(NBTEditorPanel panel, JComponent component) {
        @Nullable Container parent = component.getParent();
        if (parent == null) {
            throw new AssertionError("Detached component: " + component.getName());
        }
        return SwingUtilities.convertRectangle(parent, component.getBounds(), panel);
    }

    /// Recursively lays out an offscreen Swing component hierarchy.
    ///
    /// @param container root to lay out
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /// Writes one deterministic GZIP standalone NBT fixture.
    ///
    /// @param target fixture target
    /// @param root compound root
    /// @throws IOException when fixture serialization fails
    private static void writeTag(Path target, CompoundTag root) throws IOException {
        try (OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(target));
             GZIPOutputStream gzipOutput = new GZIPOutputStream(rawOutput)) {
            NBTCodec.of().writeTag(gzipOutput, root);
        }
    }

    /// Finds one required named descendant of a requested Swing component type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return required matching component
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

    /// Finds one optional named descendant.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null`
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

    /// Runs one value operation synchronously on the EDT.
    ///
    /// @param operation EDT operation
    /// @param <T> result type
    /// @return operation result
    private static <T> T onEdt(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(operation, "operation").get()));
        return Objects.requireNonNull(result.get(), "result");
    }

    /// Runs one void operation synchronously on the EDT.
    ///
    /// @param operation EDT operation
    private static void onEdt(Runnable operation) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(operation, "operation"));
    }

    /// Waits until every previously queued EDT callback has run.
    private static void flushEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Deterministic executor proving when blocking work is allowed to run.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// FIFO of submitted blocking operations.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one operation.
        ///
        /// @param command submitted operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Returns the pending operation count.
        ///
        /// @return queued count
        private int pendingCount() {
            return commands.size();
        }

        /// Runs the next operation.
        private void runNext() {
            commands.remove().run();
        }

        /// Drains every operation.
        private void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }

    /// Toolkit-neutral interaction substitute used without opening native dialogs.
    @NotNullByDefault
    private static final class RecordingInteractions implements NBTEditorInteractions {
        /// Source returned by the chooser, or `null` to simulate cancellation.
        private @Nullable Path chosenFile;

        /// Whether dirty-document replacement is confirmed.
        private boolean confirmDiscard = true;

        /// Creates interactions with one initial chooser result.
        ///
        /// @param chosenFile initial chooser result
        private RecordingInteractions(Path chosenFile) {
            this.chosenFile = Objects.requireNonNull(chosenFile, "chosenFile");
        }

        /// Returns the configured chooser result.
        ///
        /// @param currentFile current source, or `null`
        /// @return configured source
        @Override
        public @Nullable Path chooseFile(@Nullable Path currentFile) {
            return chosenFile;
        }

        /// Accepts exactly one supported lexical path.
        ///
        /// @param candidates immutable drop payload
        /// @return accepted source, or `null`
        @Override
        public @Nullable Path chooseDroppedFile(@Unmodifiable List<Path> candidates) {
            @Unmodifiable List<Path> paths = List.copyOf(candidates);
            return paths.size() == 1 && NBTFileType.supports(paths.get(0)) ? paths.get(0) : null;
        }

        /// Returns the configured dirty-document decision.
        ///
        /// @param currentFile current dirty source
        /// @return configured confirmation
        @Override
        public boolean confirmDiscardChanges(Path currentFile) {
            Objects.requireNonNull(currentFile, "currentFile");
            return confirmDiscard;
        }
    }
}
