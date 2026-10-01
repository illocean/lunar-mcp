package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Lists the projects in the workspace.
 *
 * <p>Needs no JDT and no build, which is the point: it exercises the entire path — extension point,
 * memoised registry, schema validation, a Job with a scheduling rule, the result envelope — against
 * a real workspace with a real answer. A stub returning {@code {}} would prove the same amount as
 * P1's 47 green checks.
 */
public final class ListProjects implements Tool {

    private static final Map<String, Object> SCHEMA = schema();

    public ListProjects() {
    }

    private static Map<String, Object> schema() {
        Map<String, Object> includeClosed = new LinkedHashMap<>();
        includeClosed.put("type", "boolean");
        includeClosed.put("default", Boolean.TRUE);
        includeClosed.put("description",
                "Include projects that are not currently open. Defaults to true.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("includeClosed", includeClosed);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        // Explicit, not implicit. A schema that does not say this and a validator that does not
        // enforce it is how a client learns to send arguments that are silently dropped.
        s.put("additionalProperties", false);
        // Not Map.copyOf: it does not preserve insertion order, and tools/list is read by humans
        // comparing two runs.
        return java.util.Collections.unmodifiableMap(s);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("list_projects", "List workspace projects",
                "Lists every project in the Eclipse workspace by name, with whether it is currently "
                        + "open and its location. Takes no required arguments. Use this first to find "
                        + "out what exists before touching any project.",
                SCHEMA, ToolSpec.RiskTier.READ);
    }

    @Override
    public ToolResult call(Map<String, Object> args, CallBudget budget) throws Exception {
        long started = System.nanoTime();
        boolean includeClosed = !Boolean.FALSE.equals(args.get("includeClosed"));

        IWorkspace ws = ResourcesPlugin.getWorkspace();
        IProject[] projects = ws.getRoot().getProjects();

        List<Object> out = new ArrayList<>(projects.length);
        for (IProject p : projects) {
            budget.checkCancelled();
            if (!p.isOpen() && !includeClosed) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", p.getName());
            one.put("open", p.isOpen());
            // getLocation() is null for a project with no explicit location on disk, which is the
            // common case, so the workspace-relative path is the primary answer.
            one.put("path", p.getFullPath().toString());
            one.put("natureCount", p.isOpen() ? p.getDescription().getNatureIds().length : 0);
            out.add(one);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projects", out);
        data.put("count", out.size());

        String json = Json.write(data);
        long bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ToolResult.Meta meta = new ToolResult.Meta(bytes, bytes, false, null,
                (System.nanoTime() - started) / 1_000_000L, budget.budgetMs(),
                budget.cancellationHonoured());
        return ToolResult.ok(data, meta);
    }
}
