package ro.upt.eduplatform.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "en_results",
    uniqueConstraints = @UniqueConstraint(columnNames = {"anonymous_id", "year"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnResult {

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

    @Column(name = "romanian_grade")
    private Double romanianGrade;

    @Column(name = "mathematics_grade")
    private Double mathematicsGrade;

    @Column(name = "environment", length = 20)
    private String environment;

    @Column(name = "average_category", length = 10)
    private String averageCategory;

    @Column(name = "native_language_grade")
    private Double nativeLanguageGrade;

    @Column(name = "average")
    private Double average;

    @Column(name = "average_grade_viii")
    private Double averageGradeVIII;
}
