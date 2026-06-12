package ro.upt.eduplatform.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.upt.eduplatform.model.BacResult;
import ro.upt.eduplatform.repository.BacResultRepository;
import ro.upt.eduplatform.repository.EnResultRepository;
import ro.upt.eduplatform.service.CorrelationService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnImputationService {

    private final BacResultRepository bacRepository;
    private final EnResultRepository enRepository;
    private final CorrelationService correlationService;

    private static final int EN_TO_BAC_GAP = 4;
    private static final int EXCLUDED_BAC_YEAR = 2022;

    private final Map<String, Double> cohortEnAvg = new ConcurrentHashMap<>();
    private final Map<String, Double> cohortBacAvg = new ConcurrentHashMap<>();
    private final Map<Integer, Double> cohortPearson = new ConcurrentHashMap<>();

    private volatile boolean statsLoaded = false;

    public synchronized void loadStatistics() {
        if (statsLoaded) return;
        log.info("Loading cohort statistics for EN imputation...");

        for (CorrelationService.CohortStatistics stat : correlationService.getAllCohortStatistics()) {
            if (stat.pearsonCoefficient() != null) {
                cohortPearson.put(stat.bacYear(), stat.pearsonCoefficient());
            }
        }

        for (Integer year : enRepository.findDistinctAni()) {
            List<Object[]> rows = enRepository.statisticsByCountyEnvironmentYear(year);
            for (Object[] row : rows) {
                String county = (String) row[0];
                String env = (String) row[1];
                Double avg = row[2] != null ? ((Number) row[2]).doubleValue() : null;
                if (county != null && env != null && avg != null) {
                    cohortEnAvg.put(key(county, env, year), avg);
                }
            }
        }

        for (Integer year : bacRepository.findDistinctYears()) {
            List<Object[]> rows = bacRepository.statisticsByCountyEnvironmentYear(year);
            for (Object[] row : rows) {
                String county = (String) row[0];
                String env = (String) row[1];
                Double avg = row[2] != null ? ((Number) row[2]).doubleValue() : null;
                if (county != null && env != null && avg != null) {
                    cohortBacAvg.put(key(county, env, year), avg);
                }
            }
        }

        statsLoaded = true;
        log.info("Loaded EN imputation stats: {} EN cohorts, {} BAC cohorts, {} Pearson coefficients",
                cohortEnAvg.size(), cohortBacAvg.size(), cohortPearson.size());
    }

    public Double imputeEnGrade(BacResult bac) {
        if (!statsLoaded) loadStatistics();

        if (bac.getCounty() == null || "XX".equals(bac.getCounty())) return null;
        if (bac.getEnvironment() == null) return null;
        if (bac.getYear() == null || bac.getGeneralAverage() == null) return null;
        if (bac.getYear() == EXCLUDED_BAC_YEAR) return null;

        String env = bac.getEnvironment().toUpperCase();
        if (!"URBAN".equals(env) && !"RURAL".equals(env)) return null;

        int bacYear = bac.getYear();
        int enYear = bacYear - EN_TO_BAC_GAP;

        Double cohortBacMean = cohortBacAvg.get(key(bac.getCounty(), env, bacYear));
        Double cohortEnMean = cohortEnAvg.get(key(bac.getCounty(), env, enYear));
        Double r = cohortPearson.get(bacYear);

        if (cohortBacMean == null || cohortEnMean == null || r == null) return null;

        double imputedEn = cohortEnMean + r * (bac.getGeneralAverage() - cohortBacMean);

        double clamped = Math.max(1.0, Math.min(10.0, imputedEn));
        return Math.round(clamped * 10.0) / 10.0;
    }

    public Double imputeEnGradeFromCohortOnly(BacResult bac) {
        if (!statsLoaded) loadStatistics();

        if (bac.getCounty() == null || "XX".equals(bac.getCounty())) return null;
        if (bac.getEnvironment() == null) return null;
        if (bac.getYear() == null) return null;
        if (bac.getYear() == EXCLUDED_BAC_YEAR) return null;

        String env = bac.getEnvironment().toUpperCase();
        if (!"URBAN".equals(env) && !"RURAL".equals(env)) return null;

        int enYear = bac.getYear() - EN_TO_BAC_GAP;
        Double cohortEnMean = cohortEnAvg.get(key(bac.getCounty(), env, enYear));
        if (cohortEnMean == null) return null;

        return Math.round(cohortEnMean * 10.0) / 10.0;
    }

    public void clearCache() {
        cohortEnAvg.clear();
        cohortBacAvg.clear();
        cohortPearson.clear();
        statsLoaded = false;
        log.info("EnImputationService cache cleared");
    }

    private String key(String county, String env, int year) {
        return county + "_" + env + "_" + year;
    }
}