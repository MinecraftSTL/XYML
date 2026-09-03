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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Registry of intentionally supported analysis input families.
@NotNullByDefault
public enum AnalyzableType {
    /// Limited game launch-log analysis ordered from most exclusive environment cause to least.
    LOG(List.of(
            new JRE32BitAnalyzer(),
            new VirtualMemoryAnalyzer(),
            new JREVersionAnalyzer(),
            new ForgeMissingDependencyAnalyzer(),
            new FabricMissingDependencyAnalyzer(),
            new CodePageAnalyzer()));

    /// Immutable ordered analyzers registered for launch logs.
    private final @Unmodifiable List<Analyzer<LogAnalyzable>> logAnalyzers;

    /// Creates a registry entry from an immutable analyzer snapshot.
    ///
    /// @param logAnalyzers ordered launch-log analyzers
    AnalyzableType(List<Analyzer<LogAnalyzable>> logAnalyzers) {
        this.logAnalyzers = List.copyOf(logAnalyzers);
    }

    /// Returns the immutable ordered launch-log analyzers.
    ///
    /// @return registered launch-log analyzers
    public @Unmodifiable List<Analyzer<LogAnalyzable>> logAnalyzers() {
        return logAnalyzers;
    }
}
