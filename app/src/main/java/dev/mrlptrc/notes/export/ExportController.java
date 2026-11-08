package dev.mrlptrc.notes.export;

import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/exports")
class ExportController {

    private final NoteExportService exportService;

    ExportController(NoteExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ExportDescriptor createExport() {
        return exportService.createExport();
    }

    @GetMapping
    List<ExportDescriptor> listExports() {
        return exportService.listExports();
    }

    @GetMapping(value = "/{exportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotesSnapshot> downloadExport(@PathVariable String exportId) {
        NotesSnapshot snapshot = exportService.downloadExport(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("notes-" + exportId + ".json")
                                .build()
                                .toString())
                .body(snapshot);
    }
}
