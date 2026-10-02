package de.kxine.pfmdiff.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ComparisonReport(
        Path originalRoot,
        Path comparisonRoot,
        Instant createdAt,
        List<EntryResult> entries) {

    public ComparisonReport {
        entries = List.copyOf(entries);
    }

    public long count(EntryResult.Status status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }

    public boolean hasDifferences() {
        return entries.stream().anyMatch(entry -> entry.status() == EntryResult.Status.CHANGED
                || entry.status() == EntryResult.Status.ADDED
                || entry.status() == EntryResult.Status.REMOVED);
    }

    public boolean hasErrors() {
        return entries.stream().anyMatch(EntryResult::hasError);
    }

    public boolean hasSkipped() {
        return entries.stream().anyMatch(entry -> entry.status() == EntryResult.Status.SKIPPED);
    }
}
