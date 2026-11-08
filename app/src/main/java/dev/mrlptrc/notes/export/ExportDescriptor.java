package dev.mrlptrc.notes.export;

import java.time.Instant;

public record ExportDescriptor(String exportId, Instant createdAt, long size) {}
