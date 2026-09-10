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
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.io.NBTReadReport;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
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
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(NBTEditorStrings.english().fileFilter().contains("*.nbt"));
        assertTrue(NBTEditorStrings.english().fileFilter().contains("*.xyml_old"));
        assertEquals("Create NBT file", NBTEditorStrings.english().newTooltip());
        assertEquals("新建 NBT 文件", NBTEditorStrings.simplifiedChinese().newTooltip());
        assertEquals("new_tag", NBTEditorStrings.traditionalChinese().defaultTagName());
        assertEquals(
                "Editing was interrupted. Reload this file before continuing.",
                NBTEditorStrings.english().editUncertainText());
    }

    /// Routes the independent new-file command through the controller and leaves the target absent until save.
    @Test
    void createsNewFileFromTheToolbar() throws Exception {
        Path target = temporaryDirectory.resolve("toolbar-created.nbt");
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor iconExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        RecordingInteractions interactions = new RecordingInteractions(target);
        interactions.chosenNewFile = target;
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                interactions,
                () -> { },
                iconExecutor));
        try {
            iconExecutor.runAll();
            onEdt(() -> {
                AbstractButton create = findNamed(panel, "nbtEditorNew", AbstractButton.class);
                assertTrue(create.isEnabled());
                create.doClick();
            });
            assertEquals(NBTEditorStatus.OPENING, controller.snapshot().status());
            ioExecutor.runNext();
            flushEdt();
            assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
            assertTrue(controller.snapshot().dirty());
            assertFalse(Files.exists(target));
            assertTrue(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Keeps tolerant-read diagnostics visible and requires explicit approval before a repair-only save.
    @Test
    void requiresRepairConfirmationForRecoveredSource() throws Exception {
        Path source = temporaryDirectory.resolve("recovered.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        byte[] damaged = Files.readAllBytes(source);
        damaged[damaged.length - 8] ^= 1;
        Files.write(source, damaged);

        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor iconExecutor = new ManualExecutor();
        RecordingInteractions interactions = new RecordingInteractions(source);
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                interactions,
                () -> { },
                iconExecutor));
        try {
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            iconExecutor.runAll();
            flushEdt();

            assertTrue(controller.snapshot().document().requiresRepair());
            assertTrue(findNamed(panel, "nbtEditorReadWarning", JComponent.class).isVisible());
            assertTrue(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());

            onEdt(() -> findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick());
            assertEquals(1, interactions.repairConfirmations());
            assertFalse(interactions.repairApproved());
            assertEquals(0, ioExecutor.pendingCount());

            interactions.setRepairApproved(true);
            onEdt(() -> findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick());
            assertEquals(2, interactions.repairConfirmations());
            ioExecutor.runNext();
            awaitControllerStatus(controller, NBTEditorStatus.READY);
            assertFalse(controller.snapshot().document().requiresRepair());
            assertFalse(findNamed(panel, "nbtEditorReadWarning", JComponent.class).isVisible());
            assertFalse(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
            assertEquals(1, NBTCodec.of().readTag(source, TagType.COMPOUND).getInt("value"));
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
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
        iconExecutor.awaitPendingCount(1);
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
        ioExecutor.awaitPendingCount(1);
        ioExecutor.runNext();
        flushEdt();

        onEdt(() -> {
            assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
            JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
            NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
            JTabbedPane tabs = findNamed(panel, "nbtEditorTabs", JTabbedPane.class);
            assertEquals(2, tabs.getTabCount());
            assertEquals("Fields", tabs.getTitleAt(0));
            assertEquals("Subtree SNBT", tabs.getTitleAt(1));
            JPopupMenu popup = Objects.requireNonNull(tree.getComponentPopupMenu(), "tree popup");
            assertEquals(7, popup.getComponentCount());
            assertEquals("Add tag", ((JMenuItem) popup.getComponent(0)).getText());
            assertEquals("Copy", ((JMenuItem) popup.getComponent(1)).getText());
            assertEquals("Paste", ((JMenuItem) popup.getComponent(2)).getText());
            assertEquals("Delete", ((JMenuItem) popup.getComponent(4)).getText());
            assertEquals("Move up", ((JMenuItem) popup.getComponent(5)).getText());
            assertEquals("Move down", ((JMenuItem) popup.getComponent(6)).getText());
            assertEquals("save", panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)));
            assertEquals("undo", panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)));
            assertEquals("redo", panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)));
            assertEquals("delete", tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)));
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
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            assertTrue(value.isEnabled());
            assertEquals("1", value.getText());
            value.setText("41");
            AbstractButton apply = findNamed(panel, "nbtEditorApply", AbstractButton.class);
            assertNull(apply.getClientProperty("JButton.buttonType"));
            apply.doClick();
            assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
            assertFalse(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
        });
        ioExecutor.awaitPendingCount(1);
        ioExecutor.runNext();
        flushEdt();

        onEdt(() -> {
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            assertTrue(controller.snapshot().dirty());
            assertTrue(
                    findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled(),
                    findNamed(panel, "nbtEditorEditStatus", JLabel.class).getToolTipText());
            assertEquals("41", value.getText());
            AbstractButton undo = findNamed(panel, "nbtEditorUndo", AbstractButton.class);
            AbstractButton redo = findNamed(panel, "nbtEditorRedo", AbstractButton.class);
            assertTrue(undo.isEnabled());
            undo.doClick();
        });
        ioExecutor.runNext();
        flushEdt();
        onEdt(() -> {
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            assertFalse(controller.snapshot().dirty());
            assertEquals("1", value.getText());
            AbstractButton redo = findNamed(panel, "nbtEditorRedo", AbstractButton.class);
            assertTrue(redo.isEnabled());
            redo.doClick();
        });
        ioExecutor.runNext();
        flushEdt();
        onEdt(() -> {
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            assertTrue(controller.snapshot().dirty());
            assertEquals("41", value.getText());
            assertPaintsOpaqueContent(panel);
        });

        interactions.confirmDiscard = false;
        assertFalse(onEdt(() -> panel.openDroppedPaths(List.of(second))));
        assertEquals(0, ioExecutor.pendingCount());
        interactions.confirmDiscard = true;
        assertTrue(onEdt(() -> panel.openDroppedPaths(List.of(second))));
        ioExecutor.awaitPendingCount(1);
        ioExecutor.runNext();
        flushEdt();
        assertEquals(second.toAbsolutePath().normalize(), controller.snapshot().file());
        assertFalse(controller.snapshot().dirty());
        ioExecutor.runAll();

        onEdt(() -> {
            JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
            NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
            tree.setSelectionPath(model.pathForAddress(List.of(0)));
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            value.setText("6");
            findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
        });
        ioExecutor.runNext();
        flushEdt();
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

    /// Coalesces busy routes and prevents a stale confirmation from overtaking a reentrant newer route.
    @Test
    void opensOnlyTheLatestRouteRequestAfterBusyEditing() throws Exception {
        Path source = temporaryDirectory.resolve("busy-source.dat");
        Path superseded = temporaryDirectory.resolve("superseded.dat");
        Path pending = temporaryDirectory.resolve("pending.dat");
        Path expected = temporaryDirectory.resolve("reentrant-latest.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        writeTag(superseded, new CompoundTag().addInt("value", 2));
        writeTag(pending, new CompoundTag().addInt("value", 3));
        writeTag(expected, new CompoundTag().addInt("value", 4));
        ManualExecutor ioExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        List<Path> openingPaths = new ArrayList<>();
        controller.subscribe(change -> {
            @Nullable NBTEditorSnapshot current = change.currentValue();
            if (current != null && current.status() == NBTEditorStatus.OPENING && current.file() != null) {
                openingPaths.add(current.file());
            }
        });
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
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                value.setText("4");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                panel.open(superseded);
                panel.open(pending.getParent().resolve("child").resolve("..").resolve(pending.getFileName()));
            });
            assertEquals(0, interactions.discardConfirmations);
            interactions.discardConfirmationAction = () -> panel.open(expected);

            ioExecutor.runNext();
            awaitControllerStatus(controller, NBTEditorStatus.OPENING);
            assertEquals(2, interactions.discardConfirmations);
            ioExecutor.awaitPendingCount(1);
            assertEquals(1, ioExecutor.pendingCount());

            ioExecutor.runNext();
            flushEdt();
            assertEquals(expected.toAbsolutePath().normalize(), controller.snapshot().file());
            assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
            assertEquals(2, interactions.discardConfirmations);
            assertEquals(4, ioExecutor.submissionCount());
            assertEquals(List.of(source.toAbsolutePath().normalize(), expected.toAbsolutePath().normalize()),
                    openingPaths);
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Discards a retained route request when the panel closes during controller work.
    @Test
    void doesNotOpenARetainedRouteAfterClose() throws Exception {
        Path source = temporaryDirectory.resolve("closing-source.dat");
        Path retained = temporaryDirectory.resolve("retained.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        writeTag(retained, new CompoundTag().addInt("value", 2));
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
        onEdt(() -> panel.open(source));
        ioExecutor.runNext();
        flushEdt();

        onEdt(() -> {
            JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
            NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
            tree.setSelectionPath(model.pathForAddress(List.of(0)));
            JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
            value.setText("3");
            findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
            panel.open(retained);
        });
        assertEquals(2, ioExecutor.submissionCount());

        panel.close();
        ioExecutor.runAll();
        flushEdt();
        assertEquals(NBTEditorStatus.CLOSED, controller.snapshot().status());
        assertEquals(0, interactions.discardConfirmations);
        assertEquals(3, ioExecutor.submissionCount());
    }

    /// Prevents accidental classpath image reads from being moved back onto the EDT.
    @Test
    void rejectsSynchronousNbtIconDecodingOnTheEdt() {
        assertThrows(IllegalStateException.class, () -> onEdt(NBTTreeCellRenderer::loadIcons));
    }

    /// Disables Swing HTML interpretation for untrusted NBT names and scalar values.
    @Test
    void rendersNbtTextWithoutHtmlInterpretation() {
        JLabel rendered = onEdt(() -> {
            NBTTreeCellRenderer renderer = new NBTTreeCellRenderer(NBTEditorStrings.english());
            return (JLabel) renderer.getTreeCellRendererComponent(
                    new JTree(),
                    "<html><img src='https://invalid.example/image'>",
                    false,
                    false,
                    true,
                    0,
                    false);
        });

        assertEquals(Boolean.TRUE, rendered.getClientProperty("html.disable"));
        assertNull(rendered.getClientProperty("html"));
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

    /// Reports an externally replaced source as an ordinary failed save and does not queue a redundant clean save.
    @Test
    void disablesSaveAfterAStaleSourceFailure() throws Exception {
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
                findNamed(panel, "nbtEditorValue", JTextPane.class).setText("2");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            });
            ioExecutor.runNext();
            flushEdt();
            writeTag(source, new CompoundTag().addInt("value", 99));
            onEdt(() -> findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick());
            ioExecutor.runNext();
            awaitControllerStatus(controller, NBTEditorStatus.READY);
            flushEdt();
            onEdt(() -> {
                assertEquals(NBTEditorStatus.READY, controller.snapshot().status());
                assertFalse(findNamed(panel, "nbtEditorSave", AbstractButton.class).isEnabled());
                findNamed(panel, "nbtEditorSave", AbstractButton.class).doClick();
            });
            assertEquals(0, ioExecutor.pendingCount());
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Retains invalid scalar and name drafts while background validation disables the form.
    @Test
    void retainsRejectedFieldDraftsAcrossBackgroundValidation() throws Exception {
        Path source = temporaryDirectory.resolve("invalid-fields.dat");
        writeTag(source, new CompoundTag().addInt("value", 1).addInt("other", 2));
        ManualExecutor ioExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { }));
        try {
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                value.setText("not-an-int");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                assertEquals("not-an-int", value.getText());
                assertFalse(value.isEnabled());
            });
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("not-an-int", value.getText());
                assertEquals("error", value.getClientProperty("JComponent.outline"));
                JTextField name = findNamed(panel, "nbtEditorNodeName", JTextField.class);
                name.setText("other");
                findNamed(panel, "nbtEditorRename", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                assertEquals("other", name.getText());
                assertFalse(name.isEnabled());
            });
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                assertEquals("other", findNamed(panel, "nbtEditorNodeName", JTextField.class).getText());
                assertFalse(controller.snapshot().dirty());
            });
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Exposes type conversion, complete numeric aggregates, SNBT, endpoint menus, and String preview.
    @Test
    void supportsAdvancedStructuredEditingWithoutLosingDrafts() throws Exception {
        Path source = temporaryDirectory.resolve("advanced.dat");
        ListTag<IntTag> numbers = new ListTag<>(TagType.INT);
        numbers.addTag(new IntTag(1)).addTag(new IntTag(2));
        CompoundTag discontinuous = new CompoundTag().addInt("0", 0).addInt("2", 2);
        writeTag(source, new CompoundTag()
                .addInt("number", 255)
                .addByteArray("bytes", new byte[]{0, 127, -1})
                .addString("message", "A\u00a7cB")
                .addTag("numbers", numbers)
                .addTag("map", discontinuous)
                .addDouble("floating", 0.0D));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor backgroundExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor), SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { },
                backgroundExecutor));
        try {
            backgroundExecutor.runNext();
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                JComboBox<?> radix = findNamed(panel, "nbtEditorNumberRadix", JComboBox.class);
                assertTrue(radix.isVisible());
                assertEquals("Decimal", radix.getSelectedItem());
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                value.setText("invalid");
                radix.setSelectedIndex(1);
                assertEquals("invalid", value.getText());
                assertFalse(radix.isEnabled());
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JComboBox<?> radix = findNamed(panel, "nbtEditorNumberRadix", JComboBox.class);
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("Decimal", radix.getSelectedItem());
                assertEquals("invalid", value.getText());
                assertTrue(radix.isEnabled());
                value.setText("511");
                radix.setSelectedIndex(1);
                assertEquals("511", value.getText());
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JComboBox<?> radix = findNamed(panel, "nbtEditorNumberRadix", JComboBox.class);
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("0x1FF", value.getText());
                radix.setSelectedIndex(0);
                assertEquals("0x1FF", value.getText());
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                assertEquals("511", findNamed(panel, "nbtEditorValue", JTextPane.class).getText());
                JComboBox<?> type = findNamed(panel, "nbtEditorNodeType", JComboBox.class);
                assertTrue(type.isEnabled());
                type.setSelectedItem(TagType.STRING.name());
                assertTrue(findNamed(panel, "nbtEditorTypeApply", AbstractButton.class).isEnabled());
                findNamed(panel, "nbtEditorTypeApply", AbstractButton.class).doClick();
            });
            ioExecutor.runNext();
            flushEdt();
            assertEquals("255", ((StringTag) ((CompoundTag) Objects.requireNonNull(
                    controller.snapshot().document(), "document").rootSnapshot()).get("number")).getValue());

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(1)));
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertFalse(value.isEnabled());
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(1)));
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("0, 127, -1", value.getText());
                assertTrue(value.isEnabled());
                findNamed(panel, "nbtEditorNumberRadix", JComboBox.class).setSelectedIndex(1);
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("0x0, 0x7F, 0xFF", value.getText());
                value.setText("A, 14, E2");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            });
            ioExecutor.runNext();
            flushEdt();
            ByteArrayTag bytes = (ByteArrayTag) ((CompoundTag) Objects.requireNonNull(
                    controller.snapshot().document(), "document").rootSnapshot()).get("bytes");
            assertArrayEquals(new byte[]{10, 20, -30}, bytes.getArray());

            onEdt(() -> findNamed(panel, "nbtEditorTabs", JTabbedPane.class).setSelectedIndex(1));
            backgroundExecutor.runAll();
            flushEdt();
            onEdt(() -> {
                JTextArea snbt = findNamed(panel, "nbtEditorSnbt", JTextArea.class);
                assertTrue(snbt.getText().startsWith("[B;"));
                assertTrue(snbt.getText().contains("10B"));
                assertTrue(findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).isEnabled());
                findNamed(panel, "nbtEditorTabs", JTabbedPane.class).setSelectedIndex(0);
                findNamed(panel, "nbtEditorNumberRadix", JComboBox.class).setSelectedIndex(0);
            });

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(3)));
            });
            backgroundExecutor.runAll();
            flushEdt();
            onEdt(() -> {
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("1, 2", value.getText());
                assertTrue(value.isEnabled());
                value.setText("7, 8");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            });
            ioExecutor.runNext();
            flushEdt();
            ListTag<?> editedNumbers = (ListTag<?>) ((CompoundTag) Objects.requireNonNull(
                    controller.snapshot().document(), "document").rootSnapshot()).get("numbers");
            assertEquals(7, ((IntTag) editedNumbers.getTag(0)).getValue());
            assertEquals(8, ((IntTag) editedNumbers.getTag(1)).getValue());

            onEdt(() -> findNamed(panel, "nbtEditorTabs", JTabbedPane.class).setSelectedIndex(1));
            backgroundExecutor.runAll();
            flushEdt();
            onEdt(() -> {
                JTextArea snbt = findNamed(panel, "nbtEditorSnbt", JTextArea.class);
                assertTrue(snbt.isEnabled());
                assertTrue(snbt.getText().startsWith("["));
                assertTrue(snbt.getText().contains("7"));
                assertTrue(snbt.getText().contains("8"));
                findNamed(panel, "nbtEditorTabs", JTabbedPane.class).setSelectedIndex(0);
            });

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(1, 0)));
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                assertEquals("10", value.getText());
                assertTrue(value.isEnabled());
                value.setText("-1");
                findNamed(panel, "nbtEditorApply", AbstractButton.class).doClick();
            });
            ioExecutor.runNext();
            flushEdt();
            bytes = (ByteArrayTag) ((CompoundTag) Objects.requireNonNull(
                    controller.snapshot().document(), "document").rootSnapshot()).get("bytes");
            assertArrayEquals(new byte[]{-1, 20, -30}, bytes.getArray());

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(2)));
                JTextPane value = findNamed(panel, "nbtEditorValue", JTextPane.class);
                value.setText("A\u00a7cB");
                value.setCaretPosition(1);
                findNamed(panel, "nbtEditorSectionSign", AbstractButton.class).doClick();
                assertEquals("A\u00a7\u00a7cB", value.getText());
                value.setText("A\u00a7cB");
                findNamed(panel, "nbtEditorFormattingPreviewToggle", JCheckBox.class).doClick();
                assertEquals("A\u00a7cB", value.getText());
                assertEquals(new java.awt.Color(0xFF5555), StyleConstants.getForeground(
                        value.getStyledDocument().getCharacterElement(3).getAttributes()));
                assertNull(findNamedOrNull(panel, "nbtEditorFormattingPreview", JComponent.class));

                tree.setSelectionPath(model.pathForAddress(List.of(3, 0)));
                JPopupMenu popup = Objects.requireNonNull(tree.getComponentPopupMenu(), "tree popup");
                assertFalse(popup.getComponent(5).isVisible());
                assertTrue(popup.getComponent(6).isVisible());
                assertTrue(popup.getComponent(6).isEnabled());
                tree.setSelectionPath(model.pathForAddress(List.of(3, 1)));
                assertTrue(popup.getComponent(5).isVisible());
                assertTrue(popup.getComponent(5).isEnabled());
                assertFalse(popup.getComponent(6).isVisible());

                tree.setSelectionPath(model.pathForAddress(List.of(5)));
                assertEquals("0.0", findNamed(panel, "nbtEditorValue", JTextPane.class).getText());
                assertFalse(findNamed(panel, "nbtEditorNumberRadix", JComboBox.class).isVisible());

                tree.setSelectionPath(model.pathForAddress(List.of(4)));
                JComboBox<?> type = findNamed(panel, "nbtEditorNodeType", JComboBox.class);
                assertEquals(1, type.getItemCount());
                assertEquals(TagType.COMPOUND.name(), type.getSelectedItem());
                tree.setSelectionPath(model.pathForAddress(List.of()));
                assertFalse(type.isEnabled());
            });
        } finally {
            panel.close();
            ioExecutor.runAll();
            backgroundExecutor.runAll();
            flushEdt();
        }
    }

    /// Ignores late copy and delete completions after the user changes the selected row.
    @Test
    void ignoresLateContextCommandResultsAfterSelectionChanges() throws Exception {
        Path source = temporaryDirectory.resolve("late-context-command.dat");
        writeTag(source, new CompoundTag().addInt("first", 1).addInt("second", 2));
        ManualExecutor ioExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { }));
        try {
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                findNamed(panel, "nbtEditorCopy", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                tree.setSelectionPath(model.pathForAddress(List.of(1)));
            });
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTEditorTreeNode selected = (NBTEditorTreeNode) tree.getLastSelectedPathComponent();
                assertEquals("second", selected.node().getName());
                assertNotEquals("Copied", findNamed(panel, "nbtEditorEditStatus", JLabel.class).getText());
            });

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                findNamed(panel, "nbtEditorDelete", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                tree.setSelectionPath(model.pathForAddress(List.of(1)));
            });
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTEditorTreeNode selected = (NBTEditorTreeNode) tree.getLastSelectedPathComponent();
                assertEquals("second", selected.node().getName());
                assertEquals("second", findNamed(panel, "nbtEditorNodeName", JTextField.class).getText());
            });
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Keeps Region slots fixed and requires explicit confirmation before clearing a chunk root.
    @Test
    void protectsFixedRegionStructureAndChunkClearing() throws Exception {
        Path source = temporaryDirectory.resolve("r.0.0.mca");
        ChunkRegion region = new ChunkRegion();
        region.setChunk(1023, new Chunk(new CompoundTag().addInt("DataVersion", 3953)));
        NBTCodec.of().writeRegion(source, region);
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
                tree.setSelectionPath(model.pathForAddress(List.of()));
                assertEquals("Region",
                        findNamed(panel, "nbtEditorNodeType", JComboBox.class).getSelectedItem());
                tree.setSelectionPath(model.pathForAddress(List.of(1023)));
                assertEquals("Chunk",
                        findNamed(panel, "nbtEditorNodeType", JComboBox.class).getSelectedItem());
                assertFalse(findNamed(panel, "nbtEditorValue", JTextPane.class).isEnabled());
                assertFalse(findNamed(panel, "nbtEditorSnbt", JTextArea.class).isEnabled());
                assertFalse(findNamed(panel, "nbtEditorDelete", AbstractButton.class).isEnabled());
                assertFalse(findNamed(panel, "nbtEditorMoveUp", AbstractButton.class).isEnabled());
                assertFalse(findNamed(panel, "nbtEditorMoveDown", AbstractButton.class).isEnabled());
                Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(
                        tree,
                        tree.getLastSelectedPathComponent(),
                        true,
                        false,
                        false,
                        1,
                        false);
                assertEquals("Chunk", ((JLabel) rendered).getToolTipText());

                tree.setSelectionPath(model.pathForAddress(List.of(1023, 0)));
                assertEquals("Chunk root",
                        findNamed(panel, "nbtEditorNodeName", JTextField.class).getText());
                assertFalse(findNamed(panel, "nbtEditorSnbt", JTextArea.class).isEnabled());
                AbstractButton delete = findNamed(panel, "nbtEditorDelete", AbstractButton.class);
                assertTrue(delete.isEnabled());
                delete.doClick();
                assertFalse(controller.snapshot().dirty());
            });
            assertEquals(1, interactions.clearChunkConfirmations);

            interactions.confirmClearChunk = true;
            onEdt(() -> findNamed(panel, "nbtEditorDelete", AbstractButton.class).doClick());
            assertEquals(2, interactions.clearChunkConfirmations);
            ioExecutor.runNext();
            flushEdt();
            assertTrue(controller.snapshot().dirty());
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                NBTEditorTreeNode chunk = (NBTEditorTreeNode) model.pathForAddress(List.of(1023))
                        .getLastPathComponent();
                assertEquals(0, chunk.childCount());
            });
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Shows localized validation beside the SNBT editor while retaining technical hover detail.
    @Test
    void showsLocalizedSnbtValidationFeedback() throws Exception {
        Path source = temporaryDirectory.resolve("invalid-snbt.dat");
        writeTag(source, new CompoundTag().addInt("value", 1));
        ManualExecutor ioExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        ManualExecutor backgroundExecutor = new ManualExecutor();
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.simplifiedChinese(),
                new RecordingInteractions(source),
                () -> { },
                backgroundExecutor));
        try {
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                JTabbedPane tabs = findNamed(panel, "nbtEditorTabs", JTabbedPane.class);
                tabs.setSelectedIndex(1);
                assertEquals("正在加载子树 SNBT...",
                        findNamed(panel, "nbtEditorSnbtStatus", JLabel.class).getText());
                assertFalse(findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).isEnabled());
            });
            backgroundExecutor.awaitPendingCount(1);
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTextArea snbt = findNamed(panel, "nbtEditorSnbt", JTextArea.class);
                assertTrue(snbt.isEnabled());
                snbt.setText("{broken");
                findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).doClick();
                assertEquals(NBTEditorStatus.EDITING, controller.snapshot().status());
                assertEquals("{broken", snbt.getText());
                assertFalse(snbt.isEnabled());
            });
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTextArea snbt = findNamed(panel, "nbtEditorSnbt", JTextArea.class);
                JLabel status = findNamed(panel, "nbtEditorSnbtStatus", JLabel.class);
                assertEquals("{broken", snbt.getText());
                assertEquals("请输入符合所选 NBT 类型的值。", status.getText());
                assertNotNull(status.getToolTipText());
                assertEquals("error", snbt.getClientProperty("JComponent.outline"));
                assertFalse(controller.snapshot().dirty());
            });
        } finally {
            panel.close();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Loads SNBT only for its visible tab and rejects a completion from an obsolete selection.
    @Test
    void loadsSnbtLazilyAndDiscardsObsoleteSelectionResults() throws Exception {
        Path source = temporaryDirectory.resolve("lazy-snbt.dat");
        writeTag(source, new CompoundTag().addInt("first", 1).addInt("second", 2));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor backgroundExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { },
                backgroundExecutor));
        try {
            backgroundExecutor.awaitPendingCount(1);
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();

            onEdt(() -> {
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(0)));
                assertFalse(findNamed(panel, "nbtEditorSnbt", JTextArea.class).isEnabled());
            });
            assertEquals(0, backgroundExecutor.pendingCount());

            onEdt(() -> {
                JTabbedPane tabs = findNamed(panel, "nbtEditorTabs", JTabbedPane.class);
                tabs.setSelectedIndex(1);
                assertEquals("Loading subtree SNBT...",
                        findNamed(panel, "nbtEditorSnbtStatus", JLabel.class).getText());
                JTree tree = findNamed(panel, "nbtEditorTree", JTree.class);
                NBTLazyTreeModel model = (NBTLazyTreeModel) tree.getModel();
                tree.setSelectionPath(model.pathForAddress(List.of(1)));
            });
            backgroundExecutor.awaitPendingCount(2);

            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                assertEquals("", findNamed(panel, "nbtEditorSnbt", JTextArea.class).getText());
                assertEquals("Loading subtree SNBT...",
                        findNamed(panel, "nbtEditorSnbtStatus", JLabel.class).getText());
            });
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> {
                JTextArea snbt = findNamed(panel, "nbtEditorSnbt", JTextArea.class);
                assertEquals("2I", snbt.getText().trim());
                assertTrue(snbt.isEnabled());
                assertEquals(" ", findNamed(panel, "nbtEditorSnbtStatus", JLabel.class).getText());
            });
        } finally {
            panel.close();
            backgroundExecutor.runAll();
            ioExecutor.runAll();
            flushEdt();
        }
    }

    /// Releases a completed text load rejected by a changed backing revision so the row can retry.
    @Test
    void releasesRejectedCurrentTextLoad() {
        ManualExecutor backgroundExecutor = new ManualExecutor();
        JTextPane target = onEdt(() -> new JTextPane());
        NBTAsyncTextLoader loader = onEdt(() -> new NBTAsyncTextLoader(target, 4, backgroundExecutor));
        AtomicReference<Boolean> accepted = new AtomicReference<>(true);
        onEdt(() -> loader.reset("row"));
        onEdt(() -> loader.load(
                "row",
                () -> "stale",
                accepted::get,
                () -> { },
                () -> { },
                failure -> { }));
        accepted.set(false);
        backgroundExecutor.runNext();
        flushEdt();
        assertFalse(onEdt(loader::isLoading));

        accepted.set(true);
        onEdt(() -> loader.load(
                "row",
                () -> "fresh",
                accepted::get,
                () -> { },
                () -> { },
                failure -> { }));
        assertTrue(onEdt(loader::isLoading));
        backgroundExecutor.runNext();
        flushEdt();
        assertEquals("fresh", onEdt(() -> target.getText()));

        AtomicInteger acceptedCalls = new AtomicInteger();
        onEdt(() -> {
            loader.reset("chunked");
            target.setText("draft");
        });
        onEdt(() -> loader.load(
                "chunked",
                () -> "0123456789",
                () -> acceptedCalls.getAndIncrement() < 2,
                () -> { },
                () -> { },
                failure -> { }));
        backgroundExecutor.runNext();
        flushEdt();
        flushEdt();
        assertFalse(onEdt(loader::isLoading));
        assertEquals("draft", onEdt(() -> target.getText()));
        onEdt(loader::close);
    }

    /// Restores the complete prior draft when a later styled-document chunk is rejected.
    @Test
    void reportsStyledDocumentInsertionFailure() {
        ManualExecutor backgroundExecutor = new ManualExecutor();
        DefaultStyledDocument rejectingDocument = new DefaultStyledDocument() {
            /// Serialization identifier for the Swing document superclass contract.
            private static final long serialVersionUID = 1L;

            /// Rejects the second asynchronous chunk to exercise rollback after partial insertion.
            /// @param offset requested insertion offset
            /// @param text requested text
            /// @param attributes requested attributes
            /// @throws BadLocationException for the configured second chunk
            @Override
            public void insertString(int offset, String text, @Nullable AttributeSet attributes)
                    throws BadLocationException {
                if ("4567".equals(text)) {
                    throw new BadLocationException("rejected chunk", offset);
                }
                super.insertString(offset, text, attributes);
            }
        };
        JTextPane target = onEdt(() -> new JTextPane(rejectingDocument));
        NBTAsyncTextLoader loader = onEdt(() -> new NBTAsyncTextLoader(target, 4, backgroundExecutor));
        AtomicReference<@Nullable String> failure = new AtomicReference<>();
        AtomicInteger successes = new AtomicInteger();

        onEdt(() -> {
            loader.reset("row");
            target.setText("draft");
            target.setCaretPosition(1);
            target.moveCaretPosition(4);
        });
        onEdt(() -> loader.load(
                "row",
                () -> "0123456789",
                () -> true,
                () -> { },
                successes::incrementAndGet,
                failure::set));
        backgroundExecutor.runNext();
        flushEdt();

        assertFalse(onEdt(loader::isLoading));
        assertFalse(onEdt(() -> loader.isLoaded("row")));
        assertEquals(0, successes.get());
        assertEquals("rejected chunk", failure.get());
        assertEquals("draft", onEdt(() -> target.getText()));
        assertEquals(4, onEdt(() -> target.getCaret().getDot()));
        assertEquals(1, onEdt(() -> target.getCaret().getMark()));
        onEdt(loader::close);
    }

    /// Inserts a large serialized subtree over multiple EDT turns before enabling replacement.
    @Test
    void batchesLargeSnbtInsertionOnTheEdt() throws Exception {
        Path source = temporaryDirectory.resolve("large-snbt.dat");
        writeTag(source, new CompoundTag().addByteArray("large", new byte[100_000]));
        ManualExecutor ioExecutor = new ManualExecutor();
        ManualExecutor backgroundExecutor = new ManualExecutor();
        NBTEditorController controller = new NBTEditorController(
                new NBTDocumentService(ioExecutor),
                SwingUiDispatcher.INSTANCE);
        NBTEditorPanel panel = onEdt(() -> new NBTEditorPanel(
                controller,
                NBTEditorStrings.english(),
                new RecordingInteractions(source),
                () -> { },
                backgroundExecutor));
        try {
            backgroundExecutor.runNext();
            flushEdt();
            onEdt(() -> panel.open(source));
            ioExecutor.runNext();
            flushEdt();
            onEdt(() -> findNamed(panel, "nbtEditorTabs", JTabbedPane.class).setSelectedIndex(1));
            backgroundExecutor.runNext();
            flushEdt();

            int firstChunkLength = onEdt(() ->
                    findNamed(panel, "nbtEditorSnbt", JTextArea.class).getText().length());
            assertTrue(firstChunkLength > 0);
            assertTrue(firstChunkLength < 100_000);
            assertFalse(onEdt(() ->
                    findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).isEnabled()));

            int remainingTurns = 100;
            while (!onEdt(() ->
                    findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).isEnabled())
                    && remainingTurns-- > 0) {
                flushEdt();
            }
            assertTrue(onEdt(() ->
                    findNamed(panel, "nbtEditorReplaceSnbt", AbstractButton.class).isEnabled()));
            String complete = onEdt(() ->
                    findNamed(panel, "nbtEditorSnbt", JTextArea.class).getText());
            assertTrue(complete.length() > 250_000);
            assertTrue(complete.contains("large"));
        } finally {
            panel.close();
            backgroundExecutor.runAll();
            ioExecutor.runAll();
            flushEdt();
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
            graphics.setColor(panel.getBackground());
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue((image.getRGB(500, 350) >>> 24) != 0);
        assertFalse(panel.isOpaque());
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
            ioExecutor.runAll();
            flushEdt();
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
        Rectangle listTypeLabel = componentBounds(
                panel,
                findNamed(panel, "nbtEditorListTypeLabel", JLabel.class));
        Rectangle listType = componentBounds(panel, findNamed(panel, "nbtEditorListType", JComponent.class));
        assertFalse(back.intersects(open));
        assertFalse(open.intersects(reload));
        assertFalse(reload.intersects(save));
        assertTrue(apply.y - value.getMaxY() <= 16.0D);
        assertFalse(listTypeLabel.intersects(listType));
        assertTrue(listType.y >= listTypeLabel.getMaxY());
        assertTrue(tree.getWidth() >= 300);
        assertTrue(findNamed(panel, "nbtEditorValue", JTextPane.class).getWidth() >= 200);

        BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(panel.getBackground());
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            panel.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        assertTrue((image.getRGB(500, 350) >>> 24) != 0);
        assertEquals(255, image.getRGB(0, 0) >>> 24);
        assertEquals(255, image.getRGB(999, 699) >>> 24);
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

    /// Waits for an asynchronous controller transition while keeping the Swing event queue responsive.
    ///
    /// @param condition expected state predicate
    private static void awaitEdtCondition(BooleanSupplier condition) {
        BooleanSupplier selected = Objects.requireNonNull(condition, "condition");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!selected.getAsBoolean() && System.nanoTime() < deadline) {
            flushEdt();
            Thread.yield();
        }
        assertTrue(selected.getAsBoolean(), "Timed out waiting for the Swing controller transition");
    }

    /// Waits for one controller status from a non-EDT test call.
    ///
    /// @param controller controller under test
    /// @param expected expected status
    private static void awaitControllerStatus(NBTEditorController controller, NBTEditorStatus expected) {
        awaitEdtCondition(() -> controller.snapshot().status() == expected);
    }

    /// Deterministic executor proving when blocking work is allowed to run.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Maximum time a test waits for asynchronous resource resolution to enqueue its command.
        private static final long COMMAND_TIMEOUT_SECONDS = 5L;

        /// FIFO of submitted blocking operations.
        private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();

        /// Total operations submitted over this executor's lifetime.
        private final AtomicInteger submissions = new AtomicInteger();

        /// Queues one operation.
        ///
        /// @param command submitted operation
        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            commands.add(command);
        }

        /// Returns the pending operation count.
        ///
        /// @return queued count
        private int pendingCount() {
            return commands.size();
        }

        /// Returns the total operation count submitted so far.
        ///
        /// @return submitted operation count
        private int submissionCount() {
            return submissions.get();
        }

        /// Waits until at least the requested number of operations has been submitted.
        ///
        /// @param expected minimum operation count
        private void awaitPendingCount(int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
            while (commands.size() < expected && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(commands.size() >= expected,
                    () -> "Timed out waiting for " + expected + " executor operation(s)");
        }

        /// Runs the next operation.
        private void runNext() {
            try {
                Runnable command = commands.poll(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (command == null) {
                    throw new AssertionError("Timed out waiting for an executor operation");
                }
                command.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for an executor operation", interrupted);
            }
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

        /// Target returned by the new-document chooser, or `null` to simulate cancellation.
        private @Nullable Path chosenNewFile;

        /// Whether dirty-document replacement is confirmed.
        private boolean confirmDiscard = true;

        /// Number of dirty-document replacement confirmations requested.
        private int discardConfirmations;

        /// One-shot action invoked inside dirty confirmation to model a nested EDT event, or `null`.
        private @Nullable Runnable discardConfirmationAction;

        /// Whether destructive chunk-root clearing is confirmed.
        private boolean confirmClearChunk;

        /// Number of destructive chunk-root confirmations requested.
        private int clearChunkConfirmations;

        /// Whether tolerant-read repair publication is approved.
        private boolean repairApproved;

        /// Number of repair-save confirmations requested.
        private int repairConfirmations;

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

        /// Returns the configured new-document target.
        ///
        /// @param currentFile current source, or `null`
        /// @return configured target
        @Override
        public @Nullable Path chooseNewFile(@Nullable Path currentFile) {
            return chosenNewFile;
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
            discardConfirmations++;
            @Nullable Runnable action = discardConfirmationAction;
            discardConfirmationAction = null;
            if (action != null) {
                action.run();
            }
            return confirmDiscard;
        }

        /// Returns the configured chunk-root decision and records that confirmation was requested.
        ///
        /// @param source Region source
        /// @param localIndex fixed chunk slot
        /// @return configured confirmation
        @Override
        public boolean confirmClearChunk(Path source, int localIndex) {
            Objects.requireNonNull(source, "source");
            if (localIndex < 0 || localIndex >= 1024) {
                throw new AssertionError("Invalid local chunk index: " + localIndex);
            }
            clearChunkConfirmations++;
            return confirmClearChunk;
        }

        /// Returns the configured repair-save decision and records the prompt.
        ///
        /// @param source source to rewrite
        /// @param report tolerant-read report
        /// @return configured approval
        @Override
        public boolean confirmRepairSave(
                Path source,
                NBTReadReport report) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(report, "report");
            repairConfirmations++;
            return repairApproved;
        }

        /// Updates the repair-save decision for the next prompt.
        ///
        /// @param approved whether repair publication is approved
        private void setRepairApproved(boolean approved) {
            repairApproved = approved;
        }

        /// Returns the number of repair-save prompts.
        ///
        /// @return prompt count
        private int repairConfirmations() {
            return repairConfirmations;
        }

        /// Returns the current repair-save decision.
        ///
        /// @return whether repair publication is approved
        private boolean repairApproved() {
            return repairApproved;
        }
    }
}
