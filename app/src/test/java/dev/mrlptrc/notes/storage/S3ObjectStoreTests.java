package dev.mrlptrc.notes.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers
class S3ObjectStoreTests {

    @Container
    static final GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_ROOT", "/s3mockroot")
            .withExposedPorts(9090);

    private static S3Client client;

    @BeforeAll
    static void createClient() {
        client = S3Client.builder()
                .endpointOverride(URI.create("http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void storesRetrievesAndListsObjects() {
        String bucket = "adapter-" + UUID.randomUUID().toString().substring(0, 8);
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        ObjectStore store = new S3ObjectStore(client, bucket);
        String key = "exports/notes.json";
        byte[] content = "{\"notes\":[]}".getBytes(StandardCharsets.UTF_8);

        store.put(key, content, "application/json");

        ObjectStore.StoredObject result = store.get(key);
        assertArrayEquals(content, result.bytes());
        assertEquals("application/json", result.contentType());
        assertTrue(
                store.list("exports/").stream().anyMatch(object -> object.key().equals(key)));
    }
}
