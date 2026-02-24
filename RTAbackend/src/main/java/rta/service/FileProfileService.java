package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rta.entity.RtaFieldMapping;
import rta.entity.RtaFileProfile;
import rta.repository.RtaFieldMappingRepository;
import rta.repository.RtaFileProfileRepository;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileProfileService {

    private final RtaFileProfileRepository fileProfileRepository;
    private final RtaFieldMappingRepository fieldMappingRepository;

    /**
     * Required canonical fields that every merchant file profile must include.
     */
    public static final List<String> REQUIRED_CANONICAL_FIELDS = List.of(
            "customer_reference",
            "account_num",
            "bank_code",
            "amount",
            "currency",
            "transaction_date",
            "recurring_type",
            "frequency_value",
            "start_date"
    );

    /**
     * Default data types for each required canonical field.
     */
    private static final Map<String, String> DEFAULT_DATA_TYPES = Map.of(
            "customer_reference", "STRING",
            "account_num", "STRING",
            "bank_code", "STRING",
            "amount", "DECIMAL",
            "currency", "STRING",
            "transaction_date", "DATE",
            "recurring_type", "STRING",
            "frequency_value", "INTEGER",
            "start_date", "DATE"
    );

    /**
     * Create a default file profile with the 9 required field mappings for a
     * new merchant.
     */
    @Transactional
    public RtaFileProfile createDefaultFileProfile(String merchantId, String createdBy,
            String fileType, String fieldDelimiter, Boolean hasHeader,
            String dateFormat, List<Map<String, Object>> fieldMappings) {

        // Create the file profile
        RtaFileProfile profile = new RtaFileProfile();
        profile.setMerchantId(merchantId);
        profile.setVersionNo(1);
        profile.setFileType(fileType != null ? fileType : "CSV");
        profile.setEncoding("UTF-8");
        profile.setFieldDelimiter(fieldDelimiter != null ? fieldDelimiter : ",");
        profile.setHasHeader(hasHeader != null ? hasHeader : true);
        profile.setHasFooter(false);
        profile.setLineEnding("CRLF");
        profile.setDateFormat(dateFormat != null ? dateFormat : "yyyy-MM-dd");
        profile.setDatetimeFormat("yyyy-MM-dd HH:mm:ss");
        profile.setStatus("ACTIVE");
        profile.setEffectiveFrom(LocalDateTime.now());
        profile.setCreatedAt(LocalDateTime.now());
        profile.setCreatedBy(createdBy);
        RtaFileProfile savedProfile = fileProfileRepository.save(profile);

        // Create field mappings
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            // Use custom field mappings from the request
            for (int i = 0; i < fieldMappings.size(); i++) {
                Map<String, Object> fm = fieldMappings.get(i);
                RtaFieldMapping mapping = new RtaFieldMapping();
                mapping.setProfileId(savedProfile.getProfileId());
                mapping.setCanonicalField((String) fm.get("canonicalField"));
                mapping.setDataType((String) fm.getOrDefault("dataType",
                        DEFAULT_DATA_TYPES.getOrDefault((String) fm.get("canonicalField"), "STRING")));
                mapping.setRequired((Boolean) fm.getOrDefault("required", true));
                mapping.setSourceColumnName((String) fm.getOrDefault("sourceColumnName",
                        (String) fm.get("canonicalField")));
                mapping.setSourceColumnIdx(fm.get("sourceColumnIdx") != null
                        ? ((Number) fm.get("sourceColumnIdx")).intValue() : i);
                mapping.setValidationRegex((String) fm.get("validationRegex"));
                mapping.setDefaultValue((String) fm.get("defaultValue"));
                mapping.setCreatedAt(LocalDateTime.now());
                fieldMappingRepository.save(mapping);
            }
        } else {
            // Create default mappings for all required fields
            int idx = 0;
            for (String field : REQUIRED_CANONICAL_FIELDS) {
                RtaFieldMapping mapping = new RtaFieldMapping();
                mapping.setProfileId(savedProfile.getProfileId());
                mapping.setCanonicalField(field);
                mapping.setDataType(DEFAULT_DATA_TYPES.getOrDefault(field, "STRING"));
                mapping.setRequired(true);
                mapping.setSourceColumnName(field);
                mapping.setSourceColumnIdx(idx++);
                mapping.setCreatedAt(LocalDateTime.now());
                fieldMappingRepository.save(mapping);
            }
        }

        log.info("Created default file profile for merchant {}: profileId={}", merchantId, savedProfile.getProfileId());
        return savedProfile;
    }

    /**
     * Get the active file profile for a merchant.
     */
    public Optional<RtaFileProfile> getActiveProfile(String merchantId) {
        return fileProfileRepository.findByMerchantIdAndStatus(merchantId, "ACTIVE");
    }

    /**
     * Get all file profiles for a merchant.
     */
    public List<RtaFileProfile> getProfilesByMerchant(String merchantId) {
        return fileProfileRepository.findByMerchantIdOrderByVersionNoDesc(merchantId);
    }

    /**
     * Get field mappings for a profile.
     */
    public List<RtaFieldMapping> getFieldMappings(Long profileId) {
        return fieldMappingRepository.findByProfileIdOrderBySourceColumnIdxAsc(profileId);
    }

    /**
     * Update field mappings for an existing profile.
     */
    @Transactional
    public void updateFieldMappings(Long profileId, List<Map<String, Object>> fieldMappings, String modifiedBy) {
        // Delete existing mappings
        fieldMappingRepository.deleteByProfileId(profileId);

        // Create new mappings
        for (int i = 0; i < fieldMappings.size(); i++) {
            Map<String, Object> fm = fieldMappings.get(i);
            RtaFieldMapping mapping = new RtaFieldMapping();
            mapping.setProfileId(profileId);
            mapping.setCanonicalField((String) fm.get("canonicalField"));
            mapping.setDataType((String) fm.getOrDefault("dataType",
                    DEFAULT_DATA_TYPES.getOrDefault((String) fm.get("canonicalField"), "STRING")));
            mapping.setRequired((Boolean) fm.getOrDefault("required", true));
            mapping.setSourceColumnName((String) fm.getOrDefault("sourceColumnName",
                    (String) fm.get("canonicalField")));
            mapping.setSourceColumnIdx(fm.get("sourceColumnIdx") != null
                    ? ((Number) fm.get("sourceColumnIdx")).intValue() : i);
            mapping.setValidationRegex((String) fm.get("validationRegex"));
            mapping.setDefaultValue((String) fm.get("defaultValue"));
            mapping.setCreatedAt(LocalDateTime.now());
            fieldMappingRepository.save(mapping);
        }

        // Update profile timestamp
        fileProfileRepository.findById(profileId).ifPresent(profile -> {
            profile.setLastModifiedAt(LocalDateTime.now());
            profile.setLastModifiedBy(modifiedBy);
            fileProfileRepository.save(profile);
        });

        log.info("Updated field mappings for profileId={}", profileId);
    }

    /**
     * Validate an incoming CSV file against the merchant's active file profile.
     * Returns a list of validation error messages. Empty list = valid.
     */
    public List<String> validateFile(String merchantId, String[] headerRow, List<String[]> dataRows) {
        List<String> errors = new ArrayList<>();

        // Get active profile
        Optional<RtaFileProfile> profileOpt = getActiveProfile(merchantId);
        if (profileOpt.isEmpty()) {
            errors.add("No active file profile found for merchant: " + merchantId);
            return errors;
        }

        RtaFileProfile profile = profileOpt.get();
        List<RtaFieldMapping> mappings = getFieldMappings(profile.getProfileId());

        if (mappings.isEmpty()) {
            errors.add("No field mappings configured for merchant: " + merchantId);
            return errors;
        }

        // Build a map of header name -> column index from the actual file
        Map<String, Integer> headerMap = new HashMap<>();
        if (headerRow != null) {
            for (int i = 0; i < headerRow.length; i++) {
                headerMap.put(headerRow[i].trim().toLowerCase(), i);
            }
        }

        // Validate required columns are present in the header
        for (RtaFieldMapping mapping : mappings) {
            if (Boolean.TRUE.equals(mapping.getRequired())) {
                String sourceCol = mapping.getSourceColumnName() != null
                        ? mapping.getSourceColumnName().toLowerCase()
                        : mapping.getCanonicalField().toLowerCase();

                if (Boolean.TRUE.equals(profile.getHasHeader())) {
                    // Header-based validation
                    if (!headerMap.containsKey(sourceCol)) {
                        errors.add("Missing required column: '" + mapping.getSourceColumnName()
                                + "' (canonical: " + mapping.getCanonicalField() + ")");
                    }
                } else {
                    // Index-based validation
                    if (mapping.getSourceColumnIdx() != null && headerRow != null
                            && mapping.getSourceColumnIdx() >= headerRow.length) {
                        errors.add("Column index " + mapping.getSourceColumnIdx()
                                + " out of range for field: " + mapping.getCanonicalField());
                    }
                }
            }
        }

        // If header validation already has errors, return early
        if (!errors.isEmpty()) {
            return errors;
        }

        // Validate data rows
        for (int rowIdx = 0; rowIdx < dataRows.size(); rowIdx++) {
            String[] row = dataRows.get(rowIdx);
            for (RtaFieldMapping mapping : mappings) {
                int colIdx;
                if (Boolean.TRUE.equals(profile.getHasHeader())) {
                    String sourceCol = mapping.getSourceColumnName() != null
                            ? mapping.getSourceColumnName().toLowerCase()
                            : mapping.getCanonicalField().toLowerCase();
                    Integer idx = headerMap.get(sourceCol);
                    if (idx == null) {
                        continue;
                    }
                    colIdx = idx;
                } else {
                    colIdx = mapping.getSourceColumnIdx() != null ? mapping.getSourceColumnIdx() : 0;
                }

                if (colIdx >= row.length) {
                    if (Boolean.TRUE.equals(mapping.getRequired())) {
                        errors.add("Row " + (rowIdx + 1) + ": Missing value for required field '"
                                + mapping.getCanonicalField() + "'");
                    }
                    continue;
                }

                String value = row[colIdx].trim();

                // Check required field is not empty
                if (Boolean.TRUE.equals(mapping.getRequired()) && value.isEmpty()) {
                    errors.add("Row " + (rowIdx + 1) + ": Empty value for required field '"
                            + mapping.getCanonicalField() + "'");
                    continue;
                }

                // Validate data type
                if (!value.isEmpty() && mapping.getDataType() != null) {
                    switch (mapping.getDataType().toUpperCase()) {
                        case "INTEGER":
                            try {
                                Long.parseLong(value);
                            } catch (NumberFormatException e) {
                                errors.add("Row " + (rowIdx + 1) + ": Invalid integer for '"
                                        + mapping.getCanonicalField() + "': " + value);
                            }
                            break;
                        case "DECIMAL":
                            try {
                                Double.parseDouble(value);
                            } catch (NumberFormatException e) {
                                errors.add("Row " + (rowIdx + 1) + ": Invalid decimal for '"
                                        + mapping.getCanonicalField() + "': " + value);
                            }
                            break;
                        case "DATE":
                            // Basic date format check
                            if (profile.getDateFormat() != null) {
                                try {
                                    java.time.format.DateTimeFormatter formatter
                                            = java.time.format.DateTimeFormatter.ofPattern(profile.getDateFormat());
                                    java.time.LocalDate.parse(value, formatter);
                                } catch (Exception e) {
                                    errors.add("Row " + (rowIdx + 1) + ": Invalid date for '"
                                            + mapping.getCanonicalField() + "': " + value
                                            + " (expected format: " + profile.getDateFormat() + ")");
                                }
                            }
                            break;
                    }
                }

                // Validate regex if configured
                if (!value.isEmpty() && mapping.getValidationRegex() != null
                        && !mapping.getValidationRegex().isBlank()) {
                    if (!value.matches(mapping.getValidationRegex())) {
                        errors.add("Row " + (rowIdx + 1) + ": Value '" + value
                                + "' does not match pattern for '" + mapping.getCanonicalField() + "'");
                    }
                }

                // Validate allowed values if configured
                if (!value.isEmpty() && mapping.getAllowedValues() != null
                        && !mapping.getAllowedValues().isBlank()) {
                    List<String> allowed = Arrays.asList(mapping.getAllowedValues().split(","));
                    if (!allowed.contains(value)) {
                        errors.add("Row " + (rowIdx + 1) + ": Value '" + value
                                + "' not in allowed values for '" + mapping.getCanonicalField() + "': "
                                + mapping.getAllowedValues());
                    }
                }
            }

            // Limit error messages to prevent flooding
            if (errors.size() > 100) {
                errors.add("... validation stopped after 100+ errors");
                break;
            }
        }

        return errors;
    }

    /**
     * Get the list of required canonical fields.
     */
    public List<String> getRequiredCanonicalFields() {
        return REQUIRED_CANONICAL_FIELDS;
    }
}
