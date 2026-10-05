package com.github.lunar.run;

import static com.github.lunar.tools.Tools.*;
import static com.github.lunar.tools.ToolSpec.RiskTier.*;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.RequestError;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolProvider;
import com.github.lunar.tools.ToolResult;
import com.github.lunar.tools.ToolSpec;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.TextConsole;

/** Native launches; only reading the workbench console crosses onto the UI thread. */
public final class RunTools implements ToolProvider {
    private static final String LAUNCH_ID = "com.github.lunar.launchId";
    private static final String JUNIT_TYPE = "org.eclipse.jdt.junit.launchconfig";

    @Override public List<Tool> tools() {
        return List.of(
            tool("list_launch_configs", "List launch configurations", READ, object(pageProperties()),
                (a,b) -> listConfigurations(a,b)),
            tool("list_launches", "List running and retained launches. Set includeTerminated to also get"
                + " terminated launches Eclipse still holds whose configuration has been deleted.", READ,
                object(Map.of("offset", integer(0,0,Integer.MAX_VALUE), "limit", integer(50,1,200),
                    "includeTerminated", bool(false))),
                (a,b) -> listLaunches(a,b)),
            tool("create_launch_config", "Create a Java application or JUnit launch configuration", MUTATE,
                object(Map.of("name", string(), "project", string(), "mainType", string(),
                    "type", Map.of("type", "string", "enum", List.of("javaApplication", "junit"), "default", "javaApplication"),
                    "programArguments", Map.of("type", "string", "default", ""),
                    "vmArguments", Map.of("type", "string", "default", ""),
                    "stopInMain", bool(false)), "name", "project", "mainType"), this::createConfiguration),
            tool("delete_launch_config", "Delete a launch configuration. Refuses unless confirm is the exact name.", DESTRUCTIVE,
                object(Map.of("configuration", string(), "confirm", string()), "configuration", "confirm"),
                this::deleteConfiguration),
            tool("launch", "Build and launch an existing configuration, which runs the program's main method",
                EXECUTE,
                object(Map.of("configuration", string(), "mode", Map.of("type", "string",
                    "enum", List.of("run", "debug"), "default", "run")), "configuration"),
                (a,b) -> launchInfo(start(configuration(text(a,"configuration"), javaApplication()), text(a,"mode"), b))),
            tool("terminate", "Terminate a launch, including its debug targets", DESTRUCTIVE,
                object(Map.of("launchId", string()), "launchId"), (a,b) -> {
                    ILaunch launch = launch(text(a,"launchId")); b.checkCancelled();
                    if (!launch.isTerminated()) {
                        if (!launch.canTerminate()) throw new RequestError("launch_unterminable", "Launch cannot terminate");
                        launch.terminate();
                    }
                    return launchInfo(launch);
                }),
            tool("get_console_output", "Read a character page of process output; falls back to the console document", READ,
                object(Map.of("launchId", string(), "process", integer(0,0,10000),
                    "offset", integer(0,0,Integer.MAX_VALUE), "limit", integer(8192,1,65536)), "launchId"),
                this::consoleOutput),
            tool("get_log_entries", "Read a UTF-8 byte page of Eclipse's native log, preserving tracebacks", READ,
                object(Map.of("offset", integer(0,0,Long.MAX_VALUE), "limit", integer(8192,4,65536))),
                this::logEntries),
            tool("run_tests", "Run an existing JUnit configuration and return native test results. Runs the project's test code.", EXECUTE,
                object(Map.of("configuration", string()), "configuration"), this::runTests)
        );
    }

    @FunctionalInterface private interface Action { Object call(Map<String,Object> args, CallBudget budget) throws Exception; }
    private static Tool tool(String name, String description, ToolSpec.RiskTier risk,
            Map<String,Object> schema, Action action) {
        ToolSpec spec = new ToolSpec(name, description, description, schema, risk);
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ISchedulingRule schedulingRule() {
                // Build-family jobs need the workspace rule; never join them while holding it.
                return name.equals("launch") || name.equals("run_tests") ? null : Tool.super.schedulingRule();
            }
            @Override public ToolResult call(Map<String,Object> args, CallBudget budget) throws Exception {
                budget.checkCancelled();
                return ToolResult.ok(action.call(args,budget), null);
            }
        };
    }
    private static Map<String,Object> pageProperties() {
        return Map.of("offset", integer(0,0,Integer.MAX_VALUE), "limit", integer(50,1,200));
    }
    private static String text(Map<String,Object> args,String key) { return (String) args.get(key); }
    private static int number(Map<String,Object> args,String key) { return ((Number) args.get(key)).intValue(); }
    private static ILaunchManager manager() { return DebugPlugin.getDefault().getLaunchManager(); }
    private static Map<String,Object> page(String key,List<?> items,Map<String,Object> args) {
        int offset = number(args,"offset"), end = (int) Math.min(items.size(), (long) offset + number(args,"limit"));
        if (offset > items.size()) throw new RequestError("invalid_range", "offset exceeds current result length");
        return Map.of(key, items.subList(offset,end), "offset", offset, "nextOffset", end,
            "total", items.size(), "hasMore", end < items.size());
    }

    private Object listConfigurations(Map<String,Object> args,CallBudget budget) throws Exception {
        var configurations = new ArrayList<>(Arrays.asList(manager().getLaunchConfigurations()));
        configurations.sort(Comparator.comparing(ILaunchConfiguration::getName));
        List<Object> items = new ArrayList<>();
        for (ILaunchConfiguration c : configurations) { budget.checkCancelled(); items.add(configurationInfo(c)); }
        return page("configurations",items,args);
    }
    private Object listLaunches(Map<String,Object> args,CallBudget budget) throws Exception {
        boolean includeTerminated = !Boolean.FALSE.equals(args.get("includeTerminated"));
        List<Object> items = new ArrayList<>();
        for (ILaunch launch : manager().getLaunches()) { budget.checkCancelled();
            // Eclipse retains a terminated launch after its configuration is deleted. It cannot be
            // relaunched, named or terminated again, and its configuration reads back as null, so it is
            // omitted unless asked for rather than handed to callers as a null-deref trap.
            if (!includeTerminated && launch.isTerminated() && launch.getLaunchConfiguration() == null) continue;
            items.add(launchInfo(launch)); }
        return page("launches",items,args);
    }
    private static Map<String,Object> configurationInfo(ILaunchConfiguration c) throws Exception {
        return Map.of("id", c.isWorkingCopy() ? c.getName() : c.getMemento(), "name", c.getName(),
            "type", c.getType().getIdentifier(), "project", c.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,""),
            "supportsRun", c.supportsMode(ILaunchManager.RUN_MODE), "supportsDebug", c.supportsMode(ILaunchManager.DEBUG_MODE));
    }
    private static synchronized String launchId(ILaunch launch) {
        String id = launch.getAttribute(LAUNCH_ID);
        if (id == null) { id = UUID.randomUUID().toString(); launch.setAttribute(LAUNCH_ID,id); }
        return id;
    }
    private static ILaunch launch(String id) {
        for (ILaunch launch : manager().getLaunches()) if (launchId(launch).equals(id)) return launch;
        throw new RequestError("launch_unavailable", "Unknown or removed launchId; call list_launches");
    }
    private static Map<String,Object> launchInfo(ILaunch launch) throws Exception {
        Map<String,Object> info = new LinkedHashMap<>();
        info.put("launchId",launchId(launch)); info.put("mode",launch.getLaunchMode());
        info.put("terminated",launch.isTerminated()); info.put("canTerminate",launch.canTerminate());
        info.put("configuration",launch.getLaunchConfiguration() == null ? null : configurationInfo(launch.getLaunchConfiguration()));
        List<Object> processes = new ArrayList<>();
        IProcess[] nativeProcesses = launch.getProcesses();
        for (int index = 0; index < nativeProcesses.length; index++) {
            IProcess process = nativeProcesses[index];
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("index",index); p.put("label",process.getLabel()); p.put("terminated",process.isTerminated());
            if (process.isTerminated()) p.put("exitValue",process.getExitValue());
            processes.add(p);
        }
        info.put("processes",processes); info.put("debugTargetCount",launch.getDebugTargets().length);
        return info;
    }
    private static ILaunchConfiguration configuration(String id) throws Exception {
        return configuration(id, null);
    }

    /**
     * @param allowedTypes configuration type ids the caller will accept, or null for any type.
     *     Launching is code execution, and the type is what decides what actually runs, so a tool
     *     that means to run a main method says so rather than letting a JUnit or Ant config
     *     through the same argument.
     */
    private static ILaunchConfiguration configuration(String id, List<String> allowedTypes) throws Exception {
        ILaunchConfiguration found = null;
        for (ILaunchConfiguration c : manager().getLaunchConfigurations()) {
            if (c.getMemento().equals(id)) return checked(c, allowedTypes);
            if (c.getName().equals(id)) {
                if (found != null) throw new RequestError("configuration_ambiguous", "Ambiguous configuration name; use id from list_launch_configs");
                found = c;
            }
        }
        if (found == null) throw new RequestError("configuration_missing", "No launch configuration matches; call list_launch_configs");
        return checked(found, allowedTypes);
    }

    /** The one configuration type {@code launch} will start; JUnit has its own tool. */
    private static List<String> javaApplication() {
        return List.of(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
    }

    private static ILaunchConfiguration checked(ILaunchConfiguration c, List<String> allowedTypes) throws Exception {
        if (allowedTypes == null) return c;
        String type = c.getType().getIdentifier();
        if (!typeAllowed(type, allowedTypes))
            throw new RequestError("configuration_type_not_allowed",
                    "launch accepts only " + allowedTypes + " configurations; this one is "
                    + type + ". Use run_tests for a JUnit configuration.");
        return c;
    }

    /** Pure half of {@link #checked}: what the filter accepts, testable without a launch manager. */
    static boolean typeAllowed(String type, List<String> allowedTypes) {
        return allowedTypes == null || allowedTypes.contains(type);
    }
    private Object createConfiguration(Map<String,Object> args,CallBudget budget) throws Exception {
        String name = text(args,"name"), projectName = text(args,"project"), mainType = text(args,"mainType");
        boolean junit = "junit".equals(text(args,"type"));
        if (name.isBlank() || !manager().isValidLaunchConfigurationName(name)) throw new RequestError("invalid_name", "Invalid configuration name");
        if (manager().isExistingLaunchConfigurationName(name)) throw new RequestError("configuration_exists", "Configuration already exists; choose a new name");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) throw new RequestError("project_unavailable", "Project must be an open Java project");
        if (mainType.isBlank() || JavaCore.create(project).findType(mainType) == null) throw new RequestError("symbol_unresolved", "mainType does not exist in the project");
        String typeId = junit ? JUNIT_TYPE : IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION;
        ILaunchConfigurationType type = manager().getLaunchConfigurationType(typeId);
        if (type == null) throw new RequestError("launch_type_unavailable", (junit ? "JUnit" : "Java application") + " launch type is unavailable");
        ILaunchConfigurationWorkingCopy copy = type.newInstance(null,name);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,projectName);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,mainType);
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,text(args,"programArguments"));
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,text(args,"vmArguments"));
        copy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_STOP_IN_MAIN,(Boolean) args.get("stopInMain"));
        // Names below match JUnitLaunchConfigurationConstants; the delegate resolves the project
        // from ATTR_PROJECT_NAME, so an empty CONTAINER falls through to evaluateTests, which
        // narrows the selection by ATTR_TESTNAME.
        if (junit) {
            copy.setAttribute("org.eclipse.jdt.junit.CONTAINER","");
            copy.setAttribute("org.eclipse.jdt.junit.TESTNAME",mainType);
            copy.setAttribute("org.eclipse.jdt.junit.TEST_KIND","org.eclipse.jdt.junit.loader.junit4");
            copy.setAttribute("org.eclipse.jdt.junit.KEEPRUNNING_ATTR",Boolean.FALSE);
        }
        copy.setMappedResources(new IResource[]{ project });
        budget.checkCancelled();
        return configurationInfo(copy.doSave());
    }

    private Object deleteConfiguration(Map<String,Object> args,CallBudget budget) throws Exception {
        ILaunchConfiguration target = configuration(text(args,"configuration"));
        if (!target.getName().equals(text(args,"confirm"))) throw new RequestError("confirm_required", "confirm must be the exact configuration name");
        for (ILaunch launch : manager().getLaunches()) {
            if (!launch.isTerminated() && target.equals(launch.getLaunchConfiguration()))
                throw new RequestError("launch_running", "Configuration has a running launch; call terminate first");
        }
        Map<String,Object> data = configurationInfo(target);
        // API deletion also fires the listener, so the manager's cache stops reporting a config
        // that no longer exists on disk.
        target.delete();
        return data;
    }

    private static ILaunch start(ILaunchConfiguration configuration,String mode,CallBudget budget) throws Exception {
        if (!configuration.supportsMode(mode)) throw new RequestError("mode_unsupported", "Configuration does not support " + mode);
        buildBeforeLaunch(configuration,budget);
        budget.checkCancelled();
        ILaunch launch = configuration.launch(mode,budget.monitor(),false,true);
        launchId(launch);
        return launch;
    }
    private static void buildBeforeLaunch(ILaunchConfiguration configuration,CallBudget budget) throws Exception {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        LinkedHashSet<IProject> projects = new LinkedHashSet<>();
        String name = configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,"");
        if (!name.isBlank()) projects.add(workspace.getRoot().getProject(name));
        IResource[] mapped = configuration.getMappedResources();
        if (mapped != null) for (IResource resource : mapped) if (resource != null && resource.getProject() != null) projects.add(resource.getProject());
        for (IProject project : projects) if (!project.isOpen()) throw new RequestError("project_unavailable", "Launch project is not open: " + project.getName());
        IBuildConfiguration[] builds = new IBuildConfiguration[projects.size()];
        int index = 0;
        for (IProject project : projects) builds[index++] = project.getActiveBuildConfig();
        // Native dependency ordering avoids building unrelated projects for a mapped launch.
        workspace.run(monitor -> {
            if (projects.isEmpty()) workspace.getRoot().refreshLocal(IResource.DEPTH_INFINITE,monitor);
            else for (IProject project : projects) project.refreshLocal(IResource.DEPTH_INFINITE,monitor);
            budget.checkCancelled();
            if (builds.length == 0) workspace.build(IncrementalProjectBuilder.INCREMENTAL_BUILD,monitor);
            else workspace.build(builds,IncrementalProjectBuilder.INCREMENTAL_BUILD,true,monitor);
        },workspace.getRuleFactory().buildRule(),IWorkspace.AVOID_UPDATE,budget.monitor());
        Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD,budget.monitor());
        Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD,budget.monitor());
        budget.checkCancelled();
        IResource[] checked = projects.isEmpty() ? new IResource[]{workspace.getRoot()} : projects.toArray(IResource[]::new);
        for (IResource resource : checked) {
            if (resource.findMaxProblemSeverity(IMarker.PROBLEM,true,IResource.DEPTH_INFINITE) == IMarker.SEVERITY_ERROR)
                throw new RequestError("build_failed", "Build has errors in " + resource.getFullPath() + "; call get_problems before launch");
        }
    }

    private Object consoleOutput(Map<String,Object> args,CallBudget budget) throws Exception {
        ILaunch launch = launch(text(args,"launchId")); IProcess[] processes = launch.getProcesses();
        int processIndex = number(args,"process"), offset = number(args,"offset"), limit = number(args,"limit");
        if (processIndex >= processes.length) throw new RequestError("invalid_range", "process index is unavailable; call list_launches");
        IProcess process = processes[processIndex]; IStreamsProxy proxy = process.getStreamsProxy();
        String output = "";
        if (proxy != null) {
            var outMonitor = proxy.getOutputStreamMonitor();
            var errMonitor = proxy.getErrorStreamMonitor();
            String stdout = outMonitor == null ? "" : outMonitor.getContents();
            String stderr = errMonitor == null ? "" : errMonitor.getContents();
            output = (stdout == null ? "" : stdout) + (stderr == null ? "" : stderr);
        }
        if (!output.isEmpty()) return characterPage(output,offset,limit,"stdout_then_stderr");
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display == null || display.isDisposed()) return characterPage("",offset,limit,"stream_monitors");
        FutureTask<Object> read = new FutureTask<>(() -> {
            budget.checkCancelled(); IConsole console = DebugUITools.getConsole(process);
            if (!(console instanceof TextConsole textConsole)) return characterPage("",offset,limit,"no_console");
            IDocument document = textConsole.getDocument(); int length = document.getLength();
            if (offset > length) throw new RequestError("invalid_range", "Console was cleared or offset exceeds current length; reset offset");
            if (offset < length && Character.isLowSurrogate(document.getChar(offset)))
                throw new RequestError("invalid_range", "offset splits a character; use returned nextOffset");
            int end = (int) Math.min(length,(long) offset + limit);
            if (end < length && Character.isLowSurrogate(document.getChar(end))) end += end == offset + 1 ? 1 : -1;
            return Map.of("text",document.get(offset,end-offset),"offset",offset,"nextOffset",end,
                "totalCharacters",length,"hasMore",end < length,"source","console_document");
        });
        display.asyncExec(read);
        try {
            // Timed event waits provide prompt cancellation while the UI is busy; no sleep-based launch polling.
            while (!read.isDone()) { budget.checkCancelled(); try { return read.get(Math.min(100,budget.remainingMs()),TimeUnit.MILLISECONDS); } catch (java.util.concurrent.TimeoutException pending) { } catch (java.util.concurrent.ExecutionException failed) { throw unwrap(failed); } }
            budget.checkCancelled(); return read.get();
        } finally { if (!read.isDone()) read.cancel(false); }
    }
    /**
     * Hand back what a {@link FutureTask} body threw, rather than the wrapper.
     *
     * <p>The console fallback above runs on the UI thread, so its failures arrive wrapped in
     * {@code ExecutionException}. {@code ToolRunner} classifies by {@code instanceof}, so left
     * wrapped the two documented {@code invalid_range} signals collapsed to {@code tool_failed} and
     * a cancellation was reported as a failure -- which a client that retries on {@code cancelled}
     * never retries. An {@link Error} cause is rewrapped rather than smuggled through as an
     * {@code Exception}, so this never narrows what the caller is told.
     */
    static Exception unwrap(java.util.concurrent.ExecutionException failed) {
        return failed.getCause() instanceof Exception cause ? cause : new Exception(failed.getCause());
    }
    private static Map<String,Object> characterPage(String value,int offset,int limit,String source) {
        if (offset > value.length()) throw new RequestError("invalid_range", "offset exceeds current output length; reset offset");
        if (offset < value.length() && Character.isLowSurrogate(value.charAt(offset)))
            throw new RequestError("invalid_range", "offset splits a character; use returned nextOffset");
        int end = (int) Math.min(value.length(),(long) offset + limit);
        if (end < value.length() && Character.isLowSurrogate(value.charAt(end))) end += end == offset + 1 ? 1 : -1;
        return Map.of("text",value.substring(offset,end),"offset",offset,"nextOffset",end,
            "totalCharacters",value.length(),"hasMore",end < value.length(),"source",source);
    }
    private Object logEntries(Map<String,Object> args,CallBudget budget) throws Exception {
        var path = Platform.getLogFileLocation().toFile().toPath();
        long offset = ((Number) args.get("offset")).longValue(); int limit = number(args,"limit");
        try (SeekableByteChannel channel = Files.newByteChannel(path,StandardOpenOption.READ)) {
            long size = channel.size();
            if (offset > size) throw new RequestError("invalid_range", "Log rotated or offset exceeds file length; reset offset");
            channel.position(offset); ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit,size-offset));
            while (buffer.hasRemaining()) { budget.checkCancelled(); if (channel.read(buffer) < 0) break; }
            byte[] bytes = buffer.array(); int count = buffer.position();
            if (count != 0 && (bytes[0] & 0xc0) == 0x80) throw new RequestError("invalid_range", "offset is not a UTF-8 boundary; use returned nextOffset");
            int complete = completePage(bytes,count);
            long next = offset + complete;
            return Map.of("text",new String(bytes,0,complete,StandardCharsets.UTF_8),"offset",offset,
                "nextOffset",next,"fileBytes",size,"hasMore",next < size,"path",path.toString());
        }
    }
    /**
     * A page of zero complete bytes is refused rather than returned. It can only happen at the very
     * tail of a log whose last character was cut short mid-write, and answering it hands back a
     * nextOffset equal to the offset the client already asked for: a client that pages by nextOffset
     * would re-issue the identical request forever, and no error ever explains why.
     */
    private static int completePage(byte[] bytes,int count) {
        int complete = utf8Prefix(bytes,count);
        if (complete == 0 && count > 0) throw new RequestError("invalid_range", "log ends mid-character; read to the end of the file");
        return complete;
    }
    private static int utf8Prefix(byte[] bytes,int length) {
        if (length == 0) return 0;
        int start = length - 1;
        while (start > 0 && (bytes[start] & 0xc0) == 0x80) start--;
        int lead = bytes[start] & 0xff;
        int width = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
        return length-start < width ? start : length;
    }

    private Object runTests(Map<String,Object> args,CallBudget budget) throws Exception {
        ILaunchConfiguration original = configuration(text(args,"configuration"));
        if (!original.getType().getIdentifier().equals(JUNIT_TYPE)) throw new RequestError("not_junit_configuration", "run_tests requires an existing JUnit configuration; call list_launch_configs");
        // An unsaved private copy gives this run a unique native session name without editing the user's configuration.
        ILaunchConfigurationWorkingCopy copy = original.copy("lunar-tests-" + UUID.randomUUID());
        copy.setAttribute(ILaunchManager.ATTR_PRIVATE,true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ITestRunSession> selected = new AtomicReference<>();
        AtomicReference<Map<String,Object>> result = new AtomicReference<>();
        TestRunListener listener = new TestRunListener() {
            int total, failures, errors, ignored;
            final List<Object> failureDetails = new ArrayList<>();
            @Override public synchronized void sessionLaunched(ITestRunSession session) {
                if (session.getTestRunName().equals(copy.getName())) selected.compareAndSet(null,session);
            }
            @Override public synchronized void testCaseFinished(ITestCaseElement test) {
                if (selected.get() != test.getTestRunSession()) return;
                total++; ITestElement.Result state = test.getTestResult(false);
                if (state == ITestElement.Result.FAILURE) failures++;
                if (state == ITestElement.Result.ERROR) errors++;
                if (state == ITestElement.Result.IGNORED) ignored++;
                if ((state == ITestElement.Result.FAILURE || state == ITestElement.Result.ERROR) && failureDetails.size() < 50) {
                    var trace = test.getFailureTrace();
                    failureDetails.add(Map.of("class",bounded(test.getTestClassName(),1024),"method",bounded(test.getTestMethodName(),1024),
                        "result",state.toString(),"trace",trace == null ? "" : bounded(trace.getTrace(),4096)));
                }
            }
            @Override public synchronized void sessionFinished(ITestRunSession session) {
                if (!selected.compareAndSet(session,null)) return;
                boolean completed = session.getProgressState() == ITestElement.ProgressState.COMPLETED;
                result.set(Map.of("completed",completed,"passed",completed && total > 0 && session.getTestResult(true) == ITestElement.Result.OK,
                    "result",session.getTestResult(true).toString(),"tests",total,"failures",failures,"errors",errors,"ignored",ignored,
                    "failureDetails",List.copyOf(failureDetails),"failureDetailsTruncated",failures+errors > failureDetails.size()));
                // Eclipse forbids retaining the native test session after this callback; only plain result data survives.
                done.countDown();
            }
        };
        JUnitCore.addTestRunListener(listener); ILaunch launch = null;
        try {
            launch = start(copy,ILaunchManager.RUN_MODE,budget);
            await(done,budget);
            Map<String,Object> data = new LinkedHashMap<>(result.get());
            data.put("launch",launchInfo(launch)); data.put("configuration",original.getName());
            return data;
        } catch (OperationCanceledException cancelled) {
            if (launch != null && launch.canTerminate()) launch.terminate();
            throw cancelled;
        } finally { JUnitCore.removeTestRunListener(listener); selected.set(null); }
    }
    private static String bounded(String value,int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0,max) + "…"; }
    private static void await(CountDownLatch done,CallBudget budget) throws InterruptedException {
        while (done.getCount() != 0) { budget.checkCancelled(); done.await(Math.min(100,budget.remainingMs()),TimeUnit.MILLISECONDS); }
        budget.checkCancelled();
    }
    public static void selfCheck() {
        byte[] text = "a漢🙂z".getBytes(StandardCharsets.UTF_8);
        for (int end = 1; end <= text.length; end++) {
            int complete = utf8Prefix(text,end);
            String prefix = new String(text,0,complete,StandardCharsets.UTF_8);
            if (prefix.indexOf('\ufffd') >= 0 || !"a漢🙂z".startsWith(prefix)) throw new AssertionError("UTF-8 page split");
        }
        // A log whose last character was cut short mid-write must be refused, not answered with a page
        // the client cannot advance past. The prefix loop above never sees this case: every prefix of
        // a whole string yields at least one complete character.
        byte[] truncated = {(byte)0xf0,(byte)0x9f,(byte)0x98};
        try { completePage(truncated,truncated.length); throw new AssertionError("a log page that cannot advance was served"); }
        catch (RequestError expected) { }
        if (completePage("a漢🙂z".getBytes(StandardCharsets.UTF_8),9) != 9) throw new AssertionError("a whole page was not returned whole");
        Map<String,Object> page = characterPage("abc",1,1,"check");
        if (!page.get("text").equals("b") || !page.get("nextOffset").equals(2)) throw new AssertionError("Console page");
        Map<String,Object> emoji = characterPage("\ud83d\ude42z",0,1,"check");
        if (!emoji.get("text").equals("\ud83d\ude42") || !emoji.get("nextOffset").equals(2)) throw new AssertionError("Console surrogate progression");
        try { characterPage("\ud83d\ude42",1,1,"check"); throw new AssertionError("Split console offset accepted"); }
        catch (RequestError expected) { }
        // The console fallback runs on the UI thread, so everything it throws arrives wrapped, and
        // ToolRunner classifies by instanceof. Assert the wrapper does not cost a client the code.
        var wrapped = unwrap(new java.util.concurrent.ExecutionException(new RequestError("invalid_range","offset splits a character")));
        if (!(wrapped instanceof RequestError code) || !"invalid_range".equals(code.code)) throw new AssertionError("a wrapped RequestError lost its code");
        if (!(unwrap(new java.util.concurrent.ExecutionException(new OperationCanceledException())) instanceof OperationCanceledException)) throw new AssertionError("a wrapped cancellation is reported as a failure");
        if (!(unwrap(new java.util.concurrent.ExecutionException(new StackOverflowError())).getCause() instanceof Error)) throw new AssertionError("an Error cause was dropped rather than kept as the cause");
        List<Tool> tools = new RunTools().tools();
        // A destructive tool that cannot be validated end to end is worse than an absent one.
        Tool deleter = tools.stream().filter(t -> t.spec().name().equals("delete_launch_config")).findFirst()
            .orElseThrow(() -> new AssertionError("delete_launch_config missing"));
        Object required = deleter.spec().inputSchema().get("required");
        if (!(required instanceof List<?> r) || !r.contains("confirm")) throw new AssertionError("delete_launch_config is not confirm-gated");
        // Running a project's main method and running its tests both execute code the workspace
        // never compiled here. EXECUTE is the top tier, so compareTo is now always <= 0 against it
        // and the ordering carries no meaning here: the tier has to be EXECUTE by name.
        if (ToolSpec.RiskTier.values()[ToolSpec.RiskTier.values().length - 1] != ToolSpec.RiskTier.EXECUTE)
            throw new AssertionError("EXECUTE is not the highest risk tier; a client that ranks the "
                    + "published order would file running a project's code below a file write");
        for (String name : List.of("launch", "run_tests")) {
            ToolSpec.RiskTier tier = tools.stream().filter(t -> t.spec().name().equals(name))
                    .findFirst().orElseThrow(() -> new AssertionError(name + " missing")).spec().riskTier();
            if (tier != ToolSpec.RiskTier.EXECUTE)
                throw new AssertionError(name + " is tiered " + tier + "; running code must be EXECUTE");
        }
        // The tier is only half the fix: launch must also refuse a config that is not a Java
        // application, or a JUnit/Ant config still executes through the same argument.
        if (!typeAllowed(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION, javaApplication()))
            throw new AssertionError("launch no longer accepts a Java application config");
        for (String rejected : List.of(JUNIT_TYPE,
                "org.eclipse.ant.core.antBuilder", "org.eclipse.pde.ui.RuntimeWorkbench")) {
            if (typeAllowed(rejected, javaApplication()))
                throw new AssertionError("launch accepts a " + rejected + " configuration");
        }
    }
}
