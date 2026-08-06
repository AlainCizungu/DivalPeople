package ai.dival.dip.modules.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores notification parameters as JSON text.
 *
 * <p>Text rather than a native JSON column: the parameters are only ever read as a whole, never
 * queried into, so the portability is worth more than the query support.
 */
@Converter
public class NotificationParamsConverter implements AttributeConverter<Map<String, String>, String> {

    private static final Logger log = LoggerFactory.getLogger(NotificationParamsConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() { };
    private static final String EMPTY = "{}";

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception ex) {
            // Losing a parameter must not lose the notification: the key still renders.
            log.warn("Could not serialise notification parameters; storing empty", ex);
            return EMPTY;
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception ex) {
            log.warn("Could not read notification parameters; treating as empty", ex);
            return Map.of();
        }
    }
}
