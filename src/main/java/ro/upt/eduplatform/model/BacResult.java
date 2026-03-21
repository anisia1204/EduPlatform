package ro.upt.eduplatform.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "bac_results",
    uniqueConstraints = @UniqueConstraint(columnNames = {"anonymous_id", "year"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anonymous_id", nullable = false, length = 16)
    private String anonymousId;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "county", length = 5)
    private String county;

    @Column(name = "school_unit")
    private String schoolUnit;

    @Column(name = "profile", length = 300)
    private String profile;

    @Column(name = "general_average")
    private Double generalAverage;

    @Column(name = "romanian_grade")
    private Double romanianGrade;

    @Column(name = "mandatory_subject_grade")
    private Double mandatorySubjectGrade;

    @Column(name = "elective_subject_grade")
    private Double electiveSubjectGrade;

    /** true = promovat, false = respins, null = absent */
    @Column(name = "is_passed")
    private Boolean isPassed;

    @Column(name = "raw_result", length = 30)
    private String rawResult;

    /**
     * Categoria de medie - folosita direct ca feature pentru ML.
     * Valori: "sub5", "5-6", "6-7", "7-8", "8-9", "9-10"
     */
    @Column(name = "average_category", length = 20)
    private String averageCategory;

    @Column(name = "environment", length = 10)
    private String environment;
}
