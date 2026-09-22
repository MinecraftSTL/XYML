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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.DeletionMode;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Headless tests for the independent Swing Mod page and production constructor boundary.
@NotNullByDefault
public final class ModCatalogPanelTest {
    /// Deterministic page labels.
    private static final ModCatalogStrings STRINGS = new ModCatalogStrings(
            "Mods",
            "Search",
            "State",
            "All",
            "Enabled",
            "Disabled",
            "Select a Mod",
            "Identifier",
            "Version",
            "Game version",
            "Loader",
            "Authors",
            "File",
            "Description",
            "Enabled");

    /// Deterministic model statuses.
    private static final ModCatalogStatusStrings STATUS_STRINGS = new ModCatalogStatusStrings(
            "Loading Mods",
            "No Mods",
            "%d Mods",
            "Load failed: %s",
            "Importing Mods",
            "Enabling Mod",
            "Disabling Mod",
            "Deleting Mod",
            "Write failed: %s");

    /// Deterministic action presentation.
    private static final ModCatalogActionStrings ACTION_STRINGS = new ModCatalogActionStrings(
            "Refresh",
            "Refresh Mods",
            "Import",
            "Import Mods",
            "Open directory",
            "Open the Mods directory",
            "Reveal",
            "Reveal this Mod",
            "Delete",
            "Delete this Mod",
            "Import Mods",
            "Mod archives",
            "Delete %s?",
            "Mod operation failed");

    /// Verifies viewport range loading, single selection, details, and every command boundary.
    @Test
    public void delegatesSingleSelectionAndAllLocalCommands() throws Exception {
        RecordingModel model = new RecordingModel(items(100));
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<@Nullable ModCatalogPanel> panelReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    model,
                    STRINGS,
                    ACTION_STRINGS,
                    interactions,
                    downloads::incrementAndGet,
                    checks::incrementAndGet);
            panelReference.set(panel);
            panel.setSize(new Dimension(900, 620));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<?> list = panel.choiceList().getList();
            assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
            assertFalse(model.requestedRanges().isEmpty());
            assertTrue(model.requestedRanges().get(0).length() < model.items().size());

            list.setSelectedIndex(1);
            assertEquals("mod-1", model.selectedKeys().get(0));
            assertEquals("Mod 1", findTextArea(panel, "modsDetailTitle").getText());

            findButton(panel, "modsEnabled").doClick();
            assertEquals("mod-1:false", model.enabledCommands().get(0));
            findButton(panel, "modsImport").doClick();
            assertEquals(List.of(Path.of("incoming.jar")), model.imports().get(0));
            assertEquals(Map.of(), model.importConflictActions().get(0));
            findButton(panel, "modsOpenDirectory").doClick();
            assertEquals(1, interactions.openCount());
            findButton(panel, "modsCheckUpdates").doClick();
            findButton(panel, "modsDownload").doClick();
            assertEquals(1, checks.get());
            assertEquals(1, downloads.get());
            findButton(panel, "modsReveal").doClick();
            assertEquals(model.items().get(1).path(), interactions.revealedPath());
            findButton(panel, "modsDelete").doClick();
            assertEquals(List.of("mod-1"), model.deletedKeys());

            JTextField search = findTextField(panel, "modsSearch");
            assertEquals(Boolean.TRUE, search.getClientProperty("JTextField.showClearButton"));
            search.setText("shader");
            assertEquals("shader", model.queries().get(model.queries().size() - 1));
            findComboBox(panel, "modsFilter").setSelectedItem(ModCatalogFilter.DISABLED);
            assertEquals(ModCatalogFilter.DISABLED,
                    model.filters().get(model.filters().size() - 1));
            panel.close();
        });

        assertTrue(model.closed());
        assertNotNull(panelReference.get());
    }

    /// Renders an explicitly disabled Mod row with a muted surface and its embedded logo.
    @Test
    public void rendersDisabledModSurfaceAndArchiveIcon() throws Exception {
        ModCatalogItem disabledItem = new ModCatalogItem(
                "disabled",
                Path.of("mods", "disabled.jar"),
                "disabled",
                "Disabled Mod",
                "Description",
                "Author",
                "1.0",
                "1.21.1",
                ModLoaderType.FABRIC,
                "disabled.jar",
                onePixelLogo(),
                false);
        RecordingModel model = new RecordingModel(List.of(disabledItem));
        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS,
                    new RecordingInteractions());
            JList<ChoiceListEntry<ModCatalogItem>> list = panel.choiceList().getList();
            list.setSize(new Dimension(48, 68));
            ListCellRenderer<? super ChoiceListEntry<ModCatalogItem>> renderer = list.getCellRenderer();
            Component row = renderer.getListCellRendererComponent(
                    list,
                    ChoiceListEntry.loaded(0, disabledItem),
                    0,
                    false,
                    false);
            assertFalse(row.isOpaque());
            assertEquals(list.getBackground(), row.getBackground());
            assertEquals("", findLabel(row, "richChoiceListBadge").getText());
            assertEquals(1, findLabel(row, "richChoiceListIcon").getIcon().getIconWidth());
            panel.close();
        });
    }

    /// Creates a deterministic one-pixel PNG payload for the archive-logo row test.
    ///
    /// @return Base64-encoded one-pixel PNG
    private static String onePixelLogo() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /// Same-key imports prompt for a decision and submit that exact decision with the source batch.
    @Test
    public void resolvesImportConflictsBeforeSubmittingMutation() throws Exception {
        RecordingModel model = new RecordingModel(items(1));
        RecordingInteractions interactions = new RecordingInteractions();
        Path conflict = Path.of("incoming.jar").toAbsolutePath().normalize();
        model.replaceImportConflicts(List.of(conflict));
        interactions.replaceImportConflictAction(ModImportConflictAction.KEEP);

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            findButton(panel, "modsImport").doClick();

            assertEquals(List.of(conflict), interactions.importConflictSources());
            assertEquals(List.of(Path.of("incoming.jar")), model.imports().get(0));
            assertEquals(
                    Map.of(conflict, ModImportConflictAction.KEEP),
                    model.importConflictActions().get(0));
            panel.close();
        });
    }

    /// A failed import presents one Retry action that replays the exact captured batch.
    @Test
    public void retriesFailedImportWithExactCapturedBatch() throws Exception {
        RecordingModel model = new RecordingModel(items(1));
        RecordingInteractions interactions = new RecordingInteractions();
        model.replaceImportFailure(new IOException("locked"));

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            findButton(panel, "modsImport").doClick();

            @Nullable Runnable retryAction = interactions.retryAction();
            assertNotNull(retryAction);
            retryAction.run();

            assertEquals(2, model.imports().size());
            assertEquals(model.imports().get(0), model.imports().get(1));
            assertEquals(
                    model.importConflictActions().get(0),
                    model.importConflictActions().get(1));
            panel.close();
        });
    }

    /// An access-denied publish failure identifies the target instead of showing the raw path pair.
    @Test
    public void explainsAccessDeniedWithTargetPath() throws Exception {
        RecordingModel model = new RecordingModel(items(1));
        RecordingInteractions interactions = new RecordingInteractions();
        Path target = Path.of("terra-1.0.0-both.jar").toAbsolutePath().normalize();
        model.replaceImportFailure(new AccessDeniedException("publish.tmp", target.toString(), null));
        String expectedDetail = i18n("exception.file_in_use", target.toString());

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            findButton(panel, "modsImport").doClick();

            @Nullable String retryDetail = interactions.retryDetail();
            assertEquals(expectedDetail, retryDetail);
            assertTrue(Objects.requireNonNull(retryDetail).contains(target.toString()));
            assertFalse(retryDetail.contains(" -> "));
            panel.close();
        });
    }

    /// A conflict discovered during background preflight returns to the same choice flow and retries.
    @Test
    public void resolvesLateImportConflictAndRetriesMutation() throws Exception {
        RecordingModel model = new RecordingModel(items(1));
        RecordingInteractions interactions = new RecordingInteractions();
        Path conflict = Path.of("incoming.jar").toAbsolutePath().normalize();
        model.replaceLateImportConflict(conflict);
        interactions.replaceImportConflictAction(ModImportConflictAction.SKIP);

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            findButton(panel, "modsImport").doClick();

            assertEquals(List.of(conflict), interactions.importConflictSources());
            assertEquals(2, model.imports().size());
            assertEquals(Map.of(), model.importConflictActions().get(0));
            assertEquals(
                    Map.of(conflict, ModImportConflictAction.SKIP),
                    model.importConflictActions().get(1));
            panel.close();
        });
    }

    /// A page event queued before deletion cannot reselect a row that has left the current logical index.
    @Test
    public void ignoresLoadedRowsThatLeftCurrentLogicalIndex() throws Exception {
        RecordingModel model = new RecordingModel(items(2));

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    model, STRINGS, ACTION_STRINGS, new RecordingInteractions());
            panel.setSize(new Dimension(1120, 620));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<?> list = panel.choiceList().getList();
            list.setSelectedIndex(1);
            assertEquals(List.of("mod-1"), model.selectedKeys());

            model.replaceFilteredLocalKeys(List.of("mod-0"));
            ListDataEvent stalePageEvent = new ListDataEvent(
                    panel.choiceList().getChoiceModel(),
                    ListDataEvent.CONTENTS_CHANGED,
                    1,
                    1);
            assertDoesNotThrow(() -> {
                for (ListDataListener listener
                        : panel.choiceList().getChoiceModel().getListDataListeners()) {
                    listener.contentsChanged(stalePageEvent);
                }
            });

            assertTrue(list.isSelectionEmpty());
            assertEquals(List.of("mod-1"), model.selectedKeys());
            panel.close();
        });
    }

    /// A page-scoped drop defers supported Mods past the native callback and detaches on close.
    @Test
    public void importsSupportedDroppedModsOnlyWhileOpen() throws Exception {
        RecordingModel model = new RecordingModel(items(4));
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable ModCatalogPanel> panelReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            panelReference.set(panel);
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
            TransferHandler.TransferSupport transfer = fileTransfer(panel, List.of(
                    new File("first.jar"),
                    new File("notes.txt"),
                    new File("second.litemod")));

            assertTrue(handler.canImport(transfer));
            assertTrue(handler.importData(transfer));
            assertTrue(model.imports().isEmpty());
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(
                    List.of(
                            Path.of("first.jar").toAbsolutePath().normalize(),
                            Path.of("second.litemod").toAbsolutePath().normalize()),
                    model.imports().get(0));

            ModCatalogPanel panel = Objects.requireNonNull(panelReference.get());
            panel.close();
            assertNull(panel.getTransferHandler());
        });
    }

    /// A supported drop is accepted and installed when the catalog has no visible rows.
    @Test
    public void importsSupportedDroppedModsIntoEmptyCatalog() throws Exception {
        RecordingModel model = new RecordingModel(items(0));
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable ModCatalogPanel> panelReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            panelReference.set(panel);
            assertTrue(findButton(panel, "modsImport").isEnabled());
            assertFalse(findButton(panel, "modsSelectAll").isEnabled());

            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
            TransferHandler.TransferSupport transfer = fileTransfer(panel, List.of(
                    new File("empty-target.jar"),
                    new File("notes.txt")));
            assertTrue(handler.canImport(transfer));
            assertTrue(handler.importData(transfer));
            assertTrue(model.imports().isEmpty());
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, model.imports().size());
            assertEquals(
                    List.of(Path.of("empty-target.jar").toAbsolutePath().normalize()),
                    model.imports().get(0));

            ModCatalogPanel panel = Objects.requireNonNull(panelReference.get());
            panel.close();
        });
    }

    /// Logical select-all and batch commands use stable keys without loading off-screen rows.
    @Test
    public void batchesFilteredStableKeysWithoutWideningViewportLoads() throws Exception {
        RecordingModel model = new RecordingModel(items(100));
        RecordingInteractions interactions = new RecordingInteractions();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            panel.setSize(new Dimension(1120, 620));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            int rangeRequestCount = model.requestedRanges().size();
            assertTrue(rangeRequestCount > 0);
            assertTrue(model.requestedRanges().get(0).length() < model.items().size());

            JList<?> list = panel.choiceList().getList();
            list.setSelectionInterval(0, 1);
            findButton(panel, "modsSelectAll").doClick();
            int[] expectedIndices = new int[model.items().size()];
            for (int index = 0; index < expectedIndices.length; index++) {
                expectedIndices[index] = index;
            }
            assertArrayEquals(expectedIndices, list.getSelectedIndices());
            assertFalse(findButton(panel, "modsEnabled").isEnabled());
            assertFalse(findButton(panel, "modsReveal").isEnabled());
            assertFalse(findButton(panel, "modsDelete").isEnabled());

            findButton(panel, "modsEnableSelected").doClick();
            findButton(panel, "modsDisableSelected").doClick();
            @Unmodifiable List<String> allKeys = model.items().stream()
                    .map(ModCatalogItem::localKey)
                    .toList();
            assertEquals(List.of(allKeys, allKeys), model.enabledBatches());
            assertEquals(List.of(true, false), model.enabledBatchStates());

            interactions.batchDeleteConfirmed = false;
            findButton(panel, "modsDeleteSelected").doClick();
            assertEquals(List.of(), model.deletedBatches());
            interactions.batchDeleteConfirmed = true;
            findButton(panel, "modsDeleteSelected").doClick();
            assertEquals(List.of(allKeys), model.deletedBatches());
            assertEquals(List.of(100, 100), interactions.batchDeleteCounts());
            assertTrue(model.requestedRanges().size() >= rangeRequestCount);
            assertTrue(model.requestedRanges().stream()
                    .allMatch(range -> range.length() < model.items().size()));
            panel.close();
        });

        assertTrue(model.closed());
    }

    /// Verifies that production construction schedules Core refresh without synchronous disk work.
    @Test
    public void productionConstructorQueuesRealIndexWorkInHeadlessMode() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        Path modsDirectory = Path.of("build", "test-mods").toAbsolutePath().normalize();
        GameRepository repository = repositoryWithModsDirectory(modsDirectory);

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    repository,
                    new GameInstanceID("test-instance"),
                    executor,
                    STRINGS,
                    STATUS_STRINGS,
                    ACTION_STRINGS);
            assertEquals(ModCatalogStatus.LOADING, panel.displayedSnapshot().status());
            assertEquals(1, executor.pendingCount());
            panel.close();
        });

        assertEquals(1, executor.pendingCount());
    }

    /// Constrained and restored page heights hand overflow to the existing list and complete details scroll panes.
    @Test
    public void dynamicallyScrollsListAndCompleteDetailsAtConstrainedHeight() throws Exception {
        RecordingModel model = new RecordingModel(items(100));
        RecordingInteractions interactions = new RecordingInteractions();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            JScrollPane detailsScroll = findComponent(panel, "modsDetailsScroll", JScrollPane.class);
            JPanel details = findComponent(panel, "modsDetails", JPanel.class);
            AbstractButton deleteButton = findButton(panel, "modsDelete");

            panel.setSize(new Dimension(1120, 620));
            layoutRecursively(panel);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());
            assertTrue(
                    panel.choiceList().getVerticalScrollBar().getMaximum()
                            > panel.choiceList().getVerticalScrollBar().getVisibleAmount());
            panel.choiceList().getVerticalScrollBar().setValue(
                    panel.choiceList().getVerticalScrollBar().getUnitIncrement() * 2);
            assertTrue(panel.choiceList().getVerticalScrollBar().getValue() > 0);

            panel.setSize(new Dimension(1120, 280));
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(panel.choiceList().getViewport().getExtentSize().height > 0);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            > detailsScroll.getVerticalScrollBar().getVisibleAmount());
            int detailsBottom = detailsScroll.getVerticalScrollBar().getMaximum()
                    - detailsScroll.getVerticalScrollBar().getVisibleAmount();
            detailsScroll.getVerticalScrollBar().setValue(detailsBottom);
            Rectangle deleteBounds = SwingUtilities.convertRectangle(
                    deleteButton.getParent(),
                    deleteButton.getBounds(),
                    details);
            assertTrue(detailsScroll.getViewport().getViewRect().intersects(deleteBounds));

            panel.setSize(new Dimension(1120, 620));
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());
            panel.close();
        });

        assertTrue(model.closed());
    }

    /// Switches the first layout to a stacked catalog when the instance shell is narrow.
    @Test
    public void switchesResponsiveOrientationAtNarrowWidth() throws Exception {
        RecordingModel model = new RecordingModel(items(4));
        RecordingInteractions interactions = new RecordingInteractions();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            JSplitPane split = findComponent(panel, "modsCatalogSplit", JSplitPane.class);
            assertEquals(JSplitPane.VERTICAL_SPLIT, split.getOrientation());

            panel.setSize(new Dimension(1120, 620));
            layoutRecursively(panel);
            assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation());

            panel.setSize(new Dimension(480, 420));
            panel.invalidate();
            layoutRecursively(panel);
            assertEquals(JSplitPane.VERTICAL_SPLIT, split.getOrientation());
            assertTrue(split.getTopComponent().getWidth() <= split.getWidth());
            assertTrue(split.getBottomComponent().getWidth() <= split.getWidth());
            panel.close();
        });

        assertTrue(model.closed());
    }

    /// Wraps long values, keeps detail actions contained, and clamps a user-adjusted divider.
    @Test
    public void wrapsLongDetailsAndClampsTheDivider() throws Exception {
        ModCatalogItem longItem = new ModCatalogItem(
                "very-long-mod-key-without-natural-breaks",
                Path.of("mods", "very-long-mod-file-name-without-natural-breaks.jar"),
                "very-long-mod-id-without-natural-breaks",
                "A very long Mod display title that must wrap inside the details pane",
                "A very long description that remains readable in the scrollable description area",
                "First Author, Second Author, Third Author, Fourth Author",
                "26.2.1-alpha.123456789",
                "1.21.1-neoforge-very-long-version",
                ModLoaderType.FABRIC,
                "very-long-mod-file-name-without-natural-breaks.jar",
                true);
        RecordingModel model = new RecordingModel(List.of(longItem));
        RecordingInteractions interactions = new RecordingInteractions();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            panel.setSize(new Dimension(1120, 620));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(0);
            layoutRecursively(panel);

            JSplitPane split = findComponent(panel, "modsCatalogSplit", JSplitPane.class);
            JScrollPane detailsScroll = findComponent(panel, "modsDetailsScroll", JScrollPane.class);
            JTextArea title = findTextArea(panel, "modsDetailTitle");
            JTextArea file = findTextArea(panel, "modsDetailFile");
            AbstractButton reveal = findButton(panel, "modsReveal");
            assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation());
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                    detailsScroll.getHorizontalScrollBarPolicy());
            assertTrue(title.getLineWrap());
            assertTrue(title.getWrapStyleWord());
            assertTrue(file.getLineWrap());
            assertFalse(file.getWrapStyleWord());

            int divider = split.getDividerLocation();
            title.setText(title.getText() + " " + "extra-long-title-segment".repeat(8));
            layoutRecursively(panel);
            assertEquals(divider, split.getDividerLocation());
            assertTrue(title.getPreferredSize().height
                    > title.getFontMetrics(title.getFont()).getHeight());

            Rectangle revealBounds = SwingUtilities.convertRectangle(
                    reveal.getParent(),
                    reveal.getBounds(),
                    detailsScroll.getViewport().getView());
            assertTrue(detailsScroll.getViewport().getViewRect().contains(revealBounds));

            int leftMinimum = split.getLeftComponent().getMinimumSize().width;
            int rightMinimum = split.getRightComponent().getMinimumSize().width;
            split.setDividerLocation(0);
            layoutRecursively(panel);
            assertTrue(split.getLeftComponent().getWidth() >= leftMinimum);
            split.setDividerLocation(split.getWidth());
            layoutRecursively(panel);
            assertTrue(split.getRightComponent().getWidth() >= rightMinimum);
            panel.close();
        });

        assertTrue(model.closed());
    }

    /// Creates immutable deterministic public rows.
    ///
    /// @param count row count
    /// @return immutable rows
    private static @Unmodifiable List<ModCatalogItem> items(int count) {
        List<ModCatalogItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = "mod-" + index;
            items.add(new ModCatalogItem(
                    key,
                    Path.of("mods", key + ".jar"),
                    key,
                    "Mod " + index,
                    "Description " + index,
                    "Author",
                    "1.0",
                    "1.21.1",
                    ModLoaderType.FABRIC,
                    key + ".jar",
                    true));
        }
        return List.copyOf(items);
    }

    /// Creates one local-file-list transfer wrapper for page drop tests.
    ///
    /// @param component transfer target
    /// @param files local file payload
    /// @return transfer support exposing the Java file-list flavor
    private static TransferHandler.TransferSupport fileTransfer(
            JPanel component,
            @Unmodifiable List<File> files) {
        return new TransferHandler.TransferSupport(component, new FileListTransferable(files));
    }

    /// Immutable file-list transferable for page drop tests.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable local files.
        private final @Unmodifiable List<File> files;

        /// Creates one file-list payload.
        ///
        /// @param files local files to expose
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(files);
        }

        /// Returns the supported Java file-list flavor.
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        /// Reports whether the requested flavor is supported.
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        /// Returns the immutable local files.
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    /// Creates a dynamic real repository boundary that exposes only the production constructor path.
    ///
    /// @param modsDirectory deterministic Mod directory
    /// @return GameRepository proxy
    private static GameRepository repositoryWithModsDirectory(Path modsDirectory) {
        return (GameRepository) Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getModsDirectory")) {
                        return modsDirectory;
                    }
                    if (method.getName().equals("toString")) {
                        return "HeadlessModRepository";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == Objects.requireNonNull(arguments)[0];
                    }
                    throw new AssertionError("Unexpected synchronous repository call: " + method.getName());
                });
    }

    /// Recursively lays out a Swing component tree for real viewport measurement.
    ///
    /// @param component root component
    private static void layoutRecursively(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /// Finds one named abstract button.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching button
    private static AbstractButton findButton(Component root, String name) {
        return findComponent(root, name, AbstractButton.class);
    }

    /// Finds one named text area.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching text area
    private static JTextArea findTextArea(Component root, String name) {
        return findComponent(root, name, JTextArea.class);
    }

    /// Finds one named text field.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching field
    private static JTextField findTextField(Component root, String name) {
        return findComponent(root, name, JTextField.class);
    }

    /// Finds one named combo box.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching combo box
    private static JComboBox<?> findComboBox(Component root, String name) {
        return findComponent(root, name, JComboBox.class);
    }

    /// Finds one named label.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching label
    private static JLabel findLabel(Component root, String name) {
        return findComponent(root, name, JLabel.class);
    }

    /// Recursively finds one named component of the requested type.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(
            Component root,
            String name,
            Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return findComponent(child, name, type);
                } catch (IllegalArgumentException ignored) {
                    // Continue through siblings until the named component is found.
                }
            }
        }
        throw new IllegalArgumentException("Component not found: " + name);
    }

    /// Executor that records production work without executing real Core I/O.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Pending production tasks.
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        /// Enqueues one task.
        @Override
        public void execute(Runnable command) {
            tasks.add(Objects.requireNonNull(command));
        }

        /// Returns queued task count.
        ///
        /// @return pending task count
        private int pendingCount() {
            return tasks.size();
        }
    }

    /// Immediate model that records all panel commands while preserving real viewport slicing.
    @NotNullByDefault
    private static final class RecordingModel implements ModCatalogModel {
        /// Immutable public source rows.
        private final @Unmodifiable List<ModCatalogItem> items;

        /// Current logical row identities, which may advance before the fake visual source.
        private @Unmodifiable List<String> filteredLocalKeys;

        /// Snapshot transition support.
        private final ValueChangeSupport<ModCatalogSnapshot> changes = new ValueChangeSupport<>(this);

        /// Requested exact viewport ranges.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Selected stable keys.
        private final List<String> selectedKeys = new ArrayList<>();

        /// Search query commands.
        private final List<String> queries = new ArrayList<>();

        /// Filter commands.
        private final List<ModCatalogFilter> filters = new ArrayList<>();

        /// Enabled-state commands encoded as key and value.
        private final List<String> enabledCommands = new ArrayList<>();

        /// Stable-key batches submitted for enabled-state changes.
        private final List<@Unmodifiable List<String>> enabledBatches = new ArrayList<>();

        /// Enabled states matching submitted stable-key batches.
        private final List<Boolean> enabledBatchStates = new ArrayList<>();

        /// Import commands.
        private final List<@Unmodifiable List<Path>> imports = new ArrayList<>();

        /// Conflict decisions matching import commands.
        private final List<@Unmodifiable Map<Path, ModImportConflictAction>>
                importConflictActions = new ArrayList<>();

        /// Import sources classified as conflicting before submission.
        private @Unmodifiable List<Path> importConflicts = List.of();

        /// Conflict surfaced only when an import without its decision reaches the fake backend.
        private @Nullable Path lateImportConflict;

        /// One-shot asynchronous import failure.
        private @Nullable Throwable importFailure;

        /// Deleted stable keys.
        private final List<String> deletedKeys = new ArrayList<>();

        /// Stable-key batches submitted for permanent deletion.
        private final List<@Unmodifiable List<String>> deletedBatches = new ArrayList<>();

        /// Current immutable snapshot.
        private ModCatalogSnapshot snapshot;

        /// Whether close was called.
        private boolean closed;

        /// Creates one ready immediate model.
        ///
        /// @param items immutable source rows
        private RecordingModel(@Unmodifiable List<ModCatalogItem> items) {
            this.items = List.copyOf(items);
            filteredLocalKeys = items.stream().map(ModCatalogItem::localKey).toList();
            snapshot = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.of(items.size()),
                    0L,
                    ModCatalogStatus.READY,
                    items.size() + " Mods",
                    ModCatalogWriteStatus.IDLE,
                    "",
                    "",
                    ModCatalogFilter.ALL,
                    !items.isEmpty(),
                    true);
        }

        /// Returns current snapshot.
        @Override
        public ModCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Registers one transition listener.
        @Override
        public Subscription subscribe(ValueChangeListener<ModCatalogSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns deterministic Mod directory.
        @Override
        public Path modsDirectory() {
            return Path.of("mods").toAbsolutePath().normalize();
        }

        /// Returns immutable stable keys matching the fake's logical list order.
        @Override
        public @Unmodifiable List<String> filteredLocalKeys() {
            return filteredLocalKeys;
        }

        /// Keeps the already-ready fake source unchanged.
        @Override
        public void loadIfNeeded() {
        }

        /// Records no-op refresh for this immediate source.
        @Override
        public void refresh() {
        }

        /// Records one query.
        @Override
        public void setSearchQuery(String query) {
            queries.add(query);
        }

        /// Records one filter.
        @Override
        public void setFilter(ModCatalogFilter filter) {
            filters.add(filter);
        }

        /// Records one stable selection.
        @Override
        public void selectMod(String localKey) {
            if (!filteredLocalKeys.contains(localKey)) {
                throw new IllegalArgumentException("Unknown filtered Mod: " + localKey);
            }
            selectedKeys.add(localKey);
        }

        /// Clears selection in the immediate snapshot.
        @Override
        public void clearSelection() {
            snapshot = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    snapshot.itemCount(),
                    snapshot.contentRevision(),
                    snapshot.status(),
                    snapshot.statusText(),
                    snapshot.writeStatus(),
                    snapshot.writeStatusText(),
                    snapshot.searchQuery(),
                    snapshot.filter(),
                    snapshot.listEnabled(),
                    snapshot.refreshEnabled());
        }

        /// Returns configured normalized import conflicts.
        @Override
        public @Unmodifiable List<Path> findImportConflicts(@Unmodifiable List<Path> sources) {
            return importConflicts;
        }

        /// Records one enabled-state command.
        @Override
        public CompletionStage<ModCatalogSnapshot> setModEnabled(String localKey, boolean enabled) {
            enabledCommands.add(localKey + ":" + enabled);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one stable-key enabled-state batch.
        @Override
        public CompletionStage<ModCatalogSnapshot> setModsEnabled(
                @Unmodifiable List<String> localKeys,
                boolean enabled) {
            enabledBatches.add(List.copyOf(localKeys));
            enabledBatchStates.add(enabled);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one import command.
        @Override
        public CompletionStage<ModCatalogSnapshot> importMods(
                @Unmodifiable List<Path> sources,
                @Unmodifiable Map<Path, ModImportConflictAction> conflictActions) {
            imports.add(List.copyOf(sources));
            importConflictActions.add(Map.copyOf(conflictActions));
            @Nullable Path conflict = lateImportConflict;
            if (conflict != null && !conflictActions.containsKey(conflict)) {
                return CompletableFuture.failedFuture(new ModImportConflictException(conflict));
            }
            @Nullable Throwable failure = importFailure;
            importFailure = null;
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one deletion command.
        @Override
        public CompletionStage<ModCatalogSnapshot> deleteMod(String localKey) {
            deletedKeys.add(localKey);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one stable-key deletion batch.
        @Override
        public CompletionStage<ModCatalogSnapshot> deleteMods(
                @Unmodifiable List<String> localKeys) {
            deletedBatches.add(List.copyOf(localKeys));
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns exact source size.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Returns stable fake revision.
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(0L);
        }

        /// Returns one completed exact viewport slice.
        @Override
        public CompletionStage<ChoicePage<ModCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            IndexRange effective = desiredRange.clampToItemCount(items.size());
            requestedRanges.add(effective);
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    effective,
                    items.subList(effective.startInclusive(), effective.endExclusive()),
                    OptionalInt.of(items.size()),
                    effective.endExclusive() == items.size()));
        }

        /// Records closure.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns immutable source rows.
        ///
        /// @return rows
        private @Unmodifiable List<ModCatalogItem> items() {
            return items;
        }

        /// Advances logical row identities without replacing already loaded visual rows.
        ///
        /// @param localKeys replacement logical identities
        private void replaceFilteredLocalKeys(@Unmodifiable List<String> localKeys) {
            filteredLocalKeys = List.copyOf(localKeys);
        }

        /// Returns requested viewport ranges.
        ///
        /// @return immutable ranges
        private @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns selected keys.
        ///
        /// @return immutable keys
        private @Unmodifiable List<String> selectedKeys() {
            return List.copyOf(selectedKeys);
        }

        /// Returns recorded queries.
        ///
        /// @return immutable queries
        private @Unmodifiable List<String> queries() {
            return List.copyOf(queries);
        }

        /// Returns recorded filters.
        ///
        /// @return immutable filters
        private @Unmodifiable List<ModCatalogFilter> filters() {
            return List.copyOf(filters);
        }

        /// Returns enabled-state commands.
        ///
        /// @return immutable commands
        private @Unmodifiable List<String> enabledCommands() {
            return List.copyOf(enabledCommands);
        }

        /// Returns immutable enabled-state target batches.
        ///
        /// @return immutable stable-key batches
        private @Unmodifiable List<@Unmodifiable List<String>> enabledBatches() {
            return List.copyOf(enabledBatches);
        }

        /// Returns immutable enabled states matching target batches.
        ///
        /// @return immutable enabled states
        private @Unmodifiable List<Boolean> enabledBatchStates() {
            return List.copyOf(enabledBatchStates);
        }

        /// Returns import commands.
        ///
        /// @return immutable source lists
        private @Unmodifiable List<@Unmodifiable List<Path>> imports() {
            return List.copyOf(imports);
        }

        /// Returns conflict decisions matching recorded imports.
        ///
        /// @return immutable decision maps
        private @Unmodifiable List<@Unmodifiable Map<Path, ModImportConflictAction>>
                importConflictActions() {
            return List.copyOf(importConflictActions);
        }

        /// Replaces the deterministic pre-import conflict classification.
        ///
        /// @param conflicts normalized conflicting sources
        private void replaceImportConflicts(@Unmodifiable List<Path> conflicts) {
            importConflicts = List.copyOf(conflicts);
        }

        /// Configures one conflict that appears only during asynchronous import preflight.
        ///
        /// @param conflict normalized conflicting source
        private void replaceLateImportConflict(Path conflict) {
            lateImportConflict = conflict.toAbsolutePath().normalize();
        }

        /// Configures one one-shot asynchronous import failure.
        ///
        /// @param failure failure retained for the next import call
        private void replaceImportFailure(Throwable failure) {
            importFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Returns deleted keys.
        ///
        /// @return immutable keys
        private @Unmodifiable List<String> deletedKeys() {
            return List.copyOf(deletedKeys);
        }

        /// Returns immutable deletion target batches.
        ///
        /// @return immutable stable-key batches
        private @Unmodifiable List<@Unmodifiable List<String>> deletedBatches() {
            return List.copyOf(deletedBatches);
        }

        /// Returns whether close was called.
        ///
        /// @return closure state
        private boolean closed() {
            return closed;
        }
    }

    /// Headless interaction boundary recording every desktop and confirmation command.
    @NotNullByDefault
    private static final class RecordingInteractions implements ModCatalogInteractions {
        /// Latest revealed exact path.
        private @Nullable Path revealedPath;

        /// Directory-open command count.
        private int openCount;

        /// Whether the next selected-batch deletion is confirmed.
        private boolean batchDeleteConfirmed = true;

        /// Selected counts presented for batch deletion confirmation.
        private final List<Integer> batchDeleteCounts = new ArrayList<>();

        /// Sources presented for import conflict resolution.
        private final List<Path> importConflictSources = new ArrayList<>();

        /// Deterministic import conflict response.
        private ModImportConflictAction importConflictAction = ModImportConflictAction.REPLACE;

        /// Latest captured retry request.
        private @Nullable Runnable retryAction;

        /// Detail supplied with the latest retry request.
        private @Nullable String retryDetail;

        /// Returns one deterministic import choice.
        @Override
        public @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
            return List.of(Path.of("incoming.jar"));
        }

        /// Records and resolves one import conflict.
        @Override
        public @Nullable ModImportConflictAction resolveImportConflict(Component owner, Path source) {
            importConflictSources.add(source);
            return importConflictAction;
        }

        /// Confirms every deterministic deletion.
        @Override
        public boolean confirmDelete(Component owner, ModCatalogItem target) {
            return true;
        }

        /// Records and returns the controlled selected-batch deletion decision.
        @Override
        public boolean confirmDeleteSelected(Component owner, int selectedCount) {
            batchDeleteCounts.add(selectedCount);
            return batchDeleteConfirmed;
        }

        /// Maps the single deletion confirmation to permanent mode for this headless test.
        ///
        /// @param owner dialog owner
        /// @param target selected Mod
        /// @return permanent mode when confirmed, otherwise null
        @Override
        public @Nullable DeletionMode chooseDeleteMode(Component owner, ModCatalogItem target) {
            return confirmDelete(owner, target) ? DeletionMode.PERMANENT : null;
        }

        /// Maps the batch deletion confirmation to permanent mode for this headless test.
        ///
        /// @param owner dialog owner
        /// @param selectedCount selected Mod count
        /// @return permanent mode when confirmed, otherwise null
        @Override
        public @Nullable DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount) {
            return confirmDeleteSelected(owner, selectedCount) ? DeletionMode.PERMANENT : null;
        }

        /// Records one exact reveal path.
        @Override
        public CompletionStage<@Nullable Void> reveal(Path target) {
            revealedPath = target;
            return CompletableFuture.completedFuture(null);
        }

        /// Records one directory-open command.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            openCount++;
            return CompletableFuture.completedFuture(null);
        }

        /// Fails the test if an unexpected asynchronous error is presented.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            throw new AssertionError(title + ": " + detail);
        }

        /// Captures one explicit retry request without showing a dialog.
        @Override
        public void showRetryableFailure(
                Component owner,
                String title,
                String detail,
                Runnable retryAction) {
            this.retryAction = Objects.requireNonNull(retryAction, "retryAction");
            this.retryDetail = Objects.requireNonNull(detail, "detail");
        }

        /// Returns latest revealed path.
        ///
        /// @return revealed path, or `null`
        private @Nullable Path revealedPath() {
            return revealedPath;
        }

        /// Returns directory-open count.
        ///
        /// @return open count
        private int openCount() {
            return openCount;
        }

        /// Returns immutable selected counts presented for batch deletion.
        ///
        /// @return immutable confirmation counts
        private @Unmodifiable List<Integer> batchDeleteCounts() {
            return List.copyOf(batchDeleteCounts);
        }

        /// Returns immutable sources presented for import conflict resolution.
        ///
        /// @return immutable conflict sources
        private @Unmodifiable List<Path> importConflictSources() {
            return List.copyOf(importConflictSources);
        }

        /// Returns the latest captured retry request.
        ///
        /// @return retry request, or null when none was presented
        private @Nullable Runnable retryAction() {
            return retryAction;
        }

        /// Returns the detail supplied with the latest retry request.
        ///
        /// @return retry detail, or null when none was presented
        private @Nullable String retryDetail() {
            return retryDetail;
        }

        /// Replaces the deterministic conflict response.
        ///
        /// @param action new response
        private void replaceImportConflictAction(ModImportConflictAction action) {
            importConflictAction = action;
        }
    }
}
