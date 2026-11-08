package dev.mrlptrc.notes.export;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/imports")
class ImportController {

    private final NoteImportService importService;

    ImportController(NoteImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/{exportId}")
    @ResponseStatus(HttpStatus.OK)
    ImportReport importExport(@PathVariable String exportId) {
        return importService.importExport(exportId);
    }
}
