package com.github.lunar.debug;

import static com.github.lunar.tools.Tools.*;
import static com.github.lunar.tools.ToolSpec.RiskTier.*;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.RequestError;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolProvider;
import com.github.lunar.tools.ToolResult;
import com.github.lunar.tools.ToolSpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IIndexedValue;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;

/** Native debug state, with bounded frame handles that never survive a resume or a step. */
public final class DebugTools implements ToolProvider {
    private static final FrameHandles HANDLES = new FrameHandles();
    private static final Map<IDebugElement,String> IDS = new WeakHashMap<>();
    private static final IDebugEventSetListener EVENT_LISTENER = DebugTools::invalidateEvents;
    private static DebugPlugin listenerPlugin;

    @Override public List<Tool> tools() {
        ensureListening();
        return List.of(
            tool("list_debug_targets", "Page targets and their threads. Targets whose debuggee has exited are "
                + "omitted even though Eclipse still registers them; pass includeTerminated to see them.", READ,
                object(Map.of("offset",integer(0,0,Integer.MAX_VALUE),"limit",integer(20,1,100),
                    "threadOffset",integer(0,0,Integer.MAX_VALUE),"threadLimit",integer(50,1,200),
                    "includeTerminated",bool(false))), this::targets),
            tool("list_breakpoints", "List registered breakpoints", READ, object(paging()), this::breakpoints),
            tool("set_breakpoint", "Create or enable a Java line breakpoint; path is project-relative when project is given, workspace-absolute otherwise", MUTATE,
                object(Map.of("project",string(),"path",string(),"typeName",string(),"line",Map.of("type","integer","minimum",1,"maximum",Integer.MAX_VALUE),
                    "enabled",bool(true)),"path","typeName","line"), this::setBreakpoint),
            tool("remove_breakpoint", "Remove one registered breakpoint by its returned id", MUTATE,
                object(Map.of("breakpointId",string()),"breakpointId"), (a,b) -> {
                    String id = text(a,"breakpointId");
                    for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
                        b.checkCancelled();
                        if (id.equals(breakpointId(breakpoint))) { breakpoint.delete(); return Map.of("removed",id); }
                    }
                    throw new RequestError("breakpoint_unavailable", "Unknown breakpointId; call list_breakpoints");
                }),
            tool("get_frames", "Page suspended-thread frames and issue session-bound frame handles", READ,
                object(Map.of("threadId",string(),"offset",integer(0,0,Integer.MAX_VALUE),"limit",integer(50,1,100)),"threadId"),
                this::frames),
            tool("get_variables", "Expand one variable level; use returned index paths and native array paging", READ,
                object(Map.of("frameId",string(),"variablePath",Map.of("type","array","items",Map.of("type","integer","minimum",0,"maximum",Integer.MAX_VALUE),"default",List.of()),
                    "offset",integer(0,0,Integer.MAX_VALUE),"limit",integer(50,1,100),"maxValueLength",integer(512,1,4096)),"frameId"),
                this::variables),
            tool("continue_execution", "Resume one suspended thread; invalidates its frame handles", BUILD,
                object(Map.of("threadId",string()),"threadId"), (a,b) -> control(a,b,"resume")),
            tool("step_into", "Step into and wait for suspension or termination", MUTATE,
                object(Map.of("threadId",string()),"threadId"), (a,b) -> control(a,b,"into")),
            tool("step_over", "Step over and wait for suspension or termination", MUTATE,
                object(Map.of("threadId",string()),"threadId"), (a,b) -> control(a,b,"over")),
            tool("step_return", "Step out and wait for suspension or termination", MUTATE,
                object(Map.of("threadId",string()),"threadId"), (a,b) -> control(a,b,"return"))
        );
    }

    private static synchronized void ensureListening() {
        if (listenerPlugin == null && DebugPlugin.getDefault() != null) {
            listenerPlugin = DebugPlugin.getDefault(); listenerPlugin.addDebugEventListener(EVENT_LISTENER);
        }
    }
    @Override public void close() {
        synchronized (DebugTools.class) {
            if (listenerPlugin != null) listenerPlugin.removeDebugEventListener(EVENT_LISTENER);
            listenerPlugin = null; HANDLES.clear(); IDS.clear();
        }
    }

    @FunctionalInterface private interface Action { Object call(Map<String,Object> args,CallBudget budget) throws Exception; }
    private static Tool tool(String name,String description,ToolSpec.RiskTier risk,Map<String,Object> schema,Action action) {
        ToolSpec spec = new ToolSpec(name,description,description,schema,risk);
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ISchedulingRule schedulingRule() {
                // Dispatcher serializes controls; JDI must acquire its own per-thread native rule.
                if (name.equals("set_breakpoint") || name.equals("remove_breakpoint")) return Tool.super.schedulingRule();
                return null;
            }
            @Override public ToolResult call(Map<String,Object> args,CallBudget budget) throws Exception {
                budget.checkCancelled(); return ToolResult.ok(action.call(args,budget),null);
            }
        };
    }
    private static Map<String,Object> paging() { return Map.of("offset",integer(0,0,Integer.MAX_VALUE),"limit",integer(50,1,100)); }
    private static String text(Map<String,Object> args,String key) { return (String) args.get(key); }
    private static int number(Map<String,Object> args,String key) { return ((Number) args.get(key)).intValue(); }
    private static synchronized String id(IDebugElement element) { return IDS.computeIfAbsent(element,ignored -> UUID.randomUUID().toString()); }
    private static int end(int offset,int length,int limit) {
        if (offset > length) throw new RequestError("invalid_range", "offset exceeds current result length");
        return (int) Math.min(length,(long) offset+limit);
    }
    private static Map<String,Object> page(String key,List<?> items,int offset,int next,int total) {
        return Map.of(key,items,"offset",offset,"nextOffset",next,"total",total,"hasMore",next < total);
    }
    private static String bounded(String value,int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end) + "…";
    }

    private Object targets(Map<String,Object> args,CallBudget budget) throws Exception {
        boolean includeTerminated = !Boolean.FALSE.equals(args.get("includeTerminated"));
        List<IDebugTarget> live = new ArrayList<>();
        for (IDebugTarget target : DebugPlugin.getDefault().getLaunchManager().getDebugTargets()) {
            budget.checkCancelled();
            // Eclipse keeps a launch's target registered after the debuggee exits, so a listing that
            // trusts isTerminated() alone reports a dead target forever. A target with no threads has
            // nothing left to frame, inspect or control, which is the actionable definition of dead.
            if (includeTerminated || (!target.isTerminated() && target.getThreads().length > 0)) live.add(target);
        }
        int offset = number(args,"offset"), next = end(offset,live.size(),number(args,"limit"));
        List<Object> items = new ArrayList<>();
        for (int index = offset; index < next; index++) {
            budget.checkCancelled(); IDebugTarget target = live.get(index);
            IThread[] nativeThreads = target.getThreads();
            boolean suspended = false;
            for (IThread candidate : nativeThreads) if (candidate.isSuspended()) { suspended = true; break; }
            Map<String,Object> info = new LinkedHashMap<>();
            info.put("targetId",id(target)); info.put("name",bounded(target.getName(),1024));
            info.put("terminated",target.isTerminated());
            // IDebugTarget.isSuspended() means the whole process is suspended and stays false when only
            // one thread is stopped at a breakpoint, which contradicts the thread list beside it.
            info.put("suspended",suspended);
            info.put("model",target.getModelIdentifier());
            List<Object> threads = new ArrayList<>();
            int threadOffset = Math.min(number(args,"threadOffset"),nativeThreads.length);
            int threadNext = end(threadOffset,nativeThreads.length,number(args,"threadLimit"));
            for (int t = threadOffset; t < threadNext; t++) {
                budget.checkCancelled(); threads.add(threadInfo(nativeThreads[t]));
            }
            info.put("threads",threads); info.put("threadCount",nativeThreads.length);
            info.put("threadOffset",threadOffset); info.put("nextThreadOffset",threadNext);
            info.put("threadsHasMore",threadNext < nativeThreads.length); items.add(info);
        }
        return page("targets",items,offset,next,live.size());
    }
    private static Map<String,Object> threadInfo(IThread thread) throws Exception {
        return Map.of("threadId",id(thread),"name",bounded(thread.getName(),1024),"suspended",thread.isSuspended(),
            "terminated",thread.isTerminated(),"canResume",thread.canResume(),"canStepInto",thread.canStepInto(),
            "canStepOver",thread.canStepOver(),"canStepReturn",thread.canStepReturn());
    }
    private static IThread thread(String threadId) throws Exception {
        for (IDebugTarget target : DebugPlugin.getDefault().getLaunchManager().getDebugTargets()) {
            if (target.isTerminated()) continue;
            for (IThread thread : target.getThreads()) if (id(thread).equals(threadId)) return thread;
        }
        throw new RequestError("thread_unavailable", "Unknown or terminated threadId; call list_debug_targets");
    }
    private Object breakpoints(Map<String,Object> args,CallBudget budget) throws Exception {
        IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
        Arrays.sort(breakpoints,java.util.Comparator.comparing(DebugTools::breakpointId,
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        int offset = number(args,"offset"), next = end(offset,breakpoints.length,number(args,"limit"));
        List<Object> items = new ArrayList<>();
        for (int index = offset; index < next; index++) { budget.checkCancelled(); items.add(breakpointInfo(breakpoints[index])); }
        return page("breakpoints",items,offset,next,breakpoints.length);
    }
    /**
     * Eclipse keeps the IBreakpoint object after its marker is deleted, and getMarker() then throws
     * DebugException. Return null for that dead object: one stale breakpoint in the workspace used to
     * make list_breakpoints and remove_breakpoint fail wholesale, leaving no way to inspect or clear
     * it without the Eclipse UI.
     */
    private static String breakpointId(IBreakpoint breakpoint) {
        try {
            IMarker marker = breakpoint.getMarker();
            return marker.getResource().getFullPath() + "#" + marker.getId();
        } catch (Exception dead) { return null; }
    }
    private static Map<String,Object> breakpointInfo(IBreakpoint breakpoint) throws Exception {
        Map<String,Object> info = new LinkedHashMap<>();
        info.put("model",breakpoint.getModelIdentifier());
        // Every accessor below goes through Breakpoint.ensureMarker(), which re-checks the marker and
        // throws DebugException once the file is deleted, so the whole read has to be guarded rather
        // than just getMarker(). One dead breakpoint used to fail the entire listing workspace-wide.
        try {
            String id = breakpointId(breakpoint);
            info.put("breakpointId",id);
            IMarker marker = breakpoint.getMarker();
            info.put("path",marker.getResource().getFullPath().toString());
            info.put("enabled",breakpoint.isEnabled());
            if (breakpoint instanceof ILineBreakpoint line) info.put("line",line.getLineNumber());
            if (breakpoint instanceof IJavaLineBreakpoint line) info.put("typeName",line.getTypeName());
            info.put("stale",false);
        } catch (Exception dead) {
            // Marker is gone: no resource, line or usable id, and nothing left to remove.
            info.put("breakpointId",null); info.put("stale",true);
        }
        return info;
    }

    /**
     * Resolve a breakpoint target from either addressing form.
     *
     * <p>{@code set_breakpoint} was the one debug tool taking a workspace-absolute path while every
     * other tool took a project and a project-relative path. Both are accepted now: give
     * {@code project} with a relative path, or the absolute path alone. Package-private so
     * {@link SelfCheck} exercises the decision rather than a copy of it.
     */
    static String workspacePath(String project, String path) {
        if (project == null || project.isBlank()) return path;
        if (project.indexOf('/') >= 0 || project.indexOf('\\') >= 0
                || project.equals(".") || project.equals(".."))
            throw new RequestError("invalid_project", "project must be the exact name from list_projects");
        if (path.startsWith("/"))
            throw new RequestError("invalid_path", "path is relative to the project when project is given");
        return "/" + project + "/" + path;
    }
    private Object setBreakpoint(Map<String,Object> args,CallBudget budget) throws Exception {
        String path = text(args,"path"), typeName = text(args,"typeName"); int line = number(args,"line");
        Path resourcePath = new Path(workspacePath(text(args,"project"), path));
        if (resourcePath.getDevice() != null || resourcePath.segmentCount() < 2
                || Arrays.asList(resourcePath.segments()).stream().anyMatch(segment -> segment.equals("..") || segment.equals(".")))
            throw new RequestError("invalid_path", "path must identify a workspace file, e.g. /Project/src/Main.java");
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(resourcePath);
        if (!(resource instanceof IFile file) || !file.isAccessible()) throw new RequestError("resource_missing", "Java source file is not accessible");
        if (!(JavaCore.create(file) instanceof ICompilationUnit unit)) throw new RequestError("not_java_source", "path is not a Java compilation unit");
        boolean typeFound = false;
        for (IType type : unit.getAllTypes()) if (type.getFullyQualifiedName('$').equals(typeName)) { typeFound = true; break; }
        if (!typeFound) throw new RequestError("symbol_unresolved", "typeName is not declared in this source file; nested types use '$'");
        try (BufferedReader source = new BufferedReader(new InputStreamReader(file.getContents(),file.getCharset()))) {
            for (int index = 1; index <= line; index++) {
                budget.checkCancelled(); if (source.readLine() == null) throw new RequestError("invalid_range", "line exceeds source length");
            }
        }
        IJavaLineBreakpoint existing = JDIDebugModel.lineBreakpointExists(file,typeName,line);
        boolean created = existing == null;
        IJavaLineBreakpoint breakpoint = created ? JDIDebugModel.createLineBreakpoint(file,typeName,line,-1,-1,0,true,new LinkedHashMap<>()) : existing;
        breakpoint.setEnabled((Boolean) args.get("enabled"));
        Map<String,Object> info = new LinkedHashMap<>(breakpointInfo(breakpoint)); info.put("created",created);
        return info;
    }

    private Object frames(Map<String,Object> args,CallBudget budget) throws Exception {
        IThread thread = thread(text(args,"threadId")); requireSuspended(thread);
        Generation generation = HANDLES.generation(thread); IStackFrame[] frames = thread.getStackFrames();
        int offset = number(args,"offset"), next = end(offset,frames.length,number(args,"limit"));
        List<Object> items = new ArrayList<>();
        for (int index = offset; index < next; index++) {
            budget.checkCancelled(); IStackFrame frame = frames[index];
            String handle = HANDLES.put(frame,budget.sessionId(),generation);
            items.add(Map.of("frameId",handle,"index",index,"name",bounded(frame.getName(),1024),"line",frame.getLineNumber(),"threadId",id(thread)));
        }
        requireSuspended(thread); HANDLES.requireGeneration(thread,generation);
        return page("frames",items,offset,next,frames.length);
    }
    private Object variables(Map<String,Object> args,CallBudget budget) throws Exception {
        String frameId = text(args,"frameId"); IStackFrame frame = HANDLES.resolve(frameId,budget.sessionId());
        List<?> path = (List<?>) args.get("variablePath");
        if (path.size() > 16) throw new RequestError("invalid_range", "variablePath depth exceeds 16");
        IVariable[] children = frame.getVariables(); IValue value = null;
        for (Object item : path) {
            budget.checkCancelled(); int index = ((Number) item).intValue();
            IVariable variable;
            if (value instanceof IIndexedValue indexed) {
                int first = indexed.getInitialOffset();
                if (index < first || (long) index >= (long) first+indexed.getSize()) throw new RequestError("invalid_range", "variablePath index is unavailable");
                variable = indexed.getVariable(index);
            } else {
                if (index >= children.length) throw new RequestError("invalid_range", "variablePath index is unavailable");
                variable = children[index];
            }
            value = variable.getValue();
            children = value instanceof IIndexedValue ? null : value.getVariables();
            if (children == null) children = new IVariable[0];
        }
        int offset = number(args,"offset"), limit = number(args,"limit"), maximum = number(args,"maxValueLength");
        int total = value instanceof IIndexedValue indexed ? indexed.getSize() : children.length;
        int next = end(offset,total,limit), first = value instanceof IIndexedValue indexed ? indexed.getInitialOffset() : 0;
        IVariable[] selected = next == offset ? new IVariable[0] : value instanceof IIndexedValue indexed
            ? indexed.getVariables(first+offset,next-offset) : Arrays.copyOfRange(children,offset,next);
        List<Object> items = new ArrayList<>();
        for (int index = 0; index < selected.length; index++) {
            budget.checkCancelled(); IVariable variable = selected[index]; IValue childValue = variable.getValue();
            String nativeValue = childValue.getValueString();
            List<Object> childPath = new ArrayList<>(path); childPath.add(first+offset+index);
            items.add(Map.of("name",bounded(variable.getName(),1024),"type",bounded(variable.getReferenceTypeName(),1024),
                "value",bounded(nativeValue,maximum),"valueTruncated",nativeValue != null && nativeValue.length() > maximum,
                "hasChildren",childValue.hasVariables(),"variablePath",childPath));
        }
        HANDLES.resolve(frameId,budget.sessionId());
        Map<String,Object> data = new LinkedHashMap<>(page("variables",items,offset,next,total));
        data.put("frameId",frameId); data.put("variablePath",path); return data;
    }
    private Object control(Map<String,Object> args,CallBudget budget,String action) throws Exception {
        IThread thread = thread(text(args,"threadId")); requireSuspended(thread);
        boolean possible = switch (action) {
            case "resume" -> thread.canResume(); case "into" -> thread.canStepInto();
            case "over" -> thread.canStepOver(); case "return" -> thread.canStepReturn(); default -> false;
        };
        if (!possible) throw new RequestError("thread_cannot_" + action, "Thread cannot " + action + " in its current state");
        CountDownLatch stopped = new CountDownLatch(1);
        IDebugEventSetListener listener = events -> {
            for (DebugEvent event : events) if ((event.getSource() == thread || event.getSource() == thread.getDebugTarget())
                    && (event.getKind() == DebugEvent.SUSPEND || event.getKind() == DebugEvent.TERMINATE)) stopped.countDown();
        };
        DebugPlugin.getDefault().addDebugEventListener(listener);
        try {
            budget.checkCancelled(); HANDLES.invalidate(thread);
            switch (action) {
                case "resume" -> thread.resume(); case "into" -> thread.stepInto();
                case "over" -> thread.stepOver(); case "return" -> thread.stepReturn();
            }
            if (!action.equals("resume")) {
                while (stopped.getCount() != 0) {
                    budget.checkCancelled(); stopped.await(Math.min(100,budget.remainingMs()),TimeUnit.MILLISECONDS);
                }
            }
            budget.checkCancelled(); return threadInfo(thread);
        } finally { DebugPlugin.getDefault().removeDebugEventListener(listener); }
    }
    private static void requireSuspended(IThread thread) {
        if (thread.isTerminated()) throw new RequestError("thread_terminated", "Thread has terminated; call list_debug_targets");
        if (!thread.isSuspended()) throw new RequestError("thread_not_suspended", "Thread is not suspended; refresh targets and frames");
    }
    private static void invalidateEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.RESUME && event.getKind() != DebugEvent.TERMINATE && event.getKind() != DebugEvent.SUSPEND) continue;
            if (event.getSource() instanceof IThread thread) HANDLES.invalidate(thread);
            else if (event.getSource() instanceof IDebugTarget target) HANDLES.invalidate(target);
        }
    }

    private record Generation(long thread,long target) { }
    private record FrameHandle(String session,IStackFrame frame,Generation generation) { }
    private static final class FrameHandles {
        private final LinkedHashMap<String,FrameHandle> frames = new LinkedHashMap<>();
        private final WeakHashMap<IDebugElement,Long> generations = new WeakHashMap<>();
        synchronized void clear() { frames.clear(); generations.clear(); }
        synchronized Generation generation(IThread thread) {
            return new Generation(generations.getOrDefault(thread,0L),generations.getOrDefault(thread.getDebugTarget(),0L));
        }
        synchronized void requireGeneration(IThread thread,Generation expected) {
            if (!generation(thread).equals(expected)) throw new RequestError("frame_stale", "Stale frameId; call get_frames after suspension");
        }
        synchronized String put(IStackFrame frame,String session,Generation generation) {
            requireGeneration(frame.getThread(),generation);
            while (frames.size() >= 512) frames.remove(frames.keySet().iterator().next());
            String id = UUID.randomUUID().toString(); frames.put(id,new FrameHandle(session,frame,generation)); return id;
        }
        IStackFrame resolve(String id,String session) throws Exception {
            FrameHandle handle;
            synchronized (this) { handle = frames.get(id); }
            if (handle == null || !handle.session().equals(session)) throw new RequestError("frame_invalid", "Unknown, expired or foreign-session frameId; call get_frames");
            IThread thread = handle.frame().getThread(); requireSuspended(thread); requireGeneration(thread,handle.generation());
            for (IStackFrame live : thread.getStackFrames()) if (live == handle.frame()) {
                requireSuspended(thread); requireGeneration(thread,handle.generation()); return live;
            }
            throw new RequestError("frame_gone", "Frame no longer exists; call get_frames");
        }
        synchronized void invalidate(IDebugElement element) {
            generations.merge(element,1L,Long::sum);
            frames.entrySet().removeIf(entry -> entry.getValue().frame().getThread() == element
                || entry.getValue().frame().getDebugTarget() == element);
        }
    }

    public static void selfCheck() throws Exception {
        AtomicBoolean suspended = new AtomicBoolean(true);
        IStackFrame[] live = new IStackFrame[1];
        IThread thread = (IThread) Proxy.newProxyInstance(DebugTools.class.getClassLoader(),new Class<?>[]{IThread.class},(proxy,method,args) -> switch (method.getName()) {
            case "isSuspended" -> suspended.get(); case "isTerminated" -> false;
            case "getStackFrames" -> live; case "getDebugTarget" -> null;
            case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null;
        });
        IStackFrame frame = (IStackFrame) Proxy.newProxyInstance(DebugTools.class.getClassLoader(),new Class<?>[]{IStackFrame.class},(proxy,method,args) -> switch (method.getName()) {
            case "getThread" -> thread; case "getDebugTarget" -> null;
            case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null;
        });
        live[0] = frame; FrameHandles handles = new FrameHandles();
        String handle = handles.put(frame,"one",handles.generation(thread));
        if (handles.resolve(handle,"one") != frame) throw new AssertionError("Live frame missing");
        try { handles.resolve(handle,"two"); throw new AssertionError("Cross-session frame accepted"); } catch (IllegalArgumentException expected) { }
        suspended.set(false);
        try { handles.resolve(handle,"one"); throw new AssertionError("Running frame accepted"); } catch (RequestError expected) { }
        // The codes are the contract: callers branch on them instead of parsing a message.
        try { requireSuspended(thread); throw new AssertionError("running thread accepted"); }
        catch (RequestError expected) { if (!"thread_not_suspended".equals(expected.code)) throw new AssertionError("code"); }
        suspended.set(true); handles.invalidate(thread);
        try { handles.resolve(handle,"one"); throw new AssertionError("Resumed frame accepted"); } catch (IllegalArgumentException expected) { }
        try { requireSuspended((IThread) Proxy.newProxyInstance(DebugTools.class.getClassLoader(),
                new Class<?>[]{IThread.class},(proxy,method,args) -> switch (method.getName()) {
                    case "isSuspended" -> false; case "isTerminated" -> true;
                    case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null; }));
            throw new AssertionError("terminated thread accepted"); }
        catch (RequestError expected) { if (!"thread_terminated".equals(expected.code)) throw new AssertionError("code"); }
        // set_breakpoint addresses a file two ways; both must land on the same workspace path.
        if (!"/Proj/src/Main.java".equals(workspacePath("Proj", "src/Main.java")))
            throw new AssertionError("project-relative path not resolved");
        if (!"/Proj/Main.java".equals(workspacePath("Proj", "Main.java")))
            throw new AssertionError("project-root file not resolved");
        if (!"/Proj/src/Main.java".equals(workspacePath(null, "/Proj/src/Main.java")))
            throw new AssertionError("absolute path mangled");
        if (!"/Proj/src/Main.java".equals(workspacePath("", "/Proj/src/Main.java")))
            throw new AssertionError("blank project must fall back to the absolute form");
        if (!"/Proj/src/Main.java".equals(workspacePath("  ", "/Proj/src/Main.java")))
            throw new AssertionError("blank project must fall back to the absolute form");
        try { workspacePath("../evil", "src/Main.java"); throw new AssertionError("traversal project accepted"); }
        catch (RequestError expected) { if (!"invalid_project".equals(expected.code)) throw new AssertionError("code"); }
        try { workspacePath("Proj", "/src/Main.java"); throw new AssertionError("absolute path mixed with project"); }
        catch (RequestError expected) { if (!"invalid_path".equals(expected.code)) throw new AssertionError("code"); }
    }
}
