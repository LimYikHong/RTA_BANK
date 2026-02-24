package rta.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rta_file_profile")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RtaFileProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "file_type")
    private String fileType;

    @Column(name = "encoding")
    private String encoding;

    @Column(name = "field_delimiter")
    private String fieldDelimiter;

    @Column(name = "quote_char")
    private String quoteChar;

    @Column(name = "escape_char")
    private String escapeChar;

    @Column(name = "has_header")
    private Boolean hasHeader;

    @Column(name = "has_footer")
    private Boolean hasFooter;

    @Column(name = "line_ending")
    private String lineEnding;

    @Column(name = "compression")
    private String compression;

    @Column(name = "date_format")
    private String dateFormat;

    @Column(name = "datetime_format")
    private String datetimeFormat;

    @Column(name = "record_layout")
    private String recordLayout;

    @Column(name = "extra_rules_json")
    private String extraRulesJson;

    @Column(name = "status")
    private String status;

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "sample_uri")
    private String sampleUri;

    @Column(name = "schema_hash")
    private String schemaHash;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;
}
