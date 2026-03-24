package ro.upt.eduplatform.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ro.upt.eduplatform.service.BacExcelImportService;
import ro.upt.eduplatform.service.EnExcelImportService;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final BacExcelImportService bacExcelImportService;
    private final EnExcelImportService enExcelImportService;

    @PostMapping("/import/bac/{an}")
    public ResponseEntity<?> importBacExcel(
            @PathVariable int an,
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("Import BAC {} solicitat, fisier: {}", an, file.getOriginalFilename());
            int salvate = bacExcelImportService.importFromExcel(file.getInputStream(), an);
            return ResponseEntity.ok(Map.of("an", an, "salvate", salvate));
        } catch (Exception e) {
            log.error("Eroare import BAC {}: {}", an, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/import/en/{an}")
    public ResponseEntity<?> importEnExcel(
            @PathVariable int an,
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("Import EN {} solicitat, fisier: {}", an, file.getOriginalFilename());
            int salvate = enExcelImportService.importFromExcel(file.getInputStream(), an);
            return ResponseEntity.ok(Map.of("an", an, "salvate", salvate));
        } catch (Exception e) {
            log.error("Eroare import EN {}: {}", an, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}