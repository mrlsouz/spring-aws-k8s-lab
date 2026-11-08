package dev.mrlptrc.notes.export;

import dev.mrlptrc.notes.Note;
import dev.mrlptrc.notes.NoteRepository;
import dev.mrlptrc.notes.storage.ObjectStore;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class NoteImportService {

    private final NoteRepository noteRepository;
    private final ObjectStore objectStore;
    private final ObjectMapper objectMapper;

    public NoteImportService(NoteRepository noteRepository, ObjectStore objectStore, ObjectMapper objectMapper) {
        this.noteRepository = noteRepository;
        this.objectStore = objectStore;
        this.objectMapper = objectMapper;
    }

    public ImportReport importExport(String exportId) {
        if (!isUuid(exportId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        NotesSnapshot snapshot;
        try {
            byte[] bytes = objectStore.get("exports/" + exportId + ".json").bytes();
            snapshot = objectMapper.readValue(bytes, NotesSnapshot.class);
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            throw storageFailure();
        } catch (SdkException failure) {
            throw storageFailure();
        } catch (JacksonException failure) {
            throw invalidSnapshot();
        }

        validate(snapshot, exportId);
        return importNotes(snapshot.notes());
    }

    private ImportReport importNotes(List<NoteSnapshot> notes) {
        Map<String, Note> knownNotes = new LinkedHashMap<>();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        int imported = 0;
        int skipped = 0;

        for (int index = 0; index < notes.size(); index++) {
            NoteSnapshot snapshot = notes.get(index);
            Note existing;
            try {
                if (!knownNotes.containsKey(snapshot.id())) {
                    knownNotes.put(snapshot.id(), noteRepository.get(snapshot.id()));
                }
                existing = knownNotes.get(snapshot.id());
                Note incoming = toNote(snapshot);
                if (existing == null) {
                    if (noteRepository.createIfAbsent(incoming)) {
                        knownNotes.put(snapshot.id(), incoming);
                        imported++;
                    } else {
                        Note concurrentlyCreated = noteRepository.get(snapshot.id());
                        if (concurrentlyCreated == null) {
                            throw new IllegalStateException("Note was not available after an import conflict");
                        }
                        knownNotes.put(snapshot.id(), concurrentlyCreated);
                        if (sameContent(concurrentlyCreated, incoming)) {
                            skipped++;
                        } else {
                            conflicts.add(snapshot.id());
                        }
                    }
                } else if (sameContent(existing, incoming)) {
                    skipped++;
                } else {
                    conflicts.add(snapshot.id());
                }
            } catch (RuntimeException failure) {
                return new ImportReport(imported, skipped, List.copyOf(conflicts), failedIdsFrom(notes, index));
            }
        }
        return new ImportReport(imported, skipped, List.copyOf(conflicts), List.of());
    }

    private static void validate(NotesSnapshot snapshot, String requestedExportId) {
        if (snapshot == null
                || snapshot.schemaVersion() != 1
                || !requestedExportId.equals(snapshot.exportId())
                || snapshot.generatedAt() == null
                || snapshot.notes() == null) {
            throw invalidSnapshot();
        }
        for (NoteSnapshot note : snapshot.notes()) {
            if (note == null
                    || note.id() == null
                    || note.id().isBlank()
                    || note.title() == null
                    || note.title().isBlank()
                    || note.title().length() > 200
                    || (note.content() != null && note.content().length() > 10_000)
                    || note.createdAt() == null) {
                throw invalidSnapshot();
            }
        }
    }

    private static List<String> failedIdsFrom(List<NoteSnapshot> notes, int start) {
        LinkedHashSet<String> failed = new LinkedHashSet<>();
        for (int index = start; index < notes.size(); index++) {
            failed.add(notes.get(index).id());
        }
        return List.copyOf(failed);
    }

    private static boolean sameContent(Note left, Note right) {
        return left.getTitle().equals(right.getTitle())
                && java.util.Objects.equals(left.getContent(), right.getContent())
                && left.getCreatedAt().equals(right.getCreatedAt());
    }

    private static Note toNote(NoteSnapshot snapshot) {
        Note note = new Note();
        note.setId(snapshot.id());
        note.setTitle(snapshot.title());
        note.setContent(snapshot.content());
        note.setCreatedAt(snapshot.createdAt());
        return note;
    }

    private static boolean isUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return value.length() == 36 && uuid.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static ResponseStatusException invalidSnapshot() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid notes export snapshot");
    }

    private static ResponseStatusException storageFailure() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Object storage operation failed");
    }
}
