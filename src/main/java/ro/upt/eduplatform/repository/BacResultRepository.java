package ro.upt.eduplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ro.upt.eduplatform.model.BacResult;

@Repository
public interface BacResultRepository extends JpaRepository<BacResult, Long> {

    @Query("SELECT COUNT(b) > 0 FROM BacResult b WHERE b.anonymousId = :anonymousId AND b.year = :year")
    boolean existsByAnonymousIdAndYear(@Param("anonymousId") String anonymousId, @Param("year") Integer year);
}
