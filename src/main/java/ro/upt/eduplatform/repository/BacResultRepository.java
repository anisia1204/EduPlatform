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
}
