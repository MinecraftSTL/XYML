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
package space.minecraftstl.xyml.ui.swing.page.settings;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.util.PortablePath;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Manages effective local and user game directories through the established directory manager.
///
/// The list renders persisted [PortablePath] values without probing the filesystem. Path parsing and relative-path
/// conversion run on the injected background executor before a state mutation is dispatched back to the EDT. The
/// production service preserves `GameDirectoryManager`'s local/user storage, selected-repository, and read-only
/// backup-and-overwrite behavior.
@NotNullByDefault
public final class GameDirectoryManagementPanel extends JPanel implements AutoCloseable {
    /// Model backing the effective local-first directory list.
    private final DefaultListModel<GameDirectoryManagementEntry> directoryListModel = new DefaultListModel<>();

    /// Single-selection list that changes the process-wide current game directory.
    private final JList<GameDirectoryManagementEntry> directoryList = new JList<>(directoryListModel);

    /// Starts creation of a new local or user-level directory entry.
    private final JButton addButton = new JButton(i18n("button.add"));

    /// Starts editing the selected directory entry.
    private final JButton editButton = new JButton(i18n("button.edit"));

    /// Permanently removes the selected directory entry after confirmation.
    private final JButton removeButton = new JButton(i18n("button.remove"));

    /// Editable custom directory display name.
    private final JTextField nameField = new JTextField();

    /// Editable path text converted to a portable path outside the EDT.
    private final JTextField pathField = new JTextField();

    /// Requests relative local storage when the selected path can be expressed from the launcher directory.
    private final JCheckBox relativePathBox = new JCheckBox(i18n("game_directory.use_relative_path"));

    /// Opens the native directory chooser.
    private final JButton choosePathButton = new JButton(i18n("game_directory.instance_directory.choose"));

    /// Applies the current add or edit form.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Discards form edits and returns to the selected entry.
    private final JButton cancelButton = new JButton(i18n("button.cancel"));

    /// Concise local operation feedback.
    private final JLabel statusLabel = new JLabel();

    /// Toolkit-neutral directory list and mutation service.
    private final GameDirectoryManagementService service;

    /// Native chooser and confirmation boundary.
    private final GameDirectoryManagementInteraction interaction;

    /// Executor used for path conversion outside the event dispatch thread.
    private final Executor pathExecutor;

    /// Service subscription owned by this panel.
    private final Subscription serviceSubscription;

    /// Snapshot currently rendered by the list, or `null` before construction completes.
    private @Nullable GameDirectoryManagementSnapshot displayedSnapshot;

    /// Form state determining whether save creates or updates an entry.
    private EditorMode editorMode = EditorMode.IDLE;

    /// Existing entry being edited, or `null` while creating or idle.
    private @Nullable GameDirectoryManagementEntry editedEntry;

    /// Sequence used to reject stale background path conversions.
    private long editSequence;

    /// Whether a path conversion and save is currently pending.
    private boolean savePending;

    /// Whether list replacement is currently selecting an item programmatically.
    private boolean applyingSnapshot;

    /// Whether the panel released its service subscription and disabled its controls.
    private boolean closed;

    /// Creates the production panel backed by the process-wide initialized game-directory manager.
    ///
    /// @return configured directory management panel
    public static GameDirectoryManagementPanel createForCurrentDirectories() {
        EdtDispatcher.requireEventDispatchThread();
        LauncherGameDirectoryManagementService service = new LauncherGameDirectoryManagementService();
        try {
            return new GameDirectoryManagementPanel(
                    service,
                    new SwingGameDirectoryManagementInteraction(),
                    Schedulers.io());
        } catch (RuntimeException failure) {
            service.close();
            throw failure;
        }
    }

    /// Opens the add-directory editor using the same validated workflow as the page toolbar action.
    public void beginAddingDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        beginAdd();
    }

    /// Creates a directory management panel on the event dispatch thread.
    ///
    /// This package-visible constructor supports deterministic Swing tests with an in-memory service and interaction.
    ///
    /// @param service immutable-state and mutation source
    /// @param interaction native dialog boundary
    /// @param pathExecutor executor that must not run filesystem-sensitive path work on the EDT
    GameDirectoryManagementPanel(
            GameDirectoryManagementService service,
            GameDirectoryManagementInteraction interaction,
            Executor pathExecutor) {
        super(new BorderLayout(0, 12));
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        this.pathExecutor = Objects.requireNonNull(pathExecutor, "pathExecutor");
        configureComponents();
        serviceSubscription = service.subscribe(this::serviceSnapshotChanged);
        applySnapshot(service.snapshot());
    }

    /// Returns the immutable directory state currently represented by the list.
    ///
    /// @return displayed effective local-first directory state
    public GameDirectoryManagementSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial game directory snapshot was not applied");
    }

    /// Returns the selected rendered game-directory entry.
    ///
    /// @return selected entry, or `null` when no entry is selected
    public @Nullable GameDirectoryManagementEntry selectedEntry() {
        EdtDispatcher.requireEventDispatchThread();
        return directoryList.getSelectedValue();
    }

    /// Releases the service subscription and service state on the EDT.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            editSequence++;
            serviceSubscription.unsubscribe();
            service.close();
            setControlsEnabled(false);
        });
    }

    /// Configures the stable list, editor, and action controls.
    private void configureComponents() {
        JPanel content = new JPanel(new MigLayout(
                "insets 20, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]12[]"));
        content.setOpaque(false);
        content.add(createHeader(), "growx");
        content.add(createContent(), "grow, push");
        content.add(statusLabel, "growx");
        add(content, BorderLayout.CENTER);

        directoryList.setName("gameDirectoryManagementList");
        directoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        directoryList.setCellRenderer((list, value, index, selected, focus) -> renderDirectory(list, value, selected));
        directoryList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot && !closed) {
                selectCurrentDirectory(directoryList.getSelectedValue());
            }
        });

        addButton.setName("gameDirectoryManagementAdd");
        addButton.addActionListener(event -> beginAdd());
        editButton.setName("gameDirectoryManagementEdit");
        editButton.addActionListener(event -> beginEditSelected());
        removeButton.setName("gameDirectoryManagementRemove");
        removeButton.addActionListener(event -> removeSelected());
        nameField.setName("gameDirectoryManagementName");
        pathField.setName("gameDirectoryManagementPath");
        relativePathBox.setName("gameDirectoryManagementRelativePath");
        choosePathButton.setName("gameDirectoryManagementChoosePath");
        choosePathButton.addActionListener(event -> chooseDirectoryPath());
        saveButton.setName("gameDirectoryManagementSave");
        saveButton.addActionListener(event -> saveEditor());
        cancelButton.setName("gameDirectoryManagementCancel");
        cancelButton.addActionListener(event -> cancelEditor());
        setEditorEnabled(false);
    }

    /// Creates the title and list actions.
    ///
    /// @return configured heading row
    private JPanel createHeader() {
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]8[]", "[]"));
        header.setOpaque(false);
        JLabel heading = new JLabel(i18n("game_directory.title"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");
        header.add(addButton);
        header.add(editButton);
        header.add(removeButton);
        return header;
    }

    /// Creates side-by-side directory selection and editing surfaces.
    ///
    /// @return configured content surface
    private JPanel createContent() {
        JPanel content = new JPanel(new MigLayout("insets 0, fill", "[45%,grow,fill]14[grow,fill]", "[grow,fill]"));
        content.setOpaque(false);
        JScrollPane listScroll = new JScrollPane(directoryList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        content.add(listScroll, "grow, push");
        content.add(createEditor(), "grow, push");
        return content;
    }

    /// Creates the compact add/edit form.
    ///
    /// @return configured editor surface
    private JPanel createEditor() {
        JPanel editor = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]8[]12[]8[]8[]12[]8[]"));
        editor.setOpaque(false);
        editor.add(new JLabel(i18n("game_directory.name")), "growx");
        editor.add(nameField, "growx");
        editor.add(new JSeparator(), "growx");
        editor.add(new JLabel(i18n("game_directory.instance_directory")), "growx");
        JPanel pathRow = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]", "[]"));
        pathRow.setOpaque(false);
        pathRow.add(pathField, "growx");
        pathRow.add(choosePathButton);
        editor.add(pathRow, "growx");
        editor.add(relativePathBox, "growx");
        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]", "[]"));
        actions.setOpaque(false);
        actions.add(new JPanel(), "growx");
        actions.add(cancelButton);
        actions.add(saveButton);
        editor.add(actions, "growx");
        return editor;
    }

    /// Renders a directory name, selected marker, and persisted local or absolute path.
    ///
    /// @param list source list
    /// @param entry rendered entry, or `null` while initializing
    /// @param selected whether the row is Swing-selected
    /// @return configured renderer component
    private static JLabel renderDirectory(
            JList<?> list,
            @Nullable GameDirectoryManagementEntry entry,
            boolean selected) {
        String text = "";
        if (entry != null) {
            String marker = entry.selected() ? i18n("game_directory.selected") + " - " : "";
            text = marker + entry.displayName() + "\n" + entry.path().getPath();
        }
        JLabel label = new JLabel("<html>" + escapeHtml(text).replace("\n", "<br>") + "</html>");
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        if (selected) {
            label.setBackground(list.getSelectionBackground());
            label.setForeground(list.getSelectionForeground());
        } else {
            label.setBackground(list.getBackground());
            label.setForeground(list.getForeground());
        }
        return label;
    }

    /// Escapes persisted user text before it is inserted into the simple list renderer HTML.
    ///
    /// @param text source text
    /// @return escaped HTML text
    private static String escapeHtml(String text) {
        return Objects.requireNonNull(text, "text")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /// Selects the clicked directory as the process-wide current directory.
    ///
    /// @param entry clicked entry, or `null` when the list selection is cleared
    private void selectCurrentDirectory(@Nullable GameDirectoryManagementEntry entry) {
        EdtDispatcher.requireEventDispatchThread();
        if (entry == null || entry.selected()) {
            updateActionAvailability();
            return;
        }
        try {
            service.select(entry.id());
            statusLabel.setText("");
        } catch (RuntimeException failure) {
            interaction.showFailure(this, failureDetail(failure));
            restoreSnapshotSelection();
        }
        updateActionAvailability();
    }

    /// Starts a new directory editor with a relative local path preference.
    private void beginAdd() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || savePending) {
            return;
        }
        editorMode = EditorMode.ADD;
        editedEntry = null;
        nameField.setText("");
        pathField.setText(".minecraft");
        relativePathBox.setSelected(true);
        statusLabel.setText("");
        setEditorEnabled(true);
        nameField.requestFocusInWindow();
        updateActionAvailability();
    }

    /// Starts editing the currently selected directory entry.
    private void beginEditSelected() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || savePending) {
            return;
        }
        @Nullable GameDirectoryManagementEntry selected = directoryList.getSelectedValue();
        if (selected == null) {
            return;
        }
        editorMode = EditorMode.EDIT;
        editedEntry = selected;
        nameField.setText(selected.displayName());
        pathField.setText(selected.path().getPath());
        relativePathBox.setSelected(!selected.path().isAbsolute());
        statusLabel.setText("");
        setEditorEnabled(true);
        nameField.requestFocusInWindow();
        updateActionAvailability();
    }

    /// Opens the native chooser and places its result in the path input without probing the filesystem on the EDT.
    private void chooseDirectoryPath() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || editorMode == EditorMode.IDLE || savePending) {
            return;
        }
        @Nullable Path initialDirectory = parsePathForChooser(pathField.getText());
        @Nullable Path selectedDirectory = interaction.chooseDirectory(this, initialDirectory);
        if (selectedDirectory != null) {
            pathField.setText(selectedDirectory.toString());
        }
    }

    /// Parses a suggested chooser directory without accessing filesystem metadata.
    ///
    /// @param text current raw path text
    /// @return syntactically valid path, or `null` when the text cannot be parsed
    private static @Nullable Path parsePathForChooser(String text) {
        String candidate = Objects.requireNonNull(text, "text").trim();
        if (candidate.isBlank()) {
            return null;
        }
        try {
            return Path.of(candidate).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            return null;
        }
    }

    /// Begins background portable-path preparation before a state mutation.
    private void saveEditor() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || editorMode == EditorMode.IDLE || savePending) {
            return;
        }
        String requestedName = nameField.getText();
        String requestedPath = pathField.getText();
        boolean relative = relativePathBox.isSelected();
        EditorMode requestedMode = editorMode;
        @Nullable GameDirectoryManagementEntry requestedEntry = editedEntry;
        if (requestedMode == EditorMode.EDIT && requestedEntry == null) {
            throw new IllegalStateException("Editing state lost its target directory");
        }
        long request = ++editSequence;
        savePending = true;
        setEditorEnabled(false);
        updateActionAvailability();
        statusLabel.setText(i18n("message.doing"));
        try {
            CompletableFuture.supplyAsync(
                            () -> prepareEdit(requestedName, requestedPath, relative),
                            pathExecutor)
                    .whenComplete((@Nullable GameDirectoryManagementEdit edit, @Nullable Throwable failure) ->
                            EdtDispatcher.execute(() -> completeSavePreparation(
                                    request,
                                    requestedMode,
                                    requestedEntry,
                                    edit,
                                    failure)));
        } catch (RuntimeException failure) {
            completeSaveFailure(failure);
        }
    }

    /// Converts raw form values to a portable edit outside the event dispatch thread.
    ///
    /// @param requestedName raw display name
    /// @param requestedPath raw path text
    /// @param relative whether a local relative path is preferred
    /// @return validated portable edit
    private static GameDirectoryManagementEdit prepareEdit(
            String requestedName,
            String requestedPath,
            boolean relative) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Game directory path preparation must not run on the EDT");
        }
        String name = Objects.requireNonNull(requestedName, "requestedName").trim();
        String pathText = Objects.requireNonNull(requestedPath, "requestedPath").trim();
        if (pathText.isBlank()) {
            throw new IllegalArgumentException("Game directory path must not be blank");
        }
        Path parsedPath = Path.of(pathText);
        PortablePath path = relative ? createRelativePortablePath(parsedPath) : PortablePath.of(parsedPath.toString());
        return new GameDirectoryManagementEdit(name, path);
    }

    /// Converts one path to a launcher-relative portable path when possible without reading directory metadata.
    ///
    /// @param parsedPath syntactically valid user-provided path
    /// @return relative portable path when both locations share a root, otherwise the original portable path
    private static PortablePath createRelativePortablePath(Path parsedPath) {
        Path normalized = Objects.requireNonNull(parsedPath, "parsedPath").toAbsolutePath().normalize();
        try {
            Path relative = Metadata.CURRENT_DIRECTORY.relativize(normalized);
            return PortablePath.fromPath(relative);
        } catch (IllegalArgumentException failure) {
            return PortablePath.of(parsedPath.toString());
        }
    }

    /// Commits a prepared edit or returns the editor to an enabled state after path validation fails.
    ///
    /// @param request originating save sequence
    /// @param requestedMode add or edit operation
    /// @param requestedEntry edit target, or `null` while adding
    /// @param edit prepared values, or `null` after failure
    /// @param failure preparation failure, or `null` after success
    private void completeSavePreparation(
            long request,
            EditorMode requestedMode,
            @Nullable GameDirectoryManagementEntry requestedEntry,
            @Nullable GameDirectoryManagementEdit edit,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || request != editSequence) {
            return;
        }
        if (failure != null || edit == null) {
            completeSaveFailure(failure == null
                    ? new IllegalStateException("Game directory path preparation produced no edit")
                    : failure);
            return;
        }
        try {
            commitEdit(requestedMode, requestedEntry, edit, false);
            completeSaveSuccess();
        } catch (GameDirectoryStorageOverwriteRequiredException protectedStorage) {
            retryProtectedSave(requestedMode, requestedEntry, edit, protectedStorage);
        } catch (RuntimeException operationFailure) {
            completeSaveFailure(operationFailure);
        }
    }

    /// Retries a prepared operation only after the user confirms backup and overwrite.
    ///
    /// @param requestedMode add or edit operation
    /// @param requestedEntry edit target, or `null` while adding
    /// @param edit prepared values
    /// @param protectedStorage initial confirmation requirement
    private void retryProtectedSave(
            EditorMode requestedMode,
            @Nullable GameDirectoryManagementEntry requestedEntry,
            GameDirectoryManagementEdit edit,
            GameDirectoryStorageOverwriteRequiredException protectedStorage) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(protectedStorage, "protectedStorage");
        if (!interaction.confirmReadOnlyOverwrite(this)) {
            completeSaveCancelled();
            return;
        }
        try {
            commitEdit(requestedMode, requestedEntry, edit, true);
            completeSaveSuccess();
        } catch (RuntimeException retryFailure) {
            completeSaveFailure(retryFailure);
        }
    }

    /// Calls the appropriate service mutation for one prepared editor state.
    ///
    /// @param requestedMode add or edit operation
    /// @param requestedEntry edit target, or `null` while adding
    /// @param edit prepared values
    /// @param allowReadOnlyOverwrite whether confirmation was granted
    private void commitEdit(
            EditorMode requestedMode,
            @Nullable GameDirectoryManagementEntry requestedEntry,
            GameDirectoryManagementEdit edit,
            boolean allowReadOnlyOverwrite) {
        if (requestedMode == EditorMode.ADD) {
            service.add(edit, allowReadOnlyOverwrite);
        } else if (requestedMode == EditorMode.EDIT && requestedEntry != null) {
            service.update(requestedEntry.id(), edit, allowReadOnlyOverwrite);
        } else {
            throw new IllegalStateException("No valid game directory editor operation is active");
        }
    }

    /// Completes a successful add or update and resets the editor.
    private void completeSaveSuccess() {
        EdtDispatcher.requireEventDispatchThread();
        savePending = false;
        editorMode = EditorMode.IDLE;
        editedEntry = null;
        statusLabel.setText(i18n("message.success"));
        setEditorEnabled(false);
        updateActionAvailability();
    }

    /// Restores the editor after the user declines read-only storage recovery.
    private void completeSaveCancelled() {
        EdtDispatcher.requireEventDispatchThread();
        savePending = false;
        statusLabel.setText("");
        setEditorEnabled(true);
        updateActionAvailability();
    }

    /// Restores the editor and displays one terminal operation error.
    ///
    /// @param failure path preparation or mutation failure
    private void completeSaveFailure(Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        savePending = false;
        statusLabel.setText(i18n("message.failed"));
        setEditorEnabled(true);
        updateActionAvailability();
        interaction.showFailure(this, failureDetail(Objects.requireNonNull(failure, "failure")));
    }

    /// Removes the selected entry after normal and read-only recovery confirmations.
    private void removeSelected() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || savePending) {
            return;
        }
        @Nullable GameDirectoryManagementEntry selected = directoryList.getSelectedValue();
        if (selected == null || !interaction.confirmRemoval(this, selected)) {
            return;
        }
        try {
            service.remove(selected.id(), false);
            statusLabel.setText(i18n("message.success"));
        } catch (GameDirectoryStorageOverwriteRequiredException protectedStorage) {
            retryProtectedRemoval(selected, protectedStorage);
        } catch (RuntimeException failure) {
            statusLabel.setText(i18n("message.failed"));
            interaction.showFailure(this, failureDetail(failure));
        }
        updateActionAvailability();
    }

    /// Retries one removal after read-only storage recovery is confirmed.
    ///
    /// @param selected stable selected entry
    /// @param protectedStorage initial confirmation requirement
    private void retryProtectedRemoval(
            GameDirectoryManagementEntry selected,
            GameDirectoryStorageOverwriteRequiredException protectedStorage) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(protectedStorage, "protectedStorage");
        if (!interaction.confirmReadOnlyOverwrite(this)) {
            return;
        }
        try {
            service.remove(selected.id(), true);
            statusLabel.setText(i18n("message.success"));
        } catch (RuntimeException failure) {
            statusLabel.setText(i18n("message.failed"));
            interaction.showFailure(this, failureDetail(failure));
        }
    }

    /// Cancels an add or edit without mutating persisted state.
    private void cancelEditor() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || savePending) {
            return;
        }
        editorMode = EditorMode.IDLE;
        editedEntry = null;
        nameField.setText("");
        pathField.setText("");
        statusLabel.setText("");
        setEditorEnabled(false);
        updateActionAvailability();
    }

    /// Receives a service transition and coalesces it onto the EDT.
    ///
    /// @param change service transition
    private void serviceSnapshotChanged(ValueChange<GameDirectoryManagementSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(service.snapshot());
            }
        });
    }

    /// Applies a replacement list while preserving its explicitly selected current directory.
    ///
    /// @param snapshot replacement immutable state
    private void applySnapshot(GameDirectoryManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        GameDirectoryManagementSnapshot replacement = Objects.requireNonNull(snapshot, "snapshot");
        @Nullable GameDirectoryID previousSelection = selectedId(directoryList.getSelectedValue());
        @Nullable GameDirectoryManagementEntry currentEntry = findCurrentEntry(replacement);
        directoryListModel.clear();
        for (GameDirectoryManagementEntry entry : replacement.entries()) {
            directoryListModel.addElement(entry);
        }
        applyingSnapshot = true;
        try {
            if (currentEntry != null) {
                directoryList.setSelectedValue(currentEntry, true);
            } else {
                selectEntryById(previousSelection);
            }
        } finally {
            applyingSnapshot = false;
        }
        displayedSnapshot = replacement;
        updateActionAvailability();
    }

    /// Restores list selection to the explicitly current directory after a selection mutation fails.
    private void restoreSnapshotSelection() {
        @Nullable GameDirectoryManagementSnapshot snapshot = displayedSnapshot;
        if (snapshot != null) {
            applySnapshot(snapshot);
        }
    }

    /// Selects one item by stable ID without publishing another service selection request.
    ///
    /// @param id desired stable identifier, or `null` to clear the list selection
    private void selectEntryById(@Nullable GameDirectoryID id) {
        if (id == null) {
            directoryList.clearSelection();
            return;
        }
        for (int index = 0; index < directoryListModel.size(); index++) {
            GameDirectoryManagementEntry entry = directoryListModel.get(index);
            if (entry.id().equals(id)) {
                directoryList.setSelectedIndex(index);
                return;
            }
        }
        directoryList.clearSelection();
    }

    /// Finds the process-wide current entry represented by one snapshot.
    ///
    /// @param snapshot rendered state
    /// @return selected entry, or `null` when none is selected
    private static @Nullable GameDirectoryManagementEntry findCurrentEntry(GameDirectoryManagementSnapshot snapshot) {
        for (GameDirectoryManagementEntry entry : Objects.requireNonNull(snapshot, "snapshot").entries()) {
            if (entry.selected()) {
                return entry;
            }
        }
        return null;
    }

    /// Extracts a selected entry's stable ID.
    ///
    /// @param entry selected entry, or `null`
    /// @return stable identifier, or `null`
    private static @Nullable GameDirectoryID selectedId(@Nullable GameDirectoryManagementEntry entry) {
        return entry == null ? null : entry.id();
    }

    /// Enables or disables editor inputs while retaining the current form text.
    ///
    /// @param enabled whether an add or edit form is active and no save is pending
    private void setEditorEnabled(boolean enabled) {
        boolean available = enabled && !closed && !savePending;
        nameField.setEnabled(available);
        pathField.setEnabled(available);
        relativePathBox.setEnabled(available);
        choosePathButton.setEnabled(available);
        saveButton.setEnabled(available);
        cancelButton.setEnabled(available);
    }

    /// Enables every panel control during close without changing model state.
    ///
    /// @param enabled whether the page is interactive
    private void setControlsEnabled(boolean enabled) {
        directoryList.setEnabled(enabled);
        addButton.setEnabled(enabled);
        editButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
        nameField.setEnabled(enabled);
        pathField.setEnabled(enabled);
        relativePathBox.setEnabled(enabled);
        choosePathButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        cancelButton.setEnabled(enabled);
    }

    /// Updates action availability from selection and editor state.
    private void updateActionAvailability() {
        boolean interactive = !closed && !savePending;
        @Nullable GameDirectoryManagementEntry selected = directoryList.getSelectedValue();
        directoryList.setEnabled(interactive);
        addButton.setEnabled(interactive && editorMode == EditorMode.IDLE);
        editButton.setEnabled(interactive && editorMode == EditorMode.IDLE && selected != null);
        removeButton.setEnabled(interactive && editorMode == EditorMode.IDLE && selected != null);
        if (editorMode == EditorMode.IDLE) {
            setEditorEnabled(false);
        }
    }

    /// Returns a concise visible message for an operation failure.
    ///
    /// @param failure terminal operation failure
    /// @return non-blank failure detail
    private static String failureDetail(Throwable failure) {
        Throwable source = Objects.requireNonNull(failure, "failure");
        @Nullable String message = source.getMessage();
        return message == null || message.isBlank() ? source.getClass().getSimpleName() : message;
    }

    /// Editor mode for the reusable right-side form.
    @NotNullByDefault
    private enum EditorMode {
        /// No add or edit form is active.
        IDLE,

        /// The form will add one new entry.
        ADD,

        /// The form will update one attached entry.
        EDIT
    }
}
