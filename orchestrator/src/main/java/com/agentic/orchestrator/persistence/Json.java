package com.agentic.orchestrator.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSON conversion for the columns that hold structured values.
 *
 * <p>Failures throw rather than returning a default. A plan that will not deserialise means the
 * stored state is not what this build expects, and quietly substituting an empty plan would turn a
 * loud startup failure into a run that silently does nothing.
 */
@Component
public class Json {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialise " + value.getClass(), failure);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not deserialise " + type.getSimpleName(), failure);
        }
    }

    public Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not deserialise map", failure);
        }
    }

    public List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not deserialise list", failure);
        }
    }
}
