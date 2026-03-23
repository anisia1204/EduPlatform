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

}
