package dev.mrlptrc.notes.storage;

import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final String bucket;

    S3ObjectStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public StoredObject get(String key) {
        var response = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new StoredObject(response.asByteArray(), response.response().contentType());
    }

    @Override
    public List<StoredObjectSummary> list(String prefix) {
        return client
                .listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build())
                .contents()
                .stream()
                .map(object -> new StoredObjectSummary(object.key(), object.lastModified(), object.size()))
                .toList();
    }
}
