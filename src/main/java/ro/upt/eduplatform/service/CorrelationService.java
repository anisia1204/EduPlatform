package ro.upt.eduplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.upt.eduplatform.repository.BacResultRepository;
import ro.upt.eduplatform.repository.EnResultRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CorrelationService {

    private final EnResultRepository enRepository;
    private final BacResultRepository bacRepository;

    private static final int EN_TO_BAC_GAP = 4;

    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public record CohortStatistics(
            Integer enYear,
            Integer bacYear,
            Long totalEnCandidates,
            Long totalBacCandidates,
            Double nationalEnAverage,
            Double nationalBacAverage,
            Double bacPassingRate,
            Double pearsonCoefficient,
            String correlationLabel
    ) {}

    public record CountyCohortStatistics(
            String county,
            Integer enYear,
            Integer bacYear,
            Long enCandidates,
            Long bacCandidates,
            Double enCountyAverage,
            Double bacCountyAverage,
            Double bacPassingRate
    ) {}

    public record CountyEvolution(
            String county,
            Double enAverage,
            Double bacAverage,
            Double averageDelta,
            Double bacPassingRate
    ) {}

    @SuppressWarnings("unchecked")
    public List<CohortStatistics> getAllCohortStatistics() {
        return (List<CohortStatistics>) cache.computeIfAbsent("allCohorts", k -> {
            log.info("Computing all cohort statistics (first call — will be cached)");
            return computeAllCohorts();
        });
    }

    @SuppressWarnings("unchecked")
    public List<CountyCohortStatistics> getCountyStatisticsForCohort(int enYear) {
        return (List<CountyCohortStatistics>) cache.computeIfAbsent(
                "countyStats_" + enYear, k -> computeCountyStats(enYear));
    }

    public List<CountyEvolution> getEvolutionPerCounty(int enYear) {
        return getCountyStatisticsForCohort(enYear).stream()
                .map(s -> new CountyEvolution(
                        s.county(), s.enCountyAverage(), s.bacCountyAverage(),
                        Math.round((s.bacCountyAverage() - s.enCountyAverage()) * 100.0) / 100.0,
                        s.bacPassingRate()
                ))
                .sorted(Comparator.comparingDouble(CountyEvolution::averageDelta).reversed())
                .collect(Collectors.toList());
    }

    public List<Integer> getCompleteEnYears() {
        List<Integer> enYears = enRepository.findDistinctAni();
        Set<Integer> bacYears = new HashSet<>(bacRepository.findDistinctYears());
        return enYears.stream()
                .filter(y -> bacYears.contains(y + EN_TO_BAC_GAP))
                .sorted()
                .collect(Collectors.toList());
    }

    public void clearCache() {
        cache.clear();
        log.info("Correlation cache cleared");
    }

    private List<CohortStatistics> computeAllCohorts() {
        List<Integer> enYears = getCompleteEnYears();
        List<CohortStatistics> results = new ArrayList<>();

        for (Integer enYear : enYears) {
            int bacYear = enYear + EN_TO_BAC_GAP;
            CohortStatistics stats = computeOneCohort(enYear, bacYear);
            if (stats != null) results.add(stats);
        }
        return results.stream()
                .sorted(Comparator.comparingInt(CohortStatistics::enYear))
                .collect(Collectors.toList());
    }

    private CohortStatistics computeOneCohort(int enYear, int bacYear) {
        Long enTotal = enRepository.countByYear(enYear);
        Long bacTotal = bacRepository.countByYear(bacYear);

        if (enTotal == null || enTotal == 0 || bacTotal == null || bacTotal == 0) return null;

        Double enNational = enRepository.avgAverageByYear(enYear);
        Double bacNational = bacRepository.avgMediaByYear(bacYear);
        Double passRate = bacRepository.passingRateByYear(bacYear);

        List<CountyCohortStatistics> countyStats = computeCountyStats(enYear);
        double pearson = computePearson(countyStats);

        log.info("Cohort EN{} → BAC{}: {} EN, {} BAC, r={:.3f}", enYear, bacYear, enTotal, bacTotal, pearson);

        return new CohortStatistics(
                enYear, bacYear, enTotal, bacTotal,
                round2(enNational), round2(bacNational),
                round1(passRate), round3(pearson),
                labelCorrelation(pearson)
        );
    }

    private List<CountyCohortStatistics> computeCountyStats(int enYear) {
        int bacYear = enYear + EN_TO_BAC_GAP;

        List<Object[]> enRows = enRepository.aggregateByCountyAndYear(enYear);
        List<Object[]> bacRows = bacRepository.statisticsPerCountyAndYear(bacYear);

        Map<String, double[]> enByCounty = new HashMap<>();
        for (Object[] row : enRows) {
            String county = (String) row[0];
            double avg = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            long count = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            enByCounty.put(county, new double[]{avg, count});
        }

        List<CountyCohortStatistics> result = new ArrayList<>();
        for (Object[] row : bacRows) {
            String county = (String) row[0];
            if ("XX".equals(county)) continue;
            double bacAvg = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            long bacCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            long bacPassed = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            double passRate = bacCount > 0 ? (double) bacPassed / bacCount * 100 : 0;

            double[] en = enByCounty.get(county);
            if (en == null || en[0] == 0) continue;

            result.add(new CountyCohortStatistics(
                    county, enYear, bacYear,
                    (long) en[1], bacCount,
                    round2(en[0]), round2(bacAvg), round1(passRate)
            ));
        }

        return result.stream()
                .sorted(Comparator.comparingDouble(CountyCohortStatistics::enCountyAverage).reversed())
                .collect(Collectors.toList());
    }

    private double computePearson(List<CountyCohortStatistics> counties) {
        List<CountyCohortStatistics> valid = counties.stream()
                .filter(c -> c.enCountyAverage() > 0 && c.bacCountyAverage() > 0)
                .toList();
        int n = valid.size();
        if (n < 5) return 0.0;

        double[] x = valid.stream().mapToDouble(CountyCohortStatistics::enCountyAverage).toArray();
        double[] y = valid.stream().mapToDouble(CountyCohortStatistics::bacCountyAverage).toArray();

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i]; sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i]; sumY2 += y[i] * y[i];
        }
        double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denom == 0 ? 0.0 : (n * sumXY - sumX * sumY) / denom;
    }

    private String labelCorrelation(double r) {
        double abs = Math.abs(r);
        String dir = r >= 0 ? "positive" : "negative";
        if (abs >= 0.7) return "Strong " + dir + " correlation";
        if (abs >= 0.5) return "Moderate " + dir + " correlation";
        if (abs >= 0.3) return "Weak " + dir + " correlation";
        return "Negligible correlation";
    }

    private static double round1(Double v) { return v == null ? 0.0 : Math.round(v * 10.0) / 10.0; }
    private static double round2(Double v) { return v == null ? 0.0 : Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}