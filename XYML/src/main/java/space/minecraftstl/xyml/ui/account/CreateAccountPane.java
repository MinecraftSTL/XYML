/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.account;

import com.jfoenix.controls.*;
import com.jfoenix.validation.base.ValidatorBase;
import javafx.application.Platform;
import javafx.beans.NamedArg;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.*;
import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountFactory;
import space.minecraftstl.xyml.auth.CharacterSelector;
import space.minecraftstl.xyml.auth.NoSelectedCharacterException;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorAccountFactory;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.auth.authlibinjector.BoundAuthlibInjectorAccountFactory;
import space.minecraftstl.xyml.auth.microsoft.MicrosoftAccountFactory;
import space.minecraftstl.xyml.auth.offline.OfflineAccountFactory;
import space.minecraftstl.xyml.auth.yggdrasil.GameProfile;
import space.minecraftstl.xyml.auth.yggdrasil.YggdrasilService;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.game.TexturesLoader;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.Controllers;
import space.minecraftstl.xyml.ui.FXUtils;
import space.minecraftstl.xyml.ui.SVG;
import space.minecraftstl.xyml.ui.construct.*;
import space.minecraftstl.xyml.upgrade.IntegrityChecker;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.javafx.BindingMapping;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static javafx.beans.binding.Bindings.bindContent;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.setting.SettingsManager.getAuthlibInjectorServers;
import static space.minecraftstl.xyml.ui.FXUtils.*;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.javafx.ExtendedProperties.classPropertyFor;

/// Dialog for creating offline, Microsoft, and authlib-injector accounts.
@NotNullByDefault
public class CreateAccountPane extends JFXDialogLayout implements DialogAware {
    private static final Pattern USERNAME_CHECKER_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");
    /// Localization key for the acknowledgement required by an illegal offline username.
    private static final String INVALID_USERNAME_CONFIRMATION_KEY =
            "account.methods.offline.name.invalid.confirmation";

    private boolean showMethodSwitcher;
    private AccountFactory<?> factory;

    private final Label lblErrorMessage;
    private final JFXButton btnAccept;
    private final SpinnerPane spinner;
    private final Node body;
    private final HBox actions;
    private @Nullable Node detailsPane; // AccountDetailsInputPane for Offline / Mojang / authlib-injector, Label for Microsoft
    private final Pane detailsContainer;

    private final BooleanProperty logging = new SimpleBooleanProperty();

    private @Nullable TaskExecutor loginTask;

    public CreateAccountPane() {
        this((AccountFactory<?>) null);
    }

    public CreateAccountPane(@Nullable AccountFactory<?> factory) {
        if (factory == null) {
            if (AccountListPage.RESTRICTED.get()) {
                showMethodSwitcher = false;
                factory = Accounts.FACTORY_MICROSOFT;
            } else {
                showMethodSwitcher = true;
                String preferred = settings().preferredLoginTypeProperty().get();
                try {
                    factory = Accounts.getAccountFactory(preferred);
                } catch (IllegalArgumentException e) {
                    factory = Accounts.FACTORY_OFFLINE;
                }
            }
        } else {
            showMethodSwitcher = false;
        }
        this.factory = factory;

        {
            String title;
            if (showMethodSwitcher) {
                title = "account.create";
            } else {
                title = "account.create." + Accounts.getLoginType(factory);
            }
            setHeading(new Label(i18n(title)));
        }

        {
            lblErrorMessage = new Label();
            lblErrorMessage.setWrapText(true);
            lblErrorMessage.setMaxWidth(400);

            btnAccept = new JFXButton(i18n("account.login"));
            btnAccept.getStyleClass().add("dialog-accept");
            btnAccept.setOnAction(e -> onAccept());

            spinner = new SpinnerPane();
            spinner.getStyleClass().add("small-spinner-pane");
            spinner.setContent(btnAccept);

            JFXButton btnCancel = new JFXButton(i18n("button.cancel"));
            btnCancel.getStyleClass().add("dialog-cancel");
            btnCancel.setOnAction(e -> onCancel());
            onEscPressed(this, btnCancel::fire);

            actions = new HBox(spinner, btnCancel);
            actions.setAlignment(Pos.CENTER_RIGHT);

            setActions(lblErrorMessage, actions);
        }

        if (showMethodSwitcher) {
            TabControl.Tab<?>[] tabs = new TabControl.Tab[Accounts.FACTORIES.size()];
            @Nullable TabControl.Tab<?> selected = null;
            for (int i = 0; i < tabs.length; i++) {
                AccountFactory<?> f = Accounts.FACTORIES.get(i);
                tabs[i] = new TabControl.Tab<>(Accounts.getLoginType(f), Accounts.getLocalizedLoginTypeName(f));
                tabs[i].setUserData(f);
                if (factory == f) {
                    selected = tabs[i];
                }
            }

            TabHeader tabHeader = new TabHeader(tabs);
            tabHeader.getStyleClass().add("add-account-tab-header");
            tabHeader.setMinWidth(USE_PREF_SIZE);
            tabHeader.setMaxWidth(USE_PREF_SIZE);
            tabHeader.getSelectionModel().select(selected);
            onChange(tabHeader.getSelectionModel().selectedItemProperty(),
                    newItem -> {
                        if (newItem == null)
                            return;
                        AccountFactory<?> newMethod = (AccountFactory<?>) newItem.getUserData();
                        settings().preferredLoginTypeProperty().set(Accounts.getLoginType(newMethod));
                        this.factory = newMethod;
                        initDetailsPane();
                    });

            detailsContainer = new StackPane();
            detailsContainer.setPadding(new Insets(15, 0, 0, 0));

            VBox boxBody = new VBox(tabHeader, detailsContainer);
            boxBody.setAlignment(Pos.CENTER);
            body = boxBody;
            setBody(body);

        } else {
            detailsContainer = new StackPane();
            detailsContainer.setPadding(new Insets(10, 0, 0, 0));
            body = detailsContainer;
            setBody(body);
        }
        initDetailsPane();

        setPrefWidth(560);
    }

    public CreateAccountPane(AuthlibInjectorServer authServer) {
        this(Accounts.getAccountFactoryByAuthlibInjectorServer(authServer));
    }

    /// Starts account creation and requests explicit confirmation for an illegal offline username.
    private void onAccept() {
        spinner.showSpinner();
        lblErrorMessage.setText("");

        if (!(factory instanceof MicrosoftAccountFactory)) {
            body.setDisable(true);
        }

        @Nullable String username;
        @Nullable String password;
        @Nullable Object additionalData;
        if (detailsPane instanceof AccountDetailsInputPane) {
            AccountDetailsInputPane details = (AccountDetailsInputPane) detailsPane;
            username = details.getUsername();
            password = details.getPassword();
            additionalData = details.getAdditionalData();
        } else {
            username = null;
            password = null;
            additionalData = null;
        }

        Runnable doCreate = () -> {
            logging.set(true);

            loginTask = Task.supplyAsync(() -> factory.create(new DialogCharacterSelector(), username, password, null, additionalData))
                    .whenComplete(Schedulers.javafx(), account -> {
                        if (Accounts.isAccountFilesReadOnly(account)) {
                            body.setDisable(false);
                            spinner.hideSpinner();
                            Controllers.confirmBackupAndOverwrite(i18n("account.storage.read_only"), () -> {
                                Accounts.forceOverwriteAccountFiles(account);
                                completeLogin(account);
                            });
                            return;
                        }

                        completeLogin(account);
                    }, exception -> {
                        if (exception instanceof NoSelectedCharacterException) {
                            fireEvent(new DialogCloseEvent());
                        } else {
                            lblErrorMessage.setText(Accounts.localizeErrorMessage(exception));
                        }
                        body.setDisable(false);
                        spinner.hideSpinner();
                    }).executor(true);
        };

        if (!Metadata.SKIP_OFFLINE_USERNAME_CHECK
                && factory instanceof OfflineAccountFactory
                && isInvalidOfflineUsername(username)) {
            Controllers.dialog(new InvalidUsernameConfirmationPane(doCreate, () -> {
                body.setDisable(false);
                spinner.hideSpinner();
            }));
        } else {
            doCreate.run();
        }
    }

    /// Returns whether a username violates the vanilla offline account name limits.
    private static boolean isInvalidOfflineUsername(@Nullable String username) {
        return username != null
                && (!USERNAME_CHECKER_PATTERN.matcher(username).matches() || username.length() > 16);
    }

    /// Replaces Unicode punctuation with spaces before the confirmation text is displayed.
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

    /// Returns whether two confirmation strings match after ignoring all whitespace.
    static boolean matchesConfirmation(String input, String expected) {
        return removeWhitespace(input).contentEquals(removeWhitespace(expected));
    }

    /// Removes Unicode whitespace so line wrapping and copied spacing do not affect matching.
    private static String removeWhitespace(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    /// Returns whether a Unicode code point belongs to one of the punctuation categories.
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

    /// Adds the logged-in account, selects it, and closes the dialog.
    private void completeLogin(Account account) {
        int oldIndex = Accounts.getAccounts().indexOf(account);
        if (oldIndex == -1) {
            Accounts.getAccounts().add(account);
        } else {
            // Add an already-added account by replacing the existing entry with the new credentials.
            Accounts.getAccounts().remove(oldIndex);
            Accounts.getAccounts().add(oldIndex, account);
        }

        Accounts.setSelectedAccount(account);

        spinner.hideSpinner();
        fireEvent(new DialogCloseEvent());
    }

    private void onCancel() {
        if (loginTask != null) {
            loginTask.cancel();
        }
        fireEvent(new DialogCloseEvent());
    }

    private void initDetailsPane() {
        if (detailsPane != null) {
            btnAccept.disableProperty().unbind();
            detailsContainer.getChildren().remove(detailsPane);
            lblErrorMessage.setText("");
            setActions(lblErrorMessage, actions);
        }

        if (factory == Accounts.FACTORY_MICROSOFT) {
            detailsPane = new MicrosoftAccountLoginPane(true);
            setActions();
        } else {
            detailsPane = new AccountDetailsInputPane(factory, btnAccept::fire);
            btnAccept.disableProperty().bind(((AccountDetailsInputPane) detailsPane).validProperty().not());
            setActions(lblErrorMessage, actions);
        }

        detailsContainer.getChildren().add(detailsPane);
    }

    private static class AccountDetailsInputPane extends GridPane {

        // ==== authlib-injector hyperlinks ====
        private static final String @Unmodifiable [] ALLOWED_LINKS = {"homepage", "register"};

        /// Creates localized links exposed by an authlib-injector server.
        private static @Unmodifiable List<Hyperlink> createHyperlinks(@Nullable AuthlibInjectorServer server) {
            if (server == null) {
                return emptyList();
            }

            Map<String, String> links = server.getLinks();
            List<Hyperlink> result = new ArrayList<>();
            for (String key : ALLOWED_LINKS) {
                String value = links.get(key);
                if (value != null) {
                    JFXHyperlink link = new JFXHyperlink(i18n("account.injector.link." + key));
                    FXUtils.installSlowTooltip(link, value);
                    link.setOnAction(e -> FXUtils.openLink(value));
                    result.add(link);
                }
            }
            return unmodifiableList(result);
        }
        // =====

        private final AccountFactory<?> factory;
        private @Nullable AuthlibInjectorServer server;
        private @Nullable JFXComboBox<AuthlibInjectorServer> cboServers;
        private @Nullable JFXTextField txtUsername;
        private @Nullable JFXPasswordField txtPassword;
        private @Nullable JFXTextField txtUUID;
        private final BooleanBinding valid;

        public AccountDetailsInputPane(AccountFactory<?> factory, Runnable onAction) {
            this.factory = factory;

            setVgap(22);
            setHgap(15);
            setAlignment(Pos.CENTER);

            ColumnConstraints col0 = new ColumnConstraints();
            col0.setMinWidth(USE_PREF_SIZE);
            getColumnConstraints().add(col0);
            ColumnConstraints col1 = new ColumnConstraints();
            col1.setHgrow(Priority.ALWAYS);
            getColumnConstraints().add(col1);

            int rowIndex = 0;

            if (!IntegrityChecker.isOfficial() && !(factory instanceof OfflineAccountFactory)) {
                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.WARNING);
                hintPane.setSegment(i18n("unofficial.hint"));
                GridPane.setColumnSpan(hintPane, 2);
                add(hintPane, 0, rowIndex);

                rowIndex++;
            }

            if (factory instanceof BoundAuthlibInjectorAccountFactory) {
                this.server = ((BoundAuthlibInjectorAccountFactory) factory).getServer();

                Label lblServers = new Label(i18n("account.injector.server"));
                setHalignment(lblServers, HPos.LEFT);
                add(lblServers, 0, rowIndex);

                Label lblServerName = new Label(this.server.getName());
                lblServerName.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(lblServerName, Priority.ALWAYS);

                HBox linksContainer = new HBox();
                linksContainer.setAlignment(Pos.CENTER);
                linksContainer.getChildren().setAll(createHyperlinks(this.server));
                linksContainer.setMinWidth(USE_PREF_SIZE);

                HBox boxServers = new HBox(lblServerName, linksContainer);
                boxServers.setAlignment(Pos.CENTER_LEFT);
                add(boxServers, 1, rowIndex);

                rowIndex++;
            } else if (factory instanceof AuthlibInjectorAccountFactory) {
                Label lblServers = new Label(i18n("account.injector.server"));
                setHalignment(lblServers, HPos.LEFT);
                add(lblServers, 0, rowIndex);

                cboServers = new JFXComboBox<>();
                cboServers.setCellFactory(jfxListCellFactory(server -> new TwoLineListItem(server.getName(), server.getUrl())));
                cboServers.setConverter(stringConverter(AuthlibInjectorServer::getName));
                bindContent(cboServers.getItems(), getAuthlibInjectorServers());
                cboServers.getItems().addListener(onInvalidating(
                        () -> Platform.runLater( // the selection will not be updated as expected if we call it immediately
                                cboServers.getSelectionModel()::selectFirst)));
                cboServers.getSelectionModel().selectFirst();
                cboServers.setPromptText(i18n("account.injector.empty"));
                BooleanBinding noServers = createBooleanBinding(cboServers.getItems()::isEmpty, cboServers.getItems());
                classPropertyFor(cboServers, "jfx-combo-box-warning").bind(noServers);
                classPropertyFor(cboServers, "jfx-combo-box").bind(noServers.not());
                HBox.setHgrow(cboServers, Priority.ALWAYS);
                HBox.setMargin(cboServers, new Insets(0, 10, 0, 0));
                cboServers.setMaxWidth(Double.MAX_VALUE);

                HBox linksContainer = new HBox();
                linksContainer.setAlignment(Pos.CENTER);
                onChangeAndOperate(cboServers.valueProperty(), server -> {
                    this.server = server;
                    linksContainer.getChildren().setAll(createHyperlinks(server));

                    if (txtUsername != null)
                        txtUsername.validate();
                });
                linksContainer.setMinWidth(USE_PREF_SIZE);

                JFXButton btnAddServer = FXUtils.newToggleButton4(SVG.ADD, 20);
                btnAddServer.setOnAction(e -> {
                    Controllers.dialog(new AddAuthlibInjectorServerPane());
                });

                HBox boxServers = new HBox(cboServers, linksContainer, btnAddServer);
                add(boxServers, 1, rowIndex);

                rowIndex++;
            }

            if (factory.getLoginType().requiresUsername) {
                Label lblUsername = new Label(i18n("account.username"));
                setHalignment(lblUsername, HPos.LEFT);
                add(lblUsername, 0, rowIndex);

                txtUsername = new JFXTextField();
                txtUsername.setValidators(
                        new RequiredValidator(),
                        new Validator(i18n("input.email"), username -> {
                            if (requiresEmailAsUsername()) {
                                return username.contains("@");
                            } else {
                                return true;
                            }
                        }));
                setValidateWhileTextChanged(txtUsername, true);
                txtUsername.setOnAction(e -> onAction.run());
                add(txtUsername, 1, rowIndex);

                rowIndex++;
            }

            if (factory.getLoginType().requiresPassword) {
                Label lblPassword = new Label(i18n("account.password"));
                setHalignment(lblPassword, HPos.LEFT);
                add(lblPassword, 0, rowIndex);

                txtPassword = new JFXPasswordField();
                txtPassword.setValidators(new RequiredValidator());
                setValidateWhileTextChanged(txtPassword, true);
                txtPassword.setOnAction(e -> onAction.run());
                add(txtPassword, 1, rowIndex);

                rowIndex++;
            }

            if (factory instanceof OfflineAccountFactory) {
                txtUsername.setPromptText(i18n("account.methods.offline.name.special_characters"));
                FXUtils.installFastTooltip(txtUsername, i18n("account.methods.offline.name.special_characters"));

                JFXHyperlink purchaseLink = new JFXHyperlink(i18n("account.methods.microsoft.purchase"));
                purchaseLink.setExternalLink(YggdrasilService.PURCHASE_URL);
                HBox linkPane = new HBox(purchaseLink);
                GridPane.setColumnSpan(linkPane, 2);
                add(linkPane, 0, rowIndex);

                rowIndex++;

                HBox box = new HBox();
                MenuUpDownButton advancedButton = new MenuUpDownButton();
                box.getChildren().setAll(advancedButton);
                advancedButton.setText(i18n("settings.advanced"));
                GridPane.setColumnSpan(box, 2);
                add(box, 0, rowIndex);

                rowIndex++;

                Label lblUUID = new Label(i18n("account.methods.offline.uuid"));
                lblUUID.managedProperty().bind(advancedButton.selectedProperty());
                lblUUID.visibleProperty().bind(advancedButton.selectedProperty());
                setHalignment(lblUUID, HPos.LEFT);
                add(lblUUID, 0, rowIndex);

                txtUUID = new JFXTextField();
                txtUUID.managedProperty().bind(advancedButton.selectedProperty());
                txtUUID.visibleProperty().bind(advancedButton.selectedProperty());
                txtUUID.setValidators(new UUIDValidator());
                txtUUID.promptTextProperty().bind(BindingMapping.of(txtUsername.textProperty()).map(name -> OfflineAccountFactory.getUUIDFromUserName(name).toString()));
                txtUUID.setOnAction(e -> onAction.run());
                add(txtUUID, 1, rowIndex);

                rowIndex++;

                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.WARNING);
                hintPane.managedProperty().bind(advancedButton.selectedProperty());
                hintPane.visibleProperty().bind(advancedButton.selectedProperty());
                hintPane.setText(i18n("account.methods.offline.uuid.hint"));
                GridPane.setColumnSpan(hintPane, 2);
                add(hintPane, 0, rowIndex);

                rowIndex++;
            }

            valid = new BooleanBinding() {
                {
                    if (cboServers != null)
                        bind(cboServers.valueProperty());
                    if (txtUsername != null)
                        bind(txtUsername.textProperty());
                    if (txtPassword != null)
                        bind(txtPassword.textProperty());
                    if (txtUUID != null)
                        bind(txtUUID.textProperty());
                }

                @Override
                protected boolean computeValue() {
                    if (cboServers != null && cboServers.getValue() == null)
                        return false;
                    if (txtUsername != null && !txtUsername.validate())
                        return false;
                    if (txtPassword != null && !txtPassword.validate())
                        return false;
                    if (txtUUID != null && !txtUUID.validate())
                        return false;
                    return true;
                }
            };
        }

        private boolean requiresEmailAsUsername() {
            if ((factory instanceof AuthlibInjectorAccountFactory) && this.server != null) {
                return !server.isNonEmailLogin();
            }
            if (factory instanceof BoundAuthlibInjectorAccountFactory bound) {
                return !bound.getServer().isNonEmailLogin();
            }
            return false;
        }

        /// Returns factory-specific UUID or authlib-injector data for account creation.
        public @Nullable Object getAdditionalData() {
            if (factory instanceof AuthlibInjectorAccountFactory) {
                return getAuthServer();
            } else if (factory instanceof OfflineAccountFactory) {
                UUID uuid = txtUUID == null ? null : StringUtils.isBlank(txtUUID.getText()) ? null : UUIDs.parse(txtUUID.getText());
                return new OfflineAccountFactory.AdditionalData(uuid, null);
            } else {
                return null;
            }
        }

        public @Nullable AuthlibInjectorServer getAuthServer() {
            return this.server;
        }

        public @Nullable String getUsername() {
            return txtUsername == null ? null : txtUsername.getText();
        }

        public @Nullable String getPassword() {
            return txtPassword == null ? null : txtPassword.getText();
        }

        public BooleanBinding validProperty() {
            return valid;
        }

        public void focus() {
            if (txtUsername != null) {
                txtUsername.requestFocus();
            }
        }
    }

    public static class DialogCharacterSelector extends JFXDialogLayout implements CharacterSelector {

        private final AdvancedListBox listBox = new AdvancedListBox();
        private final JFXButton cancel = new JFXButton();

        private final CountDownLatch latch = new CountDownLatch(1);
        /// Profile selected by the player, or `null` until a profile is chosen.
        private @Nullable GameProfile selectedProfile = null;

        public DialogCharacterSelector() {
            setStyle("-fx-padding: 8px;");

            cancel.setText(i18n("button.cancel"));
            cancel.setOnAction(e -> latch.countDown());
            cancel.getStyleClass().add("dialog-cancel");

            listBox.startCategory(i18n("account.choose").toUpperCase(Locale.ROOT));

            setBody(listBox);

            HBox hbox = new HBox();
            hbox.setAlignment(Pos.CENTER_RIGHT);
            hbox.getChildren().add(cancel);
            setActions(hbox);

            onEscPressed(this, cancel::fire);
        }

        @Override
        public GameProfile select(YggdrasilService service, List<GameProfile> profiles) throws NoSelectedCharacterException {
            Platform.runLater(() -> {
                for (GameProfile profile : profiles) {
                    Canvas portraitCanvas = new Canvas(32, 32);
                    TexturesLoader.bindAvatar(portraitCanvas, service, profile.getId());

                    IconedItem accountItem = new IconedItem(portraitCanvas, profile.getName());
                    FXUtils.onClicked(accountItem, () -> {
                        selectedProfile = profile;
                        latch.countDown();
                    });
                    listBox.add(accountItem);
                }
                Controllers.dialog(this);
            });

            try {
                latch.await();

                if (selectedProfile == null)
                    throw new NoSelectedCharacterException();

                return selectedProfile;
            } catch (InterruptedException ignored) {
                throw new NoSelectedCharacterException();
            } finally {
                Platform.runLater(() -> fireEvent(new DialogCloseEvent()));
            }
        }
    }

    /// Dialog that requires the user to reproduce the localized illegal-name warning.
    @NotNullByDefault
    private static final class InvalidUsernameConfirmationPane extends DialogPane implements DialogAware {
        /// Action that continues creating the account after a valid confirmation.
        private final Runnable confirm;

        /// Action that restores the account form when confirmation is cancelled.
        private final Runnable cancel;

        /// Input field containing the user's acknowledgement.
        private final JFXTextField input;

        /// Punctuation-free localized acknowledgement displayed above the input field.
        private final String expectedText;

        /// Creates a confirmation dialog and wires its accept and cancel actions.
        private InvalidUsernameConfirmationPane(Runnable confirm, Runnable cancel) {
            this.confirm = confirm;
            this.cancel = cancel;
            expectedText = replacePunctuationWithSpaces(i18n(INVALID_USERNAME_CONFIRMATION_KEY));

            setTitle(i18n("message.warning"));

            Label warning = new Label(i18n("account.methods.offline.name.invalid"));
            warning.setWrapText(true);
            warning.setMaxWidth(Double.MAX_VALUE);

            Label instruction = new Label(i18n("account.methods.offline.name.invalid.confirmation.prompt"));
            instruction.setWrapText(true);
            instruction.setMaxWidth(Double.MAX_VALUE);

            TextArea confirmationText = new TextArea(expectedText);
            confirmationText.setEditable(false);
            confirmationText.setWrapText(true);
            confirmationText.setPrefRowCount(3);
            confirmationText.setMinHeight(Region.USE_PREF_SIZE);
            confirmationText.setMaxWidth(Double.MAX_VALUE);
            confirmationText.setFocusTraversable(true);

            input = new JFXTextField();
            input.setPromptText(i18n("account.methods.offline.name.invalid.confirmation.input"));
            input.setMaxWidth(Double.MAX_VALUE);
            input.textProperty().addListener((observable, oldValue, newValue) ->
                    setValid(newValue != null && matchesConfirmation(newValue, expectedText)));
            input.setOnAction(event -> {
                if (isValid()) {
                    onAccept();
                }
            });

            VBox content = new VBox(10, warning, instruction, confirmationText, input);
            content.setFillWidth(true);
            setBody(content);
            setValid(false);
        }

        /// Focuses the acknowledgement input when the confirmation dialog opens.
        @Override
        public void onDialogShown() {
            input.requestFocus();
        }

        /// Closes the confirmation dialog and continues account creation.
        @Override
        protected void onAccept() {
            super.onAccept();
            confirm.run();
        }

        /// Closes the confirmation dialog and restores the account form.
        @Override
        protected void onCancel() {
            super.onCancel();
            cancel.run();
        }
    }

    @Override
    public void onDialogShown() {
        if (detailsPane instanceof AccountDetailsInputPane) {
            ((AccountDetailsInputPane) detailsPane).focus();
        }
    }

    private static class UUIDValidator extends ValidatorBase {

        public UUIDValidator() {
            this(i18n("account.methods.offline.uuid.malformed"));
        }

        public UUIDValidator(@NamedArg("message") String message) {
            super(message);
        }

        @Override
        protected void eval() {
            if (srcControl.get() instanceof TextInputControl) {
                evalTextInputField();
            }
        }

        private void evalTextInputField() {
            TextInputControl textField = ((TextInputControl) srcControl.get());
            if (StringUtils.isBlank(textField.getText())) {
                hasErrors.set(false);
                return;
            }

            try {
                UUIDs.parse(textField.getText());
                hasErrors.set(false);
            } catch (IllegalArgumentException ignored) {
                hasErrors.set(true);
            }
        }
    }

    private static final String MICROSOFT_ACCOUNT_EDIT_PROFILE_URL = "https://support.microsoft.com/account-billing/837badbc-999e-54d2-2617-d19206b9540a";
}
