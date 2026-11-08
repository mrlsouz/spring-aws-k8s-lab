package dev.mrlptrc.notes.storage;

import java.time.Instant;
import java.util.List;

public interface ObjectStore {

    record StoredObject(byte[] bytes, String contentType) {}

    record StoredObjectSummary(String key, Instant lastModified, long size) {}

    void put(String key, byte[] bytes, String contentType);

    StoredObject get(String key);

    List<StoredObjectSummary> list(String prefix);
}
