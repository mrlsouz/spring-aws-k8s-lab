package dev.mrlptrc.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;

class NoteRepositoryTests {

    private MeterRegistry meterRegistry;
    private DynamoDbTable<Note> table;
    private NoteRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        table = mock(DynamoDbTable.class);
        repository = new NoteRepository(meterRegistry, table);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    void createRecordsSuccessfulPutOperation() {
        Note note = new Note("note-1", "title", "content", Instant.EPOCH);

        repository.create(note);

        assertTimer("put", "success", 1);
    }

    @Test
    void listRecordsEmptyScanAsSuccessfulOperation() {
        when(table.scan())
                .thenReturn(PageIterable.create(
                        () -> List.of(Page.builder(Note.class).items(List.of()).build())
                                .iterator()));

        assertEquals(List.of(), repository.list());

        assertTimer("scan", "success", 1);
    }

    @Test
    void getAndDeleteRecordSuccessfulOperations() {
        Note note = new Note("note-1", "title", "content", Instant.EPOCH);
        when(table.getItem(ArgumentMatchers.any(Key.class))).thenReturn(note);
        when(table.deleteItem(ArgumentMatchers.any(Key.class))).thenReturn(note);

        assertSame(note, repository.get("note-1"));
        repository.delete("note-1");

        assertTimer("get", "success", 1);
        assertTimer("delete", "success", 1);
    }

    @Test
    void createRecordsFailureAndRethrowsOriginalException() {
        Note note = new Note("note-1", "title", "content", Instant.EPOCH);
        SdkClientException failure = SdkClientException.create("DynamoDB unavailable");
        doThrow(failure).when(table).putItem(note);

        assertSame(failure, assertThrows(SdkClientException.class, () -> repository.create(note)));

        assertTimer("put", "failure", 1);
    }

    private void assertTimer(String operation, String result, long count) {
        assertEquals(
                count,
                meterRegistry
                        .get("notes.dynamodb.operation")
                        .tags("operation", operation, "result", result)
                        .timer()
                        .count());
    }
}
