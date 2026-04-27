package ro.upt.eduplatform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.upt.eduplatform.model.BacResult;
import ro.upt.eduplatform.model.EnResult;
import ro.upt.eduplatform.repository.BacResultRepository;
import ro.upt.eduplatform.repository.EnResultRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final BacResultRepository bacRepository;
    private final EnResultRepository enRepository;

    public record CountyStatistics(
            String county,
            Integer year,
            Long totalCandidates,
            Long passed,
            Long failed,
            Double passingRate,
            Double generalAverage,
            Map<String, Long> categoryDistribution
    ) {}

    public record YearlyTrend(
            Integer year,
            Double generalAverage,
            Long totalCandidates,
            Double passingRate
    ) {}

    public record EnCountyStatistics(
            String county,
            Integer year,
            Long totalCandidates,
            Double generalAverage,
            Double romanianAverage,
            Double mathAverage,
            Map<String, Long> gradeDistribution
    ) {}

    public record EnYearlyTrend(
            Integer year,
            Double generalAverage,
            Double romanianAverage,
            Double mathAverage,
            Long totalCandidates
    ) {}

    public CountyStatistics calculateCountyStatistics(String county, int year) {
        List<BacResult> results = bacRepository.findByCountyAndYear(county.toUpperCase(), year);
        if (results.isEmpty()) return null;

        long total = results.size();
        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.getIsPassed())).count();
        long failed = results.stream().filter(r -> Boolean.FALSE.equals(r.getIsPassed())).count();

        double avg = results.stream()
                .filter(r -> r.getGeneralAverage() != null)
                .mapToDouble(BacResult::getGeneralAverage)
                .average().orElse(0.0);

        double rata = total > 0 ? (double) passed / total * 100 : 0;

        Map<String, Long> distribution = results.stream()
                .filter(r -> r.getAverageCategory() != null)
                .collect(Collectors.groupingBy(BacResult::getAverageCategory, Collectors.counting()));

        return new CountyStatistics(
                county.toUpperCase(), year, total, passed, failed,
                Math.round(rata * 10.0) / 10.0,
                Math.round(avg * 100.0) / 100.0,
                distribution
        );
    }

    public List<YearlyTrend> getLongitudinalTrends() {
        List<Object[]> raw = bacRepository.statisticsPerYear();
        List<YearlyTrend> trends = new ArrayList<>();

        for (Object[] row : raw) {
            int an       = ((Number) row[0]).intValue();
            double media = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            long total   = ((Number) row[2]).longValue();
            Double rata  = bacRepository.passingRateByYear(an);
            trends.add(new YearlyTrend(
                    an,
                    Math.round(media * 100.0) / 100.0,
                    total,
                    rata != null ? Math.round(rata * 10.0) / 10.0 : 0.0
            ));
        }
        return trends;
    }

    public List<CountyStatistics> compareCounties(int year) {
        List<String> counties = bacRepository.findDistinctCounties();
        return counties.stream()
                .map(j -> calculateCountyStatistics(j, year))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(CountyStatistics::generalAverage).reversed())
                .collect(Collectors.toList());
    }

    public Map<String, Long> getGradeDistribution(String county, int year) {
        List<BacResult> results = bacRepository.findByCountyAndYear(county.toUpperCase(), year);

        Map<String, Long> distribution = new LinkedHashMap<>();
        String[] intervale = {"1-2","2-3","3-4","4-5","5-6","6-7","7-8","8-9","9-10"};
        for (String i : intervale) distribution.put(i, 0L);

        for (BacResult r : results) {
            if (r.getGeneralAverage() == null) continue;
            distribution.merge(getInterval(r.getGeneralAverage()), 1L, Long::sum);
        }
        return distribution;
    }

    public EnCountyStatistics calculateEnCountyStatistics(String county, int year) {
        List<EnResult> results = enRepository.findByCountyAndYear(county.toUpperCase(), year);
        if (results.isEmpty()) return null;

        long total = results.size();

        double avgGen = results.stream()
                .filter(r -> r.getAverage() != null)
                .mapToDouble(EnResult::getAverage)
                .average().orElse(0.0);

        double avgRomanian = results.stream()
                .filter(r -> r.getRomanianGrade() != null)
                .mapToDouble(EnResult::getRomanianGrade)
                .average().orElse(0.0);

        double avgMath = results.stream()
                .filter(r -> r.getMathematicsGrade() != null)
                .mapToDouble(EnResult::getMathematicsGrade)
                .average().orElse(0.0);

        Map<String, Long> distribution = new LinkedHashMap<>();
        String[] intervals = {"1-2","2-3","3-4","4-5","5-6","6-7","7-8","8-9","9-10"};
        for (String i : intervals) distribution.put(i, 0L);
        for (EnResult r : results) {
            if (r.getAverage() == null) continue;
            distribution.merge(getInterval(r.getAverage()), 1L, Long::sum);
        }

        return new EnCountyStatistics(
                county.toUpperCase(), year, total,
                Math.round(avgGen * 100.0) / 100.0,
                Math.round(avgRomanian * 100.0) / 100.0,
                Math.round(avgMath * 100.0) / 100.0,
                distribution
        );
    }

    public List<EnYearlyTrend> getEnLongitudinalTrends() {
        List<Integer> years = enRepository.findDistinctAni();
        List<EnYearlyTrend> trends = new ArrayList<>();

        for (Integer an : years) {
            Long total   = enRepository.countByYear(an);
            Double avgGen   = enRepository.avgAverageByYear(an);
            Double avgRom   = enRepository.avgRomanianByYear(an);
            Double avgMath  = enRepository.avgMathByYear(an);
            if (total == null || total == 0) continue;

            trends.add(new EnYearlyTrend(
                    an,
                    avgGen  != null ? Math.round(avgGen  * 100.0) / 100.0 : 0.0,
                    avgRom  != null ? Math.round(avgRom  * 100.0) / 100.0 : 0.0,
                    avgMath != null ? Math.round(avgMath * 100.0) / 100.0 : 0.0,
                    total
            ));
        }
        return trends.stream()
                .sorted(Comparator.comparingInt(EnYearlyTrend::year))
                .collect(Collectors.toList());
    }

    public List<Integer> getAvailableBacYears() {
        return bacRepository.findDistinctYears();
    }

    public List<Integer> getAvailableEnYears() {
        return enRepository.findDistinctAni();
    }

    public List<String> getAvailableCounties() {
        return bacRepository.findDistinctCounties();
    }

    public Map<String, Object> getSummary() {
        Long totalBac = bacRepository.count();
        Long totalEn  = enRepository.countAll();
        List<Integer> bacYears = bacRepository.findDistinctYears();
        List<Integer> enYears  = enRepository.findDistinctAni();
        List<String> counties  = bacRepository.findDistinctCounties()
                .stream().filter(c -> !"XX".equals(c)).collect(Collectors.toList());

        return Map.of(
                "totalBacCandidates", totalBac != null ? totalBac : 0L,
                "totalEnCandidates",  totalEn  != null ? totalEn  : 0L,
                "bacYears",  bacYears,
                "enYears",   enYears,
                "nrCounties", counties.size()
        );
    }

    private String getInterval(double grade) {
        if (grade < 2) return "1-2";
        if (grade < 3) return "2-3";
        if (grade < 4) return "3-4";
        if (grade < 5) return "4-5";
        if (grade < 6) return "5-6";
        if (grade < 7) return "6-7";
        if (grade < 8) return "7-8";
        if (grade < 9) return "8-9";
        return "9-10";
    }
}