// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.event;

import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.util.ToStringBuilder;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Fired when the manifest JSON of an installed game instance cannot be parsed.
///
/// Listeners may repair the file and return [Event.Result#ALLOW] to request another parse attempt.
@NotNullByDefault
public final class GameJsonParseFailedEvent extends Event {

    /// Identifier of the instance whose manifest could not be parsed.
    private final GameInstanceID instanceId;

    /// Path to the malformed manifest JSON.
    private final Path jsonFile;

    /// Creates an instance-manifest parse failure event.
    ///
    /// @param source repository that loaded the manifest
    /// @param jsonFile malformed manifest JSON path
    /// @param instanceId installed instance identifier
    public GameJsonParseFailedEvent(Object source, Path jsonFile, GameInstanceID instanceId) {
        super(source);
        this.instanceId = instanceId;
        this.jsonFile = jsonFile;
    }

    /// Returns the malformed manifest JSON path.
    public Path getJsonFile() {
        return jsonFile;
    }

    /// Returns the identifier of the affected installed instance.
    public GameInstanceID getInstanceId() {
        return instanceId;
    }

    /// Returns a diagnostic representation of this event.
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("source", source)
                .append("jsonFile", jsonFile)
                .append("instanceId", instanceId)
                .toString();
    }
}
