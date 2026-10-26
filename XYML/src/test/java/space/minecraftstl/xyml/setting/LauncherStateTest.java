// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
