package dev.mrlptrc.notes;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class NoteRepository {

    private static final String OPERATION_METRIC = "notes.dynamodb.operation";

    private final MeterRegistry meterRegistry;
    private final DynamoDbTable<Note> table;

    NoteRepository(MeterRegistry meterRegistry, DynamoDbTable<Note> table) {
        this.meterRegistry = meterRegistry;
        this.table = table;
    }

    public void create(Note note) {
        record("put", () -> {
            table.putItem(note);
            return null;
        });
    }

    public boolean createIfAbsent(Note note) {
        return record("put-if-absent", () -> {
            try {
                table.putItem(PutItemEnhancedRequest.builder(Note.class)
                        .item(note)
                        .conditionExpression(Expression.builder()
                                .expression("attribute_not_exists(id)")
                                .build())
                        .build());
                return true;
            } catch (ConditionalCheckFailedException conflict) {
                return false;
            }
        });
    }

    public List<Note> list() {
        return record("scan", () -> table.scan().items().stream().toList());
    }

    public Note get(String id) {
        return record(
                "get", () -> table.getItem(Key.builder().partitionValue(id).build()));
    }

    void delete(String id) {
        record("delete", () -> {
            table.deleteItem(Key.builder().partitionValue(id).build());
            return null;
        });
    }

    private <T> T record(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            return action.get();
        } catch (RuntimeException failure) {
            result = "failure";
            throw failure;
        } finally {
            sample.stop(meterRegistry.timer(OPERATION_METRIC, "operation", operation, "result", result));
        }
    }
}
