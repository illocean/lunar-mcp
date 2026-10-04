package com.github.lunar;

import com.github.lunar.debug.DebugTools;
import com.github.lunar.run.RunTools;
import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.CoreTools;
import com.github.lunar.tools.SchemaValidator;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolDispatcher;
import com.github.lunar.tools.ToolRegistry;
import com.github.lunar.tools.ToolRunner;
import com.github.lunar.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Runs concrete domain checks and rejects malformed or duplicated tool declarations. */
public final class IntegrationCheck {
    public static void main(String[] args) throws Exception {
        CoreTools.main(args);
        WorkspaceTools.main(args);
        RunTools.selfCheck();
        DebugTools.selfCheck();
        List<Tool> tools = new ArrayList<>(new CoreTools().tools());
        tools.add(new com.github.lunar.tools.ListProjects());
        tools.addAll(new WorkspaceTools().tools());
        tools.addAll(new RunTools().tools());
        tools.addAll(new DebugTools().tools());
        var names = new HashSet<String>();
        for (Tool tool : tools) {
            if (!names.add(tool.spec().name())) throw new AssertionError("duplicate: " + tool.spec().name());
            SchemaValidator.validateDefinition(tool.spec().inputSchema());
        }
        if (tools.size() != 47) throw new AssertionError("Expected 47 tools, got " + tools.size());
        if (args.length != 5) throw new AssertionError("Pass the five packaged bundles");
        for (String artifact : args) {
            try (JarFile jar = new JarFile(artifact)) {
                String symbolic = jar.getManifest().getMainAttributes().getValue("Bundle-SymbolicName");
                if (symbolic == null || !symbolic.startsWith("com.github.lunar."))
                    throw new AssertionError("Invalid Lunar artifact " + artifact);
                if (jar.stream().anyMatch(e -> e.getName().contains("SelfCheck")
                        || e.getName().contains("FrameworkCheck") || e.getName().contains("ProtocolCheck")
                        || e.getName().contains("IntegrationCheck")))
                    throw new AssertionError("Standalone checks leaked into " + artifact);
                if (symbolic.startsWith("com.github.lunar.core")) {
                    String requires = jar.getManifest().getMainAttributes().getValue("Require-Bundle");
                    if (requires.contains("jdt") || requires.contains("org.eclipse.ui")
                            || jar.getJarEntry("com/github/lunar/io/McpHttpServer.class") != null)
                        throw new AssertionError("Core gained JDT, UI or HTTP dependency");
                    if (jar.getJarEntry("com/github/lunar/io/Json.class") == null)
                        throw new AssertionError("Core JSON codec missing");
                }
                if (symbolic.startsWith("com.github.lunar.workspace")
                        && jar.getJarEntry("com/github/lunar/verify/VerificationApplication.class") == null)
                    throw new AssertionError("Verification application missing from workspace artifact");
            }
        }
        checkSessionStartOffersNoExecution(tools);
        System.out.println("INTEGRATION CHECK PASS (" + tools.size() + " concrete tools)");
    }

    /** launch is EXECUTE and it was in the session-start set, so a client's first tools/list
     *  offered arbitrary code execution from the workspace before it had asked for it. Asserted
     *  against the published list rather than the CORE constant, and here rather than in
     *  {@code CoreTools.main} because the registry is empty outside OSGi. */
    private static void checkSessionStartOffersNoExecution(List<Tool> tools) {
        Map<String, Tool> registry = new LinkedHashMap<>();
        for (Tool tool : tools) registry.put(tool.spec().name(), tool);
        Map<String, Tool> previous = ToolRegistry.all();
        ToolRegistry.installForTest(registry);
        String sessionId = ToolDispatcher.createSession();
        try {
            var session = ToolDispatcher.sessionFor(sessionId);
            List<Object> atStart = ToolDispatcher.visibleTools(session);
            // 17 is published in the README, in the diagram, in the tools table and in the curl
            // example. This is what keeps those four from drifting.
            if (atStart.size() != 17)
                throw new AssertionError("expected 17 tools visible at start, got " + atStart.size());
            for (Object entry : atStart)
                if ("execute".equals(inputSchemaOf(entry).get("x-lunar-risk-tier")))
                    throw new AssertionError(nameOf(entry) + " is EXECUTE and visible at session start");
            if (atStart.stream().noneMatch(e -> nameOf(e).equals("read_file")))
                throw new AssertionError("read_file is no longer visible at session start");
            // And launch is still reachable, so dropping it from the session-start set did not
            // just take away the only way to run a project.
            var loaded = ToolRunner.run(registry.get("load_toolset"), Map.of("name", "run"),
                    new CallBudget(30_000, sessionId), null);
            if (!loaded.ok()) throw new AssertionError("load_toolset(run) failed: " + loaded.error());
            if (ToolDispatcher.visibleTools(session).stream().noneMatch(e -> nameOf(e).equals("launch")))
                throw new AssertionError("load_toolset(run) does not reveal launch");
        } finally {
            ToolRegistry.installForTest(previous);
            ToolDispatcher.deleteSession(sessionId);
        }
    }

    private static String nameOf(Object toolListEntry) {
        return (String) ((Map<?, ?>) toolListEntry).get("name");
    }

    private static Map<?, ?> inputSchemaOf(Object toolListEntry) {
        return (Map<?, ?>) ((Map<?, ?>) toolListEntry).get("inputSchema");
    }
}
