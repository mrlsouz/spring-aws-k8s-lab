package dev.mrlptrc.notes;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${app.dynamodb.region}") String region, @Value("${app.dynamodb.endpoint}") String endpoint) {
        // This lab always uses DynamoDB Local, which accepts dummy credentials.
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }

    @Bean
    DynamoDbTable<Note> notesTable(DynamoDbClient client, @Value("${app.dynamodb.table}") String table) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build()
                .table(table, TableSchema.fromBean(Note.class));
    }
}
