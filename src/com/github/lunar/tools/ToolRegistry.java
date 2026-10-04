package com.github.lunar.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

/**
 * The tool registry, built once from the {@code com.github.lunar.core.tools} extension point.
 *
 * <p>Memoisation is the point of this class. The reference implementation this replaces re-ran
 * {@code getDeclaredMethods()} on every validate, every dispatch and every call, which is the most
 * expensive mistake in it and free to avoid. The map is built on the first lookup and never
 * rebuilt; a hot-reload hook is P3's problem and is not speculative scaffolding here.
 */
public final class ToolRegistry {

    public static final String EXTENSION_POINT = "com.github.lunar.core.tools";

    private static volatile Map<String, Tool> cache;
    private static final List<ToolProvider> providers = new ArrayList<>();

    private ToolRegistry() {
    }

    public static Map<String, Tool> all() {
        Map<String, Tool> local = cache;
        if (local == null) {
            synchronized (ToolRegistry.class) {
                local = cache;
                if (local == null) {
                    local = Collections.unmodifiableMap(build());
                    cache = local;
                }
            }
        }
        return local;
    }

    /**
     * Test seam: the self-check runs outside OSGi, where {@link Platform#getExtensionRegistry()}
     * is unavailable, so it needs to inject a map and assert the memoisation around it.
     */
    public static void installForTest(Map<String, Tool> tools) {
        cache = tools == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    private static Map<String, Tool> build() {
        Map<String, Tool> out = new LinkedHashMap<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) {
            return out;
        }
        List<String> problems = new ArrayList<>();
        for (IConfigurationElement ext : registry
                .getConfigurationElementsFor(EXTENSION_POINT)) {
            String className = ext.getAttribute("class");
            if (className == null || className.isBlank()) {
                problems.add("contribution with no class attribute");
                continue;
            }
            List<Tool> contributed;
            try {
                Object instance = ext.createExecutableExtension("class");
                if (instance instanceof ToolProvider provider) providers.add(provider);
                contributed = "provider".equals(ext.getName())
                        ? ((ToolProvider) instance).tools() : List.of((Tool) instance);
            } catch (CoreException | RuntimeException e) {
                // One bad contribution must not silently shrink the toolset. Recorded and logged.
                problems.add(className + ": " + e);
                continue;
            }
            for (Tool tool : contributed) {
                try {
                    ToolSpec spec = tool.spec();
                    String declaredTier = "provider".equals(ext.getName()) ? null
                            : ext.getAttribute("riskTier");
                    if (declaredTier != null
                            && !declaredTier.trim().equalsIgnoreCase(spec.riskTier().wire())) {
                        throw new IllegalArgumentException("plugin.xml riskTier '" + declaredTier
                                + "' disagrees with '" + spec.riskTier().wire() + "'");
                    }
                    SchemaValidator.validateDefinition(spec.inputSchema());
                    if (out.putIfAbsent(spec.name(), tool) != null) {
                        problems.add("duplicate tool name '" + spec.name() + "' from " + className);
                    }
                } catch (RuntimeException e) {
                    problems.add(className + ": invalid tool contribution: " + e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            Platform.getLog(ToolRegistry.class).error(
                    "lunar tool registry rejected " + problems.size() + " contribution(s): "
                            + String.join("; ", problems));
        }
        return out;
    }

    public static synchronized void close() {
        for (ToolProvider provider : providers) {
            try { provider.close(); }
            catch (Exception exception) { Platform.getLog(ToolRegistry.class).error("Lunar provider shutdown failed",exception); }
        }
        providers.clear();
        cache = null;
    }

    /** The MCP {@code tools/list} tool array. Ordered, so a client sees a stable list. */
    public static List<Object> toolList() {
        List<Object> tools = new ArrayList<>();
        for (Tool tool : all().values()) {
            ToolSpec s = tool.spec();
            // riskTier rides inside the schema as x-lunar-risk-tier rather than as a sibling of
            // inputSchema, because MCP fixes the tool object's members and an unrecognised sibling
            // is a client-side warning at best. P8 reads it from here when generating the
            // per-agent client permission configuration.
            Map<String, Object> schema = new LinkedHashMap<>(s.inputSchema());
            schema.put("x-lunar-risk-tier", s.riskTier().wire());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", s.name());
            one.put("title", s.title());
            one.put("description", s.description());
            one.put("inputSchema", schema);
            tools.add(one);
        }
        return tools;
    }
}
