package de.kxine.pfmdiff.model;

import java.util.List;

public record EntryResult(
        String relativePath,
        EntryType entryType,
        FileKind fileKind,
        Status status,
        Long originalSize,
        Long comparisonSize,
        String originalHash,
        String comparisonHash,
        Boolean semanticEqual,
        List<String> details,
        String error) {

    public EntryResult {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public boolean hasError() {
        return status == Status.ERROR;
    }

    public enum EntryType { FILE, DIRECTORY, SYMBOLIC_LINK, TYPE_MISMATCH }

    public enum FileKind { BINARY, XML, PDF, NOT_APPLICABLE }

    public enum Status { IDENTICAL, CHANGED, ADDED, REMOVED, ERROR, SKIPPED }
}
