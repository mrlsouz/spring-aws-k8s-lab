package dev.mrlptrc.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import tools.jackson.databind.json.JsonMapper;

class NoteExportServiceTests {

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
