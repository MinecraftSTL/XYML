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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTDocumentService;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Routable Swing NBT editor with lazy tree expansion and exact type-preserving scalar edits.
///
/// The panel never reads or writes an NBT source on the EDT. It delegates those operations to
/// `NBTDocumentService`, renders controller snapshots on the EDT, and invalidates every late
/// completion during closure. The host retains ownership of navigation through `Listener`.
@NotNullByDefault
public final class NBTEditorPanel extends JPanel implements AutoCloseable {
    /// Serialization identifier for the Swing component superclass contract.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Controller that serializes document state on the EDT.
    private final NBTEditorController controller;

    /// Stable localized visible text.
    private final NBTEditorStrings strings;

    /// File chooser, drop policy, and confirmation boundary.
    private final NBTEditorInteractions interactions;

    /// Parent-owned navigation callback.
    private final Listener listener;

    /// Tree renderer that receives already-decoded icons on the EDT.
    private final NBTTreeCellRenderer treeCellRenderer;

    /// Opens the native source chooser.
    private final JButton openButton = new JButton();

    /// Reloads the selected source from disk.
    private final JButton reloadButton = new JButton();

    /// Safely saves the dirty document.
    private final JButton saveButton = new JButton();

    /// Requests return navigation after dirty-state confirmation.
    private final JButton backButton = new JButton();

    /// Displays the exact selected source path.
    private final JLabel pathLabel = new JLabel();

    /// Renders only rows requested by the Swing tree viewport.
    private final JTree tree = new JTree(emptyTreeModel());

    /// Displays the selected node's stable name.
    private final JTextField nameField = readOnlyField("nbtEditorNodeName");

    /// Displays the selected node's stable type.
    private final JTextField typeField = readOnlyField("nbtEditorNodeType");

    /// Displays the selected node's direct child count.
    private final JTextField childrenField = readOnlyField("nbtEditorNodeChildren");

    /// Edits only scalar types with concrete HelloNBT setters.
    private final JTextArea valueArea = new JTextArea(6, 24);

    /// Applies one validated type-preserving scalar mutation.
    private final JButton applyButton = new JButton();

    /// Displays read-only or invalid-input detail for the selected node.
    private final JLabel editStatusLabel = new JLabel(" ");

    /// Displays empty, progress, ready, dirty, error, and conflict state.
    private final JLabel statusLabel = new JLabel();

    /// Indicates asynchronous open, reload, and save work without guessing progress.
    private final JProgressBar progressBar = new JProgressBar();

    /// Owned tree selection listener removed during closure.
    private final TreeSelectionListener treeSelectionListener = this::selectionChanged;

    /// Reveals dormant root children only when a real expansion begins.
    private final TreeWillExpandListener rootExpansionListener = new RootExpansionListener();

    /// Owned controller subscription removed during closure.
    private final Subscription stateSubscription;

    /// Background classpath icon load, or `null` for deterministic injected panels.
    private final @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> iconLoad;

    /// Transfer adapter that performs only lexical payload decoding on the EDT.
    private final TransferHandler nbtTransferHandler = new NBTTransferHandler();

    /// Guards terminal teardown from any calling thread.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Document identity currently represented by the tree model.
    private @Nullable NBTDocument renderedDocument;

    /// Suppresses Swing's automatic root expansion while a replacement model is installed.
    private boolean installingTreeModel;

    /// Creates a production NBT page without opening a source.
    ///
    /// The executor remains caller-owned and must be suitable for blocking filesystem work.
    ///
    /// @param ioExecutor caller-owned blocking executor
    /// @param listener parent-owned navigation callback
    public NBTEditorPanel(Executor ioExecutor, Listener listener) {
        this(
                new NBTEditorController(
                        new NBTDocumentService(Objects.requireNonNull(ioExecutor, "ioExecutor")),
                        SwingUiDispatcher.INSTANCE),
                NBTEditorStrings.localized(),
                null,
                listener,
                Objects.requireNonNull(ioExecutor, "ioExecutor"));
    }

    /// Creates a deterministic page with injected state and interaction boundaries.
    ///
    /// A null interaction boundary selects the production Swing dialogs owned by this panel.
    ///
    /// @param controller document controller
    /// @param strings stable localized text
    /// @param interactions injected interactions, or `null` for native Swing dialogs
    /// @param listener parent-owned navigation callback
    NBTEditorPanel(
            NBTEditorController controller,
            NBTEditorStrings strings,
            @Nullable NBTEditorInteractions interactions,
            Listener listener) {
        this(controller, strings, interactions, listener, null);
    }

    /// Creates a page with an optional caller-owned classpath-resource executor.
    ///
    /// @param controller document controller
    /// @param strings stable localized text
    /// @param interactions injected interactions, or `null` for native Swing dialogs
    /// @param listener parent-owned navigation callback
    /// @param iconExecutor background icon executor, or `null` to retain default tree icons
    NBTEditorPanel(
            NBTEditorController controller,
            NBTEditorStrings strings,
            @Nullable NBTEditorInteractions interactions,
            Listener listener,
            @Nullable Executor iconExecutor) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.controller = Objects.requireNonNull(controller, "controller");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = interactions == null
                ? new SwingNBTEditorInteractions(this, this.strings)
                : interactions;
        this.listener = Objects.requireNonNull(listener, "listener");
        treeCellRenderer = new NBTTreeCellRenderer(this.strings);

        setName("nbtEditorPage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        add(createHeadingBand(), BorderLayout.NORTH);
        add(createEditorSurface(), BorderLayout.CENTER);
        add(createStatusBand(), BorderLayout.SOUTH);
        configureTree();
        configureValueEditor();
        setTransferHandler(nbtTransferHandler);
        stateSubscription = this.controller.subscribe(change -> {
            @Nullable NBTEditorSnapshot current = change.currentValue();
            if (current != null && !closed.get()) {
                render(current);
            }
        });
        render(this.controller.snapshot());
        iconLoad = iconExecutor == null ? null : preloadTreeIcons(iconExecutor);
    }

    /// Returns the localized title required by a page host.
    ///
    /// @return non-blank page title
    public String title() {
        return strings.title();
    }

    /// Opens one route-supplied source asynchronously after dirty-state confirmation.
    ///
    /// This method performs only lexical path normalization on the EDT.
    ///
    /// @param file route-supplied source
    public void open(Path file) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy() || !confirmReplacement()) {
            return;
        }
        controller.open(Objects.requireNonNull(file, "file").toAbsolutePath().normalize());
    }

    /// Routes a decoded immutable drop payload through the toolkit-neutral interaction policy.
    ///
    /// @param candidates normalized lexical paths from the transfer
    /// @return whether one source was accepted for asynchronous opening
    public boolean openDroppedPaths(@Unmodifiable List<Path> candidates) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy()) {
            return false;
        }
        @Unmodifiable List<Path> paths = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        @Nullable Path selected = interactions.chooseDroppedFile(paths);
        if (selected == null || !confirmReplacement()) {
            return false;
        }
        controller.open(selected.toAbsolutePath().normalize());
        return true;
    }

    /// Requests parent-owned return navigation after dirty-state confirmation.
    public void requestClose() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get() && !controller.snapshot().busy() && confirmReplacement()) {
            listener.closeRequested();
        }
    }

    /// Releases listeners, clears native transfer hooks, cancels work, and ignores late callbacks.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Creates the compact title, path, and fixed-size icon command band.
    ///
    /// @return unframed heading band
    private JComponent createHeadingBand() {
        JPanel heading = new JPanel(new MigLayout(
                "insets 12 16 8 16, fillx",
                "[]10[][grow,fill]8[]8[]8[]",
                "[40!]"));
        heading.setOpaque(false);
        configureIconButton(
                backButton,
                "nbtEditorBack",
                "assets/swing/icons/arrow-back.svg",
                strings.backTooltip(),
                this::requestClose);
        heading.add(backButton, "w 40!, h 40!");

        JLabel titleLabel = new JLabel(strings.title());
        titleLabel.setName("nbtEditorTitle");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 24.0F));
        heading.add(titleLabel);

        pathLabel.setName("nbtEditorPath");
        pathLabel.setHorizontalAlignment(SwingConstants.LEADING);
        heading.add(pathLabel, "growx");

        configureIconButton(
                openButton,
                "nbtEditorOpen",
                "assets/swing/icons/folder-open.svg",
                strings.openTooltip(),
                this::chooseAndOpen);
        heading.add(openButton, "w 40!, h 40!");

        configureIconButton(
                reloadButton,
                "nbtEditorReload",
                "assets/swing/icons/refresh.svg",
                strings.reloadTooltip(),
                this::reload);
        heading.add(reloadButton, "w 40!, h 40!");

        configureIconButton(
                saveButton,
                "nbtEditorSave",
                "assets/swing/icons/save.svg",
                strings.saveTooltip(),
                controller::save);
        heading.add(saveButton, "w 40!, h 40!");
        return heading;
    }

    /// Creates the resizable lazy tree and selected-node detail surface.
    ///
    /// @return editor split surface
    private JComponent createEditorSurface() {
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setName("nbtEditorTreeScroll");
        treeScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel details = new JPanel(new MigLayout(
                "insets 16, fillx",
                "[92!,fill][grow,fill]",
                "[][][]10[]8[]4[]push"));
        details.setName("nbtEditorDetails");
        details.add(detailLabel(strings.nameLabel(), nameField));
        details.add(nameField, "wrap");
        details.add(detailLabel(strings.typeLabel(), typeField));
        details.add(typeField, "wrap");
        details.add(detailLabel(strings.childrenLabel(), childrenField));
        details.add(childrenField, "wrap");
        JLabel valueLabel = detailLabel(strings.valueLabel(), valueArea);
        details.add(valueLabel, "top");
        JScrollPane valueScroll = new JScrollPane(valueArea);
        valueScroll.setName("nbtEditorValueScroll");
        details.add(valueScroll, "growx, h 120:180:280, wrap");
        details.add(new JLabel(), "skip");
        applyButton.setName("nbtEditorApply");
        applyButton.setText(strings.applyText());
        applyButton.addActionListener(event -> applySelectedValue());
        applyButton.getAccessibleContext().setAccessibleName(strings.applyText());
        details.add(applyButton, "right, h 36!, wrap");
        editStatusLabel.setName("nbtEditorEditStatus");
        details.add(editStatusLabel, "skip, growx");

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, details);
        splitPane.setName("nbtEditorSplitPane");
        splitPane.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        splitPane.setResizeWeight(0.42D);
        splitPane.setContinuousLayout(true);
        return splitPane;
    }

    /// Creates the compact operation status and busy indicator band.
    ///
    /// @return unframed status band
    private JComponent createStatusBand() {
        JPanel status = new JPanel(new MigLayout(
                "insets 6 16 12 16, fillx",
                "[grow,fill][120!,fill]",
                "[]"));
        status.setOpaque(false);
        statusLabel.setName("nbtEditorStatus");
        status.add(statusLabel, "growx");
        progressBar.setName("nbtEditorProgress");
        progressBar.setIndeterminate(true);
        progressBar.getAccessibleContext().setAccessibleName(strings.openingText());
        status.add(progressBar, "h 8!");
        return status;
    }

    /// Configures viewport-friendly selection and row rendering.
    private void configureTree() {
        tree.setName("nbtEditorTree");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setLargeModel(true);
        tree.setCellRenderer(treeCellRenderer);
        tree.addTreeSelectionListener(treeSelectionListener);
        tree.addTreeWillExpandListener(rootExpansionListener);
        tree.getAccessibleContext().setAccessibleName(strings.title());
    }

    /// Configures the multi-line exact string editor and numeric input surface.
    private void configureValueEditor() {
        valueArea.setName("nbtEditorValue");
        valueArea.setLineWrap(true);
        valueArea.setWrapStyleWord(true);
        valueArea.setEnabled(false);
    }

    /// Opens the native chooser and forwards its result to the controller.
    private void chooseAndOpen() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || controller.snapshot().busy()) {
            return;
        }
        @Nullable Path selected = interactions.chooseFile(controller.snapshot().file());
        if (selected != null) {
            open(selected);
        }
    }

    /// Reloads after confirming a dirty replacement.
    private void reload() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()
                && !controller.snapshot().busy()
                && controller.snapshot().document() != null
                && confirmReplacement()) {
            controller.reload();
        }
    }

    /// Returns whether replacing the current state is allowed.
    ///
    /// @return whether no dirty state exists or the user confirmed its loss
    private boolean confirmReplacement() {
        NBTEditorSnapshot current = controller.snapshot();
        if (!current.dirty()) {
            return true;
        }
        @Nullable Path file = current.file();
        return file != null && interactions.confirmDiscardChanges(file);
    }

    /// Applies one exact selected scalar value and keeps the same logical selection.
    private void applySelectedValue() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null || !selected.editable()) {
            return;
        }
        @Unmodifiable List<Integer> address = selected.address();
        NBTValueEditResult result = controller.applyValueEdit(selected, valueArea.getText());
        if (!result.applied()) {
            editStatusLabel.setText(strings.invalidValueText());
            editStatusLabel.setToolTipText(result.errorMessage());
            valueArea.putClientProperty("JComponent.outline", "error");
            return;
        }
        editStatusLabel.setText(" ");
        editStatusLabel.setToolTipText(null);
        valueArea.putClientProperty("JComponent.outline", null);
        tree.repaint();
        restoreSelection(address);
        updateSelectedNodeDetails();
    }

    /// Applies one tree selection change to the detail editor.
    ///
    /// @param event tree selection event
    private void selectionChanged(TreeSelectionEvent event) {
        Objects.requireNonNull(event, "event");
        updateSelectedNodeDetails();
    }

    /// Reconciles all controls with one immutable controller state.
    ///
    /// @param current latest state
    private void render(NBTEditorSnapshot current) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable NBTDocument document = current.document();
        if (document != renderedDocument) {
            @Unmodifiable List<Integer> address = selectedAddress();
            renderedDocument = document;
            if (document == null) {
                tree.setModel(emptyTreeModel());
            } else {
                NBTLazyTreeModel model = new NBTLazyTreeModel(document, false);
                installingTreeModel = true;
                try {
                    tree.setModel(model);
                    tree.collapsePath(model.pathForAddress(List.of()));
                } finally {
                    installingTreeModel = false;
                }
                restoreSelection(address);
            }
        }
        @Nullable Path file = current.file();
        String pathText = file == null ? "" : file.toString();
        pathLabel.setText(pathText);
        pathLabel.setToolTipText(pathText.isEmpty() ? null : pathText);
        statusLabel.setText(statusText(current));
        statusLabel.setToolTipText(current.message());
        progressBar.setVisible(current.busy());
        boolean active = current.status() != NBTEditorStatus.CLOSED;
        openButton.setEnabled(active && !current.busy());
        reloadButton.setEnabled(active && !current.busy() && document != null);
        saveButton.setEnabled(active
                && !current.busy()
                && document != null
                && current.dirty()
                && current.status() != NBTEditorStatus.CONFLICT);
        backButton.setEnabled(active && !current.busy());
        tree.setEnabled(active && !current.busy() && document != null);
        updateSelectedNodeDetails();
    }

    /// Returns localized status text with concise backend detail for failures.
    ///
    /// @param current current state
    /// @return visible status text
    private String statusText(NBTEditorSnapshot current) {
        return switch (current.status()) {
            case EMPTY -> strings.emptyText();
            case OPENING -> strings.openingText();
            case READY -> current.dirty() ? strings.modifiedText() : strings.readyText();
            case SAVING -> strings.savingText();
            case CONFLICT -> strings.conflictText();
            case ERROR -> {
                @Nullable String message = current.message();
                yield message == null ? strings.errorText() : strings.errorText() + ": " + message;
            }
            case CLOSED -> strings.emptyText();
        };
    }

    /// Updates selected-node metadata and exact edit availability.
    private void updateSelectedNodeDetails() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        if (selected == null) {
            clearDetails();
            return;
        }
        nameField.setText(selected.presentation().displayName());
        typeField.setText(selected.presentation().type().name());
        childrenField.setText(Integer.toString(selected.childCount()));
        @Nullable String scalar = selected.currentScalarValue();
        valueArea.setText(scalar == null ? "" : scalar);
        boolean editable = selected.editable()
                && !controller.snapshot().busy()
                && !closed.get();
        valueArea.setEnabled(editable);
        valueArea.setEditable(editable);
        applyButton.setEnabled(editable);
        editStatusLabel.setText(editable ? " " : strings.readOnlyText());
        editStatusLabel.setToolTipText(null);
        valueArea.putClientProperty("JComponent.outline", null);
    }

    /// Clears details when no model row is selected.
    private void clearDetails() {
        nameField.setText("");
        typeField.setText("");
        childrenField.setText("");
        valueArea.setText("");
        valueArea.setEditable(false);
        valueArea.setEnabled(false);
        applyButton.setEnabled(false);
        editStatusLabel.setText(" ");
        editStatusLabel.setToolTipText(null);
        valueArea.putClientProperty("JComponent.outline", null);
    }

    /// Returns the selected typed node.
    ///
    /// @return selected node, or `null`
    private @Nullable NBTEditorTreeNode selectedNode() {
        @Nullable Object value = tree.getLastSelectedPathComponent();
        return value instanceof NBTEditorTreeNode node ? node : null;
    }

    /// Returns the selected immutable address without materializing any sibling.
    ///
    /// @return selected address, or the root address when no row is selected
    private @Unmodifiable List<Integer> selectedAddress() {
        @Nullable NBTEditorTreeNode selected = selectedNode();
        return selected == null ? List.of() : selected.address();
    }

    /// Restores one logical tree address when the current model still contains it.
    ///
    /// @param address immutable child-index address
    private void restoreSelection(@Unmodifiable List<Integer> address) {
        if (!(tree.getModel() instanceof NBTLazyTreeModel model)) {
            return;
        }
        try {
            TreePath path = model.pathForAddress(address);
            tree.setSelectionPath(path);
            if (!address.isEmpty()) {
                tree.scrollPathToVisible(path);
            }
        } catch (IndexOutOfBoundsException ignored) {
            tree.setSelectionPath(model.pathForAddress(List.of()));
        }
    }

    /// Performs terminal Swing teardown on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        stateSubscription.close();
        tree.removeTreeSelectionListener(treeSelectionListener);
        tree.removeTreeWillExpandListener(rootExpansionListener);
        setTransferHandler(null);
        tree.setModel(emptyTreeModel());
        renderedDocument = null;
        if (iconLoad != null) {
            iconLoad.cancel(true);
        }
        controller.close();
    }

    /// Starts one background classpath read and injects decoded icons on the EDT.
    ///
    /// A missing resource or rejected executor leaves the normal Swing tree icons in place and does
    /// not interfere with document operations.
    ///
    /// @param executor caller-owned background executor
    /// @return started future, or `null` when submission was rejected
    private @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> preloadTreeIcons(
            Executor executor) {
        try {
            CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> future = CompletableFuture.supplyAsync(
                    NBTTreeCellRenderer::loadIcons,
                    Objects.requireNonNull(executor, "executor"));
            future.whenComplete((
                    @Nullable @Unmodifiable Map<NBTNodeType, Icon> loaded,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
                        if (!closed.get() && failure == null && loaded != null) {
                            treeCellRenderer.installIcons(loaded);
                            tree.repaint();
                        }
                    }));
            return future;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Configures one fixed-size familiar-symbol icon command.
    ///
    /// @param button target button
    /// @param name stable UI-audit name
    /// @param iconResource classpath SVG resource
    /// @param tooltip localized accessible text
    /// @param action command action
    private static void configureIconButton(
            JButton button,
            String name,
            String iconResource,
            String tooltip,
            Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setIcon(themeIcon(iconResource));
        target.setToolTipText(Objects.requireNonNull(tooltip, "tooltip"));
        target.getAccessibleContext().setAccessibleName(tooltip);
        target.getAccessibleContext().setAccessibleDescription(tooltip);
        target.setHorizontalAlignment(SwingConstants.CENTER);
        target.putClientProperty("JButton.buttonType", "toolBarButton");
        target.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Creates one detail label associated with its editor component.
    ///
    /// @param text localized label
    /// @param component associated component
    /// @return configured label
    private static JLabel detailLabel(String text, JComponent component) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setLabelFor(Objects.requireNonNull(component, "component"));
        return label;
    }

    /// Creates one stable read-only detail field.
    ///
    /// @param name UI-audit component name
    /// @return configured field
    private static JTextField readOnlyField(String name) {
        JTextField field = new JTextField();
        field.setName(Objects.requireNonNull(name, "name"));
        field.setEditable(false);
        return field;
    }

    /// Creates an empty tree model without a synthetic visible placeholder node.
    ///
    /// @return empty model
    private static TreeModel emptyTreeModel() {
        return new DefaultTreeModel(null);
    }

    /// Creates a bundled SVG icon that follows its component foreground.
    ///
    /// @param iconResource classpath SVG resource
    /// @return theme-aware icon
    private static FlatSVGIcon themeIcon(String iconResource) {
        FlatSVGIcon icon = new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(NBTEditorPanel::resolveIconColor));
        return icon;
    }

    /// Resolves an icon color from its owning component and current FlatLaf theme.
    ///
    /// @param component owning component, or `null` during standalone rendering
    /// @param originalColor authored SVG fallback
    /// @return current component foreground or a stable fallback
    private static Color resolveIconColor(@Nullable Component component, Color originalColor) {
        Color authored = Objects.requireNonNull(originalColor, "originalColor");
        @Nullable Color foreground = component == null ? null : component.getForeground();
        if (foreground != null) {
            return foreground;
        }
        @Nullable Color themeForeground = UIManager.getColor("Button.foreground");
        return themeForeground == null ? authored : themeForeground;
    }

    /// Decodes Java file-list transfers into immutable normalized paths without filesystem access.
    ///
    /// @param transferable transfer payload
    /// @return immutable paths, or `null` when the payload cannot be decoded
    private static @Nullable @Unmodifiable List<Path> transferredPaths(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        if (!payload.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            Object transferData = payload.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(transferData instanceof List<?> files)) {
                return null;
            }
            List<Path> paths = new ArrayList<>(files.size());
            for (Object value : files) {
                if (!(value instanceof File file)) {
                    return null;
                }
                paths.add(file.toPath().toAbsolutePath().normalize());
            }
            return List.copyOf(paths);
        } catch (UnsupportedFlavorException | IOException | RuntimeException ignored) {
            return null;
        }
    }

    /// Parent-owned navigation operation emitted by this routable page.
    @NotNullByDefault
    @FunctionalInterface
    public interface Listener {
        /// Requests removal of this page and return to its parent route.
        void closeRequested();
    }

    /// Activates the deferred root exactly when Swing begins a user-visible expansion.
    @NotNullByDefault
    private final class RootExpansionListener implements TreeWillExpandListener {
        /// Reveals root children before Swing enumerates the expanding path.
        ///
        /// @param event pending expansion event
        /// @throws ExpandVetoException never thrown because expansion is always accepted
        @Override
        public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            TreeExpansionEvent expansion = Objects.requireNonNull(event, "event");
            @Nullable Object component = expansion.getPath().getLastPathComponent();
            JTree source = (JTree) expansion.getSource();
            if (!installingTreeModel
                    && source.getModel() instanceof NBTLazyTreeModel model
                    && component == model.getRoot()) {
                model.revealRootChildren();
            }
        }

        /// Accepts collapse without changing model visibility.
        ///
        /// @param event pending collapse event
        /// @throws ExpandVetoException never thrown because collapse is always accepted
        @Override
        public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            Objects.requireNonNull(event, "event");
        }
    }

    /// Swing transfer adapter that forwards only decoded immutable paths.
    @NotNullByDefault
    private final class NBTTransferHandler extends TransferHandler {
        /// Serialization identifier for the Swing transfer superclass contract.
        @Serial
        private static final long serialVersionUID = 1L;

        /// Reports whether file-list input can be considered in the current state.
        ///
        /// @param support Swing transfer context
        /// @return whether decoding may proceed
        @Override
        public boolean canImport(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            return !closed.get()
                    && !controller.snapshot().busy()
                    && transferSupport.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        /// Decodes and forwards one file-list transfer.
        ///
        /// @param support Swing transfer context
        /// @return whether one source was accepted
        @Override
        public boolean importData(TransferSupport support) {
            TransferSupport transferSupport = Objects.requireNonNull(support, "support");
            if (!canImport(transferSupport)) {
                return false;
            }
            @Nullable @Unmodifiable List<Path> paths = transferredPaths(transferSupport.getTransferable());
            return paths != null && openDroppedPaths(paths);
        }
    }
}
