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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Edits a bounded, safe subset of launch settings for one managed game instance.
///
/// The page exposes both values and local-override choices. Saving through [InstanceGameSettingsStore] therefore
/// changes only settings the user explicitly marks as instance-specific; clearing an override resumes preset
/// inheritance instead of copying effective values back into the instance configuration.
@NotNullByDefault
public final class InstanceGameSettingsPanel extends JPanel implements AutoCloseable {
    /// Largest manually accepted heap allocation to prevent accidental integer-sized configuration mistakes.
    private static final int MAXIMUM_MEMORY_MIB = 1_048_576;

    /// Backing store that maps UI values to the current instance's durable settings.
    private final InstanceGameSettingsStore store;

    /// Enables local automatic-memory and maximum-memory settings.
    private final JCheckBox memoryOverrideBox = new JCheckBox(i18n("settings.game.override_global"));

    /// Selects automatic memory allocation when memory settings are local.
    private final JCheckBox automaticMemoryBox = new JCheckBox(i18n("settings.memory.auto_allocate"));

    /// Manual maximum heap allocation in MiB.
    private final JTextField maximumMemoryField = new JTextField();

    /// Enables local Java strategy and its associated inputs.
    private final JCheckBox javaOverrideBox = new JCheckBox(i18n("settings.game.override_global"));

    /// Selects how the instance resolves its Java runtime.
    private final JComboBox<JavaVersionType> javaVersionTypeBox =
            new JComboBox<>(new DefaultComboBoxModel<>(JavaVersionType.values()));

    /// Java major version used when the `VERSION` strategy is selected.
    private final JTextField customJavaVersionField = new JTextField();

    /// Java executable path used when the `CUSTOM` strategy is selected.
    private final JTextField customJavaPathField = new JTextField();

    /// Enables local JVM arguments.
    private final JCheckBox jvmOptionsOverrideBox = new JCheckBox(i18n("settings.game.override_global"));

    /// Free-form JVM argument input.
    private final JTextArea jvmOptionsArea = new JTextArea(3, 40);

    /// Enables a local game working-directory strategy.
    private final JCheckBox runningDirectoryOverrideBox = new JCheckBox(i18n("settings.game.isolation"));

    /// Custom working-directory path, where an empty local value selects the instance version root.
    private final JTextField runningDirectoryField = new JTextField();

    /// Commits the currently edited snapshot after validation.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Discards unsaved field edits and reloads current repository values.
    private final JButton reloadButton = new JButton(i18n("button.refresh"));

    /// Displays validation and save results without forcing a modal dialog.
    private final JLabel statusLabel = new JLabel();

    /// Snapshot currently represented by the controls, or `null` during construction only.
    private @Nullable InstanceGameSettingsSnapshot displayedSnapshot;

    /// Prevents programmatic control updates from recomputing dependent controls repeatedly.
    private boolean applyingSnapshot;

    /// Prevents any further interaction after lifecycle cleanup.
    private boolean closed;

    /// Creates a production panel backed by one `XYMLGameRepository` instance.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank instance identifier
    public InstanceGameSettingsPanel(XYMLGameRepository repository, String instanceId) {
        this(new RepositoryInstanceGameSettingsStore(repository, instanceId));
    }

    /// Creates a settings panel with an explicit store for deterministic UI testing.
    ///
    /// @param store backing store for effective values and persistence
    InstanceGameSettingsPanel(InstanceGameSettingsStore store) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        configureComponents();
        applySnapshot(store.snapshot());
    }

    /// Returns the snapshot currently represented by the UI controls.
    ///
    /// @return displayed settings snapshot
    public InstanceGameSettingsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial game settings snapshot was not applied");
    }

    /// Releases this panel and prevents further persistence requests from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                setInteractiveControlsEnabled(false);
            }
        });
    }

    /// Builds the scrolling settings surface and its stable component names.
    private void configureComponents() {
        setName("instanceGameSettings");
        setOpaque(false);

        JPanel content = new JPanel(new MigLayout(
                "insets 20, fillx, wrap 1",
                "[grow,fill]",
                "[]16[]10[]16[]10[]16[]10[]12[]"));
        content.setOpaque(false);
        content.add(createHeading(), "growx");
        content.add(createMemorySection(), "growx");
        content.add(new JSeparator(), "growx");
        content.add(createJavaSection(), "growx");
        content.add(new JSeparator(), "growx");
        content.add(createJvmSection(), "growx");
        content.add(new JSeparator(), "growx");
        content.add(createRunningDirectorySection(), "growx");
        content.add(createFooter(), "growx");

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        configureControlInteractions();
    }

    /// Creates the page heading without a decorative panel wrapper.
    ///
    /// @return configured heading label
    private static JLabel createHeading() {
        JLabel heading = new JLabel(i18n("settings.game"));
        heading.setName("instanceGameSettingsTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22.0F));
        return heading;
    }

    /// Creates the memory override and allocation controls.
    ///
    /// @return configured memory section
    private JPanel createMemorySection() {
        JPanel section = sectionPanel("instanceGameSettingsMemory");
        section.add(sectionLabel(i18n("settings.memory")), "span 2, growx");
        memoryOverrideBox.setName("instanceGameSettingsMemoryOverride");
        automaticMemoryBox.setName("instanceGameSettingsAutomaticMemory");
        maximumMemoryField.setName("instanceGameSettingsMaximumMemory");
        section.add(memoryOverrideBox, "span 2, growx");
        section.add(automaticMemoryBox, "span 2, growx");
        section.add(new JLabel(i18n("settings.memory.manual_allocate")), "aligny center");
        section.add(maximumMemoryField, "growx");
        return section;
    }

    /// Creates the Java strategy controls and context-sensitive input fields.
    ///
    /// @return configured Java section
    private JPanel createJavaSection() {
        JPanel section = sectionPanel("instanceGameSettingsJava");
        section.add(sectionLabel(i18n("settings.game.java_directory")), "span 2, growx");
        javaOverrideBox.setName("instanceGameSettingsJavaOverride");
        javaVersionTypeBox.setName("instanceGameSettingsJavaMode");
        customJavaVersionField.setName("instanceGameSettingsJavaVersion");
        customJavaPathField.setName("instanceGameSettingsJavaPath");
        section.add(javaOverrideBox, "span 2, growx");
        section.add(new JLabel(i18n("settings.game.java_directory")), "aligny center");
        section.add(javaVersionTypeBox, "growx");
        section.add(new JLabel(i18n("settings.game.java_directory.version")), "aligny center");
        section.add(customJavaVersionField, "growx");
        section.add(new JLabel(i18n("java.install.archive")), "aligny center");
        section.add(customJavaPathField, "growx");
        return section;
    }

    /// Creates the per-instance JVM arguments field.
    ///
    /// @return configured JVM arguments section
    private JPanel createJvmSection() {
        JPanel section = sectionPanel("instanceGameSettingsJvm");
        section.add(sectionLabel(i18n("settings.advanced.jvm_args")), "span 2, growx");
        jvmOptionsOverrideBox.setName("instanceGameSettingsJvmOverride");
        jvmOptionsArea.setName("instanceGameSettingsJvmOptions");
        jvmOptionsArea.setLineWrap(true);
        jvmOptionsArea.setWrapStyleWord(true);
        section.add(jvmOptionsOverrideBox, "span 2, growx");
        JScrollPane optionsScrollPane = new JScrollPane(jvmOptionsArea);
        optionsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        section.add(optionsScrollPane, "span 2, growx, h 78!");
        return section;
    }

    /// Creates the working-directory inheritance controls.
    ///
    /// @return configured working-directory section
    private JPanel createRunningDirectorySection() {
        JPanel section = sectionPanel("instanceGameSettingsRunningDirectory");
        section.add(sectionLabel(i18n("settings.game.working_directory")), "span 2, growx");
        runningDirectoryOverrideBox.setName("instanceGameSettingsRunningDirectoryOverride");
        runningDirectoryField.setName("instanceGameSettingsRunningDirectoryPath");
        runningDirectoryOverrideBox.setToolTipText(i18n("settings.game.isolation.subtitle"));
        section.add(runningDirectoryOverrideBox, "span 2, growx");
        section.add(new JLabel(i18n("settings.game.running_directory")), "aligny center");
        section.add(runningDirectoryField, "growx");
        return section;
    }

    /// Creates the save, reload, and inline status row.
    ///
    /// @return configured action footer
    private JPanel createFooter() {
        JPanel footer = new JPanel(new MigLayout("insets 8 0 0 0, fillx", "[grow,fill][]8[]", "[]"));
        footer.setOpaque(false);
        saveButton.setName("instanceGameSettingsSave");
        reloadButton.setName("instanceGameSettingsReload");
        statusLabel.setName("instanceGameSettingsStatus");
        footer.add(statusLabel, "growx");
        footer.add(reloadButton);
        footer.add(saveButton);
        return footer;
    }

    /// Connects local controls to dependent enabled state and explicit persistence commands.
    private void configureControlInteractions() {
        memoryOverrideBox.addActionListener(event -> updateEditingAvailability());
        automaticMemoryBox.addActionListener(event -> updateEditingAvailability());
        javaOverrideBox.addActionListener(event -> updateEditingAvailability());
        javaVersionTypeBox.addActionListener(event -> updateEditingAvailability());
        jvmOptionsOverrideBox.addActionListener(event -> updateEditingAvailability());
        runningDirectoryOverrideBox.addActionListener(event -> updateEditingAvailability());
        saveButton.addActionListener(event -> saveEditedSnapshot());
        reloadButton.addActionListener(event -> reloadSnapshot());
    }

    /// Persists the currently edited values or presents one concise validation failure.
    private void saveEditedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        try {
            store.save(editedSnapshot());
            applySnapshot(store.snapshot());
            statusLabel.setText("Changes saved for this instance.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            statusLabel.setText("Cannot save: " + Objects.requireNonNullElse(exception.getMessage(), "invalid settings"));
        }
    }

    /// Restores the visible controls from the latest durable values.
    private void reloadSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        try {
            applySnapshot(store.snapshot());
            statusLabel.setText("Settings reloaded.");
        } catch (IllegalStateException exception) {
            statusLabel.setText("Cannot reload: " + Objects.requireNonNullElse(exception.getMessage(), "settings unavailable"));
        }
    }

    /// Reads all user-editable controls and rejects unsafe free-form values before storage.
    ///
    /// @return validated edited snapshot
    private InstanceGameSettingsSnapshot editedSnapshot() {
        InstanceGameSettingsSnapshot current = displayedSnapshot();
        int maximumMemory = parseMaximumMemory();
        @Nullable JavaVersionType javaVersionType = (JavaVersionType) javaVersionTypeBox.getSelectedItem();
        if (javaVersionType == null) {
            throw new IllegalArgumentException("Select a Java strategy");
        }
        String customJavaVersion = customJavaVersionField.getText().trim();
        String customJavaPath = customJavaPathField.getText().trim();
        if (javaOverrideBox.isSelected()) {
            validateJavaSelection(javaVersionType, customJavaVersion, customJavaPath, current.detectedJavaAvailable());
        }
        String runningDirectory = runningDirectoryField.getText().trim();
        if (runningDirectoryOverrideBox.isSelected()) {
            validatePath(runningDirectory, "game working directory");
        }
        return new InstanceGameSettingsSnapshot(
                current.writable(),
                memoryOverrideBox.isSelected(),
                automaticMemoryBox.isSelected(),
                maximumMemory,
                javaOverrideBox.isSelected(),
                javaVersionType,
                customJavaVersion,
                customJavaPath,
                current.detectedJavaAvailable(),
                jvmOptionsOverrideBox.isSelected(),
                jvmOptionsArea.getText().trim(),
                runningDirectoryOverrideBox.isSelected(),
                runningDirectory);
    }

    /// Parses and bounds one manual maximum-memory input.
    ///
    /// @return positive MiB memory allocation
    private int parseMaximumMemory() {
        String rawValue = maximumMemoryField.getText().trim();
        try {
            int value = Integer.parseInt(rawValue);
            if (value <= 0 || value > MAXIMUM_MEMORY_MIB) {
                throw new IllegalArgumentException(
                        "Maximum memory must be between 1 and " + MAXIMUM_MEMORY_MIB + " MiB");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Maximum memory must be a whole number of MiB", exception);
        }
    }

    /// Validates the inputs required by the selected Java strategy.
    ///
    /// @param javaVersionType selected Java strategy
    /// @param customJavaVersion version input for `VERSION`
    /// @param customJavaPath executable path input for `CUSTOM`
    /// @param detectedJavaAvailable whether an existing detected runtime can be reused
    private static void validateJavaSelection(
            JavaVersionType javaVersionType,
            String customJavaVersion,
            String customJavaPath,
            boolean detectedJavaAvailable) {
        Objects.requireNonNull(javaVersionType, "javaVersionType");
        Objects.requireNonNull(customJavaVersion, "customJavaVersion");
        Objects.requireNonNull(customJavaPath, "customJavaPath");
        switch (javaVersionType) {
            case AUTO -> {
            }
            case VERSION -> {
                try {
                    if (Integer.parseInt(customJavaVersion) <= 0) {
                        throw new IllegalArgumentException("Java version must be positive");
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Java version must be a positive whole number", exception);
                }
            }
            case DETECTED -> {
                if (!detectedJavaAvailable) {
                    throw new IllegalArgumentException(
                            "No detected Java runtime is available; choose automatic, a version, or a custom path");
                }
            }
            case CUSTOM -> {
                if (customJavaPath.isBlank()) {
                    throw new IllegalArgumentException("Custom Java path must not be blank");
                }
                validatePath(customJavaPath, "custom Java path");
            }
        }
    }

    /// Validates a non-empty path string without requiring that a future game directory already exists.
    ///
    /// @param rawPath optional user path
    /// @param fieldName user-facing field name for a validation error
    private static void validatePath(String rawPath, String fieldName) {
        Objects.requireNonNull(rawPath, "rawPath");
        Objects.requireNonNull(fieldName, "fieldName");
        if (rawPath.isBlank()) {
            return;
        }
        try {
            Path.of(rawPath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid " + fieldName, exception);
        }
    }

    /// Applies a durable snapshot without saving any control events back to the store.
    ///
    /// @param snapshot latest effective values and override state
    private void applySnapshot(InstanceGameSettingsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        applyingSnapshot = true;
        try {
            displayedSnapshot = snapshot;
            memoryOverrideBox.setSelected(snapshot.memoryOverridden());
            automaticMemoryBox.setSelected(snapshot.automaticMemory());
            maximumMemoryField.setText(Integer.toString(snapshot.maximumMemoryMiB()));
            javaOverrideBox.setSelected(snapshot.javaOverridden());
            javaVersionTypeBox.setSelectedItem(snapshot.javaVersionType());
            customJavaVersionField.setText(snapshot.customJavaVersion());
            customJavaPathField.setText(snapshot.customJavaPath());
            jvmOptionsOverrideBox.setSelected(snapshot.jvmOptionsOverridden());
            jvmOptionsArea.setText(snapshot.jvmOptions());
            runningDirectoryOverrideBox.setSelected(snapshot.runningDirectoryOverridden());
            runningDirectoryField.setText(snapshot.runningDirectory());
            updateEditingAvailability();
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Recomputes control availability from writable state, override choices, and Java mode.
    private void updateEditingAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable InstanceGameSettingsSnapshot snapshot = displayedSnapshot;
        boolean writable = !closed && snapshot != null && snapshot.writable();
        memoryOverrideBox.setEnabled(writable);
        automaticMemoryBox.setEnabled(writable && memoryOverrideBox.isSelected());
        maximumMemoryField.setEnabled(writable
                && memoryOverrideBox.isSelected()
                && !automaticMemoryBox.isSelected());
        javaOverrideBox.setEnabled(writable);
        boolean javaInputsEnabled = writable && javaOverrideBox.isSelected();
        javaVersionTypeBox.setEnabled(javaInputsEnabled);
        @Nullable JavaVersionType javaVersionType = (JavaVersionType) javaVersionTypeBox.getSelectedItem();
        customJavaVersionField.setEnabled(javaInputsEnabled && javaVersionType == JavaVersionType.VERSION);
        customJavaPathField.setEnabled(javaInputsEnabled && javaVersionType == JavaVersionType.CUSTOM);
        jvmOptionsOverrideBox.setEnabled(writable);
        jvmOptionsArea.setEnabled(writable && jvmOptionsOverrideBox.isSelected());
        runningDirectoryOverrideBox.setEnabled(writable);
        runningDirectoryField.setEnabled(writable && runningDirectoryOverrideBox.isSelected());
        saveButton.setEnabled(writable);
        reloadButton.setEnabled(!closed);
        if (!applyingSnapshot && snapshot != null && !snapshot.writable()) {
            statusLabel.setText("This instance settings file is read-only.");
        }
    }

    /// Enables or disables every persistence-oriented control during lifecycle changes.
    ///
    /// @param enabled whether controls may remain interactive
    private void setInteractiveControlsEnabled(boolean enabled) {
        memoryOverrideBox.setEnabled(enabled);
        automaticMemoryBox.setEnabled(enabled);
        maximumMemoryField.setEnabled(enabled);
        javaOverrideBox.setEnabled(enabled);
        javaVersionTypeBox.setEnabled(enabled);
        customJavaVersionField.setEnabled(enabled);
        customJavaPathField.setEnabled(enabled);
        jvmOptionsOverrideBox.setEnabled(enabled);
        jvmOptionsArea.setEnabled(enabled);
        runningDirectoryOverrideBox.setEnabled(enabled);
        runningDirectoryField.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        reloadButton.setEnabled(enabled);
    }

    /// Creates one transparent two-column section with compact field spacing.
    ///
    /// @param name stable component name for focused tests
    /// @return configured section panel
    private static JPanel sectionPanel(String name) {
        JPanel section = new JPanel(new MigLayout("insets 0, fillx, wrap 2", "[180!,fill][grow,fill]", "[]8[]8[]8[]"));
        section.setName(Objects.requireNonNull(name, "name"));
        section.setOpaque(false);
        return section;
    }

    /// Creates a bold section label that remains compact inside a settings surface.
    ///
    /// @param text localized section title
    /// @return configured label
    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15.0F));
        return label;
    }
}
