package dev.mrlptrc.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mrlptrc.notes.export.ExportDescriptor;
import dev.mrlptrc.notes.export.NoteExportService;
import dev.mrlptrc.notes.export.NotesSnapshot;
import dev.mrlptrc.notes.storage.ObjectStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

class NoteExportServiceTests {

    @Test
    void hidesObjectStorageFailureDetailsWhenWritingExport() {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        var mapper = JsonMapper.builder().findAndAddModules().build();
        when(repository.list()).thenReturn(List.of());
        doThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("provider internals")
                        .build())
                .when(objectStore)
                .put(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
        var service = new NoteExportService(repository, objectStore, mapper);

        ResponseStatusException failure =
                org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class, service::createExport);

        assertEquals(500, failure.getStatusCode().value());
        assertEquals("Object storage operation failed", failure.getReason());
    }

    @Test
    void mapsMissingSnapshotToNotFound() {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        String exportId = "a5f8a7e2-58d5-4ec8-a7b5-1a0919ee687c";
        when(objectStore.get("exports/" + exportId + ".json"))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        var service = new NoteExportService(
                repository,
                objectStore,
                JsonMapper.builder().findAndAddModules().build());

        ResponseStatusException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> service.downloadExport(exportId));

        assertEquals(404, failure.getStatusCode().value());
    }

    @Test
    void hidesObjectStorageFailureDetailsWhenDownloadingSnapshot() {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore objectStore = mock(ObjectStore.class);
        String exportId = "a5f8a7e2-58d5-4ec8-a7b5-1a0919ee687c";
        when(objectStore.get("exports/" + exportId + ".json"))
                .thenThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("provider internals")
                        .build());
        var service = new NoteExportService(
                repository,
                objectStore,
                JsonMapper.builder().findAndAddModules().build());

        ResponseStatusException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> service.downloadExport(exportId));

        assertEquals(500, failure.getStatusCode().value());
        assertEquals("Object storage operation failed", failure.getReason());
    }

    @Test
    void createsListsAndDownloadsVersionedSnapshot() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var service = new NoteExportService(repository, objectStore, mapper);
        Note note = new Note("note-1", "Portfolio", "Exportar notas", Instant.parse("2026-11-08T00:00:00Z"));
        when(repository.list()).thenReturn(List.of(note));

        ExportDescriptor created = service.createExport();

        assertTrue(created.exportId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals("application/json", objectStore.lastContentType);
        assertEquals("exports/" + created.exportId() + ".json", objectStore.lastKey);
        NotesSnapshot stored = mapper.readValue(objectStore.lastBytes, NotesSnapshot.class);
        assertEquals(1, stored.schemaVersion());
        assertEquals(created.exportId(), stored.exportId());
        assertEquals(created.createdAt(), stored.generatedAt());
        assertEquals("note-1", stored.notes().getFirst().id());
        assertEquals("Portfolio", stored.notes().getFirst().title());
        assertEquals("Exportar notas", stored.notes().getFirst().content());
        assertEquals(note.getCreatedAt(), stored.notes().getFirst().createdAt());

        assertEquals(created.exportId(), service.listExports().getFirst().exportId());
        assertEquals(stored, service.downloadExport(created.exportId()));
    }

    private static final class InMemoryObjectStore implements ObjectStore {

        private final List<StoredObjectSummary> summaries = new ArrayList<>();
        private byte[] lastBytes;
        private String lastContentType;
        private String lastKey;

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            lastKey = key;
            lastBytes = bytes;
            lastContentType = contentType;
            summaries.add(new StoredObjectSummary(key, Instant.now(), bytes.length));
        }

        @Override
        public StoredObject get(String key) {
            return new StoredObject(lastBytes, lastContentType);
        }

        @Override
        public List<StoredObjectSummary> list(String prefix) {
            return summaries.stream()
                    .filter(object -> object.key().startsWith(prefix))
                    .toList();
        }
    }
}
