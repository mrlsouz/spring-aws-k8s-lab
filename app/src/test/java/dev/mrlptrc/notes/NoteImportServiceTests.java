package dev.mrlptrc.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mrlptrc.notes.export.ImportReport;
import dev.mrlptrc.notes.export.NoteImportService;
import dev.mrlptrc.notes.export.NoteSnapshot;
import dev.mrlptrc.notes.export.NotesSnapshot;
import dev.mrlptrc.notes.storage.ObjectStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

class NoteImportServiceTests {

    private static final String EXPORT_ID = "a5f8a7e2-58d5-4ec8-a7b5-1a0919ee687c";
    private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    void importsAndSkipsRepeatedIdenticalNote() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore store = store(snapshot(note("note-1", "title", "content"), note("note-1", "title", "content")));
        when(repository.get("note-1")).thenReturn(null);
        when(repository.createIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var service = service(repository, store);

        ImportReport report = service.importExport(EXPORT_ID);

        assertEquals(new ImportReport(1, 1, List.of(), List.of()), report);
        verify(repository)
                .createIfAbsent(org.mockito.ArgumentMatchers.argThat(
                        note -> note.getId().equals("note-1")
                                && note.getTitle().equals("title")
                                && note.getContent().equals("content")
                                && note.getCreatedAt().equals(CREATED_AT)));
    }

    @Test
    void reportsExistingConflictAndDoesNotOverwrite() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        when(repository.get("note-1")).thenReturn(new Note("note-1", "original", "content", CREATED_AT));
        var service = service(repository, store(snapshot(note("note-1", "changed", "content"))));

        ImportReport report = service.importExport(EXPORT_ID);

        assertEquals(new ImportReport(0, 0, List.of("note-1"), List.of()), report);
        verify(repository, never()).createIfAbsent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reportsConflictingDuplicateIdsWithinSnapshot() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        when(repository.get("note-1")).thenReturn(null);
        when(repository.createIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var service = service(
                repository, store(snapshot(note("note-1", "first", "content"), note("note-1", "second", "content"))));

        ImportReport report = service.importExport(EXPORT_ID);

        assertEquals(new ImportReport(1, 0, List.of("note-1"), List.of()), report);
    }

    @Test
    void concurrentIdenticalImportBecomesSkippedInsteadOfOverwriting() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        when(repository.get("note-1")).thenReturn(null, new Note("note-1", "title", "content", CREATED_AT));
        when(repository.createIfAbsent(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        var service = service(repository, store(snapshot(note("note-1", "title", "content"))));

        ImportReport report = service.importExport(EXPORT_ID);

        assertEquals(new ImportReport(0, 1, List.of(), List.of()), report);
    }

    @Test
    void rejectsUnsupportedSchemaBeforeWriting() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        var service = service(repository, store(snapshot(2, note("note-1", "title", "content"))));

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.importExport(EXPORT_ID));

        assertEquals(422, failure.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidNoteBeforeWritingAnyRecords() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        var service = service(
                repository, store(snapshot(note("note-1", "valid", "content"), note("note-2", " ", "invalid"))));

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.importExport(EXPORT_ID));

        assertEquals(422, failure.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedSnapshotBeforeWriting() {
        NoteRepository repository = mock(NoteRepository.class);
        var service = service(repository, store("{broken".getBytes()));

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.importExport(EXPORT_ID));

        assertEquals(422, failure.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void returnsNotFoundForMissingExport() {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore store = mock(ObjectStore.class);
        when(store.get("exports/" + EXPORT_ID + ".json"))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        var service = service(repository, store);

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.importExport(EXPORT_ID));

        assertEquals(404, failure.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void hidesObjectStorageFailureDetails() {
        NoteRepository repository = mock(NoteRepository.class);
        ObjectStore store = mock(ObjectStore.class);
        when(store.get("exports/" + EXPORT_ID + ".json"))
                .thenThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("provider internals")
                        .build());
        var service = service(repository, store);

        ResponseStatusException failure =
                assertThrows(ResponseStatusException.class, () -> service.importExport(EXPORT_ID));

        assertEquals(500, failure.getStatusCode().value());
        assertEquals("Object storage operation failed", failure.getReason());
        verifyNoInteractions(repository);
    }

    @Test
    void reportsFirstFailedWriteAndStopsImport() throws Exception {
        NoteRepository repository = mock(NoteRepository.class);
        when(repository.get("note-1")).thenReturn(null);
        when(repository.get("note-2")).thenReturn(null);
        when(repository.createIfAbsent(org.mockito.ArgumentMatchers.argThat(
                        note -> note.getId().equals("note-1"))))
                .thenReturn(true);
        doThrow(new IllegalStateException("storage down"))
                .when(repository)
                .createIfAbsent(org.mockito.ArgumentMatchers.argThat(
                        note -> note.getId().equals("note-2")));
        var service = service(
                repository,
                store(snapshot(
                        note("note-1", "one", "content"),
                        note("note-2", "two", "content"),
                        note("note-3", "three", "content"))));

        ImportReport report = service.importExport(EXPORT_ID);

        assertEquals(new ImportReport(1, 0, List.of(), List.of("note-2", "note-3")), report);
    }

    private static NoteImportService service(NoteRepository repository, ObjectStore store) {
        return new NoteImportService(
                repository, store, JsonMapper.builder().findAndAddModules().build());
    }

    private static ObjectStore store(NotesSnapshot snapshot) throws Exception {
        return store(JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(snapshot));
    }

    private static ObjectStore store(byte[] bytes) {
        ObjectStore store = mock(ObjectStore.class);
        when(store.get("exports/" + EXPORT_ID + ".json"))
                .thenReturn(new ObjectStore.StoredObject(bytes, "application/json"));
        return store;
    }

    private static NotesSnapshot snapshot(NoteSnapshot... notes) {
        return snapshot(1, notes);
    }

    private static NotesSnapshot snapshot(int version, NoteSnapshot... notes) {
        return new NotesSnapshot(version, EXPORT_ID, CREATED_AT, List.of(notes));
    }

    private static NoteSnapshot note(String id, String title, String content) {
        return new NoteSnapshot(id, title, content, CREATED_AT);
    }
}
