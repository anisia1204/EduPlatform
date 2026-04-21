package ro.upt.eduplatform.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ro.upt.eduplatform.ml.MlPredictionService;
import ro.upt.eduplatform.service.BacExcelImportService;
import ro.upt.eduplatform.service.CorrelationService;
import ro.upt.eduplatform.service.EnExcelImportService;
import ro.upt.eduplatform.service.StatisticsService;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final StatisticsService statisticsService;
    private final MlPredictionService mlService;
    private final CorrelationService correlationService;

    private final BacExcelImportService bacExcelImportService;
    private final EnExcelImportService enExcelImportService;

    @PostMapping("/import/bac/{year}")
    public ResponseEntity<?> importBacExcel(
            @PathVariable int year,
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("Import BAC {} solicitat, fisier: {}", year, file.getOriginalFilename());
            int salvate = bacExcelImportService.importFromExcel(file.getInputStream(), year);
            return ResponseEntity.ok(Map.of("an", year, "salvate", salvate));
        } catch (Exception e) {
            log.error("Eroare import BAC {}: {}", year, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/import/en/{year}")
    public ResponseEntity<?> importEnExcel(
            @PathVariable int year,
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("Import EN {} solicitat, fisier: {}", year, file.getOriginalFilename());
            int salvate = enExcelImportService.importFromExcel(file.getInputStream(), year);
            return ResponseEntity.ok(Map.of("an", year, "salvate", salvate));
        } catch (Exception e) {
            log.error("Eroare import EN {}: {}", year, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/statistici/{county}/{year}")
    public ResponseEntity<?> statisticiJudet(
            @PathVariable String county,
            @PathVariable int year) {
        var stats = statisticsService.calculateCountyStatistics(county, year);
        if (stats == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/statistici/judete/{year}")
    public ResponseEntity<List<StatisticsService.CountyStatistics>> comparatieJudete(
            @PathVariable int year) {
        return ResponseEntity.ok(statisticsService.compareCounties(year));
    }

    @GetMapping("/statistici/tendinte")
    public ResponseEntity<List<StatisticsService.YearlyTrend>> tendinte() {
        return ResponseEntity.ok(statisticsService.getLongitudinalTrends());
    }

    @GetMapping("/statistici/distributie/{county}/{year}")
    public ResponseEntity<Map<String, Long>> distributieNote(
            @PathVariable String county,
            @PathVariable int year) {
        return ResponseEntity.ok(statisticsService.getGradeDistribution(county, year));
    }

    @PostMapping("/ml/train")
    public ResponseEntity<MlPredictionService.ModelMetrics> trainModel() {
        log.info("Antrenament ML solicitat prin API");
        return ResponseEntity.ok(mlService.trainModel());
    }

    @PostMapping("/ml/predict")
    public ResponseEntity<MlPredictionService.PredictionResult> predict(
            @RequestBody MlPredictionService.PredictionRequest request) {
        log.info("Predictie solicitata: {}", request);
        return ResponseEntity.ok(mlService.predict(request));
    }

    @GetMapping("/ml/status")
    public ResponseEntity<Map<String, Object>> mlStatus() {
        return ResponseEntity.ok(Map.of(
                "modelAntrenat", mlService.isModelTrained(),
                "metrici", mlService.getLastMetrics() != null
                        ? mlService.getLastMetrics()
                        : "Modelul nu a fost inca antrenat. Apeleaza POST /api/ml/train"
        ));
    }

    @GetMapping("/meta/ani")
    public ResponseEntity<List<Integer>> aniDisponibili() {
        return ResponseEntity.ok(statisticsService.getAvailableBacYears());
    }

    @GetMapping("/meta/ani/en")
    public ResponseEntity<List<Integer>> aniEnDisponibili() {
        return ResponseEntity.ok(statisticsService.getAvailableEnYears());
    }

    @GetMapping("/meta/judete")
    public ResponseEntity<List<String>> judeteDisponibile() {
        return ResponseEntity.ok(statisticsService.getAvailableCounties());
    }

    @GetMapping("/statistici/en/{county}/{year}")
    public ResponseEntity<?> statisticiEnJudet(
            @PathVariable String county,
            @PathVariable int year) {
        var stats = statisticsService.calculateEnCountyStatistics(county, year);
        if (stats == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/statistici/en/tendinte")
    public ResponseEntity<List<StatisticsService.EnYearlyTrend>> tendinteLongitudinaleEn() {
        return ResponseEntity.ok(statisticsService.getEnLongitudinalTrends());
    }

    @GetMapping("/corelare/cohorte")
    public ResponseEntity<List<CorrelationService.CohortStatistics>> allCohortaStatistici() {
        return ResponseEntity.ok(correlationService.getAllCohortStatistics());
    }

    @GetMapping("/corelare/cohorta/{anEn}")
    public ResponseEntity<?> cohortaStatistici(@PathVariable int anEn) {
        var stats = correlationService.getAllCohortStatistics().stream().filter(c -> c.enYear() == anEn).findFirst().orElse(null);
        if (stats == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/corelare/judete/{anEn}")
    public ResponseEntity<List<CorrelationService.CountyCohortStatistics>> corelatiePerJudet(
            @PathVariable int anEn) {
        return ResponseEntity.ok(correlationService.getCountyStatisticsForCohort(anEn));
    }

    @GetMapping("/corelare/evolutie/{anEn}")
    public ResponseEntity<List<CorrelationService.CountyEvolution>> evolutiePerJudet(
            @PathVariable int anEn) {
        return ResponseEntity.ok(correlationService.getEvolutionPerCounty(anEn));
    }

    @GetMapping("/corelare/ani-disponibili")
    public ResponseEntity<List<Integer>> aniCuCohortaCompleta() {
        return ResponseEntity.ok(correlationService.getCompleteEnYears());
    }
}