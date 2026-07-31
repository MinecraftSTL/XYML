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
package space.minecraftstl.xyml.setting;

import com.google.gson.JsonObject;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.auth.*;
import space.minecraftstl.xyml.auth.authlibinjector.*;
import space.minecraftstl.xyml.auth.microsoft.MicrosoftAccount;
import space.minecraftstl.xyml.auth.microsoft.MicrosoftAccountFactory;
import space.minecraftstl.xyml.auth.microsoft.MicrosoftService;
import space.minecraftstl.xyml.auth.offline.OfflineAccount;
import space.minecraftstl.xyml.auth.offline.OfflineAccountFactory;
import space.minecraftstl.xyml.auth.yggdrasil.RemoteAuthenticationException;
import space.minecraftstl.xyml.game.OAuthServer;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.collection.ObservableArrayList;
import space.minecraftstl.xyml.observable.collection.ObservableList;
import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.skin.InvalidSkinException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.setting.SettingsManager.getAccountMetadataRecords;
import static space.minecraftstl.xyml.setting.SettingsManager.getAuthlibInjectorServers;
import static space.minecraftstl.xyml.setting.SettingsManager.getUserAccountMetadataRecords;
import static space.minecraftstl.xyml.setting.SettingsManager.userSettings;
import static space.minecraftstl.xyml.util.Lang.immutableListOf;
import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Owns launcher account factories, persisted account state, and account-related error localization.
///
/// @author huangyuhui
@NotNullByDefault
public final class Accounts {
    /// Prevents instantiation.
    private Accounts() {
    }

    /// Supplies the bundled authlib-injector artifact to account factories.
    private static final AuthlibInjectorArtifactProvider AUTHLIB_INJECTOR_DOWNLOADER = createAuthlibInjectorArtifactProvider();

    /// Receives Microsoft OAuth callbacks on the local callback server.
    public static final OAuthServer.Factory OAUTH_CALLBACK = new OAuthServer.Factory();

    /// Creates offline accounts.
    public static final OfflineAccountFactory FACTORY_OFFLINE = new OfflineAccountFactory(AUTHLIB_INJECTOR_DOWNLOADER);

    /// Creates authlib-injector accounts against configured authentication servers.
    public static final AuthlibInjectorAccountFactory FACTORY_AUTHLIB_INJECTOR = new AuthlibInjectorAccountFactory(AUTHLIB_INJECTOR_DOWNLOADER, Accounts::getOrCreateAuthlibInjectorServer);

    /// Creates Microsoft accounts through the local OAuth callback server.
    public static final MicrosoftAccountFactory FACTORY_MICROSOFT = new MicrosoftAccountFactory(new MicrosoftService(OAUTH_CALLBACK));

    /// Immutable account factories in launcher display order.
    public static final @Unmodifiable List<AccountFactory<?>> FACTORIES =
            immutableListOf(FACTORY_OFFLINE, FACTORY_MICROSOFT, FACTORY_AUTHLIB_INJECTOR);

    /// Login-type identifiers indexed to their storage factories.
    private static final Map<String, AccountFactory<?>> type2factory = new HashMap<>();

    /// Storage login-type identifiers indexed by their factories.
    private static final Map<AccountFactory<?>, String> factory2type = new HashMap<>();

    static {
        type2factory.put("offline", FACTORY_OFFLINE);
        type2factory.put("authlibInjector", FACTORY_AUTHLIB_INJECTOR);
        type2factory.put("microsoft", FACTORY_MICROSOFT);

        type2factory.forEach((type, factory) -> factory2type.put(factory, type));
    }

    /// Returns the persisted login-type identifier for an account factory.
    ///
    /// @param factory factory to identify
    /// @return persisted login-type identifier
    /// @throws IllegalArgumentException if the factory is not recognized
    public static String getLoginType(AccountFactory<?> factory) {
        @Nullable String type = factory2type.get(factory);
        if (type != null) return type;

        if (factory instanceof BoundAuthlibInjectorAccountFactory) {
            return factory2type.get(FACTORY_AUTHLIB_INJECTOR);
        }

        throw new IllegalArgumentException("Unrecognized account factory");
    }

    /// Returns the account factory registered for a persisted login-type identifier.
    ///
    /// @param loginType persisted login-type identifier
    /// @return registered account factory
    /// @throws IllegalArgumentException if the identifier is not recognized
    public static AccountFactory<?> getAccountFactory(String loginType) {
        return Optional.ofNullable(type2factory.get(loginType))
                .orElseThrow(() -> new IllegalArgumentException("Unrecognized login type"));
    }

    /// Creates a factory bound to one authlib-injector server.
    ///
    /// @param server authentication server to bind
    /// @return bound account factory
    public static BoundAuthlibInjectorAccountFactory getAccountFactoryByAuthlibInjectorServer(AuthlibInjectorServer server) {
        return new BoundAuthlibInjectorAccountFactory(AUTHLIB_INJECTOR_DOWNLOADER, server);
    }
    // ====

    /// Returns the storage factory matching an instantiated account type.
    ///
    /// @param account account to inspect
    /// @return matching account factory
    /// @throws IllegalArgumentException if the account implementation is not recognized
    public static AccountFactory<?> getAccountFactory(Account account) {
        if (account instanceof OfflineAccount)
            return FACTORY_OFFLINE;
        else if (account instanceof AuthlibInjectorAccount)
            return FACTORY_AUTHLIB_INJECTOR;
        else if (account instanceof MicrosoftAccount)
            return FACTORY_MICROSOFT;
        else
            throw new IllegalArgumentException("Failed to determine account type: " + account);
    }

    /// Toolkit-neutral account state used by all new consumers.
    private static final ObservableArrayList<Account> accountValues = new ObservableArrayList<>(
            account -> List.of(account.changes()));

    /// Toolkit-neutral selected-account property used by all consumers.
    private static final SimpleObjectProperty<@Nullable Account> selectedAccountValue = new SimpleObjectProperty<>(
                    Accounts.class,
                    "selectedAccount");

    /// True if [#init()] has not been called.
    private static boolean initialized = false;

    /// One-shot subscription that enables offline accounts after the first Microsoft account is added.
    private static @Nullable Subscription offlineAccountSubscription;

    /// Serializes public metadata and private credentials for one account.
    ///
    /// @param account account to serialize
    /// @return separated account data
    private static SerializedAccount serializeAccount(Account account) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", getLoginType(getAccountFactory(account)));
        account.writeMetadata(metadata);
        JsonObject privateData = new JsonObject();
        account.writePrivateData(privateData);
        return new SerializedAccount(metadata, privateData);
    }

    /// Returns account IDs from metadata records.
    ///
    /// @param first the first metadata record list
    /// @param second the second metadata record list
    /// @return account IDs found in the metadata records
    private static List<AccountID> getAccountIDs(List<JsonObject> first, List<JsonObject> second) {
        ArrayList<AccountID> accountIDs = new ArrayList<>(first.size() + second.size());
        addAccountIDs(accountIDs, first);
        addAccountIDs(accountIDs, second);
        return accountIDs;
    }

    /// Adds account IDs from metadata records to a target list.
    ///
    /// @param accountIDs the target account ID list
    /// @param metadataRecords the metadata records to read
    private static void addAccountIDs(List<AccountID> accountIDs, List<JsonObject> metadataRecords) {
        for (JsonObject metadata : metadataRecords) {
            @Nullable AccountID accountID = Account.getAccountID(metadata);
            if (accountID != null && !accountIDs.contains(accountID)) {
                accountIDs.add(accountID);
            }
        }
    }

    /// Rebuilds persisted account records after an initialized account-list change.
    private static void updateAccountMetadataRecords() {
        // don't update the underlying account records before data loading is completed
        // otherwise it might cause data loss
        if (!initialized)
            return;
        ArrayList<JsonObject> globalMetadata = new ArrayList<>();
        LinkedHashMap<AccountID, JsonObject> globalPrivateData = new LinkedHashMap<>();
        ArrayList<JsonObject> portableMetadata = new ArrayList<>();
        LinkedHashMap<AccountID, JsonObject> portablePrivateData = new LinkedHashMap<>();

        for (Account account : accountValues) {
            SerializedAccount serialized = serializeAccount(account);
            if (account.isPortable()) {
                portableMetadata.add(serialized.metadata());
                if (!serialized.privateData().isEmpty()) {
                    portablePrivateData.put(account.getAccountID(), serialized.privateData());
                }
            } else {
                globalMetadata.add(serialized.metadata());
                if (!serialized.privateData().isEmpty()) {
                    globalPrivateData.put(account.getAccountID(), serialized.privateData());
                }
            }
        }

        List<AccountID> retainedAccountIDs = getAccountIDs(globalMetadata, portableMetadata);
        if (!SettingsManager.isUserGameAccountsReadOnly())
            SettingsManager.updateUserGameAccounts(globalMetadata, globalPrivateData, retainedAccountIDs);
        if (!SettingsManager.isGameAccountsReadOnly())
            SettingsManager.updateGameAccounts(portableMetadata, portablePrivateData, retainedAccountIDs);
    }

    /// Returns whether the account metadata and credential files selected by the portability flag are read-only.
    ///
    /// @param portable whether the account is stored in the local account file
    public static boolean isAccountFilesReadOnly(boolean portable) {
        return portable ? SettingsManager.isGameAccountsReadOnly() : SettingsManager.isUserGameAccountsReadOnly();
    }

    /// Returns whether the files containing the given account are read-only.
    ///
    /// @param account account whose backing files are inspected
    /// @return whether either required file is read-only
    public static boolean isAccountFilesReadOnly(Account account) {
        return isAccountFilesReadOnly(account.isPortable());
    }

    /// Returns whether the given account may be removed from its current account files.
    ///
    /// @param account account to test
    /// @return whether removal can be persisted
    public static boolean canRemoveAccount(Account account) {
        return !isAccountFilesReadOnly(account);
    }

    /// Returns whether the given account may be moved between local and user account files.
    ///
    /// @param account account to test
    /// @return whether both account stores are writable
    public static boolean canMoveAccount(Account account) {
        return !SettingsManager.isGameAccountsReadOnly() && !SettingsManager.isUserGameAccountsReadOnly();
    }

    /// Backs up and overwrites the account metadata and credential files selected by the portability flag.
    ///
    /// @param portable whether the target account files are local
    /// @throws IOException if saving either file fails
    public static void forceOverwriteAccountFiles(boolean portable) throws IOException {
        if (portable) {
            SettingsManager.forceOverwriteGameAccounts();
        } else {
            SettingsManager.forceOverwriteUserGameAccounts();
        }
    }

    /// Backs up and overwrites the account files containing the given account.
    ///
    /// @param account account selecting the target account store
    /// @throws IOException if saving either file fails
    public static void forceOverwriteAccountFiles(Account account) throws IOException {
        forceOverwriteAccountFiles(account.isPortable());
    }

    /// Backs up and overwrites both local and user account metadata and credential files.
    ///
    /// @throws IOException if saving either file fails
    public static void forceOverwriteAccountFiles() throws IOException {
        if (SettingsManager.isGameAccountsReadOnly()) {
            SettingsManager.forceOverwriteGameAccounts();
        }
        if (SettingsManager.isUserGameAccountsReadOnly()) {
            SettingsManager.forceOverwriteUserGameAccounts();
        }
    }

    /// Deserializes one persisted account record, logging and skipping invalid records.
    ///
    /// @param record persisted public metadata
    /// @param portable whether private data is stored in the local account store
    /// @return the account, or `null` when the record cannot be loaded
    private static @Nullable Account parseAccount(JsonObject record, boolean portable) {
        @Nullable AccountFactory<?> factory = type2factory.get(JsonUtils.getString(record, "type"));
        if (factory == null) {
            LOG.warning("Unrecognized account type: " + describeAccountRecord(record));
            return null;
        }

        try {
            AccountID accountID = Account.readAccountID(record);
            return factory.fromStorage(record, SettingsManager.getAccountPrivateData(accountID, portable));
        } catch (Exception e) {
            LOG.warning("Failed to load account: " + describeAccountRecord(record), e);
            return null;
        }
    }

    /// Serialized account metadata and private data.
    ///
    /// @param metadata public metadata stored in `accounts.json`
    /// @param privateData private account data stored in account private data
    @NotNullByDefault
    private record SerializedAccount(JsonObject metadata, JsonObject privateData) {
    }

    /// Returns a safe account record description for diagnostics.
    private static String describeAccountRecord(JsonObject record) {
        @Nullable AccountID accountID = Account.getAccountID(record);
        if (accountID != null) {
            return accountID.toString();
        }

        @Nullable String type = JsonUtils.getString(record, "type");
        return type != null ? "{type=" + type + "}" : "<unknown>";
    }

    /// Called when it's ready to load accounts from [SettingsManager#settings()].
    public static void init() {
        if (initialized)
            throw new IllegalStateException("Already initialized");

        // load accounts
        @Nullable Account selected = null;
        Set<AccountID> loadedAccountIDs = new HashSet<>();
        for (JsonObject record : getAccountMetadataRecords()) {
            @Nullable Account account = parseAccount(record, true);
            if (account != null && loadedAccountIDs.add(account.getAccountID())) {
                account.setPortable(true);
                accountValues.add(account);
                if (JsonUtils.getBoolean(record, "selected", false)) {
                    selected = account;
                }
            } else if (account != null) {
                LOG.warning("Skipping duplicate account ID: " + account.getAccountID());
            }
        }

        for (JsonObject record : getUserAccountMetadataRecords()) {
            @Nullable Account account = parseAccount(record, false);
            if (account != null && loadedAccountIDs.add(account.getAccountID())) {
                accountValues.add(account);
            } else if (account != null) {
                LOG.warning("Skipping duplicate account ID: " + account.getAccountID());
            }
        }

        @Nullable AccountID selectedAccountID = settings().selectedAccountProperty().get();
        if (selected == null && selectedAccountID != null) {
            for (Account account : accountValues) {
                if (account.getAccountID().equals(selectedAccountID)) {
                    selected = account;
                    break;
                }
            }
        }

        if (selected == null && !accountValues.isEmpty()) {
            selected = accountValues.get(0);
        }

        if (!SettingsManager.isUserSettingsReadOnly()
                && !SettingsManager.userSettings().enableOfflineAccountProperty().get())
            for (Account account : accountValues) {
                if (account instanceof MicrosoftAccount) {
                    UserSettings userSettings = userSettings();
                    userSettings.enableOfflineAccountProperty().set(true);
                    break;
                }
            }

        if (!SettingsManager.isUserSettingsReadOnly()
                && !SettingsManager.userSettings().enableOfflineAccountProperty().get())
            offlineAccountSubscription = accountValues.subscribe(change -> {
                if (change.kind() != space.minecraftstl.xyml.observable.collection.ListChange.Kind.ADD) {
                    return;
                }
                for (Account account : change.currentItems()) {
                    if (account instanceof MicrosoftAccount) {
                        UserSettings userSettings = userSettings();
                        userSettings.enableOfflineAccountProperty().set(true);
                        @Nullable Subscription subscription = offlineAccountSubscription;
                        offlineAccountSubscription = null;
                        if (subscription != null) {
                            subscription.unsubscribe();
                        }
                        return;
                    }
                }
            });

        selectedAccountValue.setValue(selected);

        accountValues.subscribe(change -> {
            // this method first checks whether the current selection is valid
            // if it's valid, the underlying account records will be updated
            // otherwise, the first account will be selected as an alternative(or null if accounts is empty)
            @Nullable Account account = selectedAccountValue.getValue();
            if (accountValues.isEmpty()) {
                if (account == null) {
                    // valid
                } else {
                    // the previously selected account is gone, we can only set it to null here
                    selectedAccountValue.setValue(null);
                }
            } else {
                if (accountValues.contains(account)) {
                    // valid
                } else {
                    // the previously selected account is gone
                    selectedAccountValue.setValue(accountValues.get(0));
                }
            }
            updateAccountMetadataRecords();
        });
        selectedAccountValue.subscribe(change -> {
            @Nullable Account account = selectedAccountValue.getValue();
            if (account != null)
                settings().selectedAccountProperty().set(account.getAccountID());
            else
                settings().selectedAccountProperty().set(null);
        });

        initialized = true;

        getAuthlibInjectorServers().subscribe(change -> removeDanglingAuthlibInjectorAccounts());

        if (selected != null) {
            Account finalSelected = selected;
            Schedulers.io().execute(() -> {
                try {
                    finalSelected.logIn();
                } catch (Throwable e) {
                    LOG.warning("Failed to log " + finalSelected + " in", e);
                }
            });
        }

        for (AuthlibInjectorServer server : getAuthlibInjectorServers()) {
            if (selected instanceof AuthlibInjectorAccount && ((AuthlibInjectorAccount) selected).getServer() == server)
                continue;
            Schedulers.io().execute(() -> {
                try {
                    server.fetchMetadataResponse();
                } catch (IOException e) {
                    LOG.warning("Failed to fetch authlib-injector server metadata: " + server, e);
                }
            });
        }
    }

    /// Returns the toolkit-neutral account list used as the single source of truth.
    ///
    /// @return live mutable account list
    public static ObservableList<Account> getAccounts() {
        return accountValues;
    }

    /// Returns the selected account from the toolkit-neutral state model.
    ///
    /// @return selected account, or `null` when no account is available
    public static @Nullable Account getSelectedAccount() {
        return selectedAccountValue.getValue();
    }

    /// Replaces the selected account in the toolkit-neutral state model.
    ///
    /// @param selectedAccount account to select, or `null` to clear the selection
    public static void setSelectedAccount(@Nullable Account selectedAccount) {
        Accounts.selectedAccountValue.setValue(selectedAccount);
    }

    /// Returns the toolkit-neutral selected-account property.
    ///
    /// @return live nullable selected-account property
    public static ObjectProperty<@Nullable Account> selectedAccountProperty() {
        return selectedAccountValue;
    }

    /// Creates the configured or bundled authlib-injector artifact provider.
    private static AuthlibInjectorArtifactProvider createAuthlibInjectorArtifactProvider() {
        @Nullable String authlibinjectorLocation = System.getProperty("xyml.authlibinjector.location");
        if (authlibinjectorLocation != null) {
            LOG.info("Using specified authlib-injector: " + authlibinjectorLocation);
            return new SimpleAuthlibInjectorArtifactProvider(Paths.get(authlibinjectorLocation));
        }

        @Nullable String authlibInjectorVersion = JarUtils.getAttribute("xyml.authlib-injector.version", null);
        if (authlibInjectorVersion == null)
            throw new AssertionError("Missing xyml.authlib-injector.version");

        String authlibInjectorFileName = "authlib-injector-" + authlibInjectorVersion + ".jar";
        @Nullable URL embeddedArtifact = Accounts.class.getResource("/assets/" + authlibInjectorFileName);
        return new AuthlibInjectorExtractor(embeddedArtifact,
                Metadata.DEPENDENCIES_DIRECTORY.resolve("universal").resolve(authlibInjectorFileName));
    }

    /// Returns an existing authentication server for a URL or adds a new one.
    ///
    /// @param url authlib-injector API URL
    /// @return matching configured server
    private static AuthlibInjectorServer getOrCreateAuthlibInjectorServer(String url) {
        return getAuthlibInjectorServers().stream()
                .filter(server -> url.equals(server.getUrl()))
                .findFirst()
                .orElseGet(() -> {
                    AuthlibInjectorServer server = new AuthlibInjectorServer(url);
                    getAuthlibInjectorServers().add(server);
                    return server;
                });
    }

    /// Removes writable authlib-injector accounts whose authentication server is no longer configured.
    private static void removeDanglingAuthlibInjectorAccounts() {
        accountValues.stream()
                .filter(AuthlibInjectorAccount.class::isInstance)
                .map(AuthlibInjectorAccount.class::cast)
                .filter(it -> !getAuthlibInjectorServers().contains(it.getServer()))
                .filter(Accounts::canRemoveAccount)
                .collect(toList())
                .forEach(accountValues::remove);
    }
    // ====

    /// Localization keys indexed by account factory.
    private static final Map<AccountFactory<?>, String> unlocalizedLoginTypeNames = mapOf(
            pair(Accounts.FACTORY_OFFLINE, "account.methods.offline"),
            pair(Accounts.FACTORY_AUTHLIB_INJECTOR, "account.methods.authlib_injector"),
            pair(Accounts.FACTORY_MICROSOFT, "account.methods.microsoft"));

    /// Returns the localized display name for an account factory.
    ///
    /// @param factory account factory to describe
    /// @return localized login-method name
    /// @throws IllegalArgumentException if the factory is not recognized
    public static String getLocalizedLoginTypeName(AccountFactory<?> factory) {
        return i18n(Optional.ofNullable(unlocalizedLoginTypeNames.get(factory))
                .orElseThrow(() -> new IllegalArgumentException("Unrecognized account factory")));
    }
    // ====

    /// Converts an account exception into a localized user-facing message when possible.
    ///
    /// @param exception account operation failure
    /// @return localized detail, or `null` when the underlying exception supplies no message
    public static @Nullable String localizeErrorMessage(Exception exception) {
        if (exception instanceof NoCharacterException) {
            return i18n("account.failed.no_character");
        } else if (exception instanceof ServerDisconnectException) {
            if (exception.getCause() instanceof SSLException) {
                if (exception.getCause().getMessage() != null && exception.getCause().getMessage().contains("Remote host terminated")) {
                    return i18n("account.failed.connect_authentication_server");
                }
                if (exception.getCause().getMessage() != null && (exception.getCause().getMessage().contains("No name matching") || exception.getCause().getMessage().contains("No subject alternative DNS name matching"))) {
                    return i18n("account.failed.dns");
                }
                return i18n("account.failed.ssl");
            } else {
                return i18n("account.failed.connect_authentication_server");
            }
        } else if (exception instanceof ServerResponseMalformedException) {
            return i18n("account.failed.server_response_malformed");
        } else if (exception instanceof RemoteAuthenticationException) {
            RemoteAuthenticationException remoteException = (RemoteAuthenticationException) exception;
            @Nullable String remoteMessage = remoteException.getRemoteMessage();
            if ("ForbiddenOperationException".equals(remoteException.getRemoteName()) && remoteMessage != null) {
                if (remoteMessage.contains("Invalid credentials")) {
                    return i18n("account.failed.invalid_credentials");
                } else if (remoteMessage.contains("Invalid token")) {
                    return i18n("account.failed.invalid_token");
                } else if (remoteMessage.contains("Invalid username or password")) {
                    return i18n("account.failed.invalid_password");
                } else {
                    return remoteMessage;
                }
            } else if ("ResourceException".equals(remoteException.getRemoteName()) && remoteMessage != null) {
                if (remoteMessage.contains("The requested resource is no longer available")) {
                    return i18n("account.failed.migration");
                } else {
                    return remoteMessage;
                }
            }
            return exception.getMessage();
        } else if (exception instanceof AuthlibInjectorDownloadException) {
            return i18n("account.failed.injector_download_failure");
        } else if (exception instanceof CharacterDeletedException) {
            return i18n("account.failed.character_deleted");
        } else if (exception instanceof InvalidSkinException) {
            return i18n("account.skin.invalid_skin");
        } else if (exception instanceof MicrosoftService.XboxAuthorizationException) {
            long errorCode = ((MicrosoftService.XboxAuthorizationException) exception).getErrorCode();
            if (errorCode == MicrosoftService.XboxAuthorizationException.ADD_FAMILY) {
                return i18n("account.methods.microsoft.error.add_family");
            } else if (errorCode == MicrosoftService.XboxAuthorizationException.COUNTRY_UNAVAILABLE) {
                return i18n("account.methods.microsoft.error.country_unavailable");
            } else if (errorCode == MicrosoftService.XboxAuthorizationException.MISSING_XBOX_ACCOUNT) {
                return i18n("account.methods.microsoft.error.missing_xbox_account");
            } else if (errorCode == MicrosoftService.XboxAuthorizationException.BANNED) {
                return i18n("account.methods.microsoft.error.banned");
            } else {
                return i18n("account.methods.microsoft.error.unknown", errorCode);
            }
        } else if (exception instanceof MicrosoftService.XBox400Exception) {
            return i18n("account.methods.microsoft.error.wrong_verify_method");
        } else if (exception instanceof MicrosoftService.MinecraftJavaEditionLicenseNotFoundException) {
            return i18n("account.methods.microsoft.error.no_license");
        } else if (exception instanceof MicrosoftService.MinecraftJavaEditionProfileNotFoundException) {
            return i18n("account.methods.microsoft.error.no_character");
        } else if (exception instanceof MicrosoftService.NoXuiException) {
            return i18n("account.methods.microsoft.error.add_family");
        } else if (exception instanceof OAuthServer.MicrosoftAuthenticationNotSupportedException) {
            return i18n("account.methods.microsoft.snapshot");
        } else if (exception instanceof OAuthAccount.WrongAccountException) {
            return i18n("account.failed.wrong_account");
        } else if (exception.getClass() == AuthenticationException.class) {
            return exception.getLocalizedMessage();
        } else {
            return exception.getClass().getName() + ": " + exception.getLocalizedMessage();
        }
    }
}
