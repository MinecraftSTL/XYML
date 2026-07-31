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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.setting.Accounts;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchCommand;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchScriptExportCommand;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/// Captures stable launch identities on the EDT and delegates to application-owned launch commands.
///
/// The supplied process-launch command must honor [LaunchRequest#testMode()]. Script export deliberately uses
/// the ordinary request because script generation is itself a preparation-only workflow.
@NotNullByDefault
public final class CommandInstanceMaintenanceLaunchActions implements InstanceMaintenanceLaunchActions {
    /// Produces an immutable ordinary launch request from the current account and fixed instance.
    private final Supplier<LaunchRequest> requestSupplier;

    /// Application-owned process launch command sharing the global single-flight launch boundary.
    private final HomeLaunchCommand launchCommand;

    /// Application-owned script export command sharing the normal launch preparation chain.
    private final HomeLaunchScriptExportCommand exportCommand;

    /// Creates production actions for one fixed repository instance.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId stable fixed instance identifier
    /// @param launchCommand application-owned process launch command
    /// @param exportCommand application-owned script export command
    public CommandInstanceMaintenanceLaunchActions(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            HomeLaunchCommand launchCommand,
            HomeLaunchScriptExportCommand exportCommand) {
        XYMLGameRepository capturedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID capturedInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.requestSupplier = () -> captureRequest(capturedRepository, capturedInstanceId);
        this.launchCommand = Objects.requireNonNull(launchCommand, "launchCommand");
        this.exportCommand = Objects.requireNonNull(exportCommand, "exportCommand");
    }

    /// Creates actions around an explicit request source for deterministic tests.
    ///
    /// @param requestSupplier ordinary request source called on the EDT for each user action
    /// @param launchCommand test-aware process launch command
    /// @param exportCommand script export command
    CommandInstanceMaintenanceLaunchActions(
            Supplier<LaunchRequest> requestSupplier,
            HomeLaunchCommand launchCommand,
            HomeLaunchScriptExportCommand exportCommand) {
        this.requestSupplier = Objects.requireNonNull(requestSupplier, "requestSupplier");
        this.launchCommand = Objects.requireNonNull(launchCommand, "launchCommand");
        this.exportCommand = Objects.requireNonNull(exportCommand, "exportCommand");
    }

    /// Captures the current identities and marks only the process request as test mode.
    ///
    /// @return exact observable test launch session
    @Override
    public LaunchSession testLaunch() {
        EdtDispatcher.requireEventDispatchThread();
        LaunchRequest request = Objects.requireNonNull(requestSupplier.get(), "requestSupplier returned null");
        return Objects.requireNonNull(
                launchCommand.launch(LaunchRequest.test(
                        request.accountId(),
                        request.gameDirectoryId(),
                        request.instanceId())),
                "launchCommand returned null");
    }

    /// Captures the current identities and delegates one ordinary script export.
    ///
    /// @param scriptFile selected local destination
    /// @return completion yielding the exact generated script path
    @Override
    public CompletionStage<Path> exportLaunchScript(Path scriptFile) {
        EdtDispatcher.requireEventDispatchThread();
        LaunchRequest request = Objects.requireNonNull(requestSupplier.get(), "requestSupplier returned null");
        return Objects.requireNonNull(
                exportCommand.export(request, Objects.requireNonNull(scriptFile, "scriptFile")),
                "exportCommand returned null");
    }

    /// Captures the selected account and fixed repository identities while launcher stores remain EDT-confined.
    ///
    /// @param repository repository containing the fixed instance
    /// @param instanceId fixed instance identifier
    /// @return immutable ordinary launch request
    private static LaunchRequest captureRequest(XYMLGameRepository repository, GameInstanceID instanceId) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Account account = Accounts.getSelectedAccount();
        if (account == null) {
            throw new IllegalStateException("No account is selected");
        }
        return new LaunchRequest(
                account.getAccountID().toString(),
                repository.getGameDirectory().getId().toString(),
                instanceId);
    }

    /// Rejects missing stable identities without rewriting them.
    ///
    /// @param value candidate identifier
    /// @param name diagnostic field name
    /// @return exact non-blank identifier
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
