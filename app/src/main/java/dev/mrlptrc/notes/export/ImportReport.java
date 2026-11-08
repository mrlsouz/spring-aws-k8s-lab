package dev.mrlptrc.notes.export;

import java.util.List;

public record ImportReport(int imported, int skipped, List<String> conflictingIds, List<String> failedIds) {}
