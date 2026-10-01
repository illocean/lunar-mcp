package com.github.lunar.tools;

import java.util.Map;

/**
 * A tool's declaration: what it is called, what it does, and what it accepts.
 *
 * @param name        wire name, unique across the registry, matched exactly by tools/call
 * @param title       one-line human label for menus and agent config
 * @param description prose the model reads before calling; this is the real prompt budget
 * @param inputSchema JSON Schema object, the single source of truth for both validation and the
 *                    {@code inputSchema} published in tools/list
 * @param riskTier    declaration only; enforcement uses client per-agent permissions generated
 *                    from the verified tools/list inventory.
 */
public record ToolSpec(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        RiskTier riskTier) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (inputSchema == null) {
            throw new IllegalArgumentException(name + ": inputSchema is required");
        }
        if (riskTier == null) {
            throw new IllegalArgumentException(name + ": riskTier is required");
        }
    }

    public enum RiskTier {
        READ, BUILD, MUTATE, DESTRUCTIVE;

        static RiskTier parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("riskTier is required");
            }
            for (RiskTier t : values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown riskTier '" + raw + "'; expected one of "
                    + java.util.Arrays.toString(values()));
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
