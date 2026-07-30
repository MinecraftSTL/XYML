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
import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Native modal Swing dialog for offline, Microsoft, and authlib-injector account creation.
///
/// The dialog contains no JavaFX or JFoenix reference. It delegates all authentication and storage
/// work to [SwingAccountCreationCoordinator], while its blocking confirmation and role-selection
/// methods marshal worker calls to the EDT. The caller owns and must eventually shut down the supplied executor.
@NotNullByDefault
public final class SwingAccountCreationDialog extends JDialog
        implements AccountCreationInteraction, AccountCreationListener, AutoCloseable {
    /// Card identifier for offline fields.
    private static final String OFFLINE_CARD = AccountCreationMethod.OFFLINE.name();

    /// Card identifier for Microsoft fields.
    private static final String MICROSOFT_CARD = AccountCreationMethod.MICROSOFT.name();

    /// Card identifier for authlib-injector fields.
    private static final String AUTHLIB_CARD = AccountCreationMethod.AUTHLIB_INJECTOR.name();

    /// Preferred width of the selectable invalid-username guidance.
    private static final int INVALID_USERNAME_PROMPT_WIDTH = 560;

    /// Authentication and storage gateway.
    private final AccountCreationGateway gateway;

    /// Caller-owned blocking-work executor.
    private final ExecutorService executor;

    /// Coordinator owned by this dialog.
    private final SwingAccountCreationCoordinator coordinator;

    /// Methods displayed by this dialog in exact tab order.
    private final @Unmodifiable List<AccountCreationMethod> displayedMethods;

    /// Method tabs in stable display order.
    private final JTabbedPane methodTabs = new JTabbedPane();

    /// Offline username field.
    private final JTextField offlineUsername = new JTextField();

    /// Optional explicit offline UUID field.
    private final JTextField offlineUuid = new JTextField();

    /// Microsoft browser-flow selector.
    private final JToggleButton microsoftBrowser = new JToggleButton(
            i18n("account.methods.microsoft.methods.browser"),
            true);

    /// Microsoft device-code selector.
    private final JToggleButton microsoftDevice = new JToggleButton(
            i18n("account.methods.microsoft.methods.device"));

    /// Authlib-injector server choices loaded away from the EDT.
    private final JComboBox<AuthlibServerOption> authlibServer = new JComboBox<>();

    /// Authlib-injector username field.
    private final JTextField authlibUsername = new JTextField();

    /// Authlib-injector password field.
    private final JPasswordField authlibPassword = new JPasswordField();

    /// Shared target-storage choice; unchecked selects shared-user storage.
    private final JCheckBox portable = new JCheckBox(i18n("account.portable"));

    /// User-visible status text.
    private final JLabel status = new JLabel(" ");

    /// Starts authentication for the current form.
    private final JButton login = new JButton(i18n("account.login"));

    /// Cancels current work or closes the idle dialog.
    private final JButton cancel = new JButton(i18n("button.cancel"));

    /// Current operation, or null while the form is idle.
    private final AtomicReference<@Nullable AccountCreationOperation> operation = new AtomicReference<>();

    /// Pending nested prompt closed by external workflow cancellation.
    private final AtomicReference<@Nullable JDialog> pendingPrompt = new AtomicReference<>();

    /// Background authlib-server snapshot load, or null before submission completes.
    private final AtomicReference<@Nullable Future<?>> serverLoad = new AtomicReference<>();

    /// Whether the dialog lifecycle has ended.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a production account dialog without showing it.
    ///
    /// @param owner owning Swing component, or null for an unowned dialog
    /// @param initialMethod explicit initial authentication method
    /// @param gateway authentication and storage boundary
    /// @param executor caller-owned executor for authentication and launcher-state work
    public SwingAccountCreationDialog(
            @Nullable Component owner,
            AccountCreationMethod initialMethod,
            AccountCreationGateway gateway,
            ExecutorService executor) {
        this(owner, initialMethod, false, gateway, executor);
    }

    /// Creates a production account dialog with an explicit method-visibility policy.
    ///
    /// @param owner owning Swing component, or null for an unowned dialog
    /// @param initialMethod explicit initial authentication method
    /// @param microsoftOnly whether policy suppresses every non-Microsoft method
    /// @param gateway authentication and storage boundary
    /// @param executor caller-owned executor for authentication and launcher-state work
    SwingAccountCreationDialog(
            @Nullable Component owner,
            AccountCreationMethod initialMethod,
            boolean microsoftOnly,
            AccountCreationGateway gateway,
            ExecutorService executor) {
        super(ownerWindow(owner), i18n("account.create"), ModalityType.APPLICATION_MODAL);
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        displayedMethods = microsoftOnly
                ? List.of(AccountCreationMethod.MICROSOFT)
                : List.of(
                        AccountCreationMethod.OFFLINE,
                        AccountCreationMethod.MICROSOFT,
                        AccountCreationMethod.AUTHLIB_INJECTOR);
        coordinator = new SwingAccountCreationCoordinator(
                gateway,
                this,
                executor,
                SwingUiDispatcher.INSTANCE,
                Metadata.SKIP_OFFLINE_USERNAME_CHECK);
        configureWindow();
        AccountCreationMethod effectiveInitialMethod = microsoftOnly
                ? AccountCreationMethod.MICROSOFT
                : Objects.requireNonNull(initialMethod, "initialMethod");
        createContent(effectiveInitialMethod);
        if (displayedMethods.contains(AccountCreationMethod.AUTHLIB_INJECTOR)) {
            loadAuthlibServers();
        }
    }

    /// Shows the modal dialog on the Swing EDT.
    public void open() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            throw new IllegalStateException("Account creation dialog is closed");
        }
        try {
            setVisible(true);
        } finally {
            if (!isVisible()) {
                close();
            }
        }
    }

    /// Confirms an invalid offline name using the localized typed acknowledgement.
    @Override
    public boolean confirmInvalidOfflineUsername(String username) {
        Objects.requireNonNull(username, "username");
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        EdtDispatcher.executeAndWait(() -> result.set(showInvalidUsernamePrompt(username)));
        return Boolean.TRUE.equals(result.get());
    }

    /// Confirms backup and overwrite of the selected account storage.
    @Override
    public boolean confirmReadOnlyStorage(boolean portableStorage) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        EdtDispatcher.executeAndWait(() -> {
            String message = i18n("account.storage.read_only")
                    + "\n\n"
                    + i18n("settings.file.force_write.confirm");
            @Nullable Object value = showPrompt(
                    message,
                    i18n("message.warning"),
                    JOptionPane.WARNING_MESSAGE,
                    new Object[]{i18n("settings.file.force_write"), i18n("button.cancel")},
                    i18n("button.cancel"));
            result.set(Integer.valueOf(0).equals(value));
        });
        return Boolean.TRUE.equals(result.get());
    }

    /// Shows a native single-selection list for multiple authlib-injector roles.
    @Override
    public String selectRole(@Unmodifiable List<AccountRoleOption> roles)
            throws AccountCreationCancelledException {
        @Unmodifiable List<AccountRoleOption> immutableRoles = List.copyOf(roles);
        if (immutableRoles.isEmpty()) {
            throw new AccountCreationCancelledException();
        }
        AtomicReference<@Nullable String> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(showRolePrompt(immutableRoles)));
        @Nullable String selected = result.get();
        if (selected == null) {
            throw new AccountCreationCancelledException();
        }
        return selected;
    }

    /// Closes a pending nested prompt without synchronously waiting on the EDT.
    @Override
    public void cancelPendingInteraction() {
        @Nullable JDialog prompt = pendingPrompt.getAndSet(null);
        if (prompt != null) {
            EdtDispatcher.execute(prompt::dispose);
        }
    }

    /// Updates progress presentation and opens Microsoft authorization locations when possible.
    @Override
    public void onProgress(AccountCreationNotice notice) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(notice, "notice");
        switch (notice.kind()) {
            case AUTHENTICATING -> status.setText(nonBlankOr(
                    notice.detail(),
                    i18n("account.methods.microsoft.logging_in")));
            case BROWSER_AUTHORIZATION -> {
                String location = Objects.requireNonNull(notice.location(), "authorization location");
                status.setText("<html>" + i18n("account.methods.microsoft.methods.browser")
                        + "<br>" + location + "</html>");
                openExternalLocation(location);
            }
            case DEVICE_AUTHORIZATION -> {
                String location = Objects.requireNonNull(notice.location(), "verification location");
                String code = Objects.requireNonNull(notice.code(), "device code");
                status.setText("<html>" + i18n("account.methods.microsoft.methods.device")
                        + "<br>" + location + "<br>" + code + "</html>");
                copyToClipboard(code);
                openExternalLocation(location);
            }
            case AUTHORIZATION_COMPLETED -> status.setText(
                    "<html>" + i18n("account.methods.microsoft.methods.device.hint.completed") + "</html>");
            case WRITING_STORAGE -> status.setText(i18n("settings.file.force_write"));
        }
    }

    /// Closes the dialog after the account has been committed and selected.
    @Override
    public void onSucceeded(AccountCreationResult result) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(result, "result");
        operation.set(null);
        close();
    }

    /// Restores the form after a prompt or explicit operation cancellation.
    @Override
    public void onCancelled() {
        EdtDispatcher.requireEventDispatchThread();
        operation.set(null);
        if (!closed.get()) {
            status.setText(" ");
            setBusy(false);
        }
    }

    /// Restores the form and displays a localized failure.
    @Override
    public void onFailed(String localizedMessage, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(localizedMessage, "localizedMessage");
        Objects.requireNonNull(failure, "failure");
        operation.set(null);
        LOG.warning("Native Swing account creation failed", failure);
        if (!closed.get()) {
            status.setText("<html>" + localizedMessage.replace("\n", "<br>") + "</html>");
            setBusy(false);
        }
    }

    /// Cancels work, closes nested prompts, and disposes the native dialog once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable AccountCreationOperation active = operation.getAndSet(null);
        if (active != null) {
            active.cancel();
        }
        coordinator.close();
        cancelPendingInteraction();
        @Nullable Future<?> load = serverLoad.getAndSet(null);
        if (load != null) {
            load.cancel(true);
        }
        EdtDispatcher.execute(this::dispose);
    }

    /// Configures stable dimensions and close behavior.
    private void configureWindow() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(560, 430));
        addWindowListener(new WindowAdapter() {
            /// Routes the title-bar close action through workflow cancellation.
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }

            /// Releases worker and prompt ownership when an owning window disposes this dialog.
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });
    }

    /// Builds method tabs, shared storage controls, status, and actions.
    ///
    /// @param initialMethod explicit initial method
    private void createContent(AccountCreationMethod initialMethod) {
        JPanel root = new JPanel(new MigLayout(
                "insets 20, fill",
                "[grow,fill]",
                "[][grow,fill][][pref!]"));
        SwingTransparency.revealBackgroundThroughTabs(methodTabs);
        for (AccountCreationMethod method : displayedMethods) {
            methodTabs.addTab(methodTitle(method), switch (method) {
                case OFFLINE -> createOfflinePanel();
                case MICROSOFT -> createMicrosoftPanel();
                case AUTHLIB_INJECTOR -> createAuthlibPanel();
            });
        }
        int initialIndex = displayedMethods.indexOf(initialMethod);
        if (initialIndex < 0) {
            throw new IllegalArgumentException("Initial account method is not displayed: " + initialMethod);
        }
        methodTabs.setSelectedIndex(initialIndex);
        methodTabs.addChangeListener(event -> storeSelectedMethod());
        root.add(methodTabs, "grow, wrap");

        portable.setToolTipText(i18n("account.portable"));
        root.add(portable, "wrap");

        status.setVerticalAlignment(SwingConstants.TOP);
        status.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        root.add(status, "growx, wrap");

        JPanel actions = new JPanel(new MigLayout("insets 0", "[grow][]8[]", "[]"));
        actions.add(new JLabel(), "growx");
        login.addActionListener(event -> startFromForm());
        cancel.addActionListener(event -> cancelOrClose());
        actions.add(login);
        actions.add(cancel);
        root.add(actions, "growx");
        setContentPane(root);
        pack();
        setLocationRelativeTo(getOwner());
    }

    /// Creates offline username and optional UUID fields.
    ///
    /// @return offline form panel
    private JPanel createOfflinePanel() {
        JPanel panel = formPanel();
        panel.add(new JLabel(i18n("account.username")));
        offlineUsername.setToolTipText(i18n("account.methods.offline.name.special_characters"));
        panel.add(offlineUsername, "growx, wrap");
        panel.add(new JLabel(i18n("account.methods.offline.uuid")));
        offlineUuid.setToolTipText(i18n("account.methods.offline.uuid.hint"));
        panel.add(offlineUuid, "growx, wrap");
        return panel;
    }

    /// Creates Microsoft grant-mode controls with an explicit established browser default.
    ///
    /// @return Microsoft form panel
    private JPanel createMicrosoftPanel() {
        JPanel panel = formPanel();
        panel.add(new JLabel(i18n("account.methods")));
        JPanel choices = createMicrosoftModeChoices(microsoftBrowser, microsoftDevice);
        panel.add(choices, "growx, wrap");
        JLabel hint = new JLabel("<html>" + i18n("account.methods.microsoft.hint") + "</html>");
        panel.add(hint, "span 2, growx, wrap");
        return panel;
    }

    /// Creates a mutually exclusive FlatLaf segmented control for Microsoft grant modes.
    ///
    /// This package-visible helper keeps the non-window control testable in headless builds.
    ///
    /// @param browser browser-based OAuth mode button
    /// @param device device-code mode button
    /// @return transparent two-segment control host
    static JPanel createMicrosoftModeChoices(JToggleButton browser, JToggleButton device) {
        JToggleButton validatedBrowser = Objects.requireNonNull(browser, "browser");
        JToggleButton validatedDevice = Objects.requireNonNull(device, "device");
        validatedBrowser.setName("accountMicrosoftBrowserMode");
        validatedDevice.setName("accountMicrosoftDeviceMode");
        validatedBrowser.putClientProperty("JButton.buttonType", "segmented");
        validatedDevice.putClientProperty("JButton.buttonType", "segmented");
        validatedBrowser.putClientProperty("JButton.segmentPosition", "first");
        validatedDevice.putClientProperty("JButton.segmentPosition", "last");

        ButtonGroup group = new ButtonGroup();
        group.add(validatedBrowser);
        group.add(validatedDevice);
        JPanel choices = new JPanel(new MigLayout("insets 0, gap 0", "[][]", "[]"));
        choices.setOpaque(false);
        choices.add(validatedBrowser);
        choices.add(validatedDevice);
        return choices;
    }

    /// Creates authlib-injector server and credential fields.
    ///
    /// @return authlib-injector form panel
    private JPanel createAuthlibPanel() {
        JPanel panel = formPanel();
        panel.add(new JLabel(i18n("account.injector.server")));
        authlibServer.setRenderer(serverRenderer());
        authlibServer.setSelectedIndex(-1);
        panel.add(authlibServer, "growx, wrap");
        panel.add(new JLabel(i18n("account.username")));
        panel.add(authlibUsername, "growx, wrap");
        panel.add(new JLabel(i18n("account.password")));
        panel.add(authlibPassword, "growx, wrap");
        return panel;
    }

    /// Creates one consistently aligned two-column form panel.
    ///
    /// @return form panel
    private static JPanel formPanel() {
        return new JPanel(new MigLayout("insets 16, fillx", "[pref!][grow,fill]", "[]14[]14[]"));
    }

    /// Loads server choices on the caller executor and applies a plain snapshot on the EDT.
    private void loadAuthlibServers() {
        Future<?> load = executor.submit(() -> {
            try {
                @Unmodifiable List<AuthlibServerOption> options = gateway.availableAuthlibServers();
                EdtDispatcher.execute(() -> applyAuthlibServers(options));
            } catch (Throwable failure) {
                EdtDispatcher.execute(() -> {
                    if (!closed.get()) {
                        status.setText("<html>" + gateway.localizeFailure(failure) + "</html>");
                    }
                });
            }
        });
        serverLoad.set(load);
        if (closed.get()) {
            load.cancel(true);
        }
    }

    /// Replaces authlib choices without inventing a default among multiple servers.
    ///
    /// @param options immutable server choices
    private void applyAuthlibServers(@Unmodifiable List<AuthlibServerOption> options) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        DefaultComboBoxModel<AuthlibServerOption> model = new DefaultComboBoxModel<>();
        for (AuthlibServerOption option : options) {
            model.addElement(option);
        }
        authlibServer.setModel(model);
        authlibServer.setSelectedIndex(options.size() == 1 ? 0 : -1);
    }

    /// Validates current fields, builds a request, and starts the worker operation.
    private void startFromForm() {
        EdtDispatcher.requireEventDispatchThread();
        if (operation.get() != null || closed.get()) {
            return;
        }
        try {
            AccountCreationRequest request = requestFromForm();
            status.setText(" ");
            setBusy(true);
            AccountCreationOperation started = coordinator.start(request, this);
            if (!operation.compareAndSet(null, started)) {
                started.cancel();
                throw new IllegalStateException("Account creation operation changed during start");
            }
        } catch (RuntimeException failure) {
            status.setText("<html>" + formFailureMessage(failure) + "</html>");
            setBusy(false);
        }
    }

    /// Persists a user-selected method away from the EDT through the gateway dispatcher.
    private void storeSelectedMethod() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || displayedMethods.size() == 1) {
            return;
        }
        AccountCreationMethod selectedMethod = methodAt(methodTabs.getSelectedIndex());
        try {
            executor.submit(() -> {
                try {
                    gateway.storePreferredMethod(selectedMethod);
                } catch (RuntimeException failure) {
                    LOG.warning("Failed to store preferred account method", failure);
                }
            });
        } catch (RuntimeException failure) {
            LOG.warning("Failed to schedule preferred account method persistence", failure);
        }
    }

    /// Converts visible method-specific controls to one validated request.
    ///
    /// @return validated immutable request
    private AccountCreationRequest requestFromForm() {
        AccountCreationMethod method = methodAt(methodTabs.getSelectedIndex());
        return switch (method) {
            case OFFLINE -> AccountCreationRequest.offline(
                    requiredText(offlineUsername, i18n("account.username")),
                    parseOptionalUuid(offlineUuid.getText()),
                    portable.isSelected());
            case MICROSOFT -> AccountCreationRequest.microsoft(
                    microsoftDevice.isSelected()
                            ? MicrosoftAccountLoginMode.DEVICE_CODE
                            : MicrosoftAccountLoginMode.BROWSER,
                    portable.isSelected());
            case AUTHLIB_INJECTOR -> authlibRequest();
        };
    }

    /// Builds an authlib request and clears the mutable password array immediately.
    ///
    /// @return validated authlib request
    private AccountCreationRequest authlibRequest() {
        @Nullable AuthlibServerOption server = (AuthlibServerOption) authlibServer.getSelectedItem();
        if (server == null) {
            throw new IllegalArgumentException(i18n("account.injector.empty"));
        }
        String username = requiredText(authlibUsername, i18n("account.username"));
        if (server.emailUsernameRequired() && !username.contains("@")) {
            throw new IllegalArgumentException(i18n("input.email"));
        }
        char[] passwordChars = authlibPassword.getPassword();
        try {
            String password = new String(passwordChars);
            if (password.isBlank()) {
                throw new IllegalArgumentException(i18n("account.password"));
            }
            return AccountCreationRequest.authlibInjector(
                    server.url(),
                    username,
                    password,
                    portable.isSelected());
        } finally {
            Arrays.fill(passwordChars, '\0');
            authlibPassword.setText("");
        }
    }

    /// Cancels active work or closes an idle dialog.
    private void cancelOrClose() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable AccountCreationOperation active = operation.get();
        if (active == null) {
            close();
        } else {
            active.cancel();
        }
    }

    /// Enables or disables all form controls while retaining cancellation access.
    ///
    /// @param busy true while an operation is active
    private void setBusy(boolean busy) {
        methodTabs.setEnabled(!busy);
        offlineUsername.setEnabled(!busy);
        offlineUuid.setEnabled(!busy);
        microsoftBrowser.setEnabled(!busy);
        microsoftDevice.setEnabled(!busy);
        authlibServer.setEnabled(!busy);
        authlibUsername.setEnabled(!busy);
        authlibPassword.setEnabled(!busy);
        portable.setEnabled(!busy);
        login.setEnabled(!busy);
        cancel.setText(i18n("button.cancel"));
    }

    /// Shows a typed acknowledgement prompt for an invalid offline username.
    ///
    /// @param username requested invalid name
    /// @return true only after exact whitespace-insensitive acknowledgement
    private boolean showInvalidUsernamePrompt(String username) {
        String expected = replacePunctuationWithSpaces(
                i18n("account.methods.offline.name.invalid.confirmation"));
        JTextField confirmation = new JTextField();
        String guidance = i18n("account.methods.offline.name.invalid")
                + "\n\n"
                + i18n(
                "account.methods.offline.name.invalid.confirmation.prompt",
                expected);
        JPanel content = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[]10[]"));
        content.add(createSelectablePromptText(guidance), "growx, wrap");
        confirmation.setToolTipText(username);
        content.add(confirmation, "growx");
        JButton confirmButton = new JButton(i18n("button.ok"));
        JButton cancelButton = new JButton(i18n("button.cancel"));
        bindConfirmationButton(confirmation, confirmButton, expected);
        if (closed.get()) {
            return false;
        }

        SwingUtilities.invokeLater(confirmation::requestFocusInWindow);
        @Nullable Object value = showPrompt(
                content,
                i18n("message.warning"),
                JOptionPane.WARNING_MESSAGE,
                new Object[]{confirmButton, cancelButton},
                confirmButton);
        return Integer.valueOf(0).equals(value);
    }

    /// Creates label-styled prompt guidance that supports partial selection and keyboard copying.
    ///
    /// The component deliberately keeps the ordinary pointer cursor and does not inherit or expose
    /// a component popup menu. Selection remains available through the standard Swing caret so users
    /// can drag across any required fragment while the standard keyboard selection actions remain intact.
    ///
    /// @param text complete localized prompt guidance
    /// @return configured read-only prompt text
    static JTextArea createSelectablePromptText(String text) {
        JTextArea promptText = new JTextArea(Objects.requireNonNull(text, "text"));
        promptText.setName("accountInvalidUsernamePromptText");
        promptText.setEditable(false);
        promptText.setFocusable(true);
        promptText.setLineWrap(true);
        promptText.setWrapStyleWord(true);
        promptText.setOpaque(false);
        promptText.setBorder(BorderFactory.createEmptyBorder());
        promptText.setFont(UIManager.getFont("Label.font"));
        promptText.setForeground(UIManager.getColor("Label.foreground"));
        promptText.setCursor(Cursor.getDefaultCursor());
        promptText.setComponentPopupMenu(null);
        promptText.setInheritsPopupMenu(false);
        promptText.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                DefaultEditorKit.copyAction);
        promptText.setSize(new Dimension(INVALID_USERNAME_PROMPT_WIDTH, Short.MAX_VALUE));
        Dimension preferredSize = promptText.getPreferredSize();
        promptText.setPreferredSize(new Dimension(INVALID_USERNAME_PROMPT_WIDTH, preferredSize.height));
        promptText.setCaretPosition(0);
        return promptText;
    }

    /// Keeps an invalid-username confirmation button synchronized with the acknowledgement field.
    ///
    /// @param confirmation acknowledgement input field
    /// @param confirmButton button that accepts the warning
    /// @param expected normalized localized acknowledgement
    static void bindConfirmationButton(
            JTextField confirmation,
            JButton confirmButton,
            String expected) {
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(confirmButton, "confirmButton");
        Objects.requireNonNull(expected, "expected");
        Runnable update = () -> confirmButton.setEnabled(
                matchesConfirmation(confirmation.getText(), expected));
        confirmation.getDocument().addDocumentListener(new ConfirmationDocumentListener(update));
        update.run();
    }

    /// Shows a no-default native role chooser until the user selects one or cancels.
    ///
    /// @param roles immutable role choices
    /// @return selected UUID text, or null on cancellation
    private @Nullable String showRolePrompt(@Unmodifiable List<AccountRoleOption> roles) {
        JList<AccountRoleOption> list = new JList<>(roles.toArray(AccountRoleOption[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(roleRenderer());
        list.clearSelection();
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, Math.min(280, 48 + roles.size() * 34)));
        while (!closed.get()) {
            @Nullable Object value = showPrompt(
                    scroll,
                    i18n("account.choose"),
                    JOptionPane.QUESTION_MESSAGE,
                    new Object[]{i18n("button.ok"), i18n("button.cancel")},
                    i18n("button.cancel"));
            if (!Integer.valueOf(0).equals(value)) {
                return null;
            }
            @Nullable AccountRoleOption selected = list.getSelectedValue();
            if (selected != null) {
                return selected.profileId().toString();
            }
            Toolkit.getDefaultToolkit().beep();
        }
        return null;
    }

    /// Shows one nested JOptionPane dialog and exposes it to cancellation.
    ///
    /// @param message prompt content
    /// @param title prompt title
    /// @param messageType JOptionPane message type
    /// @param options explicit ordered options
    /// @param initialValue initial option
    /// @return selected zero-based option index, or null after close
    private @Nullable Object showPrompt(
            Object message,
            String title,
            int messageType,
            Object @Unmodifiable [] options,
            Object initialValue) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane pane = new JOptionPane(
                message,
                messageType,
                JOptionPane.DEFAULT_OPTION,
                null,
                options,
                initialValue);
        wireButtonOptions(pane, options);
        JDialog prompt = pane.createDialog(this, title);
        if (!pendingPrompt.compareAndSet(null, prompt)) {
            prompt.dispose();
            throw new IllegalStateException("Another account prompt is already visible");
        }
        try {
            prompt.setVisible(true);
            @Nullable Object value = pane.getValue();
            if (value == null || value == JOptionPane.UNINITIALIZED_VALUE) {
                return null;
            }
            for (int index = 0; index < options.length; index++) {
                if (Objects.equals(options[index], value)) {
                    return index;
                }
            }
            return null;
        } finally {
            pendingPrompt.compareAndSet(prompt, null);
            prompt.dispose();
        }
    }

    /// Wires explicit button options to the value contract used by `JOptionPane` dialogs.
    ///
    /// Swing automatically wires string options but inserts component options unchanged. Explicit
    /// buttons therefore set their own option value so the dialog closes and the selected index can
    /// be resolved without rebuilding the prompt.
    ///
    /// @param pane target option pane
    /// @param options explicit ordered options
    static void wireButtonOptions(JOptionPane pane, Object @Unmodifiable [] options) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(options, "options");
        for (Object option : options) {
            if (option instanceof AbstractButton button) {
                button.addActionListener(event -> pane.setValue(option));
            }
        }
    }

    /// Returns a renderer showing authlib server name and URL without retaining mutable configuration objects.
    ///
    /// @return combo-box renderer
    private static ListCellRenderer<? super AuthlibServerOption> serverRenderer() {
        return (list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null
                    ? ""
                    : value.displayName() + " - " + value.url());
            if (selected) {
                label.setOpaque(true);
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return label;
        };
    }

    /// Returns a renderer showing a role name and stable UUID.
    ///
    /// @return role-list renderer
    private static ListCellRenderer<? super AccountRoleOption> roleRenderer() {
        return (list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.profileName() + " - " + value.profileId());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            return label;
        };
    }

    /// Opens one trusted OAuth location in the system browser without failing authentication.
    ///
    /// @param location trusted location emitted by the Microsoft OAuth callback
    private static void openExternalLocation(String location) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(location));
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException failure) {
            LOG.warning("Failed to open Microsoft authorization location", failure);
        }
    }

    /// Copies a Microsoft device code to the platform clipboard when available.
    ///
    /// @param text device code
    private static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (IllegalStateException | java.awt.HeadlessException | SecurityException failure) {
            LOG.warning("Failed to copy Microsoft device code", failure);
        }
    }

    /// Returns non-blank detail text or a localized fallback.
    ///
    /// @param detail optional detail
    /// @param fallback fallback text
    /// @return selected text
    private static String nonBlankOr(@Nullable String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    /// Reads one required trimmed text field.
    ///
    /// @param field source field
    /// @param label localized field label
    /// @return trimmed non-empty value
    private static String requiredText(JTextField field, String label) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(label);
        }
        return text;
    }

    /// Parses an optional UUID using the launcher's compact-or-hyphenated parser.
    ///
    /// @param text user-entered UUID text
    /// @return parsed UUID, or null when blank
    private static @Nullable UUID parseOptionalUuid(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return UUIDs.parse(text.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(i18n("account.methods.offline.uuid.malformed"), failure);
        }
    }

    /// Returns user-visible form validation text.
    ///
    /// @param failure validation failure
    /// @return non-empty message
    private static String formFailureMessage(RuntimeException failure) {
        @Nullable String message = failure.getLocalizedMessage();
        return message == null || message.isBlank()
                ? i18n("message.error")
                : message;
    }

    /// Replaces localized punctuation with spaces for stable typed acknowledgement matching.
    ///
    /// @param text localized acknowledgement
    /// @return punctuation-free text
    static String replacePunctuationWithSpaces(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (isPunctuation(codePoint)) {
                result.append(' ');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    /// Compares acknowledgement text while ignoring every Unicode whitespace character.
    ///
    /// @param input user-entered text
    /// @param expected localized expected text
    /// @return true when normalized text matches exactly
    static boolean matchesConfirmation(String input, String expected) {
        return removeWhitespace(input).contentEquals(removeWhitespace(expected));
    }

    /// Removes Unicode whitespace and space separators.
    ///
    /// @param text source text
    /// @return compact comparison text
    private static String removeWhitespace(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    /// Returns whether one Unicode code point belongs to a punctuation category.
    ///
    /// @param codePoint Unicode code point
    /// @return true for punctuation
    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /// Runs one validation callback after every acknowledgement-document mutation.
    @NotNullByDefault
    private static final class ConfirmationDocumentListener implements DocumentListener {
        /// Callback that recalculates confirmation availability.
        private final Runnable update;

        /// Creates a listener for one acknowledgement field.
        ///
        /// @param update confirmation-availability callback
        private ConfirmationDocumentListener(Runnable update) {
            this.update = Objects.requireNonNull(update, "update");
        }

        /// Recalculates availability after inserted text.
        ///
        /// @param event document mutation event
        @Override
        public void insertUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            update.run();
        }

        /// Recalculates availability after removed text.
        ///
        /// @param event document mutation event
        @Override
        public void removeUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            update.run();
        }

        /// Recalculates availability after an attribute-only mutation.
        ///
        /// @param event document mutation event
        @Override
        public void changedUpdate(DocumentEvent event) {
            Objects.requireNonNull(event, "event");
            update.run();
        }
    }

    /// Returns the localized title for one displayed authentication method.
    ///
    /// @param method authentication method
    /// @return localized tab title
    private static String methodTitle(AccountCreationMethod method) {
        return switch (method) {
            case OFFLINE -> i18n("account.methods.offline");
            case MICROSOFT -> i18n("account.methods.microsoft");
            case AUTHLIB_INJECTOR -> i18n("account.methods.authlib_injector");
        };
    }

    /// Maps a displayed tab index back to its exact method.
    ///
    /// @param index selected tab index
    /// @return authentication method
    private AccountCreationMethod methodAt(int index) {
        if (index < 0 || index >= displayedMethods.size()) {
            throw new IllegalArgumentException("Unknown account method tab: " + index);
        }
        return displayedMethods.get(index);
    }

    /// Resolves the owning top-level window without retaining a component reference.
    ///
    /// @param owner owning component, or null
    /// @return owning window, or null
    private static @Nullable Window ownerWindow(@Nullable Component owner) {
        return owner == null ? null : SwingUtilities.getWindowAncestor(owner);
    }
}
