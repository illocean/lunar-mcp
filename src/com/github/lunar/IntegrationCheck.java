package com.github.lunar;

import com.github.lunar.debug.DebugTools;
import com.github.lunar.run.RunTools;
import com.github.lunar.tools.CoreTools;
import com.github.lunar.tools.SchemaValidator;
import com.github.lunar.tools.Tool;
import com.github.lunar.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        System.out.println("INTEGRATION CHECK PASS (" + tools.size() + " concrete tools)");
    }
}
