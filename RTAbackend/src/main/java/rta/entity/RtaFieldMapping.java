package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_field_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaFieldMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "canonical_field")
    private String canonicalField;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "required")
    private Boolean required;

    @Column(name = "source_column_name")
    private String sourceColumnName;

    @Column(name = "source_column_idx")
    private Integer sourceColumnIdx;

    @Column(name = "fixed_start_pos")
    private Integer fixedStartPos;

    @Column(name = "fixed_end_pos")
    private Integer fixedEndPos;

    @Column(name = "transform_expr")
    private String transformExpr;

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "validation_regex")
    private String validationRegex;

    @Column(name = "allowed_values")
    private String allowedValues;

    @Column(name = "null_values")
    private String nullValues;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
