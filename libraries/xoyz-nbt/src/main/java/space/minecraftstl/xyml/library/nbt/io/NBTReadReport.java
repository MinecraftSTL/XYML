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
// Added by MinecraftSTL in 2026 for explicit tolerant-read diagnostics.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Immutable result metadata for one NBT read.
///
/// A report is clean only when the strict parser accepted the complete envelope and no diagnostic
/// was emitted. Informational extension diagnostics are exposed separately and do not force a repair
/// save. A caller must ask the user before saving a report that requires repair; saving then performs
/// a strict write of the in-memory tree.
@NotNullByDefault
public final class NBTReadReport {
    /// User-facing recovery level for one read operation.
    @NotNullByDefault
    public enum Severity {
        /// The complete source was accepted without a repair-required diagnostic.
        CLEAN,
        /// The source was recovered without confirmed data loss.
        RECOVERED,
        /// One or more bytes or values could not be reconstructed reliably.
        PARTIAL_DATA_LOSS
    }

    private final NBTFileEncoding encoding;
    private final boolean strictValid;
    private final @Unmodifiable List<NBTReadIssue> issues;
    private final Severity severity;

    /// Creates an immutable report.
    ///
    /// @param encoding detected storage envelope
    /// @param strictValid whether strict parsing accepted the complete source
    /// @param issues immutable or mutable input diagnostics
    public NBTReadReport(NBTFileEncoding encoding, boolean strictValid, List<NBTReadIssue> issues) {
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.strictValid = strictValid;
        this.issues = List.copyOf(new ArrayList<>(Objects.requireNonNull(issues, "issues")));
        this.severity = deriveSeverity(strictValid, this.issues);
    }

    /// Creates a clean report for a strictly valid source.
    @Contract("_ -> new")
    public static NBTReadReport clean(NBTFileEncoding encoding) {
        return new NBTReadReport(encoding, true, List.of());
    }

    /// Returns the detected storage envelope.
    public NBTFileEncoding encoding() {
        return encoding;
    }

    /// Returns whether strict parsing accepted the complete source.
    public boolean strictValid() {
        return strictValid;
    }

    /// Alias for callers using bean-style naming.
    public boolean isStrictValid() {
        return strictValid;
    }

    /// Returns the three-level user-facing recovery severity.
    ///
    /// Informational extension markers do not raise a clean report. A report with a recovered
    /// payload and no confirmed loss is `RECOVERED`; an issue marked `PARTIAL_DATA_LOSS` or
    /// `ERROR` is `PARTIAL_DATA_LOSS`.
    public Severity severity() {
        return severity;
    }

    /// Bean-style alias for [#severity()].
    public Severity getSeverity() {
        return severity;
    }

    /// Alias for clients which expose report levels as a status.
    public Severity status() {
        return severity;
    }

    /// Returns immutable diagnostics in detection order.
    public @Unmodifiable List<NBTReadIssue> issues() {
        return issues;
    }

    /// Returns diagnostics that require rewriting the source before it is considered repaired.
    ///
    /// @return immutable repair-required diagnostics
    public @Unmodifiable List<NBTReadIssue> repairIssues() {
        return issues.stream()
                .filter(issue -> !isInformational(issue))
                .toList();
    }

    /// Returns diagnostics which describe a supported extension without requiring a rewrite.
    ///
    /// @return immutable informational diagnostics
    public @Unmodifiable List<NBTReadIssue> informationalIssues() {
        return issues.stream()
                .filter(NBTReadReport::isInformational)
                .toList();
    }

    /// Returns whether at least one informational extension diagnostic was observed.
    public boolean hasInformationalIssues() {
        return issues.stream().anyMatch(NBTReadReport::isInformational);
    }

    /// Returns whether at least one diagnostic requires a repair publication.
    public boolean hasRepairIssues() {
        return issues.stream().anyMatch(issue -> !isInformational(issue));
    }

    /// Returns whether the source needs an explicit repair save.
    public boolean requiresRepair() {
        return !repairIssues().isEmpty() || (!strictValid && issues.isEmpty());
    }

    /// Returns whether any bytes or values were lost during recovery.
    public boolean hasPartialDataLoss() {
        return repairIssues().stream().anyMatch(issue -> issue.severity() == NBTReadIssue.Severity.PARTIAL_DATA_LOSS
                || issue.severity() == NBTReadIssue.Severity.ERROR);
    }

    /// Returns a concise diagnostic representation.
    @Override
    public String toString() {
        return "NBTReadReport[encoding=" + encoding + ", strictValid=" + strictValid
                + ", issues=" + issues + ']';
    }

    /// Returns whether one issue is a supported, non-repairing extension marker.
    private static boolean isInformational(NBTReadIssue issue) {
        return ("REGION_LZ4_EXTENSION".equals(issue.code()) || "LZ4_EXTENSION".equals(issue.code()))
                && (issue.severity() == NBTReadIssue.Severity.INFORMATIONAL
                || issue.severity() == NBTReadIssue.Severity.RECOVERED);
    }

    /// Compares all report fields.
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof NBTReadReport other
                && encoding == other.encoding
                && strictValid == other.strictValid
                && issues.equals(other.issues);
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(encoding, strictValid, issues);
    }

    /// Derives the stable report level from strict validity and issue severities.
    private static Severity deriveSeverity(boolean strictValid, List<NBTReadIssue> issues) {
        if (issues.stream().anyMatch(issue -> issue.severity() == NBTReadIssue.Severity.PARTIAL_DATA_LOSS
                || issue.severity() == NBTReadIssue.Severity.ERROR)) {
            return Severity.PARTIAL_DATA_LOSS;
        }
        if (issues.stream().anyMatch(issue -> !isInformational(issue))
                || (!strictValid && issues.isEmpty())) {
            return Severity.RECOVERED;
        }
        return Severity.CLEAN;
    }
}
