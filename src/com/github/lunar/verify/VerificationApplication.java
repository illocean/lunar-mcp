package com.github.lunar.verify;

import com.github.lunar.io.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;

/** Real Eclipse verification bootstrap; fixture content lives entirely below lunar/smoke. */
public final class VerificationApplication implements IApplication {
    private static final java.nio.file.Path ROOT = java.nio.file.Path.of("D:/EclipseIDE/lunar").toAbsolutePath().normalize();
    private static final String PROJECT = "LunarVerification";
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile WatchService watcher;

    @Override public Object start(IApplicationContext context) throws Exception {
        String[] arguments = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        java.nio.file.Path stopFile = inside(option(arguments,"-lunarStop"));
        java.nio.file.Path readyFile = inside(option(arguments,"-lunarReady"));
        Files.createDirectories(stopFile.getParent()); Files.createDirectories(readyFile.getParent());
        if (Files.exists(stopFile) || Files.exists(readyFile)) throw new IllegalArgumentException("Use fresh unique stop/ready files");
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(PROJECT);
        if (project.exists()) throw new IllegalStateException("LunarVerification already exists; refusing to borrow or delete it");
        var manager = DebugPlugin.getDefault().getLaunchManager();
        Set<String> existingConfigurations = new HashSet<>();
        for (ILaunchConfiguration configuration : manager.getLaunchConfigurations()) existingConfigurations.add(configuration.getMemento());
        Set<ILaunch> existingLaunches = new HashSet<>(Arrays.asList(manager.getLaunches()));
        Set<String> existingBreakpoints = new HashSet<>();
        for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) existingBreakpoints.add(breakpointKey(breakpoint));
        String nonce = UUID.randomUUID().toString();
        String prefix = "LunarVerification-" + nonce;
        java.nio.file.Path fixture = ROOT.resolve("smoke/project");
        boolean created = false;
        try {
            // Reset only this owned fixture, so repeated gates start from the same compiler/debug state.
            seed("ProbeMain.java",fixture.resolve("src/lunar/verify/ProbeMain.java"));
            seed("ProbeTest.java",fixture.resolve("tests/lunar/verify/ProbeTest.java"));
            Files.createDirectories(fixture.resolve("bin"));
            IProjectDescription description = workspace.newProjectDescription(PROJECT);
            description.setLocation(new Path(fixture.toString()));
            description.setNatureIds(new String[]{JavaCore.NATURE_ID});
            var builder = description.newCommand(); builder.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new org.eclipse.core.resources.ICommand[]{builder});
            project.create(description,new NullProgressMonitor()); created = true;
            project.open(new NullProgressMonitor()); project.refreshLocal(IResource.DEPTH_INFINITE,new NullProgressMonitor());
            var javaProject = JavaCore.create(project);
            List<IClasspathEntry> classpath = new ArrayList<>();
            classpath.add(JavaCore.newSourceEntry(project.getFullPath().append("src")));
            classpath.add(JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER)));
            classpath.add(JavaCore.newContainerEntry(JUnitCore.JUNIT5_CONTAINER_PATH));
            javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new),project.getFullPath().append("bin"),new NullProgressMonitor());
            var junit = JavaCore.getClasspathContainer(JUnitCore.JUNIT5_CONTAINER_PATH,javaProject);
            boolean junitAvailable = junit != null && junit.getClasspathEntries().length > 0
                && Arrays.stream(junit.getClasspathEntries()).allMatch(entry -> Files.isRegularFile(java.nio.file.Path.of(entry.getPath().toOSString())));
            if (junitAvailable) classpath.add(JavaCore.newSourceEntry(project.getFullPath().append("tests")));
            else classpath.removeIf(entry -> entry.getPath().equals(JUnitCore.JUNIT5_CONTAINER_PATH));
            javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new),project.getFullPath().append("bin"),new NullProgressMonitor());
            Map<String,String> compiler = javaProject.getOptions(false);
            JavaCore.setComplianceOptions(JavaCore.VERSION_21,compiler);
            compiler.put(JavaCore.COMPILER_PB_UNUSED_IMPORT,JavaCore.WARNING);
            compiler.put(JavaCore.COMPILER_LOCAL_VARIABLE_ATTR,JavaCore.GENERATE);
            compiler.put(JavaCore.COMPILER_LINE_NUMBER_ATTR,JavaCore.GENERATE);
            javaProject.setOptions(compiler);
            String junitConfiguration = "";
            if (junitAvailable) {
                var type = manager.getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig");
                if (type == null) throw new IllegalStateException("JUnit launch type missing despite resolved container");
                ILaunchConfigurationWorkingCopy configuration = type.newInstance(null,prefix+"-JUnit");
                configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,PROJECT);
                configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,"lunar.verify.ProbeTest");
                configuration.setAttribute("org.eclipse.jdt.junit.TEST_KIND","org.eclipse.jdt.junit.loader.junit5");
                configuration.setAttribute("org.eclipse.jdt.junit.KEEPRUNNING_ATTR",false);
                configuration.setMappedResources(new IResource[]{project});
                junitConfiguration = configuration.doSave().getName();
            }
            Map<String,Object> ready = new LinkedHashMap<>();
            ready.put("project",PROJECT); ready.put("projectLocation",fixture.toString()); ready.put("nonce",nonce);
            ready.put("configurationPrefix",prefix); ready.put("mainType","lunar.verify.ProbeMain");
            ready.put("mainPath","src/lunar/verify/ProbeMain.java"); ready.put("breakpointLine",10);
            ready.put("junitAvailable",junitAvailable); ready.put("junitConfiguration",junitConfiguration);
            ready.put("existingProjects",Arrays.stream(workspace.getRoot().getProjects()).filter(p -> !p.equals(project)).map(IProject::getName).sorted().toList());
            Files.writeString(readyFile,Json.write(ready),StandardCharsets.UTF_8);
            System.out.println("LUNAR VERIFICATION READY: " + readyFile);
            watcher = stopFile.getFileSystem().newWatchService();
            stopFile.getParent().register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY);
            Thread signal = new Thread(() -> {
                try {
                    while (!Files.exists(stopFile)) {
                        var key = watcher.take(); key.pollEvents();
                        if (!key.reset()) break;
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                  catch (java.nio.file.ClosedWatchServiceException closed) { }
                finally { stopped.countDown(); }
            },"lunar verification stop watcher");
            signal.setDaemon(true); signal.start();
            stopped.await();
            return IApplication.EXIT_OK;
        } finally {
            stop();
            if (created) {
                for (ILaunch launch : manager.getLaunches()) {
                    ILaunchConfiguration configuration = launch.getLaunchConfiguration();
                    if (!existingLaunches.contains(launch) && configuration != null
                        && configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,"").equals(PROJECT)
                        && launch.canTerminate()) launch.terminate();
                }
                for (IBreakpoint breakpoint : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
                    if (breakpoint.getMarker().getResource().getProject().equals(project)
                        && !existingBreakpoints.contains(breakpointKey(breakpoint))) breakpoint.delete();
                }
                for (ILaunchConfiguration configuration : manager.getLaunchConfigurations()) {
                    if (!existingConfigurations.contains(configuration.getMemento()) && configuration.getName().startsWith(prefix)
                        && configuration.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,"").equals(PROJECT)) configuration.delete();
                }
                project.delete(false,true,new NullProgressMonitor());
                System.out.println("LUNAR VERIFICATION CLEANUP: temporary metadata removed; fixture source retained under lunar");
            }
        }
    }
    private static String breakpointKey(IBreakpoint breakpoint) {
        return breakpoint.getMarker().getResource().getFullPath()+"#"+breakpoint.getMarker().getId();
    }
    private static void seed(String name,java.nio.file.Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        Files.copy(ROOT.resolve("smoke/seeds/"+name),destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static String option(String[] arguments,String name) {
        for (int i = 0; i+1 < arguments.length; i++) if (arguments[i].equals(name)) return arguments[i+1];
        throw new IllegalArgumentException("Missing " + name);
    }
    private static java.nio.file.Path inside(String path) {
        var value = java.nio.file.Path.of(path).toAbsolutePath().normalize();
        if (!value.startsWith(ROOT.resolve("smoke"))) throw new IllegalArgumentException("Verification artifacts must stay under lunar/smoke");
        return value;
    }
    @Override public void stop() {
        stopped.countDown(); WatchService active = watcher;
        if (active != null) try { active.close(); } catch (java.io.IOException ignored) { }
    }
}
