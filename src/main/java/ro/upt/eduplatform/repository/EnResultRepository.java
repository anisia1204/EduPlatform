package ro.upt.eduplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ro.upt.eduplatform.model.EnResult;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnResultRepository extends JpaRepository<EnResult, Long> {

    @Query("SELECT COUNT(e) > 0 FROM EnResult e WHERE e.anonymousId = :anonymousId AND e.year = :year")
    boolean existsByAnonymousIdAndYear(@Param("anonymousId") String anonymousId, @Param("year") Integer year);

    List<EnResult> findByCountyAndYear(String county, Integer year);

    List<EnResult> findByYear(Integer year);

    @Query("SELECT DISTINCT e.year FROM EnResult e ORDER BY e.year")
    List<Integer> findDistinctAni();

    @Query("SELECT e.county, AVG(e.average), COUNT(e) FROM EnResult e " +
            "WHERE e.year = :year AND e.average IS NOT NULL AND e.county <> 'XX' " +
            "GROUP BY e.county ORDER BY AVG(e.average) DESC")
    List<Object[]> aggregateByCountyAndYear(@Param("year") Integer year);

    @Query("SELECT AVG(e.average) FROM EnResult e WHERE e.year = :year AND e.average IS NOT NULL")
    Double avgAverageByYear(@Param("year") Integer year);

    @Query("SELECT COUNT(e) FROM EnResult e WHERE e.year = :year")
    Long countByYear(@Param("year") Integer year);

    @Query("SELECT AVG(e.romanianGrade) FROM EnResult e WHERE e.year = :year AND e.romanianGrade IS NOT NULL")
    Double avgRomanianByYear(@Param("year") Integer year);

    @Query("SELECT AVG(e.mathematicsGrade) FROM EnResult e WHERE e.year = :year AND e.mathematicsGrade IS NOT NULL")
    Double avgMathByYear(@Param("year") Integer year);

    @Query("SELECT COUNT(e) FROM EnResult e")
    Long countAll();
}