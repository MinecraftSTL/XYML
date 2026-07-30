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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Edits reusable global game-settings presets without coupling the shell to persistence details.
///
/// All expensive persistence remains behind [GameSettingsPresetsStore]. This panel only changes in-memory controls on
/// the Swing EDT and completes store stages back on that same EDT, so list selection and editor actions never wait on
/// file-system I/O.
@NotNullByDefault
public final class GameSettingsPresetsPanel extends JPanel implements AutoCloseable {
    /// Presentation and persistence adapter owned by this panel.
    private final GameSettingsPresetsStore store;

    /// Mutable list model rendered from immutable preset snapshots.
    private final DefaultListModel<GameSettingsPresetSnapshot> presetListModel = new DefaultListModel<>();

    /// Single-selection preset list.
    private final JList<GameSettingsPresetSnapshot> presetList = new JList<>(presetListModel);

    /// Starts creation of a new automatic or custom-named preset.
    private final JButton createButton = new JButton(i18n("settings.type.global.preset.create"));

    /// Opens the selected preset's rename prompt.
    private final JButton renameButton = new JButton(i18n("settings.type.global.preset.rename"));

    /// Requests deletion of the selected preset after user confirmation.
    private final JButton deleteButton = new JButton(i18n("settings.type.global.preset.remove"));

    /// Assigns the selected preset as the launcher default.
    private final JButton defaultButton = new JButton(i18n("settings.type.global.preset.default"));

    /// Shows the selected preset's display name above editable values.
    private final JLabel selectedNameLabel = new JLabel();

    /// Selects automatic or manually bounded memory allocation.
    private final JCheckBox autoMemoryBox = new JCheckBox(i18n("settings.memory.auto_allocate"));

    /// Optional lower heap bound in MiB.
    private final JTextField minMemoryField = new JTextField();

    /// Optional upper heap bound in MiB.
    private final JTextField maxMemoryField = new JTextField();

    /// Selects Java runtime resolution policy.
    private final JComboBox<JavaVersionType> javaTypeBox = new JComboBox<>(JavaVersionType.values());

    /// User-entered Java major-version selector used by the VERSION policy.
    private final JTextField customJavaVersionField = new JTextField();

    /// User-entered Java executable path used by the CUSTOM policy.
    private final JTextField customJavaPathField = new JTextField();

    /// User-entered JVM arguments passed in addition to launcher-generated options.
    private final JTextArea jvmOptionsArea = new JTextArea(5, 24);

    /// Disables launcher-generated JVM arguments when selected.
    private final JCheckBox noJvmOptionsBox = new JCheckBox(i18n("settings.advanced.no_jvm_args"));

    /// Selects the default isolation strategy for future game instances.
    private final JComboBox<DefaultIsolationType> isolationTypeBox = new JComboBox<>(DefaultIsolationType.values());

    /// Commits all currently visible supported editor fields.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Displays validation and asynchronous command feedback.
    private final JLabel statusLabel = new JLabel();

    /// Subscription delivering immutable store changes.
    private final Subscription storeSubscription;

    /// Immutable snapshot currently rendered by the list, or null before setup completes.
    private @Nullable GameSettingsPresetsSnapshot displayedSnapshot;

    /// Suppresses control-side state changes while a snapshot populates fields.
    private boolean applyingSnapshot;

    /// Tracks the latest command so stale completion callbacks cannot overwrite newer feedback.
    private long mutationSequence;

    /// Whether an asynchronous store command is still pending.
    private boolean mutationPending;

    /// Whether this panel has released its subscription and store resources.
    private boolean closed;

    /// Creates a global preset editor backed by the currently loaded launcher settings.
    ///
    /// @return configured preset editor panel
    public static GameSettingsPresetsPanel createForCurrentSettings() {
        EdtDispatcher.requireEventDispatchThread();
        LauncherGameSettingsPresetsStore settingsStore = LauncherGameSettingsPresetsStore.createForCurrentSettings();
        try {
            return new GameSettingsPresetsPanel(settingsStore);
        } catch (RuntimeException exception) {
            settingsStore.close();
            throw exception;
        }
    }

    /// Creates a preset editor on the Swing EDT.
    ///
    /// @param store presentation and persistence source
    public GameSettingsPresetsPanel(GameSettingsPresetsStore store) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        configureComponents();
        storeSubscription = store.subscribe(this::storeSnapshotChanged);
        applySnapshot(store.snapshot());
    }

    /// Returns the immutable state currently rendered by this panel.
    ///
    /// @return latest displayed preset state
    public GameSettingsPresetsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial preset snapshot was not applied");
    }

    /// Returns the selected rendered preset.
    ///
    /// @return selected preset, or null when no preset is available
    public @Nullable GameSettingsPresetSnapshot selectedPreset() {
        EdtDispatcher.requireEventDispatchThread();
        return presetList.getSelectedValue();
    }

    /// Releases the store subscription and disables controls from any calling thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                storeSubscription.unsubscribe();
                store.close();
                updateControlAvailability();
            }
        });
    }

    /// Builds the stable list, form, renderers, and action bindings.
    private void configureComponents() {
        setOpaque(false);
        JPanel content = new JPanel(new MigLayout(
                "insets 20, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]12[]"));
        content.setOpaque(false);
        content.add(createHeader(), "growx");
        content.add(createContentSplit(), "grow, push");
        content.add(statusLabel, "growx");
        add(content, BorderLayout.CENTER);

        presetList.setName("gameSettingsPresetList");
        presetList.setOpaque(false);
        presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        presetList.setCellRenderer(presetRenderer());
        presetList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                applySelectedPreset(presetList.getSelectedValue());
            }
        });

        createButton.setName("gameSettingsPresetCreate");
        createButton.addActionListener(event -> promptCreatePreset());
        renameButton.setName("gameSettingsPresetRename");
        renameButton.addActionListener(event -> promptRenamePreset());
        deleteButton.setName("gameSettingsPresetDelete");
        deleteButton.addActionListener(event -> confirmDeletePreset());
        defaultButton.setName("gameSettingsPresetDefault");
        defaultButton.addActionListener(event -> assignDefaultPreset());
        saveButton.setName("gameSettingsPresetSave");
        saveButton.addActionListener(event -> saveSelectedPreset());

        selectedNameLabel.setName("gameSettingsPresetName");
        selectedNameLabel.setFont(selectedNameLabel.getFont().deriveFont(Font.BOLD, 18.0F));
        minMemoryField.setName("gameSettingsPresetMinMemory");
        maxMemoryField.setName("gameSettingsPresetMaxMemory");
        javaTypeBox.setName("gameSettingsPresetJavaType");
        javaTypeBox.setRenderer(javaTypeRenderer());
        customJavaVersionField.setName("gameSettingsPresetJavaVersion");
        customJavaPathField.setName("gameSettingsPresetJavaPath");
        jvmOptionsArea.setName("gameSettingsPresetJvmOptions");
        jvmOptionsArea.setLineWrap(true);
        jvmOptionsArea.setWrapStyleWord(true);
        noJvmOptionsBox.setName("gameSettingsPresetNoJvmOptions");
        isolationTypeBox.setName("gameSettingsPresetIsolation");
        isolationTypeBox.setRenderer(isolationRenderer());
        autoMemoryBox.setName("gameSettingsPresetAutoMemory");
        autoMemoryBox.addActionListener(event -> updateMemoryControlAvailability());
        javaTypeBox.addActionListener(event -> updateJavaControlAvailability());
    }

    /// Creates the page heading and preset-management toolbar.
    ///
    /// @return configured header row
    private JPanel createHeader() {
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]8[]8[]", "[]"));
        header.setOpaque(false);
        JLabel heading = new JLabel(i18n("settings.type.global.preset.manage_all"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");
        header.add(createButton);
        header.add(renameButton);
        header.add(deleteButton);
        header.add(defaultButton);
        return header;
    }

    /// Creates the responsive list-and-editor split surface.
    ///
    /// @return configured split panel
    private JPanel createContentSplit() {
        JPanel split = new JPanel(new MigLayout("insets 0, fill", "[40%,grow,fill]14[grow,fill]", "[grow,fill]"));
        split.setOpaque(false);
        JScrollPane listScrollPane = new JScrollPane(presetList);
        listScrollPane.setBorder(BorderFactory.createEmptyBorder());
        listScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        SwingTransparency.revealBackgroundThroughScrollPane(listScrollPane);
        split.add(listScrollPane, "grow, push");
        split.add(createEditorScrollPane(), "grow, push");
        return split;
    }

    /// Creates a scrollable editor so JVM options and small displays remain usable.
    ///
    /// @return configured editor scroll pane
    private JScrollPane createEditorScrollPane() {
        JPanel editor = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]12[]8[]8[]12[]8[]8[]12[]8[]8[]"));
        editor.setOpaque(false);
        editor.add(selectedNameLabel, "growx");
        editor.add(new JSeparator(), "growx");
        editor.add(autoMemoryBox, "growx");
        editor.add(createFieldRow(i18n("settings.memory.lower_bound") + " (MiB)", minMemoryField), "growx");
        editor.add(createFieldRow(i18n("settings.memory.manual_allocate") + " (MiB)", maxMemoryField), "growx");
        editor.add(new JSeparator(), "growx");
        editor.add(createFieldRow(i18n("settings.game.java_directory"), javaTypeBox), "growx");
        editor.add(createFieldRow(i18n("settings.game.java_directory.version"), customJavaVersionField), "growx");
        editor.add(createFieldRow(i18n("settings.custom"), customJavaPathField), "growx");
        editor.add(new JSeparator(), "growx");
        editor.add(createFieldRow(i18n("settings.advanced.jvm_args"), new JScrollPane(jvmOptionsArea)), "growx, growy");
        editor.add(noJvmOptionsBox, "growx");
        editor.add(createFieldRow(i18n("settings.game.default_isolation"), isolationTypeBox), "growx");
        editor.add(saveButton, "alignx right");

        JScrollPane scrollPane = new JScrollPane(editor);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        return scrollPane;
    }

    /// Creates one compact two-column label and editor row.
    ///
    /// @param labelText localized field label
    /// @param editor editable component
    /// @return configured form row
    private static JPanel createFieldRow(String labelText, Component editor) {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[160!,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        row.add(Objects.requireNonNull(editor, "editor"), "growx");
        return row;
    }

    /// Creates a renderer that makes the default preset recognizable without hiding its name.
    ///
    /// @return preset list renderer
    private static ListCellRenderer<GameSettingsPresetSnapshot> presetRenderer() {
        return (list, value, index, selected, focus) -> {
            String text = value == null
                    ? ""
                    : value.defaultPreset()
                    ? value.displayName() + " (" + i18n("settings.type.global.preset.default") + ")"
                    : value.displayName();
            return comboRenderer(list, text, selected);
        };
    }

    /// Creates a renderer for all Java policies supported by the underlying settings API.
    ///
    /// @return Java policy combo-box renderer
    private static ListCellRenderer<JavaVersionType> javaTypeRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, javaTypeText(value), selected);
    }

    /// Creates a renderer for default isolation strategies.
    ///
    /// @return isolation strategy combo-box renderer
    private static ListCellRenderer<DefaultIsolationType> isolationRenderer() {
        return (list, value, index, selected, focus) -> comboRenderer(list, isolationText(value), selected);
    }

    /// Creates a list-style label whose solid surface is limited to the active selection.
    ///
    /// @param list source list
    /// @param text visible item text
    /// @param selected whether the item is selected
    /// @return configured renderer label
    private static JLabel comboRenderer(JList<?> list, String text, boolean selected) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setOpaque(selected);
        label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        if (selected) {
            label.setBackground(list.getSelectionBackground());
            label.setForeground(list.getSelectionForeground());
        } else {
            label.setBackground(list.getBackground());
            label.setForeground(list.getForeground());
        }
        return label;
    }

    /// Converts a Java policy to its concise user-facing label.
    ///
    /// @param type policy to display, or null while a renderer initializes
    /// @return visible policy label
    private static String javaTypeText(@Nullable JavaVersionType type) {
        if (type == JavaVersionType.AUTO) {
            return i18n("settings.game.java_directory.auto");
        }
        if (type == JavaVersionType.VERSION) {
            return i18n("settings.game.java_directory.version");
        }
        if (type == JavaVersionType.CUSTOM) {
            return i18n("settings.custom");
        }
        return "Detected Java";
    }

    /// Converts an isolation strategy to its localized user-facing label.
    ///
    /// @param type strategy to display, or null while a renderer initializes
    /// @return visible strategy label
    private static String isolationText(@Nullable DefaultIsolationType type) {
        if (type == DefaultIsolationType.ALWAYS) {
            return i18n("settings.game.default_isolation.always");
        }
        if (type == DefaultIsolationType.NEVER) {
            return i18n("settings.game.default_isolation.never");
        }
        return i18n("settings.game.default_isolation.modded");
    }

    /// Prompts for an optional custom name and starts asynchronous creation.
    private void promptCreatePreset() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String name = JOptionPane.showInputDialog(
                this,
                i18n("settings.type.global.preset.name"),
                i18n("settings.type.global.preset.create"),
                JOptionPane.PLAIN_MESSAGE);
        if (name != null) {
            beginMutation(() -> store.createPreset(name), null, true);
        }
    }

    /// Prompts for a selected preset's required custom name.
    private void promptRenamePreset() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameSettingsPresetSnapshot selected = presetList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String initialValue = selected.customName() == null ? "" : selected.customName();
        @Nullable String name = (String) JOptionPane.showInputDialog(
                this,
                i18n("settings.type.global.preset.name"),
                i18n("settings.type.global.preset.rename"),
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                initialValue);
        if (name != null) {
            beginMutation(() -> store.renamePreset(selected.id(), name), selected.id(), false);
        }
    }

    /// Confirms destructive deletion before changing any persisted preset state.
    private void confirmDeletePreset() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameSettingsPresetSnapshot selected = presetList.getSelectedValue();
        if (selected == null) {
            return;
        }
        int confirmed = JOptionPane.showConfirmDialog(
                this,
                i18n("settings.type.global.preset.remove.confirm", selected.displayName()),
                i18n("settings.type.global.preset.remove"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirmed == JOptionPane.YES_OPTION) {
            beginMutation(() -> store.deletePreset(selected.id()), null, false);
        }
    }

    /// Makes the selected preset the global default through the store.
    private void assignDefaultPreset() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameSettingsPresetSnapshot selected = presetList.getSelectedValue();
        if (selected != null) {
            beginMutation(() -> store.setDefaultPreset(selected.id()), selected.id(), false);
        }
    }

    /// Validates supported editor controls and starts an asynchronous save command.
    private void saveSelectedPreset() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameSettingsPresetSnapshot selected = presetList.getSelectedValue();
        if (selected == null) {
            return;
        }
        @Nullable Integer minMemory = parseOptionalMemory(minMemoryField.getText());
        @Nullable Integer maxMemory = parseOptionalMemory(maxMemoryField.getText());
        if (containsInvalidMemory(minMemoryField.getText(), minMemory)
                || containsInvalidMemory(maxMemoryField.getText(), maxMemory)
                || (!autoMemoryBox.isSelected() && maxMemory == null)) {
            statusLabel.setText(i18n("input.number"));
            return;
        }
        @Nullable JavaVersionType javaType = (JavaVersionType) javaTypeBox.getSelectedItem();
        @Nullable DefaultIsolationType isolationType = (DefaultIsolationType) isolationTypeBox.getSelectedItem();
        if (javaType == null || isolationType == null) {
            statusLabel.setText(i18n("message.failed"));
            return;
        }
        try {
            GameSettingsPresetEditor editor = new GameSettingsPresetEditor(
                    selected.id(),
                    autoMemoryBox.isSelected(),
                    minMemory,
                    maxMemory,
                    javaType,
                    customJavaVersionField.getText().trim(),
                    customJavaPathField.getText().trim(),
                    jvmOptionsArea.getText(),
                    noJvmOptionsBox.isSelected(),
                    isolationType);
            beginMutation(() -> store.updatePreset(editor), selected.id(), false);
        } catch (IllegalArgumentException failure) {
            statusLabel.setText(i18n("input.number"));
        }
    }

    /// Parses an optional non-negative memory value from a user-facing text field.
    ///
    /// @param text raw text value
    /// @return parsed MiB value, or null for a blank or malformed field
    private static @Nullable Integer parseOptionalMemory(String text) {
        String normalized = Objects.requireNonNull(text, "text").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(normalized);
            return value >= 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /// Determines whether a non-blank memory field failed numeric validation.
    ///
    /// @param raw raw user-entered text
    /// @param parsed parsed memory value, or null
    /// @return whether validation failed
    private static boolean containsInvalidMemory(String raw, @Nullable Integer parsed) {
        return !Objects.requireNonNull(raw, "raw").trim().isEmpty() && parsed == null;
    }

    /// Starts a store command without waiting for its completion on the EDT.
    ///
    /// @param command asynchronous store command supplier
    /// @param desiredSelection preset to select after success, or null when the normal selection should remain
    /// @param selectLastCreated whether the last entry should be selected after a create command
    private void beginMutation(
            Supplier<CompletionStage<GameSettingsPresetsSnapshot>> command,
            @Nullable GameSettingsPresetID desiredSelection,
            boolean selectLastCreated) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || mutationPending) {
            return;
        }
        long request = ++mutationSequence;
        mutationPending = true;
        statusLabel.setText(i18n("message.doing"));
        updateControlAvailability();
        try {
            CompletionStage<GameSettingsPresetsSnapshot> completion = Objects.requireNonNull(
                    Objects.requireNonNull(command, "command").get(),
                    "preset command completion");
            completion.whenComplete((@Nullable GameSettingsPresetsSnapshot snapshot, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> completeMutation(
                            request,
                            snapshot,
                            failure,
                            desiredSelection,
                            selectLastCreated)));
        } catch (RuntimeException failure) {
            completeMutation(request, null, failure, desiredSelection, selectLastCreated);
        }
    }

    /// Applies a current asynchronous command's completion state on the Swing EDT.
    ///
    /// @param request command sequence
    /// @param snapshot terminal state, or null on failure
    /// @param failure terminal failure, or null on success
    /// @param desiredSelection identity to restore after success, or null
    /// @param selectLastCreated whether the latest entry should be selected after success
    private void completeMutation(
            long request,
            @Nullable GameSettingsPresetsSnapshot snapshot,
            @Nullable Throwable failure,
            @Nullable GameSettingsPresetID desiredSelection,
            boolean selectLastCreated) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || request != mutationSequence) {
            return;
        }
        mutationPending = false;
        if (failure != null || snapshot == null) {
            statusLabel.setText(i18n("message.failed"));
            updateControlAvailability();
            return;
        }
        applySnapshot(snapshot);
        if (desiredSelection != null) {
            selectPreset(desiredSelection);
        } else if (selectLastCreated && !snapshot.presets().isEmpty()) {
            int last = snapshot.presets().size() - 1;
            selectPreset(snapshot.presets().get(last).id());
        }
        statusLabel.setText(i18n("message.success"));
        updateControlAvailability();
    }

    /// Coalesces store publications onto the Swing EDT before interacting with Swing components.
    ///
    /// @param change immutable store transition
    private void storeSnapshotChanged(ValueChange<GameSettingsPresetsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(store.snapshot());
            }
        });
    }

    /// Rebuilds the list model and restores a surviving selection from an immutable snapshot.
    ///
    /// @param snapshot new immutable state
    private void applySnapshot(GameSettingsPresetsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        GameSettingsPresetsSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
        @Nullable GameSettingsPresetSnapshot previousSelection = presetList.getSelectedValue();
        @Nullable GameSettingsPresetID previousId = previousSelection == null ? null : previousSelection.id();
        applyingSnapshot = true;
        try {
            displayedSnapshot = state;
            presetListModel.clear();
            for (GameSettingsPresetSnapshot preset : state.presets()) {
                presetListModel.addElement(preset);
            }
            @Nullable GameSettingsPresetSnapshot restored = state.findPreset(previousId);
            if (restored != null) {
                presetList.setSelectedValue(restored, true);
            } else if (!state.presets().isEmpty()) {
                presetList.setSelectedIndex(0);
            } else {
                presetList.clearSelection();
            }
            applySelectedPreset(presetList.getSelectedValue());
        } finally {
            applyingSnapshot = false;
        }
        updateControlAvailability();
    }

    /// Selects one surviving preset identity in the current list model.
    ///
    /// @param id desired identity
    private void selectPreset(GameSettingsPresetID id) {
        for (int index = 0; index < presetListModel.getSize(); index++) {
            GameSettingsPresetSnapshot candidate = presetListModel.getElementAt(index);
            if (candidate.id().equals(id)) {
                presetList.setSelectedIndex(index);
                presetList.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    /// Applies selected preset values to all form controls without treating them as a user edit.
    ///
    /// @param preset selected immutable preset, or null when no row exists
    private void applySelectedPreset(@Nullable GameSettingsPresetSnapshot preset) {
        EdtDispatcher.requireEventDispatchThread();
        boolean wasApplying = applyingSnapshot;
        applyingSnapshot = true;
        try {
            if (preset == null) {
                selectedNameLabel.setText("");
                autoMemoryBox.setSelected(true);
                minMemoryField.setText("");
                maxMemoryField.setText("");
                javaTypeBox.setSelectedItem(JavaVersionType.AUTO);
                customJavaVersionField.setText("");
                customJavaPathField.setText("");
                jvmOptionsArea.setText("");
                noJvmOptionsBox.setSelected(false);
                isolationTypeBox.setSelectedItem(DefaultIsolationType.MODDED);
            } else {
                selectedNameLabel.setText(preset.displayName());
                autoMemoryBox.setSelected(preset.autoMemory());
                minMemoryField.setText(numberText(preset.minMemoryMiB()));
                maxMemoryField.setText(numberText(preset.maxMemoryMiB()));
                javaTypeBox.setSelectedItem(preset.javaType());
                customJavaVersionField.setText(preset.customJavaVersion());
                customJavaPathField.setText(preset.customJavaPath());
                jvmOptionsArea.setText(preset.jvmOptions());
                noJvmOptionsBox.setSelected(preset.noJvmOptions());
                isolationTypeBox.setSelectedItem(preset.defaultIsolationType());
            }
            updateMemoryControlAvailability();
            updateJavaControlAvailability();
        } finally {
            applyingSnapshot = wasApplying;
        }
        updateControlAvailability();
    }

    /// Converts a nullable numeric setting to editable text.
    ///
    /// @param value configured value, or null
    /// @return decimal text or an empty optional field
    private static String numberText(@Nullable Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    /// Enables manual memory controls only when manual allocation is selected and edits are allowed.
    private void updateMemoryControlAvailability() {
        @Nullable GameSettingsPresetsSnapshot snapshot = displayedSnapshot;
        boolean editable = snapshot != null && snapshot.writable() && !closed && !mutationPending
                && presetList.getSelectedValue() != null;
        boolean manual = !autoMemoryBox.isSelected();
        minMemoryField.setEnabled(editable && manual);
        maxMemoryField.setEnabled(editable && manual);
    }

    /// Enables policy-specific Java text fields only when the selected policy needs them.
    private void updateJavaControlAvailability() {
        @Nullable GameSettingsPresetsSnapshot snapshot = displayedSnapshot;
        boolean editable = snapshot != null && snapshot.writable() && !closed && !mutationPending
                && presetList.getSelectedValue() != null;
        @Nullable JavaVersionType type = (JavaVersionType) javaTypeBox.getSelectedItem();
        customJavaVersionField.setEnabled(editable && type == JavaVersionType.VERSION);
        customJavaPathField.setEnabled(editable && type == JavaVersionType.CUSTOM);
    }

    /// Enables or disables action and editor controls for the current writable, selected, and pending states.
    private void updateControlAvailability() {
        @Nullable GameSettingsPresetsSnapshot snapshot = displayedSnapshot;
        @Nullable GameSettingsPresetSnapshot selected = presetList.getSelectedValue();
        boolean writable = snapshot != null && snapshot.writable() && !closed && !mutationPending;
        boolean hasSelection = selected != null;
        createButton.setEnabled(writable);
        renameButton.setEnabled(writable && hasSelection);
        deleteButton.setEnabled(writable && hasSelection && snapshot != null && snapshot.presets().size() > 1);
        defaultButton.setEnabled(writable && hasSelection && !selected.defaultPreset());
        autoMemoryBox.setEnabled(writable && hasSelection);
        javaTypeBox.setEnabled(writable && hasSelection);
        jvmOptionsArea.setEnabled(writable && hasSelection);
        noJvmOptionsBox.setEnabled(writable && hasSelection);
        isolationTypeBox.setEnabled(writable && hasSelection);
        saveButton.setEnabled(writable && hasSelection);
        updateMemoryControlAvailability();
        updateJavaControlAvailability();
    }
}
