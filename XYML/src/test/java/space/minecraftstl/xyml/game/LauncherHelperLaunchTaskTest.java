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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.launch.LaunchInteractionPrompt;
import space.minecraftstl.xyml.ui.swing.log.SwingGameLogWindow;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountReauthentication;
import space.minecraftstl.xyml.util.CircularArrayList;
import space.minecraftstl.xyml.util.Log4jLevel;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.ManagedProcess;
import space.minecraftstl.xyml.util.platform.windows.WinReg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the toolkit-neutral managed-process completion boundary extracted from [LauncherHelper].
@NotNullByDefault
final class LauncherHelperLaunchTaskTest {
    /// Removes process registrations left by another test before each assertion sequence.
    @BeforeEach
    void clearProcessesBeforeTest() {
        LauncherHelper.stopManagedProcesses();
    }

    /// Releases every test process and clears the static registration queue.
    @AfterEach
    void clearProcessesAfterTest() {
        LauncherHelper.stopManagedProcesses();
    }

    /// Verifies decoration is lazy, preserves the exact process, and only registers process ownership.
    @Test
    void decorationDoesNotStartTaskAndReturnsRegisteredProcess() throws Exception {
        RecordingProcess rawProcess = new RecordingProcess();
        ManagedProcess managedProcess = new ManagedProcess(rawProcess, List.of("java", "test.Main"));
        RecordingLaunchTask sourceTask = new RecordingLaunchTask(managedProcess);

        Task<ManagedProcess> decoratedTask = LauncherHelper.decorateGameLaunchTask(sourceTask);

        assertEquals(0, sourceTask.executionCount());
        assertEquals(0, LauncherHelper.countMangedProcesses());
        assertSame(managedProcess, decoratedTask.run());
        assertEquals(1, sourceTask.executionCount());
        assertEquals(1, LauncherHelper.countMangedProcesses());
    }

    /// Confirms that a production session completes at process creation without presentation readiness.
    @Test
    void productionProgressCompletesAtProcessCreation() throws Exception {
        Object result = new Object();
        Task<Object> task = LauncherHelper.applyLaunchProgressPolicy(
                Task.supplyAsync(Runnable::run, () -> result));

        assertSame(result, task.run());
    }

    /// Ensures synchronous Swing publication updates shared crash history before the mutable batch is reused.
    @Test
    void swingLogBatchIsRetainedBeforePublisherReturns() {
        RecordingProcess rawProcess = new RecordingProcess();
        ManagedProcess managedProcess = new ManagedProcess(
                rawProcess,
                List.of("java", "test.Main"));
        CircularArrayList<Log> retained = new CircularArrayList<>(11);
        SwingGameLogWindow window = new SwingGameLogWindow(
                managedProcess,
                retained,
                10,
                ignored -> { });
        Log first = new Log("first", Log4jLevel.INFO);
        Log second = new Log("second", Log4jLevel.ERROR);
        List<Log> reusableBatch = new ArrayList<>(List.of(first, second));
        try {
            LauncherHelper.publishSwingLogBatch(window, reusableBatch);

            assertTrue(reusableBatch.isEmpty());
            assertEquals(2, retained.size());
            assertSame(first, retained.get(0));
            assertSame(second, retained.get(1));
        } finally {
            window.close();
            rawProcess.destroy();
        }
    }

    /// Passes the serialized stable account ID to reauthentication and preserves its exact AuthInfo result.
    @Test
    void reauthenticationUsesStableAccountIdAndExactResult() throws Exception {
        AccountID accountId = new AccountID(UUID.fromString("00000000-0000-0000-0000-000000000123"));
        RecordingAccount account = new RecordingAccount(accountId);
        AuthInfo expected = authInfo("Recovered");
        RecordingAccountReauthentication reauthentication =
                new RecordingAccountReauthentication(expected);

        Task<AuthInfo> task = LauncherHelper.reauthenticateForLaunch(account, reauthentication);

        assertTrue(task.executor().test());
        assertSame(expected, task.getResult());
        assertEquals(accountId.toString(), reauthentication.observedAccountId());
    }

    /// Maps explicit offline recovery to the account fallback without requesting retry.
    @Test
    void authenticationRecoveryMapsOfflineSelection() throws Exception {
        RecordingAccount account = new RecordingAccount(AccountID.generate());
        AtomicInteger retryRequests = new AtomicInteger();

        Task<AuthInfo> task = LauncherHelper.resolveProductionAuthenticationRecovery(
                account,
                LaunchInteractionPrompt.Action.PLAY_OFFLINE,
                () -> {
                    retryRequests.incrementAndGet();
                    return Task.completed(authInfo("Retry"));
                });

        assertSame(account.offlineAuthInfo(), task.run());
        assertEquals(1, account.offlineCalls());
        assertEquals(0, retryRequests.get());
    }

    /// Maps explicit retry to the deferred complete login task without invoking offline fallback.
    @Test
    void authenticationRecoveryMapsRetrySelection() throws Exception {
        RecordingAccount account = new RecordingAccount(AccountID.generate());
        AuthInfo retryResult = authInfo("Retry");
        AtomicInteger retryRequests = new AtomicInteger();

        Task<AuthInfo> task = LauncherHelper.resolveProductionAuthenticationRecovery(
                account,
                LaunchInteractionPrompt.Action.RETRY_AUTHENTICATION,
                () -> {
                    retryRequests.incrementAndGet();
                    return Task.completed(retryResult);
                });

        assertTrue(task.executor().test());
        assertSame(retryResult, task.getResult());
        assertEquals(0, account.offlineCalls());
        assertEquals(1, retryRequests.get());
    }

    /// Maps cancellation to a failed stopped task and rejects unrelated prompt actions.
    @Test
    void authenticationRecoveryCancelsAndRejectsUnexpectedActions() {
        RecordingAccount account = new RecordingAccount(AccountID.generate());

        Task<AuthInfo> cancelled = LauncherHelper.resolveProductionAuthenticationRecovery(
                account,
                LaunchInteractionPrompt.Action.CANCEL,
                () -> Task.completed(authInfo("Retry")));

        assertFalse(cancelled.executor().test());
        assertEquals(Task.TaskState.FAILED, cancelled.getState());
        assertThrows(
                IllegalArgumentException.class,
                () -> LauncherHelper.resolveProductionAuthenticationRecovery(
                        account,
                        LaunchInteractionPrompt.Action.CONTINUE,
                        () -> Task.completed(authInfo("Retry"))));
    }

    /// Writes the DirectX preference once and preserves an existing per-executable choice.
    @Test
    void appliesHighPerformanceGpuPreferenceOnlyWhenMissing() {
        Path javaBinary = Path.of("runtime", "bin", "javaw.exe");
        String expectedPath = FileUtils.getAbsolutePath(javaBinary);
        RecordingWinReg emptyRegistry = new RecordingWinReg(null);

        LauncherHelper.applyHighPerformanceGpuPreference(emptyRegistry, javaBinary);

        assertEquals(1, emptyRegistry.setCalls.get());
        assertEquals(WinReg.HKEY.HKEY_CURRENT_USER, emptyRegistry.writtenRoot);
        assertEquals("Software\\Microsoft\\DirectX\\UserGpuPreferences", emptyRegistry.writtenKey);
        assertEquals(expectedPath, emptyRegistry.writtenValueName);
        assertEquals("GpuPreference=2;", emptyRegistry.writtenValue);

        RecordingWinReg configuredRegistry = new RecordingWinReg("GpuPreference=1;");
        LauncherHelper.applyHighPerformanceGpuPreference(configuredRegistry, javaBinary);
        assertEquals(0, configuredRegistry.setCalls.get());
    }

    /// Keeps registry availability and native query failures outside the launch-failure boundary.
    @Test
    void ignoresUnavailableOrFailingGpuPreferenceRegistry() {
        Path javaBinary = Path.of("runtime", "bin", "javaw.exe");
        RecordingWinReg failingRegistry = new RecordingWinReg(null);
        failingRegistry.queryFailure = new IllegalStateException("registry unavailable");

        assertDoesNotThrow(() -> LauncherHelper.applyHighPerformanceGpuPreference(null, javaBinary));
        assertDoesNotThrow(() -> LauncherHelper.applyHighPerformanceGpuPreference(failingRegistry, javaBinary));
        assertEquals(0, failingRegistry.setCalls.get());
    }

    /// Creates deterministic authentication data for account-boundary tests.
    ///
    /// @param username visible profile name
    /// @return immutable authentication fixture
    private static AuthInfo authInfo(String username) {
        return new AuthInfo(
                username,
                UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "token-" + username,
                AuthInfo.USER_TYPE_MSA,
                "{}");
    }

    /// In-memory registry fixture recording one optional string write.
    @NotNullByDefault
    private static final class RecordingWinReg extends WinReg {
        /// Existing queried value, or null when the preference is absent.
        private final @Nullable Object existingValue;

        /// Number of string-value writes.
        private final AtomicInteger setCalls = new AtomicInteger();

        /// Optional query failure used to verify launch isolation.
        private @Nullable RuntimeException queryFailure;

        /// Root supplied to the latest write.
        private @Nullable HKEY writtenRoot;

        /// Key supplied to the latest write.
        private @Nullable String writtenKey;

        /// Value name supplied to the latest write.
        private @Nullable String writtenValueName;

        /// String supplied to the latest write.
        private @Nullable String writtenValue;

        /// Creates a registry fixture with one optional existing value.
        ///
        /// @param existingValue queried value, or null
        private RecordingWinReg(@Nullable Object existingValue) {
            this.existingValue = existingValue;
        }

        /// Reports no standalone test keys.
        @Override
        public boolean exists(HKEY root, String key) {
            return false;
        }

        /// Returns the configured value or throws the configured failure.
        @Override
        public @Nullable Object queryValue(HKEY root, String key, String valueName) {
            if (queryFailure != null) {
                throw queryFailure;
            }
            return existingValue;
        }

        /// Returns no child keys.
        @Override
        public List<String> querySubKeyNames(HKEY root, String key) {
            return List.of();
        }

        /// Records one string-value write.
        @Override
        public boolean setValue(HKEY root, String key, String valueName, String value) {
            writtenRoot = root;
            writtenKey = key;
            writtenValueName = valueName;
            writtenValue = value;
            setCalls.incrementAndGet();
            return true;
        }

        /// Reports no deletion support because the launcher path does not delete preferences.
        @Override
        public boolean deleteValue(HKEY root, String key, String valueName) {
            return false;
        }
    }

    /// Account fixture that exposes a stable ID and records offline fallback calls.
    @NotNullByDefault
    private static final class RecordingAccount extends Account {
        /// Authentication data returned by offline fallback.
        private final AuthInfo offlineAuthInfo = authInfo("Offline");

        /// Number of offline fallback requests.
        private final AtomicInteger offlineCalls = new AtomicInteger();

        /// Creates a fixture with one persisted stable ID.
        ///
        /// @param accountId persisted account identifier
        private RecordingAccount(AccountID accountId) {
            super(accountId);
        }

        /// Returns the fixture profile name.
        ///
        /// @return profile name
        @Override
        public String getProfileName() {
            return "Player";
        }

        /// Returns the fixture profile UUID.
        ///
        /// @return profile UUID
        @Override
        public UUID getProfileID() {
            return offlineAuthInfo.getUUID();
        }

        /// Rejects unexpected stored-credential login in focused recovery tests.
        ///
        /// @return never returns
        @Override
        public AuthInfo logIn() {
            throw new AssertionError("Stored login is not expected");
        }

        /// Records and returns deterministic offline authentication data.
        ///
        /// @return exact offline authentication data
        @Override
        public AuthInfo playOffline() {
            offlineCalls.incrementAndGet();
            return offlineAuthInfo;
        }

        /// Returns the exact offline authentication fixture.
        ///
        /// @return offline authentication data
        private AuthInfo offlineAuthInfo() {
            return offlineAuthInfo;
        }

        /// Returns the number of offline fallback calls.
        ///
        /// @return offline-call count
        private int offlineCalls() {
            return offlineCalls.get();
        }
    }

    /// Reauthentication fixture that records the stable identifier and returns one exact result.
    @NotNullByDefault
    private static final class RecordingAccountReauthentication implements AccountReauthentication {
        /// Exact authentication result returned to the launch task.
        private final AuthInfo authInfo;

        /// Stable account ID observed by the boundary, or null before invocation.
        private final AtomicReference<@Nullable String> accountId =
                new AtomicReference<>();

        /// Creates a fixture returning one exact result.
        ///
        /// @param authInfo exact successful authentication data
        private RecordingAccountReauthentication(AuthInfo authInfo) {
            this.authInfo = authInfo;
        }

        /// Records the stable ID and returns the configured exact result.
        ///
        /// @param stableAccountId serialized stable account identifier
        /// @return completed exact authentication data
        @Override
        public CompletionStage<AuthInfo> reauthenticate(String stableAccountId) {
            accountId.set(stableAccountId);
            return CompletableFuture.completedFuture(authInfo);
        }

        /// Provides an inert lifecycle for this completed operation.
        @Override
        public void close() {
        }

        /// Returns the observed stable account identifier.
        ///
        /// @return observed identifier, or null before invocation
        private @Nullable String observedAccountId() {
            return accountId.get();
        }
    }

    /// Task fixture that records execution and publishes one stable process instance.
    @NotNullByDefault
    private static final class RecordingLaunchTask extends Task<ManagedProcess> {
        /// Process published on execution.
        private final ManagedProcess process;

        /// Number of task-body executions.
        private final AtomicInteger executions = new AtomicInteger();

        /// Creates a stopped fixture for one process.
        ///
        /// @param process process returned by execution
        private RecordingLaunchTask(ManagedProcess process) {
            this.process = process;
        }

        /// Records execution and publishes the configured process.
        @Override
        public void execute() {
            executions.incrementAndGet();
            setResult(process);
        }

        /// Returns the task-body execution count.
        ///
        /// @return current execution count
        private int executionCount() {
            return executions.get();
        }
    }

    /// In-memory process fixture that stays alive until explicitly destroyed.
    @NotNullByDefault
    private static final class RecordingProcess extends Process {
        /// Writable standard-input sink.
        private final ByteArrayOutputStream standardInput = new ByteArrayOutputStream();

        /// Non-blocking exit completion observed by native Swing log windows.
        private final CompletableFuture<Process> exitFuture = new CompletableFuture<>();

        /// Whether the fixture currently reports itself as alive.
        private volatile boolean alive = true;

        /// Returns the writable standard-input sink.
        @Override
        public OutputStream getOutputStream() {
            return standardInput;
        }

        /// Returns an empty standard-output stream.
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns an empty standard-error stream.
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Marks the fixture exited and returns a successful exit code.
        @Override
        public int waitFor() {
            completeExit();
            return 0;
        }

        /// Returns the exit code, or reports that the fixture is still alive.
        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 0;
        }

        /// Marks the fixture exited.
        @Override
        public void destroy() {
            completeExit();
        }

        /// Returns the controllable non-blocking process-exit completion.
        ///
        /// @return process-exit completion
        @Override
        public CompletableFuture<Process> onExit() {
            return exitFuture;
        }

        /// Returns whether the fixture still reports itself as alive.
        @Override
        public boolean isAlive() {
            return alive;
        }

        /// Marks the fixture exited and completes non-blocking observers once.
        private void completeExit() {
            alive = false;
            exitFuture.complete(this);
        }
    }
}
