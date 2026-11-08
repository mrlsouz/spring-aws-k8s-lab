package dev.mrlptrc.notes.export;

import java.time.Instant;

public record NoteSnapshot(String id, String title, String content, Instant createdAt) {}
