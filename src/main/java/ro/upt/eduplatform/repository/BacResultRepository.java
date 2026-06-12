package ro.upt.eduplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ro.upt.eduplatform.model.BacResult;

import java.util.List;

@Repository
public interface BacResultRepository extends JpaRepository<BacResult, Long> {

    @Query("SELECT COUNT(b) > 0 FROM BacResult b WHERE b.anonymousId = :anonymousId AND b.year = :year")
    boolean existsByAnonymousIdAndYear(@Param("anonymousId") String anonymousId, @Param("year") Integer year);

    List<BacResult> findByCountyAndYear(String county, Integer year);

    List<BacResult> findByYear(Integer year);

    @Query("SELECT DISTINCT b.year FROM BacResult b ORDER BY b.year")
    List<Integer> findDistinctYears();

    @Query("SELECT DISTINCT b.county FROM BacResult b ORDER BY b.county")
    List<String> findDistinctCounties();

    @Query("SELECT b.year, AVG(b.generalAverage), COUNT(b) FROM BacResult b GROUP BY b.year ORDER BY b.year")
    List<Object[]> statisticsPerYear();

    @Query("SELECT b.county, AVG(b.generalAverage), COUNT(b), " +
            "SUM(CASE WHEN b.isPassed = true THEN 1 ELSE 0 END) " +
            "FROM BacResult b WHERE b.year = :year " +
            "GROUP BY b.county ORDER BY b.county")
    List<Object[]> statisticsPerCountyAndYear(@Param("year") Integer year);

    @Query("SELECT AVG(b.generalAverage) FROM BacResult b WHERE b.year = :year AND b.generalAverage IS NOT NULL")
    Double avgMediaByYear(@Param("year") Integer year);

    @Query("SELECT COUNT(b) FROM BacResult b WHERE b.year = :year")
    Long countByYear(@Param("year") Integer year);

    @Query("SELECT (COUNT(CASE WHEN b.isPassed = true THEN 1 END) * 100.0 / COUNT(b)) " +
            "FROM BacResult b WHERE b.year = :year")
    Double passingRateByYear(@Param("year") Integer year);

    @Query("SELECT b FROM BacResult b WHERE b.generalAverage IS NOT NULL AND b.isPassed IS NOT NULL")
    List<BacResult> findAllWithCompleteData();

    @Query("SELECT b FROM BacResult b WHERE b.year >= 2019 AND b.year <> 2022")
    List<BacResult> findAllForEnBasedTraining();

    @Query("SELECT b.year, AVG(b.generalAverage), COUNT(b), " +
            "(COUNT(CASE WHEN b.isPassed = true THEN 1 END) * 100.0 / COUNT(b)) " +
            "FROM BacResult b WHERE b.county = :county " +
            "GROUP BY b.year ORDER BY b.year")
    List<Object[]> statisticsPerYearForCounty(@Param("county") String county);

    @Query("SELECT UPPER(b.environment), AVG(b.generalAverage), COUNT(b), " +
            "SUM(CASE WHEN b.isPassed = true THEN 1 ELSE 0 END) " +
            "FROM BacResult b WHERE b.year = :year AND b.environment IS NOT NULL " +
            "AND UPPER(b.environment) IN ('URBAN', 'RURAL') " +
            "GROUP BY UPPER(b.environment)")
    List<Object[]> statisticsByEnvironmentAndYear(@Param("year") Integer year);

    @Query("SELECT b.county, UPPER(b.environment), AVG(b.generalAverage), COUNT(b), " +
            "SUM(CASE WHEN b.isPassed = true THEN 1 ELSE 0 END) " +
            "FROM BacResult b WHERE b.year = :year AND b.environment IS NOT NULL " +
            "AND UPPER(b.environment) IN ('URBAN', 'RURAL') AND b.county <> 'XX' " +
            "GROUP BY b.county, UPPER(b.environment) ORDER BY b.county")
    List<Object[]> statisticsByCountyEnvironmentYear(@Param("year") Integer year);


    @Query("SELECT b.year, UPPER(b.environment), AVG(b.generalAverage), COUNT(b), " +
            "(COUNT(CASE WHEN b.isPassed = true THEN 1 END) * 100.0 / COUNT(b)) " +
            "FROM BacResult b WHERE b.environment IS NOT NULL " +
            "AND UPPER(b.environment) IN ('URBAN', 'RURAL') " +
            "GROUP BY b.year, UPPER(b.environment) ORDER BY b.year, UPPER(b.environment)")
    List<Object[]> environmentTrendsAllYears();

    @Query("SELECT b.year, AVG(b.generalAverage), COUNT(b), " +
            "SUM(CASE WHEN b.isPassed = true THEN 1 ELSE 0 END) " +
            "FROM BacResult b WHERE b.county = :county AND b.generalAverage IS NOT NULL " +
            "GROUP BY b.year ORDER BY b.year")
    List<Object[]> statisticsPerCountyAcrossYears(@Param("county") String county);
}