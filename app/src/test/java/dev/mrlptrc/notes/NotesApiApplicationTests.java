package dev.mrlptrc.notes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Teste de integração: sobe o DynamoDB Local num container (Testcontainers)
 * e exercita a API inteira, sem conta AWS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotesApiApplicationTests {

    @Container
    static GenericContainer<?> dynamodb = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .withExposedPorts(8000);

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "notes-local")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_ROOT", "/s3mockroot")
            .withExposedPorts(9090);

    // Porta do container é aleatória: injeta o endpoint depois que ele sobe.
    @DynamicPropertySource
    static void dynamoProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "app.dynamodb.endpoint", () -> "http://" + dynamodb.getHost() + ":" + dynamodb.getMappedPort(8000));
        registry.add("app.dynamodb.create-table", () -> "true");
        registry.add("app.s3.endpoint", () -> "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090));
        registry.add("app.s3.bucket", () -> "notes-local");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    NoteRepository repository;

    @BeforeEach
    void clearNotes() {
        repository.list().forEach(note -> repository.delete(note.getId()));
    }

    @Test
    void createGetListDelete() throws Exception {
        String body = mvc.perform(post("/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"estudar EKS\",\"content\":\"fase 4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/notes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("estudar EKS"));
        mvc.perform(get("/notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
        mvc.perform(delete("/notes/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/notes/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void exportsListsAndDownloadsNotes() throws Exception {
        mvc.perform(post("/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"snapshot note\",\"content\":\"archive me\"}"))
                .andExpect(status().isCreated());

        String body = mvc.perform(post("/exports"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exportId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String exportId = JsonPath.read(body, "$.exportId");

        mvc.perform(get("/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.exportId == '" + exportId + "')]").exists());
        mvc.perform(get("/exports/" + exportId))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Content-Disposition", "attachment; filename=\"notes-" + exportId + ".json\""))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.notes[0].title").value("snapshot note"))
                .andExpect(jsonPath("$.notes[0].content").value("archive me"));
        mvc.perform(get("/exports/not-a-uuid")).andExpect(status().isNotFound());
        mvc.perform(get("/exports/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void importsExportWithoutOverwritingExistingNotes() throws Exception {
        String noteBody = mvc.perform(post("/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"restore me\",\"content\":\"saved\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String noteId = JsonPath.read(noteBody, "$.id");
        String exportBody = mvc.perform(post("/exports"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String exportId = JsonPath.read(exportBody, "$.exportId");

        mvc.perform(delete("/notes/" + noteId)).andExpect(status().isNoContent());
        mvc.perform(post("/imports/" + exportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0));
        mvc.perform(get("/notes/" + noteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("restore me"));
        mvc.perform(post("/imports/" + exportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
    }
}
