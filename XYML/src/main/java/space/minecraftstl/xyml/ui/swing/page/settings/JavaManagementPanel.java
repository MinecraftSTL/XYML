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
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Manages locally discovered Java runtimes with refresh, local registration, selection, and directory reveal actions.
///
/// The panel is deliberately limited to local machine operations. It never offers a download path and delegates
/// registration validation to [JavaRuntimeManagementService], which keeps all persistence inside the established Java
/// manager workflow.
@NotNullByDefault
public final class JavaManagementPanel extends JPanel implements AutoCloseable {
    /// Toolkit-neutral source of local Java runtime snapshots and commands.
    private final JavaRuntimeManagementService service;

    /// Mutable list model rendered from immutable runtime snapshots.
    private final DefaultListModel<JavaRuntime> runtimeListModel = new DefaultListModel<>();

    /// Single-selection list of locally discovered Java runtimes.
    private final JList<JavaRuntime> runtimeList = new JList<>(runtimeListModel);

    /// Requests a local Java path rescan.
    private final JButton refreshButton = new JButton(i18n("button.refresh"));

    /// Opens a file or Java home directory chooser for local runtime registration.
    private final JButton addButton = new JButton(i18n("java.add"));

    /// Opens the selected runtime's containing directory in the platform file manager.
    private final JButton revealButton = new JButton(i18n("button.reveal_dir"));

    /// Displays the selected runtime's full version text.
    private final JTextField versionField = readOnlyField("javaManagementVersion");

    /// Displays the selected runtime's reported vendor.
    private final JTextField vendorField = readOnlyField("javaManagementVendor");

    /// Displays the selected runtime's platform and architecture.
    private final JTextField architectureField = readOnlyField("javaManagementArchitecture");

    /// Displays the selected runtime's exact executable path.
    private final JTextField pathField = readOnlyField("javaManagementPath");

    /// Displays current local scanning or registration feedback.
    private final JLabel statusLabel = new JLabel();

    /// Runtime snapshot subscription owned by this panel.
    private final Subscription runtimeSubscription;

    /// Snapshot currently rendered by the runtime list, or null before initial application.
    private @Nullable JavaRuntimeManagementSnapshot displayedSnapshot;

    /// Binary path to select after a successful local runtime registration, or null when none is pending.
    private @Nullable Path pendingSelectedBinary;

    /// Sequence used to ignore a stale local registration completion.
    private long registrationSequence;

    /// Whether the current visible status awaits a runtime refresh publication.
    private boolean refreshPending;

    /// Whether the panel has released its runtime subscription.
    private boolean closed;

    /// Creates a local Java management page on the event dispatch thread.
    ///
    /// @param service toolkit-neutral local Java runtime service
    public JavaManagementPanel(JavaRuntimeManagementService service) {
        super(new BorderLayout(0, 12));
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        configureComponents();
        runtimeSubscription = service.subscribe(this::runtimeSnapshotChanged);
        applySnapshot(service.snapshot());
    }

    /// Returns the local Java runtime snapshot currently represented by the list.
    ///
    /// @return displayed immutable local runtime state
    public JavaRuntimeManagementSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial Java runtime snapshot was not applied");
    }

    /// Returns the runtime selected in the single-selection list.
    ///
    /// @return selected runtime, or null when no runtime is selected
    public @Nullable JavaRuntime selectedRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        return runtimeList.getSelectedValue();
    }

    /// Releases the local runtime snapshot subscription from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                runtimeSubscription.unsubscribe();
                refreshButton.setEnabled(false);
                addButton.setEnabled(false);
                revealButton.setEnabled(false);
            }
        });
    }

    /// Builds the stable local runtime list, detail fields, and action toolbar.
    private void configureComponents() {
        JPanel content = new JPanel(new MigLayout(
                "insets 20, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]12[]"));
        content.setOpaque(false);

        content.add(createHeader(), "growx");
        content.add(createRuntimeSplit(), "grow, push");
        content.add(statusLabel, "growx");
        add(content, BorderLayout.CENTER);

        runtimeList.setName("javaManagementRuntimeList");
        runtimeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runtimeList.setCellRenderer((list, value, index, selected, focus) -> runtimeRenderer(list, value, selected));
        runtimeList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedRuntime(runtimeList.getSelectedValue());
            }
        });
        refreshButton.setName("javaManagementRefresh");
        refreshButton.addActionListener(event -> refreshLocalRuntimes());
        addButton.setName("javaManagementAdd");
        addButton.addActionListener(event -> chooseLocalRuntime());
        revealButton.setName("javaManagementReveal");
        revealButton.addActionListener(event -> revealSelectedRuntimeDirectory());
        revealButton.setEnabled(false);
    }

    /// Creates the page heading and compact action toolbar.
    ///
    /// @return configured header component
    private JPanel createHeader() {
        JPanel header = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]8[]8[]8[]", "[]"));
        header.setOpaque(false);
        JLabel heading = new JLabel(i18n("java.management"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");
        header.add(refreshButton);
        header.add(addButton);
        header.add(revealButton);
        return header;
    }

    /// Creates the runtime list and selected-runtime detail surface.
    ///
    /// @return configured split content
    private JPanel createRuntimeSplit() {
        JPanel split = new JPanel(new MigLayout("insets 0, fill", "[45%,grow,fill]14[grow,fill]", "[grow,fill]"));
        split.setOpaque(false);
        JScrollPane listScrollPane = new JScrollPane(runtimeList);
        listScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScrollPane.setBorder(BorderFactory.createEmptyBorder());
        split.add(listScrollPane, "grow, push");
        split.add(createDetailsPanel(), "grow, push");
        return split;
    }

    /// Creates read-only details for the selected local runtime.
    ///
    /// @return configured selected-runtime detail panel
    private JPanel createDetailsPanel() {
        JPanel details = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]8[]8[]8[]8[]"));
        details.setOpaque(false);
        details.add(createDetailRow(i18n("java.info.version"), versionField), "growx");
        details.add(createDetailRow(i18n("java.info.vendor"), vendorField), "growx");
        details.add(createDetailRow(i18n("java.info.architecture"), architectureField), "growx");
        details.add(new JSeparator(), "growx");
        details.add(createDetailRow(i18n("java.install.archive"), pathField), "growx");
        return details;
    }

    /// Creates one label and read-only field detail row.
    ///
    /// @param labelText localized detail label
    /// @param valueField read-only detail field
    /// @return configured detail row
    private static JPanel createDetailRow(String labelText, JTextField valueField) {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[130!,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "aligny center");
        row.add(Objects.requireNonNull(valueField, "valueField"), "growx");
        return row;
    }

    /// Creates a read-only text field with a stable test and accessibility name.
    ///
    /// @param name stable component name
    /// @return configured read-only field
    private static JTextField readOnlyField(String name) {
        JTextField field = new JTextField();
        field.setName(Objects.requireNonNull(name, "name"));
        field.setEditable(false);
        return field;
    }

    /// Renders one local runtime's version and vendor using the list's active colors.
    ///
    /// @param list source list
    /// @param runtime rendered runtime, or null while the list initializes
    /// @param selected whether this runtime is selected
    /// @return configured list renderer label
    private static JLabel runtimeRenderer(JList<?> list, @Nullable JavaRuntime runtime, boolean selected) {
        String text = runtime == null
                ? ""
                : runtime.getVersion() + " - " + displayText(runtime.getVendor());
        JLabel label = new JLabel(text);
        label.setOpaque(true);
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

    /// Starts a local scan without requesting a remote Java distribution.
    private void refreshLocalRuntimes() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        try {
            refreshPending = true;
            statusLabel.setText(i18n("message.doing"));
            service.refreshLocalRuntimes();
        } catch (RuntimeException failure) {
            refreshPending = false;
            statusLabel.setText(i18n("message.failed"));
        }
    }

    /// Opens a chooser for either a Java executable or a Java home directory.
    private void chooseLocalRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n("java.add"));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        @Nullable File selectedFile = chooser.getSelectedFile();
        if (selectedFile != null) {
            addLocalRuntime(selectedFile.toPath());
        }
    }

    /// Starts validation and registration of one user-selected local Java path.
    ///
    /// @param selectedPath Java executable or Java home directory
    private void addLocalRuntime(Path selectedPath) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        long request = ++registrationSequence;
        addButton.setEnabled(false);
        statusLabel.setText(i18n("message.doing"));
        try {
            CompletionStage<JavaRuntime> completion = Objects.requireNonNull(
                    service.addLocalRuntime(Objects.requireNonNull(selectedPath, "selectedPath")),
                    "service returned null Java registration completion");
            completion.whenComplete((@Nullable JavaRuntime runtime, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> completeLocalRuntimeAdd(request, runtime, failure)));
        } catch (RuntimeException failure) {
            completeLocalRuntimeAdd(request, null, failure);
        }
    }

    /// Handles one local Java registration completion on the event dispatch thread.
    ///
    /// @param request registration request sequence
    /// @param runtime registered runtime, or null on failure
    /// @param failure terminal failure, or null after success
    private void completeLocalRuntimeAdd(
            long request,
            @Nullable JavaRuntime runtime,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || request != registrationSequence) {
            return;
        }
        addButton.setEnabled(true);
        if (failure != null || runtime == null) {
            statusLabel.setText(i18n("java.add.failed"));
            return;
        }
        pendingSelectedBinary = runtime.getBinary();
        statusLabel.setText(i18n("message.success"));
        selectRuntime(pendingSelectedBinary);
    }

    /// Opens the selected Java executable's parent directory in the native file manager.
    private void revealSelectedRuntimeDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JavaRuntime runtime = runtimeList.getSelectedValue();
        if (runtime == null) {
            return;
        }
        @Nullable Path directory = runtime.getBinary().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            statusLabel.setText(i18n("message.failed"));
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Directory opening is unavailable");
            }
            desktop.open(directory.toFile());
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            statusLabel.setText(i18n("message.failed"));
        }
    }

    /// Coalesces a local Java runtime service transition to the latest snapshot on the event dispatch thread.
    ///
    /// @param change runtime service transition
    private void runtimeSnapshotChanged(ValueChange<JavaRuntimeManagementSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(service.snapshot());
            }
        });
    }

    /// Applies one immutable local Java snapshot while preserving a selected runtime when it remains available.
    ///
    /// @param snapshot latest discovered local Java state
    private void applySnapshot(JavaRuntimeManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable JavaRuntime previousSelection = runtimeList.getSelectedValue();
        @Nullable Path selectedBinary = pendingSelectedBinary != null
                ? pendingSelectedBinary
                : previousSelection == null ? null : previousSelection.getBinary();

        displayedSnapshot = snapshot;
        runtimeListModel.clear();
        for (JavaRuntime runtime : snapshot.runtimes()) {
            runtimeListModel.addElement(runtime);
        }
        selectRuntime(selectedBinary);
        if (refreshPending) {
            refreshPending = false;
            statusLabel.setText("");
        }
    }

    /// Selects a runtime by its canonical binary path, or clears details when no matching runtime exists.
    ///
    /// @param binary desired binary path, or null when selection should be cleared
    private void selectRuntime(@Nullable Path binary) {
        @Nullable JavaRuntime match = null;
        if (binary != null) {
            for (int index = 0; index < runtimeListModel.getSize(); index++) {
                JavaRuntime candidate = runtimeListModel.getElementAt(index);
                if (candidate.getBinary().equals(binary)) {
                    match = candidate;
                    break;
                }
            }
        }
        pendingSelectedBinary = match == null ? null : match.getBinary();
        runtimeList.setSelectedValue(match, true);
        updateSelectedRuntime(match);
    }

    /// Updates selected-runtime details and reveal availability.
    ///
    /// @param runtime selected runtime, or null when the list has no selection
    private void updateSelectedRuntime(@Nullable JavaRuntime runtime) {
        if (runtime == null) {
            versionField.setText("");
            vendorField.setText("");
            architectureField.setText("");
            pathField.setText("");
            revealButton.setEnabled(false);
            return;
        }
        versionField.setText(runtime.getVersion());
        vendorField.setText(displayText(runtime.getVendor()));
        architectureField.setText(runtime.getPlatform().toString());
        pathField.setText(runtime.getBinary().toString());
        @Nullable Path directory = runtime.getBinary().getParent();
        revealButton.setEnabled(!closed && directory != null && Files.isDirectory(directory));
    }

    /// Maps a nullable or blank runtime metadata value to localized visible text.
    ///
    /// @param value runtime metadata value, or null
    /// @return visible value or the localized unknown marker
    private static String displayText(@Nullable String value) {
        return value == null || value.isBlank() ? i18n("message.unknown") : value;
    }
}
