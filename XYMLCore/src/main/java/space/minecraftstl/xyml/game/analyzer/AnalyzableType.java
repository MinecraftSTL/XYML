// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
            new C2CompilerAnalyzer(),
            new JREVersionAnalyzer(),
            new LegacyJavaFixerAnalyzer(),
            new ClientModOnServerAnalyzer(),
            new RendererModCompatibilityAnalyzer(),
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
