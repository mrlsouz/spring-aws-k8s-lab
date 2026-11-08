package dev.mrlptrc.notes.export;

import java.time.Instant;
import java.util.List;

public record NotesSnapshot(int schemaVersion, String exportId, Instant generatedAt, List<NoteSnapshot> notes) {}
