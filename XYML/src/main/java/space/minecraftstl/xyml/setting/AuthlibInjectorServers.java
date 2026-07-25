/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonParseException;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.gson.TolerableValidationException;
import space.minecraftstl.xyml.util.gson.Validation;
import space.minecraftstl.xyml.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.setting.SettingsManager.getAuthlibInjectorServers;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Imports authlib-injector server URLs from the legacy adjacent configuration file on first launch.
@JsonSerializable
@NotNullByDefault
public final class AuthlibInjectorServers implements Validation {

    /// Legacy adjacent configuration filename.
    public static final String CONFIG_FILENAME = "authlib-injectors.json";

    /// Servers discovered from the legacy configuration during this process.
    private static final Set<AuthlibInjectorServer> servers = new CopyOnWriteArraySet<>();

    /// Returns the live thread-safe set of servers discovered during initialization.
    ///
    /// @return mutable discovered-server set
    public static Set<AuthlibInjectorServer> getServers() {
        return servers;
    }

    /// Legacy URLs as deserialized before validation; the list or an element may be `null` in malformed JSON.
    private final @Nullable List<@Nullable String> urls;

    /// Creates a legacy configuration value for Gson deserialization.
    ///
    /// @param urls configured server URLs, or `null` for malformed input rejected by [#validate()]
    private AuthlibInjectorServers(@Nullable List<@Nullable String> urls) {
        this.urls = urls;
    }

    /// Rejects a missing URL list before the configuration is consumed.
    @Override
    public void validate() throws JsonParseException, TolerableValidationException {
        if (this.urls == null) {
            throw new JsonParseException("authlib-injectors.json -> urls cannot be null.");
        }
    }

    /// Imports the adjacent legacy configuration into launcher settings for a newly created profile.
    public static void init() {
        Path configLocation;
        @Nullable Path jarPath = JarUtils.thisJarPath();
        @Nullable Path jarParent = jarPath != null ? jarPath.getParent() : null;
        if (jarPath != null && jarParent != null && Files.isRegularFile(jarPath) && Files.isWritable(jarPath)) {
            configLocation = jarParent.resolve(CONFIG_FILENAME);
        } else {
            configLocation = Paths.get(CONFIG_FILENAME);
        }

        if (SettingsManager.isNewlyCreated() && Files.exists(configLocation)) {
            @Nullable AuthlibInjectorServers configInstance;
            try {
                configInstance = JsonUtils.fromJsonFile(configLocation, AuthlibInjectorServers.class);
            } catch (IOException | JsonParseException e) {
                LOG.warning("Malformed authlib-injectors.json", e);
                return;
            }

            if (configInstance == null) {
                LOG.warning("Malformed authlib-injectors.json: root value is null");
                return;
            }

            @Nullable List<@Nullable String> configuredUrls = configInstance.urls;
            if (configuredUrls == null) {
                LOG.warning("Malformed authlib-injectors.json: urls is null");
                return;
            }

            if (!configuredUrls.isEmpty()) {
                settings().preferredLoginTypeProperty().set(Accounts.getLoginType(Accounts.FACTORY_AUTHLIB_INJECTOR));
                for (@Nullable String configuredUrl : configuredUrls) {
                    if (configuredUrl == null) {
                        LOG.warning("Malformed authlib-injectors.json: urls contains null");
                        continue;
                    }
                    String url = configuredUrl;
                    Task.supplyAsync(Schedulers.io(), () -> AuthlibInjectorServer.locateServer(url))
                            .thenAcceptAsync(Schedulers.ui(), server -> {
                                getAuthlibInjectorServers().add(server);
                                servers.add(server);
                            })
                            .start();
                }
            }
        }
    }
}
