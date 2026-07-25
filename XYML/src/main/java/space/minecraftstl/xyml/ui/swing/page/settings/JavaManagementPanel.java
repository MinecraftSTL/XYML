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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Manages discovered and disabled Java runtimes through two lifecycle-aware Swing views.
///
/// All persistence and runtime validation remain in [JavaRuntimeManagementService]. This panel owns at most one
/// operation executor, progress presentation, and completion subscription at a time. Closing it synchronously rejects
/// late service publications and task callbacks before EDT resource cleanup is dispatched.
@NotNullByDefault
public final class JavaManagementPanel extends JPanel implements AutoCloseable {
    /// Card identifier for the active-runtime view.
    private static final String MAIN_CARD = "main";

    /// Card identifier for disabled-runtime maintenance.
    private static final String DISABLED_CARD = "disabled";

    /// Toolkit-neutral source of runtime snapshots and mutation tasks.
    private final JavaRuntimeManagementService service;

    /// Native interactions isolated for deterministic panel tests.
    private final JavaManagementInteractions interactions;

    /// Mutable active-runtime list model rendered from immutable snapshots.
    private final DefaultListModel<JavaRuntime> runtimeListModel = new DefaultListModel<>();

    /// Single-selection list of active Java runtimes.
    private final JList<JavaRuntime> runtimeList = new JList<>(runtimeListModel);

    /// Mutable disabled-runtime list model rendered from immutable snapshots.
    private final DefaultListModel<DisabledJavaRuntimeEntry> disabledListModel = new DefaultListModel<>();

    /// Results of user-triggered disabled path inspections, keyed by original configured spelling.
    private final Map<String, DisabledJavaRuntimeEntry> inspectedDisabledEntries = new HashMap<>();

    /// Available paths whose most recent restore operation failed and may therefore be forcibly forgotten.
    private final Set<String> failedDisabledRestores = new HashSet<>();

    /// Single-selection list of disabled Java records.
    private final JList<DisabledJavaRuntimeEntry> disabledList = new JList<>(disabledListModel);

    /// Layout switching between active and disabled runtime management.
    private final CardLayout cardLayout = new CardLayout();

    /// Container holding the two Java-management cards.
    private final JPanel cards = new JPanel(cardLayout);

    /// Active-runtime card used for explicit visibility tests.
    private final JPanel mainView = new JPanel(new BorderLayout());

    /// Disabled-runtime card used for explicit visibility tests.
    private final JPanel disabledView = new JPanel(new BorderLayout());

    /// Requests a local Java path rescan.
    private final JButton refreshButton = new JButton(i18n("button.refresh"));

    /// Opens a chooser for local Java registration.
    private final JButton addButton = new JButton(i18n("java.add"));

    /// Opens disabled-runtime maintenance.
    private final JButton manageDisabledButton = new JButton(i18n("java.disabled.management"));

    /// Opens the selected active runtime's Java home or executable directory.
    private final JButton revealButton = new JButton(i18n("button.reveal_dir"));

    /// Dynamically disables an unmanaged runtime or uninstalls a managed runtime.
    private final JButton runtimeActionButton = new JButton();

    /// Returns from disabled-runtime maintenance to the active-runtime view.
    private final JButton disabledBackButton = new JButton(i18n("java.management"));

    /// Opens the selected disabled runtime's available containing directory.
    private final JButton disabledRevealButton = new JButton(i18n("button.reveal_dir"));

    /// Re-enables the selected disabled runtime when its executable remains valid.
    private final JButton disabledRestoreButton = new JButton(i18n("java.disabled.management.restore"));

    /// Forgets the selected invalid or failed-to-restore disabled-runtime record.
    private final JButton disabledRemoveButton = new JButton(i18n("java.disabled.management.remove"));

    /// Displays the selected active runtime's full version text.
    private final JTextField versionField = readOnlyField("javaManagementVersion");

    /// Displays the selected active runtime's reported vendor.
    private final JTextField vendorField = readOnlyField("javaManagementVendor");

    /// Displays the selected active runtime's platform and architecture.
    private final JTextField architectureField = readOnlyField("javaManagementArchitecture");

    /// Displays the selected active runtime's exact executable path.
    private final JTextField pathField = readOnlyField("javaManagementPath");

    /// Displays scanning, empty-state, success, cancellation, or failure feedback.
    private final JLabel statusLabel = new JLabel();

    /// Hosts the single active operation's progress presentation.
    private final TaskProgressHostPanel progressHost;

    /// Runtime snapshot subscription owned by this panel.
    private final Subscription runtimeSubscription;

    /// Snapshot currently rendered by both runtime lists, or null before initial application.
    private @Nullable JavaRuntimeManagementSnapshot displayedSnapshot;

    /// Binary path to select after a successful registration or restore operation.
    private @Nullable Path pendingSelectedBinary;

    /// Executor currently mutating Java runtime state, or null while the panel is idle.
    private @Nullable TaskExecutor activeExecutor;

    /// Presentation retained for the active or most recently completed operation.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Completion subscription owned by the active executor.
    private @Nullable Subscription activeCompletionSubscription;

    /// Monotonic operation identity used to reject superseded terminal callbacks.
    private long operationSequence;

    /// Whether the current status awaits a refresh publication.
    private boolean refreshPending;

    /// Whether the disabled-runtime card is currently selected.
    private boolean disabledCardVisible;

    /// Whether the panel has rejected future input and callbacks.
    private volatile boolean closed;

    /// Creates a Java management page backed by production Swing interactions.
    ///
    /// @param service toolkit-neutral Java runtime service
    public JavaManagementPanel(JavaRuntimeManagementService service) {
        this(service, new SwingJavaManagementInteractions());
    }

    /// Creates a Java management page with injectable native interactions for package tests.
    ///
    /// @param service toolkit-neutral Java runtime service
    /// @param interactions chooser, confirmation, and directory reveal interactions
    JavaManagementPanel(JavaRuntimeManagementService service, JavaManagementInteractions interactions) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.service = Objects.requireNonNull(service, "service");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        progressHost = new TaskProgressHostPanel(createTaskProgressStrings(), null, Duration.ZERO);
        configureComponents();
        runtimeSubscription = service.subscribe(this::runtimeSnapshotChanged);
        applySnapshot(service.snapshot());
    }

    /// Returns the Java runtime snapshot currently represented by both cards.
    ///
    /// @return displayed immutable runtime state
    public JavaRuntimeManagementSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial Java runtime snapshot was not applied");
    }

    /// Returns the runtime selected in the active-runtime list.
    ///
    /// @return selected runtime, or null when no runtime is selected
    public @Nullable JavaRuntime selectedRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        return runtimeList.getSelectedValue();
    }

    /// Rejects future callbacks, cancels a live operation, and releases every owned subscription and presentation.
    @Override
    public void close() {
        closed = true;
        operationSequence++;
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Builds both cards, shared status feedback, and the task progress host.
    private void configureComponents() {
        mainView.setName("javaManagementMainView");
        mainView.setOpaque(false);
        mainView.add(createMainContent(), BorderLayout.CENTER);
        disabledView.setName("javaManagementDisabledView");
        disabledView.setOpaque(false);
        disabledView.add(createDisabledContent(), BorderLayout.CENTER);
        cards.setOpaque(false);
        cards.add(mainView, MAIN_CARD);
        cards.add(disabledView, DISABLED_CARD);

        JPanel root = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill]8[]8[]"));
        root.setOpaque(false);
        root.add(cards, "grow, push");
        statusLabel.setName("javaManagementStatus");
        root.add(statusLabel, "growx, h 24!");
        progressHost.setName("javaManagementProgress");
        root.add(progressHost, "growx");
        add(root, BorderLayout.CENTER);

        configureRuntimeList();
        configureDisabledList();
        configureButtons();
        showMainView();
    }

    /// Creates the active-runtime card content.
    ///
    /// @return configured active-runtime content
    private JPanel createMainContent() {
        JPanel content = new JPanel(new MigLayout(
                "insets 20, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        content.setOpaque(false);
        content.add(createMainHeader(), "growx");
        content.add(createRuntimeSplit(), "grow, push");
        return content;
    }

    /// Creates the active-runtime heading and action toolbar.
    ///
    /// @return configured active-runtime header
    private JPanel createMainHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill]8[]8[]8[]",
                "[]"));
        header.setOpaque(false);
        JLabel heading = new JLabel(i18n("java.management"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");
        header.add(refreshButton);
        header.add(addButton);
        header.add(manageDisabledButton);
        return header;
    }

    /// Creates the active runtime list and selected-runtime details.
    ///
    /// @return configured active-runtime split content
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

    /// Creates read-only details for the selected active runtime.
    ///
    /// @return configured selected-runtime detail panel
    private JPanel createDetailsPanel() {
        JPanel details = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]8[]8[]8[]8[]12[]"));
        details.setOpaque(false);
        details.add(createDetailRow(i18n("java.info.version"), versionField), "growx");
        details.add(createDetailRow(i18n("java.info.vendor"), vendorField), "growx");
        details.add(createDetailRow(i18n("java.info.architecture"), architectureField), "growx");
        details.add(new JSeparator(), "growx");
        details.add(createDetailRow(i18n("java.install.archive"), pathField), "growx");
        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]8[]", "[]"));
        actions.setOpaque(false);
        actions.add(revealButton, "skip 1");
        actions.add(runtimeActionButton);
        details.add(actions, "growx");
        return details;
    }

    /// Creates the disabled-runtime card content.
    ///
    /// @return configured disabled-runtime content
    private JPanel createDisabledContent() {
        JPanel content = new JPanel(new MigLayout(
                "insets 20, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        content.setOpaque(false);
        content.add(createDisabledHeader(), "growx");
        JScrollPane scrollPane = new JScrollPane(disabledList);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        content.add(scrollPane, "grow, push");
        return content;
    }

    /// Creates the disabled-runtime heading and action toolbar.
    ///
    /// @return configured disabled-runtime header
    private JPanel createDisabledHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[]8[grow,fill]8[]8[]8[]",
                "[]"));
        header.setOpaque(false);
        header.add(disabledBackButton);
        JLabel heading = new JLabel(i18n("java.disabled.management"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        header.add(heading, "growx");
        header.add(disabledRevealButton);
        header.add(disabledRestoreButton);
        header.add(disabledRemoveButton);
        return header;
    }

    /// Configures active runtime list selection and rendering.
    private void configureRuntimeList() {
        runtimeList.setName("javaManagementRuntimeList");
        runtimeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        runtimeList.setCellRenderer((list, value, index, selected, focus) ->
                runtimeRenderer(list, value, selected));
        runtimeList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedRuntime(runtimeList.getSelectedValue());
            }
        });
    }

    /// Configures disabled runtime list selection and rendering.
    private void configureDisabledList() {
        disabledList.setName("javaManagementDisabledList");
        disabledList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        disabledList.setCellRenderer((list, value, index, selected, focus) ->
                disabledRenderer(list, value, selected));
        disabledList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActionAvailability();
                if (disabledCardVisible) {
                    inspectSelectedDisabledRuntime();
                }
            }
        });
    }

    /// Configures stable button names, icons, tooltips, and commands.
    private void configureButtons() {
        configureIconButton(refreshButton, "javaManagementRefresh", "assets/swing/icons/refresh.svg");
        refreshButton.addActionListener(event -> refreshLocalRuntimes());
        configureIconButton(addButton, "javaManagementAdd", "assets/swing/icons/add.svg");
        addButton.addActionListener(event -> chooseLocalRuntime());
        configureIconButton(
                manageDisabledButton,
                "javaManagementDisabled",
                "assets/swing/icons/format-list-bulleted.svg");
        manageDisabledButton.addActionListener(event -> showDisabledView());
        configureIconButton(revealButton, "javaManagementReveal", "assets/swing/icons/folder-open.svg");
        revealButton.addActionListener(event -> revealSelectedRuntimeDirectory());
        configureIconButton(runtimeActionButton, "javaManagementRuntimeAction", "assets/swing/icons/delete.svg");
        runtimeActionButton.addActionListener(event -> mutateSelectedRuntime());

        configureIconButton(
                disabledBackButton,
                "javaManagementDisabledBack",
                "assets/swing/icons/arrow-back.svg");
        disabledBackButton.addActionListener(event -> showMainView());
        configureIconButton(
                disabledRevealButton,
                "javaManagementDisabledReveal",
                "assets/swing/icons/folder-open.svg");
        disabledRevealButton.addActionListener(event -> revealSelectedDisabledRuntimeDirectory());
        configureIconButton(
                disabledRestoreButton,
                "javaManagementDisabledRestore",
                "assets/swing/icons/restore.svg");
        disabledRestoreButton.addActionListener(event -> restoreSelectedDisabledRuntime());
        configureIconButton(
                disabledRemoveButton,
                "javaManagementDisabledRemove",
                "assets/swing/icons/delete.svg");
        disabledRemoveButton.addActionListener(event -> removeSelectedDisabledRuntime());
    }

    /// Configures one icon-bearing command with stable accessibility metadata.
    ///
    /// @param button button to configure
    /// @param name stable component name
    /// @param iconResource classpath SVG resource
    private static void configureButton(JButton button, String name, String iconResource) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setIcon(new FlatSVGIcon(Objects.requireNonNull(iconResource, "iconResource"), 18, 18));
        target.setToolTipText(target.getText());
    }

    /// Configures one compact icon command while retaining its label for tooltips and assistive technology.
    ///
    /// @param button button to configure
    /// @param name stable component name
    /// @param iconResource classpath SVG resource
    private static void configureIconButton(JButton button, String name, String iconResource) {
        String label = Objects.requireNonNull(button, "button").getText();
        configureButton(button, name, iconResource);
        setIconButtonLabel(button, label);
    }

    /// Stores one icon command's visible label outside the compact button face.
    ///
    /// @param button icon-only button
    /// @param label localized command label
    private static void setIconButtonLabel(JButton button, String label) {
        JButton target = Objects.requireNonNull(button, "button");
        String accessibleLabel = Objects.requireNonNull(label, "label");
        target.setText("");
        target.setToolTipText(accessibleLabel);
        target.getAccessibleContext().setAccessibleName(accessibleLabel);
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

    /// Renders one active runtime's version and vendor using the list's active colors.
    ///
    /// @param list source list
    /// @param runtime rendered runtime, or null while the list initializes
    /// @param selected whether this runtime is selected
    /// @return configured list renderer label
    private static JLabel runtimeRenderer(JList<?> list, @Nullable JavaRuntime runtime, boolean selected) {
        String text = runtime == null
                ? ""
                : runtime.getVersion() + " - " + displayText(runtime.getVendor());
        return listLabel(list, text, selected);
    }

    /// Renders one disabled runtime's configured path.
    ///
    /// @param list source list
    /// @param entry rendered disabled record, or null while the list initializes
    /// @param selected whether this record is selected
    /// @return configured list renderer label
    private static JLabel disabledRenderer(
            JList<?> list,
            @Nullable DisabledJavaRuntimeEntry entry,
            boolean selected) {
        String text = entry == null ? "" : entry.configuredPath();
        return listLabel(list, text, selected);
    }

    /// Creates one opaque list label with selection-aware colors.
    ///
    /// @param list source list
    /// @param text visible row text
    /// @param selected whether the row is selected
    /// @return configured renderer label
    private static JLabel listLabel(JList<?> list, String text, boolean selected) {
        JLabel label = new JLabel(Objects.requireNonNull(text, "text"));
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

    /// Shows the active-runtime card.
    private void showMainView() {
        EdtDispatcher.requireEventDispatchThread();
        disabledCardVisible = false;
        cardLayout.show(cards, MAIN_CARD);
        updateActionAvailability();
    }

    /// Shows disabled-runtime maintenance without constructing new list state.
    private void showDisabledView() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        disabledCardVisible = true;
        cardLayout.show(cards, DISABLED_CARD);
        updateActionAvailability();
    }

    /// Starts a local Java path rescan without fetching a remote distribution.
    private void refreshLocalRuntimes() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || activeExecutor != null) {
            return;
        }
        try {
            refreshPending = true;
            setStatus(i18n("message.doing"));
            service.refreshLocalRuntimes();
        } catch (RuntimeException failure) {
            refreshPending = false;
            setStatus(i18n("message.failed"));
        }
    }

    /// Opens the local runtime chooser and creates exactly one registration task for an accepted path.
    private void chooseLocalRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        JavaRuntimeManagementSnapshot snapshot = displayedSnapshot();
        if (closed || activeExecutor != null || !snapshot.writable()) {
            return;
        }
        @Nullable Path selectedPath = interactions.chooseLocalRuntime(this);
        if (selectedPath == null) {
            return;
        }
        final Task<JavaRuntime> task;
        try {
            task = Objects.requireNonNull(
                    service.addLocalRuntime(selectedPath),
                    "service returned null Java registration task");
        } catch (RuntimeException failure) {
            setStatus(i18n("java.add.failed"));
            return;
        }
        startOperation(task, i18n("java.add"), i18n("java.add.failed"), result -> {
            if (result != null) {
                pendingSelectedBinary = result.getBinary();
                selectRuntime(pendingSelectedBinary);
            }
        });
    }

    /// Dispatches the selected runtime's disable or uninstall command according to its ownership.
    private void mutateSelectedRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JavaRuntime runtime = runtimeList.getSelectedValue();
        if (runtime == null || closed || activeExecutor != null) {
            return;
        }
        JavaRuntimeManagementSnapshot snapshot = displayedSnapshot();
        boolean managed = runtime.isManaged();
        if (!managed && !snapshot.writable()) {
            return;
        }
        String action = i18n(managed ? "java.uninstall" : "java.disable");
        String confirmation = i18n(managed ? "java.uninstall.confirm" : "java.disable.confirm");
        if (!interactions.confirm(this, confirmation, action)) {
            return;
        }
        final Task<@Nullable Void> task;
        try {
            task = Objects.requireNonNull(
                    managed ? service.uninstallManagedRuntime(runtime) : service.disableLocalRuntime(runtime),
                    "service returned null Java removal task");
        } catch (RuntimeException failure) {
            setStatus(i18n("message.failed"));
            return;
        }
        startOperation(task, action, i18n("message.failed"), result -> { });
    }

    /// Restores the selected disabled record when the service resolved a valid executable.
    private void restoreSelectedDisabledRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable DisabledJavaRuntimeEntry entry = disabledList.getSelectedValue();
        if (entry == null
                || entry.status() != DisabledJavaRuntimeEntry.Status.AVAILABLE
                || closed
                || activeExecutor != null
                || !displayedSnapshot().writable()) {
            return;
        }
        final Task<JavaRuntime> task;
        try {
            task = Objects.requireNonNull(
                    service.restoreDisabledRuntime(entry),
                    "service returned null Java restore task");
        } catch (RuntimeException failure) {
            setStatus(i18n("message.failed"));
            return;
        }
        startOperation(task, i18n("java.disabled.management.restore"), i18n("message.failed"), result -> {
            if (result != null) {
                failedDisabledRestores.remove(entry.configuredPath());
                pendingSelectedBinary = result.getBinary();
                showMainView();
                selectRuntime(pendingSelectedBinary);
            }
        }, () -> failedDisabledRestores.add(entry.configuredPath()));
    }

    /// Removes the selected disabled record after inspection rejected it or a valid-path restore failed.
    private void removeSelectedDisabledRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable DisabledJavaRuntimeEntry entry = disabledList.getSelectedValue();
        if (entry == null
                || !canRemoveDisabledEntry(entry)
                || closed
                || activeExecutor != null
                || !displayedSnapshot().writable()) {
            return;
        }
        final Task<@Nullable Void> task;
        try {
            task = Objects.requireNonNull(
                    service.removeDisabledRuntime(entry),
                    "service returned null disabled Java removal task");
        } catch (RuntimeException failure) {
            setStatus(i18n("message.failed"));
            return;
        }
        startOperation(task, i18n("java.disabled.management.remove"), i18n("message.failed"), result -> { });
    }

    /// Starts filesystem and Java probing only after the user explicitly selects an unchecked disabled path.
    private void inspectSelectedDisabledRuntime() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable DisabledJavaRuntimeEntry entry = disabledList.getSelectedValue();
        if (entry == null
                || entry.status() != DisabledJavaRuntimeEntry.Status.UNCHECKED
                || closed
                || activeExecutor != null) {
            return;
        }
        final Task<DisabledJavaRuntimeEntry> task;
        try {
            task = Objects.requireNonNull(
                    service.inspectDisabledRuntime(entry),
                    "service returned null disabled Java inspection task");
        } catch (RuntimeException failure) {
            setStatus(i18n("message.failed"));
            return;
        }
        startOperation(task, i18n("java.disabled.management"), i18n("message.failed"), result -> {
            if (result != null && result.configuredPath().equals(entry.configuredPath())) {
                failedDisabledRestores.remove(entry.configuredPath());
                inspectedDisabledEntries.put(entry.configuredPath(), result);
                replaceDisabledEntry(result);
            }
        });
    }

    /// Replaces one disabled list row by configured path while preserving its selection.
    ///
    /// @param replacement inspected entry replacing the unchecked row
    private void replaceDisabledEntry(DisabledJavaRuntimeEntry replacement) {
        EdtDispatcher.requireEventDispatchThread();
        for (int index = 0; index < disabledListModel.size(); index++) {
            DisabledJavaRuntimeEntry candidate = disabledListModel.getElementAt(index);
            if (candidate.configuredPath().equals(replacement.configuredPath())) {
                boolean selected = disabledList.getSelectedIndex() == index;
                disabledListModel.set(index, replacement);
                if (selected) {
                    disabledList.setSelectedIndex(index);
                }
                updateActionAvailability();
                return;
            }
        }
    }

    /// Returns whether one disabled record can be forcibly forgotten by the current UI state.
    ///
    /// @param entry selected disabled record
    /// @return true after invalid inspection or a failed restore of an available executable
    private boolean canRemoveDisabledEntry(DisabledJavaRuntimeEntry entry) {
        DisabledJavaRuntimeEntry candidate = Objects.requireNonNull(entry, "entry");
        return candidate.status() == DisabledJavaRuntimeEntry.Status.INVALID
                || failedDisabledRestores.contains(candidate.configuredPath());
    }

    /// Opens the selected active runtime's resolved directory.
    private void revealSelectedRuntimeDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JavaRuntime runtime = runtimeList.getSelectedValue();
        revealDirectory(runtime == null ? null : revealDirectoryForBinary(runtime.getBinary()));
    }

    /// Opens the selected disabled record's available directory without executing its configured binary.
    private void revealSelectedDisabledRuntimeDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable DisabledJavaRuntimeEntry entry = disabledList.getSelectedValue();
        revealDirectory(entry == null ? null : revealDirectoryForDisabledEntry(entry));
    }

    /// Delegates one validated directory reveal and translates integration failures into visible status.
    ///
    /// @param directory existing directory, or null when no reveal target exists
    private void revealDirectory(@Nullable Path directory) {
        if (closed || activeExecutor != null || directory == null) {
            return;
        }
        try {
            interactions.revealDirectory(directory);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            setStatus(i18n("message.failed"));
        }
    }

    /// Starts one preconstructed task and binds its executor to the shared progress surface.
    ///
    /// @param task task constructed exactly once by the selected command
    /// @param title localized operation title
    /// @param failureStatus localized failure feedback
    /// @param successAction EDT action consuming the task's possibly absent result
    /// @param <T> task result type
    private <T> void startOperation(
            Task<T> task,
            String title,
            String failureStatus,
            OperationSuccess<T> successAction) {
        startOperation(task, title, failureStatus, successAction, () -> { });
    }

    /// Starts one preconstructed task with explicit success and failure callbacks.
    ///
    /// @param task task constructed exactly once by the selected command
    /// @param title localized operation title
    /// @param failureStatus localized failure feedback
    /// @param successAction EDT action consuming the task's possibly absent result
    /// @param failureAction EDT action invoked after a non-cancellation failure
    /// @param <T> task result type
    private <T> void startOperation(
            Task<T> task,
            String title,
            String failureStatus,
            OperationSuccess<T> successAction,
            Runnable failureAction) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || activeExecutor != null) {
            return;
        }
        releaseCompletedPresentation();
        long sequence = ++operationSequence;
        Task<T> operation = Objects.requireNonNull(task, "task");
        TaskExecutor executor = operation.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                Objects.requireNonNull(title, "title"),
                i18n("message.doing"));
        Subscription completionSubscription = executor.subscribeTaskListener(new OperationCompletionListener<>(
                sequence,
                executor,
                operation,
                Objects.requireNonNull(failureStatus, "failureStatus"),
                Objects.requireNonNull(successAction, "successAction"),
                Objects.requireNonNull(failureAction, "failureAction")));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        setStatus(i18n("message.doing"));
        updateActionAvailability();
        try {
            progressHost.bind(presentation);
            executor.start();
        } catch (RuntimeException | Error startFailure) {
            cleanupFailedTaskStart(presentation, completionSubscription);
            setStatus(failureStatus);
            updateActionAvailability();
        }
    }

    /// Completes one operation on the EDT while rejecting late or superseded terminal callbacks.
    ///
    /// @param sequence operation identity captured before startup
    /// @param executor executor that reached a terminal state
    /// @param task task holding the possibly absent result
    /// @param failureStatus localized failure feedback
    /// @param successAction EDT action consuming the result after success
    /// @param failureAction EDT action invoked after a non-cancellation failure
    /// @param succeeded whether the complete task chain succeeded
    /// @param <T> task result type
    private <T> void operationCompleted(
            long sequence,
            TaskExecutor executor,
            Task<T> task,
            String failureStatus,
            OperationSuccess<T> successAction,
            Runnable failureAction,
            boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || operationSequence != sequence || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            if (succeeded) {
                successAction.accept(task.getResult());
                setStatus(i18n("message.success"));
            } else if (executor.isCancelled()) {
                setStatus(i18n("message.cancelled"));
            } else {
                failureAction.run();
                setStatus(failureStatus);
            }
            updateActionAvailability();
        });
    }

    /// Releases a completed presentation immediately before the next operation begins.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (presentation != null) {
            presentation.close();
        }
    }

    /// Cleans up presentation state after an executor fails to start.
    ///
    /// @param presentation presentation created for the failed startup
    /// @param completionSubscription completion registration created for the failed startup
    private void cleanupFailedTaskStart(
            TaskExecutorPresentationModel presentation,
            Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        if (activePresentation == presentation) {
            activePresentation = null;
        }
        progressHost.clear();
        presentation.close();
    }

    /// Coalesces a service transition to the latest snapshot on the event dispatch thread.
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

    /// Applies one immutable snapshot while preserving selections that remain available.
    ///
    /// @param snapshot latest Java runtime state
    private void applySnapshot(JavaRuntimeManagementSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        JavaRuntimeManagementSnapshot replacement = Objects.requireNonNull(snapshot, "snapshot");
        @Nullable JavaRuntime previousRuntime = runtimeList.getSelectedValue();
        @Nullable Path selectedBinary = pendingSelectedBinary != null
                ? pendingSelectedBinary
                : previousRuntime == null ? null : previousRuntime.getBinary();
        @Nullable DisabledJavaRuntimeEntry previousDisabled = disabledList.getSelectedValue();

        displayedSnapshot = replacement;
        runtimeListModel.clear();
        for (JavaRuntime runtime : replacement.runtimes()) {
            runtimeListModel.addElement(runtime);
        }
        disabledListModel.clear();
        Set<String> currentDisabledPaths = new HashSet<>();
        for (DisabledJavaRuntimeEntry snapshotEntry : replacement.disabledRuntimes()) {
            String configuredPath = snapshotEntry.configuredPath();
            currentDisabledPaths.add(configuredPath);
            DisabledJavaRuntimeEntry displayedEntry;
            if (snapshotEntry.status() == DisabledJavaRuntimeEntry.Status.UNCHECKED) {
                displayedEntry = inspectedDisabledEntries.getOrDefault(configuredPath, snapshotEntry);
            } else {
                displayedEntry = snapshotEntry;
                inspectedDisabledEntries.put(configuredPath, snapshotEntry);
            }
            disabledListModel.addElement(displayedEntry);
        }
        inspectedDisabledEntries.keySet().retainAll(currentDisabledPaths);
        failedDisabledRestores.retainAll(currentDisabledPaths);
        selectRuntime(selectedBinary);
        selectDisabledEntry(previousDisabled);
        if (activeExecutor == null) {
            if (refreshPending) {
                refreshPending = false;
            }
            applyBaselineStatus(replacement);
        }
        updateActionAvailability();
    }

    /// Applies scanning, empty-list, or neutral feedback for an idle snapshot.
    ///
    /// @param snapshot idle snapshot to describe
    private void applyBaselineStatus(JavaRuntimeManagementSnapshot snapshot) {
        if (!snapshot.initialized()) {
            setStatus(i18n("message.doing"));
        } else if (snapshot.runtimes().isEmpty()) {
            setStatus(i18n("settings.game.java_directory.auto.not_found"));
        } else {
            setStatus("");
        }
    }

    /// Selects an active runtime by binary path, or clears details when no match remains.
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
        if (match != null && match.getBinary().equals(pendingSelectedBinary)) {
            pendingSelectedBinary = null;
        }
        runtimeList.setSelectedValue(match, true);
        updateSelectedRuntime(match);
    }

    /// Preserves one disabled record selection by configured path when it remains available.
    ///
    /// @param previousSelection previous selected record, or null when no selection existed
    private void selectDisabledEntry(@Nullable DisabledJavaRuntimeEntry previousSelection) {
        @Nullable DisabledJavaRuntimeEntry match = null;
        if (previousSelection != null) {
            for (int index = 0; index < disabledListModel.getSize(); index++) {
                DisabledJavaRuntimeEntry candidate = disabledListModel.getElementAt(index);
                if (candidate.configuredPath().equals(previousSelection.configuredPath())) {
                    match = candidate;
                    break;
                }
            }
        }
        disabledList.setSelectedValue(match, true);
    }

    /// Updates selected-runtime details and the managed/unmanaged action label.
    ///
    /// @param runtime selected runtime, or null when the list has no selection
    private void updateSelectedRuntime(@Nullable JavaRuntime runtime) {
        if (runtime == null) {
            versionField.setText("");
            vendorField.setText("");
            architectureField.setText("");
            pathField.setText("");
            setIconButtonLabel(runtimeActionButton, i18n("java.disable"));
            runtimeActionButton.setIcon(new FlatSVGIcon("assets/swing/icons/delete.svg", 18, 18));
        } else {
            versionField.setText(runtime.getVersion());
            vendorField.setText(displayText(runtime.getVendor()));
            architectureField.setText(runtime.getPlatform().toString());
            pathField.setText(runtime.getBinary().toString());
            setIconButtonLabel(
                    runtimeActionButton,
                    i18n(runtime.isManaged() ? "java.uninstall" : "java.disable"));
            runtimeActionButton.setIcon(new FlatSVGIcon(
                    runtime.isManaged()
                            ? "assets/swing/icons/delete-forever.svg"
                            : "assets/swing/icons/delete.svg",
                    18,
                    18));
        }
        updateActionAvailability();
    }

    /// Reconciles all command enablement from lifecycle, writability, selection, and path validity.
    private void updateActionAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JavaRuntimeManagementSnapshot snapshot = displayedSnapshot;
        boolean idle = !closed && activeExecutor == null;
        boolean writable = snapshot != null && snapshot.writable();
        @Nullable JavaRuntime runtime = runtimeList.getSelectedValue();
        @Nullable DisabledJavaRuntimeEntry disabledEntry = disabledList.getSelectedValue();

        refreshButton.setEnabled(idle);
        addButton.setEnabled(idle && writable);
        manageDisabledButton.setEnabled(idle
                && snapshot != null
                && !snapshot.disabledRuntimes().isEmpty());
        revealButton.setEnabled(idle
                && runtime != null
                && revealDirectoryForBinary(runtime.getBinary()) != null);
        runtimeActionButton.setEnabled(idle
                && runtime != null
                && (runtime.isManaged() || writable));

        disabledBackButton.setEnabled(idle);
        disabledRevealButton.setEnabled(idle
                && disabledEntry != null
                && revealDirectoryForDisabledEntry(disabledEntry) != null);
        disabledRestoreButton.setEnabled(idle
                && writable
                && disabledEntry != null
                && disabledEntry.status() == DisabledJavaRuntimeEntry.Status.AVAILABLE);
        disabledRemoveButton.setEnabled(idle
                && writable
                && disabledEntry != null
                && canRemoveDisabledEntry(disabledEntry));

        mainView.setEnabled(!disabledCardVisible);
        disabledView.setEnabled(disabledCardVisible);
    }

    /// Resolves the directory that should be opened for a Java executable.
    ///
    /// A standard `JAVA_HOME/bin/java(.exe)` opens `JAVA_HOME` only when its `release` marker exists. Every other
    /// executable opens its parent directory. The executable itself is never returned.
    ///
    /// @param binary Java executable path
    /// @return existing Java home or executable parent directory, or null when neither can be opened
    static @Nullable Path revealDirectoryForBinary(Path binary) {
        Path executable = Objects.requireNonNull(binary, "binary");
        @Nullable Path executableDirectory = executable.getParent();
        if (executableDirectory == null || !Files.isDirectory(executableDirectory)) {
            return null;
        }
        @Nullable Path executableName = executable.getFileName();
        @Nullable Path directoryName = executableDirectory.getFileName();
        @Nullable Path javaHome = executableDirectory.getParent();
        if (executableName != null
                && directoryName != null
                && javaHome != null
                && isJavaExecutableName(executableName.toString())
                && "bin".equalsIgnoreCase(directoryName.toString())
                && Files.isRegularFile(javaHome.resolve("release"))) {
            return javaHome;
        }
        return executableDirectory;
    }

    /// Resolves an available directory for one disabled record.
    ///
    /// @param entry disabled runtime record
    /// @return existing Java home or configured executable parent directory, or null when unavailable
    private static @Nullable Path revealDirectoryForDisabledEntry(DisabledJavaRuntimeEntry entry) {
        DisabledJavaRuntimeEntry candidate = Objects.requireNonNull(entry, "entry");
        if (candidate.status() != DisabledJavaRuntimeEntry.Status.AVAILABLE) {
            return null;
        }
        return revealDirectoryForBinary(Objects.requireNonNull(
                candidate.resolvedBinary(),
                "available disabled runtime binary"));
    }

    /// Returns whether one filename is the standard Java launcher name.
    ///
    /// @param filename executable filename
    /// @return true for `java` or `java.exe`, ignoring case
    private static boolean isJavaExecutableName(String filename) {
        String normalized = Objects.requireNonNull(filename, "filename").toLowerCase(Locale.ROOT);
        return "java".equals(normalized) || "java.exe".equals(normalized);
    }

    /// Updates visible status and its assistive tooltip.
    ///
    /// @param status localized status text, or empty text to clear it
    private void setStatus(String status) {
        String text = Objects.requireNonNull(status, "status");
        statusLabel.setText(text);
        statusLabel.setToolTipText(text.isBlank() ? null : text);
    }

    /// Cancels a live executor and releases all task and runtime subscriptions on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        runtimeSubscription.unsubscribe();
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        @Nullable TaskExecutor executor = activeExecutor;
        activeExecutor = null;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException ignored) {
                // Closing must still release the presentation when cancellation reports an integration failure.
            }
        }
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        if (presentation != null) {
            presentation.close();
        }
        progressHost.close();
        updateActionAvailability();
    }

    /// Removes one optional task listener registration.
    ///
    /// @param subscription listener registration, or null when none is owned
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Creates localized generic task-progress controls and states for Java operations.
    ///
    /// @return localized task progress strings
    private static TaskProgressStrings createTaskProgressStrings() {
        return new TaskProgressStrings(
                i18n("swing.task.status.waiting"),
                i18n("swing.task.status.running"),
                i18n("message.success"),
                i18n("message.failed"),
                i18n("message.cancelled"),
                i18n("swing.task.progress_name"),
                i18n("button.cancel"),
                i18n("swing.task.show_details"),
                i18n("swing.task.hide_details"));
    }

    /// Maps a nullable or blank runtime metadata value to localized visible text.
    ///
    /// @param value runtime metadata value, or null
    /// @return visible value or the localized unknown marker
    private static String displayText(@Nullable String value) {
        return value == null || value.isBlank() ? i18n("message.unknown") : value;
    }

    /// Consumes one operation result after its complete task chain succeeds.
    ///
    /// @param <T> task result type
    @FunctionalInterface
    @NotNullByDefault
    private interface OperationSuccess<T> {
        /// Handles the task's possibly absent result on the event dispatch thread.
        ///
        /// @param result task result, or null for result-less operations
        void accept(@Nullable T result);
    }

    /// Bridges one task executor's terminal notification back to the owning panel.
    ///
    /// @param <T> task result type
    @NotNullByDefault
    private final class OperationCompletionListener<T> extends TaskListener {
        /// Operation identity captured before executor startup.
        private final long sequence;

        /// Executor whose terminal state this listener observes.
        private final TaskExecutor executor;

        /// Task holding the operation result.
        private final Task<T> task;

        /// Localized feedback used when the executor fails.
        private final String failureStatus;

        /// EDT callback invoked after a successful terminal outcome.
        private final OperationSuccess<T> successAction;

        /// EDT callback invoked after a non-cancellation failure.
        private final Runnable failureAction;

        /// Creates one terminal listener for a specific operation identity.
        ///
        /// @param sequence operation identity
        /// @param executor observed executor
        /// @param task result-bearing task
        /// @param failureStatus localized failure feedback
        /// @param successAction successful result consumer
        /// @param failureAction non-cancellation failure callback
        private OperationCompletionListener(
                long sequence,
                TaskExecutor executor,
                Task<T> task,
                String failureStatus,
                OperationSuccess<T> successAction,
                Runnable failureAction) {
            this.sequence = sequence;
            this.executor = Objects.requireNonNull(executor, "executor");
            this.task = Objects.requireNonNull(task, "task");
            this.failureStatus = Objects.requireNonNull(failureStatus, "failureStatus");
            this.successAction = Objects.requireNonNull(successAction, "successAction");
            this.failureAction = Objects.requireNonNull(failureAction, "failureAction");
        }

        /// Dispatches the terminal chain outcome to the panel lifecycle gate.
        ///
        /// @param succeeded whether every task in the chain succeeded
        /// @param stoppedExecutor executor reporting the terminal event
        @Override
        public void onStop(boolean succeeded, TaskExecutor stoppedExecutor) {
            if (stoppedExecutor == executor) {
                operationCompleted(
                        sequence,
                        executor,
                        task,
                        failureStatus,
                        successAction,
                        failureAction,
                        succeeded);
            }
        }
    }
}
