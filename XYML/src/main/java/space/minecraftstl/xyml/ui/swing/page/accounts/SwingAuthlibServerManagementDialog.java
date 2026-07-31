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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Native Swing workflow for reviewing, adding, selecting, and removing authlib-injector servers.
///
/// Endpoint discovery and persistence run on a caller-owned executor. All Swing state and confirmation
/// prompts remain on the EDT, while the supplied [AuthlibServerStore] keeps launcher configuration objects
/// out of the dialog.
@NotNullByDefault
final class SwingAuthlibServerManagementDialog extends JDialog implements AutoCloseable {
    /// Persistent source and sink for configured authlib-injector endpoints.
    private final AuthlibServerStore store;

    /// Caller-owned worker executor for endpoint discovery and synchronous persistence bridges.
    private final ExecutorService executor;

    /// Owned configured-server subscription.
    private final Subscription serverSubscription;

    /// Rendered configured-server options in persisted order.
    private final DefaultListModel<AuthlibServerOption> serverModel = new DefaultListModel<>();

    /// Single-choice configured-server list.
    private final JList<AuthlibServerOption> serverList = new JList<>(serverModel);

    /// Endpoint input for metadata discovery.
    private final JTextField endpoint = new JTextField();

    /// Resolves the entered endpoint before persistence.
    private final JButton resolve = new JButton(i18n("wizard.next"));

    /// Commits the currently resolved endpoint.
    private final JButton add = new JButton(i18n("account.injector.add"));

    /// Removes the currently selected configured endpoint.
    private final JButton remove = new JButton(i18n("button.remove"));

    /// Closes the modal workflow.
    private final JButton closeButton = new JButton(i18n("button.cancel"));

    /// Resolved display name awaiting an explicit persistence command.
    private final JLabel resolvedName = new JLabel(" ");

    /// Resolved normalized endpoint awaiting an explicit persistence command.
    private final JLabel resolvedUrl = new JLabel(" ");

    /// HTTP security warning associated with the resolved endpoint.
    private final JLabel httpWarning = new JLabel(i18n("account.injector.http"));

    /// Worker progress and terminal errors.
    private final JLabel status = new JLabel(" ");

    /// Prevents UI callbacks after explicit or window-manager closure.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Most recently submitted worker operation, or null while idle.
    private final AtomicReference<@Nullable Future<?>> activeOperation = new AtomicReference<>();

    /// Latest operation identity used to discard stale completions after cancellation or replacement.
    private long operationRevision;

    /// Prepared endpoint awaiting an explicit Add command, or null before successful resolution.
    private @Nullable PreparedAuthlibServer preparedServer;

    /// URL whose list selection should survive the next snapshot replacement, or null for no selection.
    private @Nullable String selectedServerUrl;

    /// Creates a modal server-management dialog on the EDT.
    ///
    /// @param owner owning component, or null for an unowned dialog
    /// @param store configured-server persistence bridge
    /// @param executor caller-owned worker executor
    SwingAuthlibServerManagementDialog(
            @Nullable Component owner,
            AuthlibServerStore store,
            ExecutorService executor) {
        super(
                ownerWindow(owner),
                i18n("account.methods.authlib_injector"),
                ModalityType.APPLICATION_MODAL);
        EdtDispatcher.requireEventDispatchThread();
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        configureComponents();
        serverSubscription = store.subscribe(change -> SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed.get()) {
                applySnapshot(change.currentValue());
            }
        }));
        applySnapshot(store.snapshot());
    }

    /// Opens the modal dialog and releases resources when the user closes it.
    void open() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            throw new IllegalStateException("Authlib-injector server dialog is closed");
        }
        pack();
        setMinimumSize(new Dimension(720, 470));
        setLocationRelativeTo(getOwner());
        setVisible(true);
    }

    /// Cancels pending work, detaches the source subscription, and disposes the native dialog.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ++operationRevision;
        @Nullable Future<?> active = activeOperation.getAndSet(null);
        if (active != null) {
            active.cancel(true);
        }
        serverSubscription.unsubscribe();
        EdtDispatcher.execute(this::dispose);
    }

    /// Builds the configured-list, endpoint-discovery, confirmation-preview, and command layout.
    private void configureComponents() {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            /// Releases source resources when a window-manager close disposes the dialog.
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });

        JPanel root = new JPanel(new MigLayout(
                "insets 16, fill, wrap 2",
                "[grow,fill][grow,fill]",
                "[]8[grow,fill]16[]"));
        root.add(configuredServersPanel(), "grow");
        root.add(discoveryPanel(), "grow");
        root.add(commandPanel(), "span 2, growx");
        setContentPane(root);
    }

    /// Creates the selected configured-server list and permanent removal command.
    ///
    /// @return configured server list surface
    private JPanel configuredServersPanel() {
        JPanel panel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[grow,fill]8[]"));
        panel.add(new JLabel(i18n("account.injector.server")));

        serverList.setName("authlibServersList");
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setVisibleRowCount(8);
        serverList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel(value == null ? "" : value.displayName() + " - " + value.url());
            label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        serverList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                @Nullable AuthlibServerOption selected = serverList.getSelectedValue();
                selectedServerUrl = selected == null ? null : selected.url();
                updateActionAvailability();
            }
        });
        panel.add(new JScrollPane(serverList), "grow");

        remove.setName("authlibServerRemove");
        remove.addActionListener(event -> removeSelectedServer());
        panel.add(remove, "right, w 110!");
        return panel;
    }

    /// Creates the endpoint discovery input and confirmation-safe resolved metadata preview.
    ///
    /// @return endpoint discovery surface
    private JPanel discoveryPanel() {
        JPanel panel = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 2",
                "[pref!][grow,fill]",
                "[]8[]8[]12[]8[]8[]12[]"));
        panel.add(new JLabel(i18n("account.injector.add")), "span 2");
        panel.add(new JLabel(i18n("account.injector.server_url")));

        endpoint.setName("authlibServerUrl");
        endpoint.addActionListener(event -> resolveEndpoint());
        panel.add(endpoint, "growx");

        resolve.setName("authlibServerResolve");
        resolve.addActionListener(event -> resolveEndpoint());
        panel.add(resolve, "span 2, right, w 110!");

        panel.add(new JLabel(i18n("account.injector.server_name")));
        panel.add(resolvedName, "growx");
        panel.add(new JLabel(i18n("account.injector.server_url")));
        panel.add(resolvedUrl, "growx");

        httpWarning.setName("authlibServerHttpWarning");
        httpWarning.setVerticalAlignment(SwingConstants.TOP);
        httpWarning.setVisible(false);
        panel.add(httpWarning, "span 2, growx");

        add.setName("authlibServerAdd");
        add.addActionListener(event -> addPreparedServer(false));
        panel.add(add, "span 2, right, w 140!");
        return panel;
    }

    /// Creates the status label and modal close command.
    ///
    /// @return bottom command row
    private JPanel commandPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        status.setName("authlibServerStatus");
        panel.add(status, "growx");
        closeButton.setName("authlibServerClose");
        closeButton.addActionListener(event -> close());
        panel.add(closeButton, "w 110!");
        return panel;
    }

    /// Replaces configured server rows while preserving a selected stable endpoint where available.
    ///
    /// @param snapshot immutable configured-server state
    private void applySnapshot(AuthlibServerSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        serverModel.clear();
        for (AuthlibServerOption server : snapshot.servers()) {
            serverModel.addElement(server);
        }

        int selection = indexOf(snapshot.servers(), selectedServerUrl);
        serverList.setSelectedIndex(selection);
        updateActionAvailability();
    }

    /// Resolves endpoint metadata on the worker executor before allowing persistence.
    private void resolveEndpoint() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || activeOperation.get() != null) {
            return;
        }
        String source = endpoint.getText().trim();
        if (source.isEmpty()) {
            showFailure(i18n("account.injector.server_url"));
            return;
        }
        preparedServer = null;
        resolvedName.setText(" ");
        resolvedUrl.setText(" ");
        httpWarning.setVisible(false);
        status.setText(" ");
        long revision = beginOperation();
        try {
            Future<?> submitted = executor.submit(() -> {
                @Nullable PreparedAuthlibServer prepared = null;
                @Nullable Throwable failure = null;
                try {
                    prepared = store.prepareServer(source);
                } catch (Throwable caught) {
                    failure = caught;
                }
                @Nullable PreparedAuthlibServer result = prepared;
                @Nullable Throwable resultFailure = failure;
                EdtDispatcher.execute(() -> resolveCompleted(revision, result, resultFailure));
            });
            activeOperation.set(submitted);
            if (closed.get() || revision != operationRevision) {
                submitted.cancel(true);
            }
        } catch (RejectedExecutionException failure) {
            completeOperation(revision);
            showFailure(failure);
        }
    }

    /// Applies one endpoint-discovery completion only when it belongs to the active operation.
    ///
    /// @param revision submitted operation identity
    /// @param prepared resolved endpoint, or null after failure
    /// @param failure resolution failure, or null after success
    private void resolveCompleted(
            long revision,
            @Nullable PreparedAuthlibServer prepared,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!completeOperation(revision)) {
            return;
        }
        if (failure != null) {
            showFailure(failure);
            return;
        }
        PreparedAuthlibServer resolved = Objects.requireNonNull(prepared, "resolved authlib server");
        preparedServer = resolved;
        AuthlibServerOption option = resolved.option();
        resolvedName.setText(option.displayName());
        resolvedUrl.setText(option.url());
        httpWarning.setVisible(option.url().startsWith("http://"));
        status.setText(" ");
        updateActionAvailability();
    }

    /// Persists the currently resolved endpoint with optional confirmed storage recovery.
    ///
    /// @param allowReadOnlyOverwrite whether the user confirmed backup-and-overwrite
    private void addPreparedServer(boolean allowReadOnlyOverwrite) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable PreparedAuthlibServer prepared = preparedServer;
        if (closed.get() || prepared == null || activeOperation.get() != null) {
            return;
        }
        status.setText(" ");
        long revision = beginOperation();
        try {
            Future<?> submitted = executor.submit(() -> {
                @Nullable Throwable failure = null;
                try {
                    store.addServer(prepared, allowReadOnlyOverwrite);
                } catch (Throwable caught) {
                    failure = caught;
                }
                @Nullable Throwable resultFailure = failure;
                EdtDispatcher.execute(() -> addCompleted(revision, prepared, resultFailure));
            });
            activeOperation.set(submitted);
            if (closed.get() || revision != operationRevision) {
                submitted.cancel(true);
            }
        } catch (RejectedExecutionException failure) {
            completeOperation(revision);
            showFailure(failure);
        }
    }

    /// Handles add completion, including one explicit retry path for newer read-only configuration files.
    ///
    /// @param revision submitted operation identity
    /// @param prepared endpoint that was submitted for persistence
    /// @param failure persistence failure, or null after success
    private void addCompleted(
            long revision,
            PreparedAuthlibServer prepared,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!completeOperation(revision)) {
            return;
        }
        if (failure instanceof AuthlibServerStorageOverwriteRequiredException) {
            if (confirmReadOnlyOverwrite()) {
                addPreparedServer(true);
            }
            return;
        }
        if (failure != null) {
            showFailure(failure);
            return;
        }
        selectedServerUrl = prepared.option().url();
        preparedServer = null;
        endpoint.setText("");
        resolvedName.setText(" ");
        resolvedUrl.setText(" ");
        httpWarning.setVisible(false);
        status.setText(" ");
        applySnapshot(store.snapshot());
    }

    /// Confirms and permanently removes the currently selected configured endpoint.
    private void removeSelectedServer() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AuthlibServerOption selected = serverList.getSelectedValue();
        if (closed.get() || selected == null || activeOperation.get() != null) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                i18n("button.remove.confirm") + "\n\n" + selected.displayName(),
                i18n("button.remove"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        removeServer(selected.url(), false);
    }

    /// Removes one selected endpoint with optional confirmed storage recovery.
    ///
    /// @param serverUrl stable configured endpoint URL
    /// @param allowReadOnlyOverwrite whether the user confirmed backup-and-overwrite
    private void removeServer(String serverUrl, boolean allowReadOnlyOverwrite) {
        EdtDispatcher.requireEventDispatchThread();
        status.setText(" ");
        long revision = beginOperation();
        try {
            Future<?> submitted = executor.submit(() -> {
                @Nullable Throwable failure = null;
                try {
                    store.removeServer(serverUrl, allowReadOnlyOverwrite);
                } catch (Throwable caught) {
                    failure = caught;
                }
                @Nullable Throwable resultFailure = failure;
                EdtDispatcher.execute(() -> removeCompleted(revision, serverUrl, resultFailure));
            });
            activeOperation.set(submitted);
            if (closed.get() || revision != operationRevision) {
                submitted.cancel(true);
            }
        } catch (RejectedExecutionException failure) {
            completeOperation(revision);
            showFailure(failure);
        }
    }

    /// Handles remove completion, including one explicit retry path for newer read-only configuration files.
    ///
    /// @param revision submitted operation identity
    /// @param serverUrl exact endpoint submitted for removal
    /// @param failure persistence failure, or null after success
    private void removeCompleted(long revision, String serverUrl, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!completeOperation(revision)) {
            return;
        }
        if (failure instanceof AuthlibServerStorageOverwriteRequiredException) {
            if (confirmReadOnlyOverwrite()) {
                removeServer(serverUrl, true);
            }
            return;
        }
        if (failure != null) {
            showFailure(failure);
            return;
        }
        selectedServerUrl = null;
        status.setText(" ");
        applySnapshot(store.snapshot());
    }

    /// Starts one exclusive worker operation and disables conflicting commands.
    ///
    /// @return new operation identity
    private long beginOperation() {
        EdtDispatcher.requireEventDispatchThread();
        long revision = ++operationRevision;
        updateActionAvailability();
        return revision;
    }

    /// Clears the active worker marker only for the currently active operation.
    ///
    /// @param revision completed operation identity
    /// @return whether the completion belongs to the current active operation
    private boolean completeOperation(long revision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || revision != operationRevision) {
            return false;
        }
        activeOperation.set(null);
        updateActionAvailability();
        return true;
    }

    /// Enables commands only when the dialog is open and no worker operation is active.
    private void updateActionAvailability() {
        EdtDispatcher.requireEventDispatchThread();
        boolean idle = !closed.get() && activeOperation.get() == null;
        endpoint.setEnabled(idle);
        resolve.setEnabled(idle);
        add.setEnabled(idle && preparedServer != null);
        remove.setEnabled(idle && serverList.getSelectedValue() != null);
        closeButton.setEnabled(!closed.get());
        serverList.setEnabled(idle);
    }

    /// Shows a localized or diagnostic error inside the dialog without creating a second nested modal.
    ///
    /// @param failure operation failure
    private void showFailure(Throwable failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getLocalizedMessage();
        showFailure(message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message);
    }

    /// Shows one validation or operational error inside the dialog.
    ///
    /// @param message non-empty display text
    private void showFailure(String message) {
        EdtDispatcher.requireEventDispatchThread();
        status.setText(Objects.requireNonNull(message, "message"));
    }

    /// Confirms backup-and-overwrite for a server list saved by a newer launcher version.
    ///
    /// @return whether the caller may retry a rejected mutation with overwrite permission
    private boolean confirmReadOnlyOverwrite() {
        EdtDispatcher.requireEventDispatchThread();
        Object[] options = {
                i18n("settings.file.force_write"),
                i18n("button.cancel")
        };
        return JOptionPane.showOptionDialog(
                this,
                i18n("account.injector.server.storage.read_only")
                        + "\n\n"
                        + i18n("settings.file.force_write.confirm"),
                i18n("message.warning"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]) == 0;
    }

    /// Finds a configured endpoint URL in immutable persisted order.
    ///
    /// @param servers immutable server options
    /// @param serverUrl server URL to find, or null for no selection
    /// @return zero-based index, or -1 when not configured
    private static int indexOf(
            @Unmodifiable List<AuthlibServerOption> servers,
            @Nullable String serverUrl) {
        if (serverUrl == null) {
            return -1;
        }
        for (int index = 0; index < servers.size(); index++) {
            if (servers.get(index).url().equals(serverUrl)) {
                return index;
            }
        }
        return -1;
    }

    /// Resolves a component's owning top-level window without retaining the component.
    ///
    /// @param owner source component, or null for no owner
    /// @return owning window, or null when unavailable
    private static @Nullable Window ownerWindow(@Nullable Component owner) {
        return owner == null ? null : SwingUtilities.getWindowAncestor(owner);
    }
}
