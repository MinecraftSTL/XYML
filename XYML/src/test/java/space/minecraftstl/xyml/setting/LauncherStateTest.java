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
package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for detached launcher state behavior.
@NotNullByDefault
public final class LauncherStateTest {
    /// Tests that neutral properties and maps publish aggregate state revisions.
    @Test
    public void publishesNeutralStateChanges() {
        LauncherState state = new LauncherState();
        long initialRevision = Objects.requireNonNull(state.changes().getValue());
        AtomicReference<Object> changedField = new AtomicReference<>(state);
        state.changedFields().subscribe(change -> changedField.set(change.currentValue()));

        state.setWidth(1280.0);
        long afterProperty = Objects.requireNonNull(state.changes().getValue());
        assertTrue(afterProperty > initialRevision);
        assertSame(state.widthProperty(), changedField.get());

        state.getShownTips().put("javaVersionTip", 21);
        assertTrue(Objects.requireNonNull(state.changes().getValue()) > afterProperty);
        assertSame(state.getShownTips(), changedField.get());
    }

    /// Tests that only window geometry changes defer automatic persistence.
    @Test
    public void identifiesDeferredWindowGeometryFields() {
        LauncherState state = new LauncherState();

        assertFalse(state.shouldSaveImmediately(state.xProperty()));
        assertFalse(state.shouldSaveImmediately(state.yProperty()));
        assertFalse(state.shouldSaveImmediately(state.widthProperty()));
        assertFalse(state.shouldSaveImmediately(state.heightProperty()));
        assertTrue(state.shouldSaveImmediately(state.schemaProperty()));
        assertTrue(state.shouldSaveImmediately(state.promptedVersionProperty()));
        assertTrue(state.shouldSaveImmediately(state.getShownTips()));
    }

    /// Retains pending state when serialization or queueing fails, then clears it after a successful retry.
    @Test
    public void clearsPendingMarkerOnlyAfterSaveIsQueued() {
        LauncherState state = new LauncherState();
        state.setSavePending(true);

        assertThrows(IllegalStateException.class, () -> SettingsManager.savePendingChanges(state, ignored -> {
            throw new IllegalStateException("save failure");
        }));
        assertTrue(state.isSavePending());

        AtomicBoolean saved = new AtomicBoolean();
        SettingsManager.savePendingChanges(state, ignored -> saved.set(true));

        assertTrue(saved.get());
        assertFalse(state.isSavePending());
    }
}
