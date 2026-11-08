package dev.mrlptrc.notes.export;

import dev.mrlptrc.notes.NoteRepository;
import dev.mrlptrc.notes.storage.ObjectStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class NoteExportService {

    private static final String PREFIX = "exports/";
    private static final Pattern EXPORT_KEY = Pattern.compile(
            "exports/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.json");

    private final NoteRepository noteRepository;
    private final ObjectStore objectStore;
    private final ObjectMapper objectMapper;

    public NoteExportService(NoteRepository noteRepository, ObjectStore objectStore, ObjectMapper objectMapper) {
        this.noteRepository = noteRepository;
        this.objectStore = objectStore;
        this.objectMapper = objectMapper;
    }

    public ExportDescriptor createExport() {
        String exportId = UUID.randomUUID().toString();
        Instant generatedAt = Instant.now();
        List<NoteSnapshot> notes = noteRepository.list().stream()
                .map(note -> new NoteSnapshot(note.getId(), note.getTitle(), note.getContent(), note.getCreatedAt()))
                .toList();
        byte[] bytes = serialize(new NotesSnapshot(1, exportId, generatedAt, notes));
        try {
            objectStore.put(key(exportId), bytes, "application/json");
        } catch (S3Exception failure) {
            throw storageFailure();
        } catch (SdkException failure) {
            throw storageFailure();
        }
        return new ExportDescriptor(exportId, generatedAt, bytes.length);
    }

    public List<ExportDescriptor> listExports() {
        try {
            return objectStore.list(PREFIX).stream()
                    .filter(object -> EXPORT_KEY.matcher(object.key()).matches())
                    .map(object -> new ExportDescriptor(
                            object.key().substring(PREFIX.length(), object.key().length() - ".json".length()),
                            object.lastModified(),
                            object.size()))
                    .toList();
        } catch (S3Exception failure) {
            throw storageFailure();
        } catch (SdkException failure) {
            throw storageFailure();
        }
    }

    public NotesSnapshot downloadExport(String exportId) {
        if (!isUuid(exportId)) {
            throw notFound();
        }
        try {
            return objectMapper.readValue(objectStore.get(key(exportId)).bytes(), NotesSnapshot.class);
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw notFound();
            }
            throw storageFailure();
        } catch (SdkException failure) {
            throw storageFailure();
        } catch (JacksonException failure) {
            throw new IllegalStateException("Could not read stored notes export", failure);
        }
    }

    private byte[] serialize(NotesSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsBytes(snapshot);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Could not serialize notes export", failure);
        }
    }

    private static String key(String exportId) {
        return PREFIX + exportId + ".json";
    }

    private static boolean isUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return value.length() == 36 && uuid.toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ResponseStatusException storageFailure() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Object storage operation failed");
    }
}
