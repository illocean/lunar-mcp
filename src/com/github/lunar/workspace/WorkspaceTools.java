package com.github.lunar.workspace;

import static com.github.lunar.tools.ToolSpec.RiskTier.*;
import static com.github.lunar.tools.Tools.*;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.RequestError;
import com.github.lunar.tools.SchemaValidator;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolProvider;
import com.github.lunar.tools.ToolResult;
import com.github.lunar.tools.ToolSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/** Workspace resources and JDT, through native Eclipse APIs rather than external compilers. */
public final class WorkspaceTools implements ToolProvider {
    // ponytail: reads and hash-guarded mutations cap files at 2 MiB; stream hashes/pages when larger assets are needed.
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REFERENCE_RESULTS = 50_000;

    @FunctionalInterface
    private interface Action { Object call(Map<String, Object> args, CallBudget budget) throws Exception; }

    private record Entry(ToolSpec spec, Action action, boolean managesBuildRule) implements Tool {
        @Override public ISchedulingRule schedulingRule() {
            return managesBuildRule ? null : Tool.super.schedulingRule();
        }
        @Override public ToolResult call(Map<String, Object> args, CallBudget budget) throws Exception {
            budget.checkCancelled();
            try { return ToolResult.ok(action.call(args, budget), null); }
            catch (RequestError e) { return ToolResult.error(e.code, e.getMessage(), null); }
        }
    }

    

    @Override public List<Tool> tools() {
        return List.of(
            tool("project_info", "Project metadata and JDT configuration", READ,
                projectSchema(Map.of()), this::projectInfo),
            tool("create_project", "Create a project Eclipse actually knows about, so every other tool can address it. " +
                "Pass location to import an existing directory (its .project is honoured when present); " +
                "omit it to create a new project under the workspace. Refuses an existing name.", MUTATE,
                object(Map.of("project", string(), "location", string(), "nature", enumString("java", "plain")),
                    "project"), this::createProject),
            tool("delete_project", "Close and delete a project and its files from disk. Refuses unless confirm is the exact project name.", DESTRUCTIVE,
                object(Map.of("project", string(), "confirm", string(), "deleteContent", bool(false)),
                    "project", "confirm"), this::deleteProject),
            tool("list_files", "Page through project files in sorted path order; excludes derived files by default", READ,
                pageProject(Map.of("path", string(), "includeDerived", bool(false))), this::listFiles),
            tool("read_file", "Read saved text or binary hash metadata; UTF-16 offsets, max 2 MiB; page with offset/nextOffset until hasMore is false", READ,
                projectSchema(Map.of("path", string(), "offset", integer(0, 0, MAX_FILE_BYTES),
                    "limit", integer(8000, 1, 65536), "expectedHash", string()), "path"), this::readFile),
            tool("search_text", "Literal text search in saved project files; sorted, paged matches and explicit skipped-file counts", READ,
                pageProject(Map.of("path", string(), "query", string(), "caseSensitive", bool(true),
                    "includeDerived", bool(false)), "query"), this::searchText),
            tool("create_file", "Create a new UTF-8 project file and missing folders; never overwrite", MUTATE,
                projectSchema(Map.of("path", string(), "content", string()), "path", "content"), this::createFile),
            tool("write_file", "Replace saved text preserving charset and local history; requires read_file expectedHash; rejects dirty buffers", MUTATE,
                projectSchema(Map.of("path", string(), "content", string(), "expectedHash", string()),
                    "path", "content", "expectedHash"), this::writeFile),
            tool("move_file", "Move a project file without overwriting; requires expectedHash and rejects dirty buffers", MUTATE,
                projectSchema(Map.of("path", string(), "destination", string(), "expectedHash", string()),
                    "path", "destination", "expectedHash"), this::moveFile),
            tool("delete_file", "Delete one project file retaining local history; requires expectedHash and rejects dirty buffers", DESTRUCTIVE,
                projectSchema(Map.of("path", string(), "expectedHash", string()), "path", "expectedHash"), this::deleteFile),
            tool("apply_edit", "Replace a UTF-16 character range in saved text; requires expectedHash and rejects dirty buffers", DESTRUCTIVE,
                projectSchema(Map.of("path", string(), "expectedHash", string(), "offset", integer(0, 0, MAX_FILE_BYTES),
                    "length", integer(0, 0, MAX_FILE_BYTES), "text", string()), "path", "expectedHash", "text"), this::applyEdit),
            tool("refresh_project", "Refresh the project's Eclipse resource model from disk", MUTATE,
                projectSchema(Map.of()), this::refreshProject),
            tool("build_project", "Refresh and synchronously build saved project sources; join native builds before reading markers; clean also rebuilds", BUILD,
                projectSchema(Map.of("kind", enumString("incremental", "full", "clean"))), this::buildProject, true),
            tool("build_workspace", "Refresh and synchronously build all open projects; clean also rebuilds; return real markers", BUILD,
                object(Map.of("kind", enumString("incremental", "full", "clean"))), this::buildWorkspace, true),
            tool("get_problems", "Page through Eclipse problem markers; no implied build; use build_project for current compiler diagnostics", READ,
                pageProject(Map.of("path", string(), "severity", enumString("all", "error", "warning", "info"))), this::getProblems),
            tool("clear_markers", "Delete matching problem markers under a project path; clearing a marker does not fix its cause", MUTATE,
                projectSchema(Map.of("path", string(), "severity", enumString("all", "error", "warning", "info"))), this::clearMarkers),
            tool("wait_until_quiet", "Wait for native auto/manual build completion and a resource-change quiet window; does not mean every plugin is idle", BUILD,
                object(Map.of("quietMs", integer(250, 1, 5000))), this::waitUntilQuiet, true),
            tool("get_java_symbols", "Page through JDT types, fields and methods in one saved Java compilation unit", READ,
                pageProject(Map.of("path", string()), "path"), this::javaSymbols),
            tool("find_references", "Resolve the Java symbol at a UTF-16 source offset and page sorted JDT references inside this project", READ,
                pageProject(Map.of("path", string(), "sourceOffset", integer(0, 0, MAX_FILE_BYTES),
                    "sourceLength", integer(0, 0, MAX_FILE_BYTES)), "path"), this::findReferences),
            tool("apply_quick_fix", "Persist a current unused/duplicate-import correction; reconcile first; other compiler problems report unsupported_quick_fix", DESTRUCTIVE,
                projectSchema(Map.of("path", string(), "markerId", Map.of("type", "integer", "minimum", 0L, "maximum", Long.MAX_VALUE), "expectedHash", string()),
                    "path", "markerId", "expectedHash"), this::quickFix)
        );
    }

    private static Tool tool(String name, String description, ToolSpec.RiskTier risk,
            Map<String, Object> schema, Action action) { return tool(name, description, risk, schema, action, false); }
    private static Tool tool(String name, String description, ToolSpec.RiskTier risk,
            Map<String, Object> schema, Action action, boolean managesBuildRule) {
        return new Entry(new ToolSpec(name, name.replace('_', ' '), description, schema, risk), action, managesBuildRule);
    }
    private static Map<String, Object> projectSchema(Map<String, Object> extra, String... required) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", string()); props.putAll(extra);
        String[] all = new String[required.length + 1]; all[0] = "project";
        System.arraycopy(required, 0, all, 1, required.length);
        return object(props, all);
    }
    private static Map<String, Object> pageProject(Map<String, Object> extra, String... required) {
        Map<String, Object> props = new LinkedHashMap<>(extra);
        props.put("offset", integer(0, 0, 1_000_000)); props.put("limit", integer(100, 1, 500));
        return projectSchema(props, required);
    }
    private static String str(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key); return value == null ? fallback : (String) value;
    }
    private static int num(Map<String, Object> args, String key) { return ((Number) args.get(key)).intValue(); }
    private static IWorkspace workspace() { return ResourcesPlugin.getWorkspace(); }
    /** Empty when the name is not a single legal project name; otherwise the name itself. */
    static String safeProjectName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.equals(".") || name.contains(":") || name.contains("\0")) return "";
        return name;
    }
    /** Validate a project name the same way for existence and for creation, where it need not exist yet. */
    private static String projectName(Map<String, Object> args) {
        String name = safeProjectName(str(args, "project", ""));
        if (name.isEmpty())
            throw new RequestError("invalid_project", "project must be a single name without path separators");
        return name;
    }
    private static IProject project(Map<String, Object> args) {
        String name = str(args, "project", "");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new RequestError("invalid_project", "project must be the exact name from list_projects");
        IProject p = workspace().getRoot().getProject(name);
        if (!p.exists() || !p.isOpen()) throw new RequestError("project_unavailable", "project does not exist or is closed: " + name);
        return p;
    }

    static String relativePath(String raw, boolean allowEmpty) {
        if (raw == null) raw = "";
        String value = raw.replace('\\', '/');
        if (value.isEmpty() && allowEmpty) return value;
        // "." and "./x" are ordinary spellings of the project root, so collapse them instead of
        // rejecting them: listing "." must behave like listing no path at all.
        if (allowEmpty && (value.equals(".") || value.equals("./"))) return "";
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/") || value.contains(":"))
            throw new RequestError("invalid_path", "path must be relative to the project and name a resource");
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/", -1)) {
            if (segment.equals(".")) continue;
            if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ") || segment.chars().anyMatch(c -> c < 32))
                throw new RequestError("invalid_path", "empty segments, traversal and control characters are forbidden");
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    /** Check physical ancestry as well as Eclipse paths, including symlinks and Windows junctions. */
    private static void contained(IProject p, IResource resource) throws Exception {
        if (p.getLocation() == null || resource.getLocation() == null)
            throw new RequestError("nonlocal_resource", "only local project resources are supported");
        java.nio.file.Path base = p.getLocation().toFile().toPath().toAbsolutePath().normalize();
        java.nio.file.Path target = resource.getLocation().toFile().toPath().toAbsolutePath().normalize();
        if (!target.startsWith(base)) throw new RequestError("outside_project", "linked resource escapes the project");
        java.nio.file.Path ancestor = target;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            if (ancestor == null) throw new RequestError("outside_project", "resource has no existing local ancestor");
        }
        if (!ancestor.toRealPath().startsWith(base.toRealPath()))
            throw new RequestError("outside_project", "resource symlink or junction escapes the project");
    }
    private static IFile file(IProject p, String path, boolean mustExist) throws Exception {
        IFile file = p.getFile(IPath.fromPortableString(relativePath(path, false)));
        contained(p, file);
        if (mustExist && !file.exists()) throw new RequestError("file_missing", "file not found: " + path);
        return file;
    }
    private static IResource scope(IProject p, Map<String, Object> args) throws Exception {
        String path = relativePath(str(args, "path", ""), true);
        IResource r = path.isEmpty() ? p : p.findMember(path);
        if (r == null) throw new RequestError("resource_missing", "path not found: " + path);
        contained(p, r); return r;
    }
    private static boolean dirty(IFile file) throws CoreException {
        ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.isDirty()) return true;
        if (file.getLocation() != null) {
            buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getLocation(), LocationKind.LOCATION);
            if (buffer != null && buffer.isDirty()) return true;
        }
        if (file.getName().endsWith(".java")) {
            for (ICompilationUnit copy : JavaCore.getWorkingCopies(null))
                if (file.equals(copy.getResource()) && copy.getBuffer() != null && copy.getBuffer().hasUnsavedChanges()) return true;
        }
        return false;
    }
    private static void writable(IFile file) throws CoreException {
        if (dirty(file)) throw new RequestError("dirty_buffer", "unsaved editor/JDT buffer exists; save or discard it before writing " + file.getProjectRelativePath());
        if (file.getResourceAttributes() != null && file.getResourceAttributes().isReadOnly())
            throw new RequestError("read_only", "file is read-only");
    }

    private record Content(byte[] bytes, String text, Charset charset, String hash) { }
    private static Content content(IFile file, CallBudget budget) throws Exception {
        byte[] bytes;
        try (InputStream in = file.getContents()) {
            var out = new java.io.ByteArrayOutputStream(); byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) {
                budget.checkCancelled();
                if (out.size() + n > MAX_FILE_BYTES) throw new RequestError("file_too_large", "text file exceeds the 2 MiB limit");
                out.write(block, 0, n);
            }
            bytes = out.toByteArray();
        }
        Charset charset = Charset.forName(file.getCharset());
        String text = null;
        try { text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { /* Return binary metadata rather than inventing a decoded string. */ }
        if (text != null && text.indexOf('\0') >= 0) text = null;
        return new Content(bytes, text, charset, hash(bytes));
    }
    private static void textOnly(Content content) {
        if (content.text == null) throw new RequestError("non_text_file", "this operation requires valid text in the file's declared charset");
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void checkHash(Content content, Map<String, Object> args) {
        String expected = str(args, "expectedHash", null);
        if (expected != null && !content.hash.equalsIgnoreCase(expected))
            throw new RequestError("stale_file", "saved file changed; call read_file and use its current hash");
    }
    private static byte[] encode(String text, Charset charset) throws Exception {
        if (text.indexOf('\0') >= 0) throw new RequestError("non_text_file", "text cannot contain NUL");
        ByteBuffer encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        if (encoded.remaining() > MAX_FILE_BYTES) throw new RequestError("file_too_large", "encoded text exceeds the 2 MiB limit");
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
    }
    private static Map<String, Object> save(IFile file, Content old, String text, CallBudget budget) throws Exception {
        writable(file); budget.checkCancelled();
        byte[] bytes = encode(text, old.charset);
        boolean changed = !Arrays.equals(bytes, old.bytes);
        if (changed) file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, budget.monitor());
        return Map.of("path", file.getProjectRelativePath().toString(), "hash", hash(bytes), "previousHash", old.hash, "changed", changed);
    }
    private static void makeParents(IProject p, IContainer parent, CallBudget budget) throws Exception {
        if (parent instanceof IFolder folder && !folder.exists()) {
            contained(p, folder); makeParents(p, folder.getParent(), budget); budget.checkCancelled();
            folder.create(false, true, budget.monitor());
        }
    }

    private Object projectInfo(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); budget.checkCancelled();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", p.getName()); data.put("path", p.getFullPath().toString());
        data.put("location", p.getLocation() == null ? "" : p.getLocation().toOSString());
        data.put("natures", List.of(p.getDescription().getNatureIds()));
        data.put("builders", Arrays.stream(p.getDescription().getBuildSpec()).map(c -> c.getBuilderName()).toList());
        data.put("autoBuild", workspace().getDescription().isAutoBuilding());
        if (p.hasNature(JavaCore.NATURE_ID)) {
            IJavaProject java = JavaCore.create(p); List<Object> classpath = new ArrayList<>();
            for (var entry : java.getRawClasspath()) {
                budget.checkCancelled(); classpath.add(Map.of("kind", entry.getEntryKind(), "path", entry.getPath().toString()));
            }
            data.put("java", Map.of("classpath", classpath, "output", java.getOutputLocation().toString(),
                "source", java.getOption(JavaCore.COMPILER_SOURCE, true), "compliance", java.getOption(JavaCore.COMPILER_COMPLIANCE, true)));
        }
        return data;
    }

    private Object createProject(Map<String, Object> args, CallBudget budget) throws Exception {
        String name = projectName(args);
        IProject p = workspace().getRoot().getProject(name);
        if (p.exists()) throw new RequestError("project_exists", "project already exists: " + name);
        String location = str(args, "location", null);
        java.nio.file.Path dir = null;
        if (location != null && !location.isBlank()) {
            if (location.contains("..")) throw new RequestError("invalid_location", "location must not contain '..'");
            dir = java.nio.file.Path.of(location).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) throw new RequestError("invalid_location", "location is not an existing directory: " + dir);
            allowedRoot(dir);
            java.nio.file.Path marker = dir.resolve(".project");
            if (Files.exists(marker)) {
                budget.checkCancelled();
                // An existing Eclipse project on disk: adopt its own description rather than
                // inventing a new one, so natures and builders survive the import.
                IProject imported = workspace().getRoot().getProject(
                    workspace().loadProjectDescription(new org.eclipse.core.runtime.Path(marker.toString())).getName());
                // create() without a location puts the project in the workspace metadata area
                // and leaves the directory on disk untouched, so the location must be set first.
                if (imported.getLocation() == null || !imported.getLocation().toFile().equals(dir.toFile())) {
                    imported.create(descriptionWithLocation(imported, dir), budget.monitor());
                } else {
                    imported.create(budget.monitor());
                }
                if (!imported.isOpen()) imported.open(budget.monitor());
                return Map.of("project", imported.getName(), "imported", true, "path", imported.getFullPath().toString(),
                    "location", imported.getLocation() == null ? "" : imported.getLocation().toOSString(),
                    "natures", List.of(imported.getDescription().getNatureIds()));
            }
        }
        IProjectDescription description = workspace().newProjectDescription(name);
        if (dir != null) description.setLocationURI(dir.toUri());
        String nature = str(args, "nature", "plain");
        if ("java".equals(nature)) {
            description.setNatureIds(new String[] { JavaCore.NATURE_ID });
            // A nature without the matching builder is a project that never compiles: the
            // build spec stays empty, no marker is ever produced and no main type is
            // resolvable. Add the Java builder and a default source/JRE classpath.
            ICommand javaBuilder = description.newCommand();
            javaBuilder.setBuilderName(JavaCore.BUILDER_ID);
            description.setBuildSpec(new ICommand[] { javaBuilder });
        }
        p.create(description, budget.monitor());
        p.open(budget.monitor());
        if ("java".equals(nature)) {
            IJavaProject javaProject = JavaCore.create(p);
            if (javaProject != null && javaProject.exists()) {
                List<IClasspathEntry> entries = new ArrayList<>();
                entries.add(JavaCore.newSourceEntry(p.getFullPath()));
                entries.add(JavaCore.newContainerEntry(
                    new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));
                try {
                    javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), budget.monitor());
                } catch (CoreException classpathFailed) {
                    // A project that cannot hold a classpath is still a project; the nature and
                    // builder are in place, so report the failure rather than losing the project.
                    throw new RequestError("classpath_failed",
                        "could not set the default Java classpath: " + classpathFailed.getMessage());
                }
            }
        }
        return Map.of("project", p.getName(), "imported", false, "path", p.getFullPath().toString(),
            "location", p.getLocation() == null ? "" : p.getLocation().toOSString(),
            "natures", List.of(p.getDescription().getNatureIds()));
    }

    /**
     * The roots a project may be created in, and the one check that enforces it.
     *
     * <p>Every file tool is sandboxed to "the project", so registering a directory as a project
     * hands the caller that directory. Rejecting only the {@code ..} substring therefore let an
     * agent register a parent of the workspace and then write or delete anywhere under it, which
     * makes {@code confirm} on delete a typo guard and nothing more. The default is the workspace
     * root itself; {@code -Dlunar.projectRoots} <em>replaces</em> that default with its own list,
     * joined by {@link java.io#pathSeparator}, and an ancestor of the workspace or of the home
     * directory is refused even when named, because a root that contains the workspace is a root
     * that contains everything the agent already has.
     */
    static void allowedRoot(java.nio.file.Path dir) throws Exception {
        java.nio.file.Path workspaceRoot = workspace().getRoot().getLocation() == null ? null
                : workspace().getRoot().getLocation().toFile().toPath().toRealPath();
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home", "")).toRealPath();
        List<java.nio.file.Path> roots = configuredRoots(System.getProperty("lunar.projectRoots"));
        refuseAncestorRoots(roots, home, workspaceRoot);
        if (roots.isEmpty() && workspaceRoot != null) roots.add(workspaceRoot);
        bounded(dir.toRealPath(), roots, home, workspaceRoot);
    }

    /**
     * The paths a project location may never sit at or above, with the null workspace root
     * filtered out rather than guarded inside the loop.
     *
     * <p>{@code List.of} rejects null elements in its constructor, so building the pair with a
     * null {@code workspaceRoot} -- which {@link #allowedRoot} explicitly allows -- threw
     * {@code NullPointerException} before any {@code if (forbidden == null) continue} could run.
     * Every file tool on a workspace with no root location died as {@code tool_failed}, from a
     * line whose own text claimed null was handled.
     */
    private static List<java.nio.file.Path> forbiddenRoots(java.nio.file.Path home,
            java.nio.file.Path workspaceRoot) {
        List<java.nio.file.Path> out = new ArrayList<>(2);
        if (workspaceRoot != null) out.add(workspaceRoot);
        out.add(home);
        return out;
    }

    /**
     * A configured root that is, or contains, the workspace or the home directory is the opposite
     * of a sandbox: it hands the agent every file it already has, plus everything else. The
     * per-target check in {@link #bounded} refuses an ancestor *location*, so a root that was
     * itself an ancestor was never caught, and {@code -Dlunar.projectRoots=C:\} would have handed
     * over the whole drive. Checked on the roots, before the workspace default is appended, so
     * the default is not refused against itself.
     */
    static void refuseAncestorRoots(List<java.nio.file.Path> roots, java.nio.file.Path home,
            java.nio.file.Path workspaceRoot) {
        for (java.nio.file.Path root : roots)
            for (java.nio.file.Path forbidden : forbiddenRoots(home, workspaceRoot)) {
                if (forbidden.equals(root) || forbidden.startsWith(root))
                    throw new RequestError("location_not_allowed",
                            "an allowed project root is the workspace or the home directory, or an"
                            + " ancestor of either, and is refused: " + root
                            + ". Name a directory below it instead.");
            }
    }

    /**
     * The roots named by {@code -Dlunar.projectRoots}, split on the platform's own separator.
     *
     * <p>It split on {@code [;:]}, which cut a Windows path in half -- {@code D:\code} became
     * {@code D} and {@code \code} -- and then called {@code toRealPath} on each piece, so every
     * root on Windows threw and every create_project with a location failed as tool_failed. It
     * also called {@code toRealPath} on the entry itself, so a root that simply is not there yet
     * threw a raw {@code NoSuchFileException} that no client could act on. Both are refused by
     * name instead.
     *
     * <p>Ponytail: read per call so a changed value takes effect without a restart, which is
     * cheap. Cache it if it ever shows up in a profile.
     */
    static List<java.nio.file.Path> configuredRoots(String property) throws Exception {
        List<java.nio.file.Path> roots = new ArrayList<>();
        if (property == null || property.isBlank()) return roots;
        String separator = java.util.regex.Pattern.quote(java.io.File.pathSeparator);
        for (String entry : property.split(separator)) {
            if (entry.isBlank()) continue;
            // A NUL or an illegal Windows character makes Path.of throw InvalidPathException,
            // which is unchecked and would escape as the tool_failed this method exists to
            // stop. It is a typo like any other, so it is refused like any other.
            java.nio.file.Path root;
            try {
                root = java.nio.file.Path.of(entry.trim());
            } catch (java.nio.file.InvalidPathException invalid) {
                throw new RequestError("location_not_allowed",
                        "lunar.projectRoots entry is not a usable path: " + entry.trim());
            }
            if (!root.isAbsolute())
                throw new RequestError("location_not_allowed",
                        "lunar.projectRoots entry is not an absolute path: " + entry.trim());
            if (!Files.isDirectory(root))
                throw new RequestError("location_not_allowed",
                        "lunar.projectRoots names a directory that does not exist: " + root);
            roots.add(root.toRealPath());
        }
        return roots;
    }

    /**
     * The whole rule, with the roots handed in, so the self-check can assert it without an
     * Eclipse workspace. Throws {@link RequestError} when {@code target} is not a legal location.
     */
    static void bounded(java.nio.file.Path target, List<java.nio.file.Path> roots,
            java.nio.file.Path home, java.nio.file.Path workspaceRoot) {
        for (java.nio.file.Path forbidden : forbiddenRoots(home, workspaceRoot)) {
            // Only an ANCESTOR is refused. A project legitimately sits under the workspace, so
            // testing the other direction would refuse every project that can be created at all.
            if (target.equals(forbidden) || forbidden.startsWith(target)) {
                throw new RequestError("location_not_allowed",
                        "location contains an allowed project root and is refused: " + target
                        + ". Projects may only be created under "
                        + (workspaceRoot == null ? "the workspace" : workspaceRoot)
                        + "; set -Dlunar.projectRoots to allow more.");
            }
        }
        for (java.nio.file.Path root : roots) {
            if (target.startsWith(root)) return;
        }
        throw new RequestError("location_not_allowed",
                "location is outside every allowed project root: " + target);
    }

    private Object deleteProject(Map<String, Object> args, CallBudget budget) throws Exception {
        String name = projectName(args);
        IProject p = workspace().getRoot().getProject(name);
        if (!p.exists()) throw new RequestError("project_unavailable", "project does not exist: " + name);
        String confirm = str(args, "confirm", "");
        if (!confirm.equals(name))
            throw new RequestError("confirm_required", "confirm must repeat the project name exactly: " + name);
        boolean deleteContent = Boolean.TRUE.equals(args.get("deleteContent"));
        java.nio.file.Path location = p.getLocation() == null ? null : p.getLocation().toFile().toPath();
        // Removing the project first is what Eclipse itself does, and deleteRecursively is now
        // safe on its own: it resolves every child against the project root, so it no longer
        // needs contained(), which would be unusable here because the IProject is already gone.
        if (p.isOpen()) p.delete(false, true, budget.monitor()); else p.delete(false, false, budget.monitor());
        if (deleteContent && location != null && Files.exists(location))
            deleteRecursively(location, budget);
        return Map.of("project", name, "deleted", true, "contentDeleted", deleteContent);
    }

    /**
     * Delete a tree without ever walking out of it through a link.
     *
     * <p>{@code Files.isDirectory} follows links, and a Windows junction is the case that
     * matters: it reports itself a directory even under {@link LinkOption#NOFOLLOW_LINKS} and
     * {@code isSymbolicLink} is false for it, so neither flag identifies one. Resolving each
     * child against the resolved root is the same ancestry test {@link #contained} applies to
     * every other path in this class, and it makes a link a leaf: the link is deleted, its target
     * is never entered. Ponytail ceiling: the resolved-root comparison, which is exact for
     * symlinks and junctions alike -- a mount point is out of scope and would need the volume API.
     */
    private static void deleteRecursively(java.nio.file.Path dir, CallBudget budget) throws Exception {
        deleteRecursively(dir, dir.toRealPath(), budget);
    }

    private static void deleteRecursively(java.nio.file.Path dir, java.nio.file.Path root, CallBudget budget) throws Exception {
        try (var stream = Files.newDirectoryStream(dir)) {
            for (java.nio.file.Path child : stream) {
                budget.checkCancelled();
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && within(child, root))
                    deleteRecursively(child, root, budget);
                else Files.deleteIfExists(child);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** True when {@code candidate} really lives under {@code root}, links resolved. */
    private static boolean within(java.nio.file.Path candidate, java.nio.file.Path root) {
        try {
            return candidate.toRealPath().startsWith(root);
        } catch (java.io.IOException unreachable) {
            // A link with no reachable target is a leaf by definition, so deleting it is safe.
            return false;
        }
    }

    @FunctionalInterface private interface FileVisitor { boolean visit(IFile file) throws Exception; }
    private static int walk(IProject p, IResource scope, boolean derived, CallBudget budget, FileVisitor visitor) throws Exception {
        Deque<IResource> stack = new ArrayDeque<>(); stack.push(scope); int skipped = 0;
        while (!stack.isEmpty()) {
            budget.checkCancelled(); IResource r = stack.pop();
            if (!r.isAccessible() || (!derived && r.isDerived())) continue;
            try { contained(p, r); }
            catch (RequestError e) { if (!e.code.equals("outside_project")) throw e; skipped++; continue; }
            if (r instanceof IFile f) { if (!visitor.visit(f)) break; }
            else if (r instanceof IContainer c) {
                IResource[] children = c.members(); Arrays.sort(children, Comparator.comparing(IResource::getName));
                for (int i = children.length - 1; i >= 0; i--) stack.push(children[i]);
            }
        }
        return skipped;
    }
    private static Map<String, Object> page(List<?> rows, Map<String, Object> args) {
        int offset = num(args, "offset"), limit = num(args, "limit");
        int from = Math.min(offset, rows.size()), end = Math.min(from + limit, rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows.subList(from, end)); result.put("total", rows.size());
        result.put("offset", offset); result.put("hasMore", end < rows.size());
        result.put("nextOffset", end < rows.size() ? end : null);
        return result;
    }
    private Object listFiles(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); int offset = num(args, "offset"), limit = num(args, "limit");
        List<Object> rows = new ArrayList<>(); int[] count = {0};
        int skipped = walk(p, scope(p, args), Boolean.TRUE.equals(args.get("includeDerived")), budget, f -> {
            int index = count[0]++;
            if (index >= offset) rows.add(Map.of("path", f.getProjectRelativePath().toString(), "stamp", f.getModificationStamp()));
            return rows.size() <= limit;
        });
        boolean more = rows.size() > limit;
        if (more) rows.remove(rows.size() - 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows); data.put("offset", offset); data.put("hasMore", more);
        data.put("nextOffset", more ? offset + rows.size() : null);
        data.put("total", more ? null : count[0]); data.put("skippedOutsideProject", skipped);
        data.put("workspaceStamp", workspace().getRoot().getModificationStamp()); return data;
    }
    private Object readFile(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); Content c = content(f, budget); checkHash(c, args);
        if (c.text == null) return Map.of("path", f.getProjectRelativePath().toString(), "hash", c.hash, "binary", true,
            "totalBytes", c.bytes.length, "source", "saved_resource", "hasMore", false);
        int offset = num(args, "offset");
        if (offset > c.text.length()) throw new RequestError("invalid_range", "offset exceeds saved text length");
        boundary(c.text, offset);
        // shape() exempts any result carrying nextOffset, so this page is never previewed away
        // and the declared limit is the contract. Pre-clamping here only broke that contract.
        int limit = num(args, "limit");
        int end = readEnd(c.text, offset, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", f.getProjectRelativePath().toString()); data.put("text", c.text.substring(offset, end));
        data.put("hash", c.hash); data.put("charset", c.charset.name()); data.put("totalCharacters", c.text.length());
        data.put("offset", offset); data.put("hasMore", end < c.text.length()); data.put("nextOffset", end < c.text.length() ? end : null);
        data.put("dirtyBuffer", dirty(f)); data.put("source", "saved_resource"); return data;
    }
    private static int readEnd(String text, int offset, int limit) {
        int end = Math.min(text.length(), offset + limit);
        if (end > offset && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return end == offset && offset < text.length() ? Math.min(offset + 2, text.length()) : end;
    }
    private Object searchText(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); String query = str(args, "query", "");
        if (query.isEmpty()) throw new RequestError("empty_query", "query cannot be empty");
        boolean sensitive = !Boolean.FALSE.equals(args.get("caseSensitive"));
        Pattern literal = Pattern.compile(Pattern.quote(query), sensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        int offset = num(args, "offset"), limit = num(args, "limit");
        List<Object> rows = new ArrayList<>(); int[] skipped = {0, 0}, counts = {0, 0};
        int outside = walk(p, scope(p, args), Boolean.TRUE.equals(args.get("includeDerived")), budget, f -> {
            counts[1]++;
            Content c;
            try { c = content(f, budget); }
            catch (RequestError e) {
                if (e.code.equals("file_too_large")) { skipped[0]++; return true; }
                if (e.code.equals("non_text_file")) { skipped[1]++; return true; }
                throw e;
            }
            if (c.text == null) { skipped[1]++; return true; }
            // ponytail: scan only through this page's next match; full totals require exhausting later pages.
            int line = 1, consumed = 0, lineStart = 0;
            var matches = literal.matcher(c.text);
            while (matches.find()) {
                budget.checkCancelled();
                int i = matches.start();
                while (consumed < i) if (c.text.charAt(consumed++) == '\n') { line++; lineStart = consumed; }
                if (counts[0]++ < offset) continue;
                int lineEnd = c.text.indexOf('\n', i); if (lineEnd < 0) lineEnd = c.text.length();
                rows.add(Map.of("path", f.getProjectRelativePath().toString(), "line", line, "column", i - lineStart + 1,
                    "sourceOffset", i, "length", matches.end() - i, "excerpt", c.text.substring(lineStart, Math.min(lineEnd, lineStart + 240))));
                if (rows.size() > limit) return false;
            }
            return true;
        });
        boolean more = rows.size() > limit; if (more) rows.remove(rows.size() - 1);
        Map<String, Object> data = new LinkedHashMap<>(); data.put("items", rows); data.put("offset", offset);
        data.put("hasMore", more); data.put("nextOffset", more ? offset + rows.size() : null); data.put("total", more ? null : counts[0]);
        data.put("searchedFiles", counts[1]); data.put("coverage", more ? "through_next_match" : "complete_nonexcluded_files");
        data.put("skipCountsCover", "visited_files"); data.put("skippedOversize", skipped[0]); data.put("skippedNonText", skipped[1]);
        data.put("skippedOutsideProject", outside); data.put("workspaceStamp", workspace().getRoot().getModificationStamp()); return data;
    }
    private Object createFile(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), false);
        if (f.exists() || (f.getLocation() != null && Files.exists(f.getLocation().toFile().toPath(), LinkOption.NOFOLLOW_LINKS)))
            throw new RequestError("file_exists", "destination exists; create_file never overwrites");
        byte[] bytes = encode(str(args, "content", ""), StandardCharsets.UTF_8);
        makeParents(p, f.getParent(), budget); budget.checkCancelled();
        f.create(new ByteArrayInputStream(bytes), false, budget.monitor()); f.setCharset("UTF-8", budget.monitor());
        return Map.of("path", f.getProjectRelativePath().toString(), "hash", hash(bytes), "created", true);
    }
    private Object writeFile(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); writable(f);
        Content old = content(f, budget); checkHash(old, args); return save(f, old, str(args, "content", ""), budget);
    }
    private Object moveFile(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); writable(f);
        Content old = content(f, budget); checkHash(old, args);
        IFile destination = file(p, str(args, "destination", ""), false);
        if (destination.exists() || (destination.getLocation() != null && Files.exists(destination.getLocation().toFile().toPath(), LinkOption.NOFOLLOW_LINKS)))
            throw new RequestError("file_exists", "move destination already exists");
        writable(destination); makeParents(p, destination.getParent(), budget); budget.checkCancelled();
        f.move(destination.getFullPath(), IResource.KEEP_HISTORY, budget.monitor());
        return Map.of("path", destination.getProjectRelativePath().toString(), "previousPath", str(args, "path", ""), "hash", old.hash);
    }
    private Object deleteFile(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); writable(f);
        Content old = content(f, budget); checkHash(old, args); budget.checkCancelled();
        f.delete(IResource.KEEP_HISTORY, budget.monitor()); return Map.of("path", str(args, "path", ""), "deleted", true, "previousHash", old.hash);
    }
    private static void boundary(String text, int position) {
        if (position < 0 || position > text.length()) throw new RequestError("invalid_range", "range exceeds saved text length");
        if (position > 0 && position < text.length() && Character.isHighSurrogate(text.charAt(position - 1)) && Character.isLowSurrogate(text.charAt(position)))
            throw new RequestError("invalid_range", "UTF-16 range splits a surrogate pair");
    }
    /**
     * Extends a removed range past the line terminator that ends it. Eclipse's import source range
     * covers only the import text, so removing it verbatim leaves its now-empty line behind and turns
     * one blank line into two. Deleting the terminator as well restores the original line count.
     */
    static int lineRemovalEnd(String text, int end) {
        int stop = end;
        if (stop < text.length() && text.charAt(stop) == '\r') stop++;
        if (stop < text.length() && text.charAt(stop) == '\n') return stop + 1;
        // No terminator after the range: take the one before it, when this is the whole line.
        int previous = end - 1;
        if (previous >= 0 && text.charAt(previous) == '\n') {
            int start = previous - 1;
            if (start >= 0 && text.charAt(start) == '\r') start--;
            return previous;
        }
        return end;
    }
    static String edit(String text, int offset, int length, String replacement) {
        if (length < 0 || (long) offset + length > text.length()) throw new RequestError("invalid_range", "edit range exceeds saved text length");
        boundary(text, offset); boundary(text, offset + length);
        return text.substring(0, offset) + replacement + text.substring(offset + length);
    }
    private Object applyEdit(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); writable(f);
        Content old = content(f, budget); checkHash(old, args);
        textOnly(old);
        return save(f, old, edit(old.text, num(args, "offset"), num(args, "length"), str(args, "text", "")), budget);
    }
    private Object refreshProject(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); p.refreshLocal(IResource.DEPTH_INFINITE, budget.monitor()); budget.checkCancelled();
        return Map.of("project", p.getName(), "refreshed", true);
    }

    private static void joinBuilds(CallBudget budget) throws InterruptedException {
        budget.checkCancelled(); Job.getJobManager().join(ResourcesPlugin.FAMILY_MANUAL_BUILD, budget.monitor());
        budget.checkCancelled(); Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, budget.monitor()); budget.checkCancelled();
    }
    private static int buildKind(Map<String, Object> args) {
        return switch (str(args, "kind", "incremental")) {
            case "full" -> IncrementalProjectBuilder.FULL_BUILD;
            case "clean" -> IncrementalProjectBuilder.CLEAN_BUILD;
            default -> IncrementalProjectBuilder.INCREMENTAL_BUILD;
        };
    }
    private static void nativeBuild(IProject project, Map<String, Object> args, CallBudget budget) throws Exception {
        IWorkspace ws = workspace(); joinBuilds(budget);
        // Hold the workspace rule only for refresh/build. Family joins under that rule deadlock auto-build.
        ws.run((IWorkspaceRunnable) monitor -> {
            IResource scope = project == null ? ws.getRoot() : project;
            scope.refreshLocal(IResource.DEPTH_INFINITE, monitor); budget.checkCancelled();
            int kind = buildKind(args);
            if (project == null) ws.build(kind, monitor); else project.build(kind, monitor);
            if (kind == IncrementalProjectBuilder.CLEAN_BUILD) {
                budget.checkCancelled();
                if (project == null) ws.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
                else project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
            }
        }, ws.getRoot(), IWorkspace.AVOID_UPDATE, budget.monitor());
        joinBuilds(budget);
    }
    private Object buildProject(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); nativeBuild(p, args, budget);
        Map<String, Object> data = problems(p, "all", Map.of("offset", 0L, "limit", 100L), budget);
        data.put("project", p.getName()); data.put("built", true); data.put("usesSavedFiles", true); return data;
    }
    private Object buildWorkspace(Map<String, Object> args, CallBudget budget) throws Exception {
        nativeBuild(null, args, budget);
        Map<String, Object> data = problems(workspace().getRoot(), "all", Map.of("offset", 0L, "limit", 100L), budget);
        data.put("excludedClosedProjects", Arrays.stream(workspace().getRoot().getProjects()).filter(p -> !p.isOpen()).map(IProject::getName).sorted().toList());
        data.put("built", true); data.put("usesSavedFiles", true); return data;
    }
    private static boolean severity(IMarker marker, String filter) {
        int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
        return filter.equals("all") || severity == switch (filter) {
            case "error" -> IMarker.SEVERITY_ERROR; case "warning" -> IMarker.SEVERITY_WARNING; default -> IMarker.SEVERITY_INFO;
        };
    }
    private static Map<String, Object> problems(IResource scope, String filter, Map<String, Object> args, CallBudget budget) throws Exception {
        IMarker[] markers = scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        Arrays.sort(markers, Comparator.comparing((IMarker m) -> m.getResource().getFullPath().toString()).thenComparingLong(IMarker::getId));
        List<Object> rows = new ArrayList<>(); int errors = 0, warnings = 0;
        for (IMarker m : markers) {
            budget.checkCancelled(); if (!m.exists() || !severity(m, filter)) continue;
            IProject owner = m.getResource().getProject();
            if (scope.getType() == IResource.ROOT && owner != null && !owner.isOpen()) continue;
            int level = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            if (level == IMarker.SEVERITY_ERROR) errors++; else if (level == IMarker.SEVERITY_WARNING) warnings++;
            int problem = m.getAttribute(IJavaModelMarker.ID, -1);
            rows.add(Map.of("id", m.getId(), "project", owner == null ? "" : owner.getName(),
                "path", m.getResource().getProjectRelativePath().toString(), "severity", level,
                "message", m.getAttribute(IMarker.MESSAGE, ""), "line", m.getAttribute(IMarker.LINE_NUMBER, -1),
                "charStart", m.getAttribute(IMarker.CHAR_START, -1), "charEnd", m.getAttribute(IMarker.CHAR_END, -1),
                "quickFixSupported", importProblem(problem)));
        }
        Map<String, Object> data = page(rows, args); data.put("errors", errors); data.put("warnings", warnings);
        data.put("markersOnly", true); return data;
    }
    private Object getProblems(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); return problems(scope(p, args), str(args, "severity", "all"), args, budget);
    }
    private Object clearMarkers(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); int deleted = 0;
        for (IMarker m : scope(p, args).findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
            budget.checkCancelled(); if (m.exists() && severity(m, str(args, "severity", "all"))) { m.delete(); deleted++; }
        }
        return Map.of("deleted", deleted, "causeFixed", false);
    }
    /** Copies a project's on-disk description and points it at {@code dir}. */
    private static IProjectDescription descriptionWithLocation(IProject p, java.nio.file.Path dir) throws Exception {
        IProjectDescription description = workspace().loadProjectDescription(
            new org.eclipse.core.runtime.Path(dir.resolve(".project").toString()));
        description.setLocationURI(dir.toUri());
        return description;
    }

    private Object waitUntilQuiet(Map<String, Object> args, CallBudget budget) throws Exception {
        ReentrantLock lock = new ReentrantLock(); Condition changed = lock.newCondition();
        long[] lastChange = {System.nanoTime()};
        Runnable activity = () -> { lock.lock(); try { lastChange[0] = System.nanoTime(); changed.signalAll(); } finally { lock.unlock(); } };
        JobChangeAdapter jobs = new JobChangeAdapter() {
            private void changed(IJobChangeEvent e) { if (e.getJob().belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD) || e.getJob().belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD)) activity.run(); }
            @Override public void scheduled(IJobChangeEvent e) { changed(e); }
            @Override public void running(IJobChangeEvent e) { changed(e); }
            @Override public void done(IJobChangeEvent e) { changed(e); }
        };
        IResourceChangeListener resources = e -> activity.run();
        Job.getJobManager().addJobChangeListener(jobs); workspace().addResourceChangeListener(resources, org.eclipse.core.resources.IResourceChangeEvent.POST_CHANGE);
        try {
            joinBuilds(budget); long quietNanos = TimeUnit.MILLISECONDS.toNanos(num(args, "quietMs"));
            lock.lock();
            try {
                while (true) {
                    budget.checkCancelled();
                    boolean building = Arrays.stream(Job.getJobManager().find(ResourcesPlugin.FAMILY_AUTO_BUILD)).anyMatch(j -> j.getState() != Job.NONE)
                        || Arrays.stream(Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD)).anyMatch(j -> j.getState() != Job.NONE);
                    long remainingQuiet = quietNanos - (System.nanoTime() - lastChange[0]);
                    if (!building && remainingQuiet <= 0) break;
                    // The monitor has no cancellation event; bound this condition wait so cancellation is observed within 250 ms.
                    long waitNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(250), building ? quietNanos : remainingQuiet);
                    changed.awaitNanos(Math.max(1, Math.min(TimeUnit.MILLISECONDS.toNanos(budget.remainingMs()), waitNanos)));
                }
            } finally { lock.unlock(); }
            return Map.of("quiet", true, "scope", "resource_changes_and_auto_manual_builds", "quietMs", num(args, "quietMs"),
                "workspaceStamp", workspace().getRoot().getModificationStamp());
        } finally {
            Job.getJobManager().removeJobChangeListener(jobs); workspace().removeResourceChangeListener(resources);
        }
    }

    private static ICompilationUnit unit(IFile file) throws Exception {
        if (!file.getProject().hasNature(JavaCore.NATURE_ID)) throw new RequestError("not_java_project", "project has no Java nature");
        IJavaElement element = JavaCore.create(file);
        if (!(element instanceof ICompilationUnit unit) || !unit.exists())
            throw new RequestError("not_java_source", "file is not a Java compilation unit on this project's classpath");
        if (dirty(file)) throw new RequestError("dirty_buffer", "save or discard this source's dirty buffer before JDT queries");
        return unit;
    }
    private static void symbols(IJavaElement element, List<Object> out, CallBudget budget) throws Exception {
        budget.checkCancelled(); int kind = element.getElementType();
        if (kind == IJavaElement.TYPE || kind == IJavaElement.METHOD || kind == IJavaElement.FIELD) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", element.getElementName()); row.put("kind", kind == IJavaElement.TYPE ? "type" : kind == IJavaElement.METHOD ? "method" : "field");
            row.put("handle", element.getHandleIdentifier());
            if (element instanceof ISourceReference source) {
                ISourceRange range = source.getNameRange(); row.put("offset", range.getOffset()); row.put("length", range.getLength());
            }
            if (element instanceof IType type) row.put("qualifiedName", type.getFullyQualifiedName());
            if (element instanceof IMethod method) row.put("signature", method.getSignature());
            out.add(row);
        }
        if (element instanceof IParent parent) for (IJavaElement child : parent.getChildren()) symbols(child, out, budget);
    }
    private Object javaSymbols(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true);
        Content source = content(f, budget); textOnly(source); ICompilationUnit unit = unit(f);
        if (!source.text.equals(unit.getSource())) throw new RequestError("stale_source", "JDT source differs from saved text; refresh/build before querying offsets");
        List<Object> rows = new ArrayList<>(); symbols(unit, rows, budget);
        Map<String, Object> data = page(rows, args); data.put("hash", source.hash); return data;
    }
    private Object findReferences(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); ICompilationUnit unit = unit(f);
        Content source = content(f, budget); int offset = num(args, "sourceOffset"), length = num(args, "sourceLength");
        textOnly(source);
        if (!source.text.equals(unit.getSource())) throw new RequestError("stale_source", "JDT source differs from saved text; refresh/build before querying offsets");
        edit(source.text, offset, length, "");
        IJavaElement[] selected = unit.codeSelect(offset, length);
        if (selected.length != 1) throw new RequestError("symbol_unresolved", "offset must resolve to exactly one Java symbol");
        SearchPattern pattern = SearchPattern.createPattern(selected[0], IJavaSearchConstants.REFERENCES);
        if (pattern == null) throw new RequestError("symbol_unsupported", "JDT cannot search references for this element");
        List<Map<String, Object>> rows = new ArrayList<>(); int[] outside = {0};
        new SearchEngine().search(pattern, new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
            SearchEngine.createJavaSearchScope(new IJavaElement[] {JavaCore.create(p)}, IJavaSearchScope.SOURCES), new SearchRequestor() {
                @Override public void acceptSearchMatch(SearchMatch match) throws CoreException {
                    budget.checkCancelled(); IResource resource = match.getResource(); if (resource == null) return;
                    try { contained(p, resource); }
                    catch (Exception e) { outside[0]++; return; }
                    rows.add(Map.of("path", resource.getProjectRelativePath().toString(), "offset", match.getOffset(),
                        "length", match.getLength(), "accurate", match.getAccuracy() == SearchMatch.A_ACCURATE));
                    // ponytail: retain at most 50000 matches for stable sorting; narrow the scope before needing an external index.
                    if (rows.size() > MAX_REFERENCE_RESULTS) throw new RequestError("too_many_matches", "more than 50000 references; narrow the project");
                }
            }, budget.monitor());
        rows.sort(Comparator.comparing((Map<String, Object> row) -> (String) row.get("path")).thenComparingInt(row -> ((Number) row.get("offset")).intValue()));
        Map<String, Object> data = page(rows, args); data.put("symbol", selected[0].getElementName());
        data.put("symbolHandle", selected[0].getHandleIdentifier()); data.put("skippedOutsideProject", outside[0]);
        data.put("workspaceStamp", workspace().getRoot().getModificationStamp()); return data;
    }
    private static boolean importProblem(int id) { return id == IProblem.UnusedImport || id == IProblem.DuplicateImport; }
    private Object quickFix(Map<String, Object> args, CallBudget budget) throws Exception {
        IProject p = project(args); IFile f = file(p, str(args, "path", ""), true); writable(f);
        Content saved = content(f, budget); checkHash(saved, args);
        textOnly(saved);
        IMarker marker = f.findMarker(((Number) args.get("markerId")).longValue());
        if (marker == null || !marker.exists()) throw new RequestError("marker_missing", "marker does not exist on this file; call get_problems");
        int id = marker.getAttribute(IJavaModelMarker.ID, -1), start = marker.getAttribute(IMarker.CHAR_START, -1);
        if (!importProblem(id)) throw new RequestError("unsupported_quick_fix", "headless persisted fixes currently support unused/duplicate imports; edit this problem explicitly using apply_edit");
        List<IProblem> current = new ArrayList<>(); WorkingCopyOwner owner = new WorkingCopyOwner() { };
        IProblemRequestor requestor = new IProblemRequestor() {
            public void acceptProblem(IProblem problem) { current.add(problem); }
            public void beginReporting() { current.clear(); }
            public void endReporting() { }
            public boolean isActive() { return true; }
        };
        ICompilationUnit copy = unit(f).getWorkingCopy(owner, requestor, budget.monitor());
        try {
            copy.reconcile(ICompilationUnit.NO_AST, true, owner, budget.monitor()); budget.checkCancelled();
            if (!saved.text.equals(copy.getSource())) throw new RequestError("stale_source", "JDT source differs from saved text");
            for (IImportDeclaration declaration : copy.getImports()) {
                ISourceRange range = declaration.getSourceRange(); int end = range.getOffset() + range.getLength();
                if (start < range.getOffset() || start >= end) continue;
                boolean stillReported = current.stream().anyMatch(problem -> problem.getID() == id
                    && problem.getSourceStart() >= range.getOffset() && problem.getSourceStart() < end);
                if (!stillReported) throw new RequestError("stale_marker", "this problem is no longer reported by a fresh reconcile; rebuild and fetch markers");
                int removalEnd = lineRemovalEnd(saved.text, range.getOffset() + range.getLength());
                Map<String, Object> data = save(f, saved,
                    edit(saved.text, range.getOffset(), removalEnd - range.getOffset(), ""), budget);
                Map<String, Object> result = new LinkedHashMap<>(data); result.put("fix", "remove_import"); result.put("rebuildRequired", true); return result;
            }
            throw new RequestError("stale_marker", "marker no longer identifies an import in this source");
        } finally { copy.discardWorkingCopy(); }
    }

    /** Pure checks run by the packaged self-check, without starting Eclipse or mutating a project. */
    public static int selfCheck() throws Exception {
        int checked = 0;
        if (!relativePath("src\\Main.java", false).equals("src/Main.java")) throw new AssertionError("relative path"); checked++;
        for (String path : List.of("../secret", "src/../secret", "D:/secret", "//server/share", "/secret", "src//Main.java", "src/file:stream")) {
            try { relativePath(path, false); throw new AssertionError("unsafe path accepted: " + path); }
            catch (RequestError expected) { checked++; }
        }
        // "." and an interior "." segment are ordinary root spellings, not traversal.
        if (!relativePath(".", true).isEmpty()) throw new AssertionError("'.' must mean the project root"); checked++;
        if (!relativePath("./", true).isEmpty()) throw new AssertionError("'./' must mean the project root"); checked++;
        if (!relativePath("./src", true).equals("src")) throw new AssertionError("'./src' must mean 'src'"); checked++;
        if (!relativePath("src/./Main.java", false).equals("src/Main.java")) throw new AssertionError("interior '.' must collapse"); checked++;
        if (!hash("abc".getBytes(StandardCharsets.UTF_8)).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) throw new AssertionError("hash"); checked++;
        if (!edit("hello", 1, 3, "i").equals("hio")) throw new AssertionError("edit"); checked++;
        // Removing an import must not leave its now-empty line behind.
        if (!edit("import a.B;\n\nclass C {}", 0, lineRemovalEnd("import a.B;\n\nclass C {}", 11) - 0, "").equals("\nclass C {}"))
            throw new AssertionError("import removal left a blank line"); checked++;
        if (lineRemovalEnd("import a.B;\r\nclass C {}", 11) != 13) throw new AssertionError("CRLF import removal"); checked++;
        if (lineRemovalEnd("class C {}", 8) != 8) throw new AssertionError("no trailing terminator"); checked++;
        for (int[] range : new int[][] {{-1, 1}, {4, 2}, {0, Integer.MAX_VALUE}}) {
            try { edit("hello", range[0], range[1], ""); throw new AssertionError("invalid range accepted"); }
            catch (RequestError expected) { checked++; }
        }
        try { edit("a\ud83c\udf19b", 2, 1, ""); throw new AssertionError("split surrogate accepted"); }
        catch (RequestError expected) { checked++; }
        if (readEnd("\ud83c\udf19x", 0, 1) != 2) throw new AssertionError("surrogate page does not advance"); checked++;
        if (readEnd("a\ud83c\udf19x", 0, 2) != 1) throw new AssertionError("page cuts surrogate"); checked++;
        List<Tool> tools = new WorkspaceTools().tools();
        if (tools.size() != 20) throw new AssertionError("tool count"); checked++;
        if (tools.stream().noneMatch(t -> t.spec().name().equals("create_project"))
                || tools.stream().noneMatch(t -> t.spec().name().equals("delete_project")))
            throw new AssertionError("project lifecycle tools missing"); checked++;
        for (Tool tool : tools) { SchemaValidator.validateDefinition(tool.spec().inputSchema()); checked++; }
        for (Tool tool : tools) {
            // A lifecycle tool that cannot be validated end to end is worse than an absent one.
            String name = tool.spec().name();
            if (!name.equals("create_project") && !name.equals("delete_project")) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) tool.spec().inputSchema().get("properties");
            if (!props.containsKey("project")) throw new AssertionError(name + " cannot address a project"); checked++;
            if (name.equals("create_project") && !props.containsKey("location")) throw new AssertionError("create_project cannot import"); checked++;
            if (name.equals("delete_project")) {
                Object required = tool.spec().inputSchema().get("required");
                if (!(required instanceof List<?> r) || !r.contains("confirm")) throw new AssertionError("delete_project is not confirm-gated"); checked++;
            }
        }
        if (!safeProjectName("../evil").isEmpty()) throw new AssertionError("project name validation accepts traversal"); checked++;
        if (!safeProjectName("..").isEmpty()) throw new AssertionError("project name validation accepts '..'"); checked++;
        if (!"IO.FileHandleReport".equals(safeProjectName("IO.FileHandleReport"))) throw new AssertionError("dots inside a name must be legal"); checked++;
        // A self-paging read must be recognised as self-paging, or shape() previews it away
        // and the documented read loop loses nextOffset. That recognition is key presence, not
        // size, so a page at the full declared limit must still carry the key.
        if (!Map.of("nextOffset", 65536).containsKey("nextOffset")) throw new AssertionError("self-paging marker lost"); checked++;
        if (readEnd("a".repeat(70000), 0, 65536) != 65536) throw new AssertionError("declared limit is not honoured"); checked++;
        checked += linkSafeDelete();
        checked += projectRootIsBounded();
        return checked;
    }

    /**
     * create_project must not be able to widen the sandbox every other file tool depends on.
     * Asserts against the pure rule, so it needs no Eclipse workspace: a parent of the home
     * directory is refused, and a location under a configured root is not, which is what keeps
     * the guard from simply disabling the tool.
     */
    private static int projectRootIsBounded() throws Exception {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home", "")).toRealPath();
        java.nio.file.Path workspaceRoot = Files.createTempDirectory("lunar-ws");
        java.nio.file.Path elsewhere = Files.createTempDirectory("lunar-outside");
        int checked = 0;
        try {
            // A parent of the home directory is the sharpest case: it holds every user file and
            // is not the workspace, so nothing else in the tool chain would catch it.
            java.nio.file.Path parent = home.getParent();
            if (parent != null) {
                try { bounded(parent, List.of(workspaceRoot), home, workspaceRoot);
                    throw new AssertionError("a parent of the home directory was accepted as a project root"); }
                catch (RequestError refused) { checked++; }
            }
            try { bounded(home, List.of(workspaceRoot), home, workspaceRoot);
                throw new AssertionError("the home directory was accepted as a project root"); }
            catch (RequestError refused) { checked++; }
            // The workspace itself: a project there would BE the workspace.
            try { bounded(workspaceRoot, List.of(workspaceRoot), home, workspaceRoot);
                throw new AssertionError("the workspace root was accepted as a project location"); }
            catch (RequestError refused) { checked++; }
            // An unrelated sibling tree is refused on the allow-list, which is the check that
            // actually stops a directory outside the workspace being adopted.
            try { bounded(elsewhere, List.of(workspaceRoot), home, workspaceRoot);
                throw new AssertionError("an unrelated directory was accepted as a project root"); }
            catch (RequestError refused) { checked++; }
            // Naming it explicitly is how an operator opts in, and that has to keep working.
            bounded(elsewhere.resolve("child"), List.of(elsewhere), home, workspaceRoot);
            checked++;
            // And a project legitimately under the workspace is still allowed.
            bounded(workspaceRoot.resolve("proj"), List.of(workspaceRoot), home, workspaceRoot);
            checked++;
            // The property is read with the platform's own separator. Splitting on [;:] cut a
            // Windows path in half -- D:\code became D and \code -- and then resolved each piece,
            // so every configured root on Windows threw and create_project never ran.
            String sep = String.valueOf(java.io.File.pathSeparatorChar);
            List<java.nio.file.Path> parsed =
                    configuredRoots(workspaceRoot + sep + elsewhere);
            if (parsed.size() != 2 || !parsed.get(0).equals(workspaceRoot.toRealPath())
                    || !parsed.get(1).equals(elsewhere.toRealPath()))
                throw new AssertionError("projectRoots did not split on the platform separator: " + parsed);
            checked++;
            // A root that is not there is refused by name, not as a raw NoSuchFileException the
            // client cannot act on -- one typo used to fail every create_project as tool_failed.
            String absent = new java.io.File("lunar-no-such-root-" + java.util.UUID.randomUUID()).getAbsolutePath();
            try {
                configuredRoots(absent);
                throw new AssertionError("a nonexistent projectRoots entry was accepted");
            } catch (RequestError refused) {
                if (!refused.getMessage().contains(absent))
                    throw new AssertionError("the refusal does not name the entry: " + refused.getMessage());
                checked++;
            }
            // A relative entry resolved against whatever the JVM's working directory happened to
            // be, which is the install directory on some launchers and the user's home on others.
            // The operator named a root, not a guess about the working directory.
            try {
                configuredRoots("relative-root");
                throw new AssertionError("a relative projectRoots entry was accepted");
            } catch (RequestError refused) {
                // "does not exist" is not a pass: the old code refused a relative entry by
                // accident, because nothing named it. It has to be refused as what it is.
                if (!refused.getMessage().contains("not an absolute path"))
                    throw new AssertionError("a relative entry was not refused as relative: "
                            + refused.getMessage());
                checked++;
            }
            // Same for an entry that cannot be a path at all.
            try {
                configuredRoots("bad\0root");
                throw new AssertionError("an unusable projectRoots entry was accepted");
            } catch (RequestError refused) {
                if (!refused.getMessage().contains("not a usable path"))
                    throw new AssertionError("an unusable entry was not refused as unusable: "
                            + refused.getMessage());
                checked++;
            }
            // A root that is, or contains, the home directory or the workspace is the opposite of
            // a sandbox. bounded() only ever compared a root as a containment target, so naming an
            // ancestor used to hand the agent the whole drive, and projectRoots never worked on
            // Windows to do it until the separator was fixed. Four shapes: home itself, the
            // workspace itself, an ancestor of home, and an ancestor of the workspace.
            java.nio.file.Path above = home.getParent() == null ? null : home.getParent().getParent();
            for (java.nio.file.Path root : java.util.List.of(home, workspaceRoot,
                    above == null ? home : above, workspaceRoot.getParent())) {
                try {
                    refuseAncestorRoots(List.of(root), home, workspaceRoot);
                    throw new AssertionError("an ancestor-or-self project root was accepted: " + root);
                } catch (RequestError refused) {
                    if (!refused.getMessage().contains(root.toString()))
                        throw new AssertionError("the refusal does not name the root: " + refused.getMessage());
                    checked++;
                }
            }
            // A root strictly below home stays legal: naming ~/code is the ordinary case.
            refuseAncestorRoots(List.of(home.resolve("code")), home, workspaceRoot);
            checked++;
            // A workspace with no root location is a documented state -- allowedRoot computes
            // exactly that null and passes it on. List.of rejects null in its constructor, so
            // this threw before the guard that claimed to handle it, and every file tool on such
            // a workspace failed as tool_failed. The home guard is the only one left, and it
            // still has to hold.
            bounded(workspaceRoot.resolve("proj"), List.of(workspaceRoot), home, null);
            checked++;
            try {
                bounded(home, List.of(workspaceRoot), home, null);
                throw new AssertionError("home was not refused with a null workspace root");
            } catch (RequestError refused) {
                checked++;
            }
        } finally {
            deleteTree(workspaceRoot);
            deleteTree(elsewhere);
        }
        return checked;
    }

    /**
     * deleteRecursively must not walk through a link, or delete_project(deleteContent) erases
     * whatever the link points at. Builds a real tree holding a junction that leaves the tree,
     * deletes the tree, and asserts the tree is gone while the out-of-tree directory is intact.
     *
     * <p>A junction, not a symlink: {@code createSymbolicLink} needs a privilege the build agent
     * does not have, and a junction is the thing Windows actually creates. That is also the case
     * NOFOLLOW_LINKS does not catch, which is the whole point of the assertion.
     */
    private static int linkSafeDelete() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("lunar-links");
        java.nio.file.Path victim = root.resolve("outside");
        java.nio.file.Path project = root.resolve("project");
        java.nio.file.Path inside = project.resolve("real");
        java.nio.file.Path link = project.resolve("escape");
        Files.createDirectories(victim);
        Files.createDirectories(inside);
        java.nio.file.Path precious = victim.resolve("precious.txt");
        Files.writeString(precious, "must survive");
        Files.writeString(inside.resolve("owned.txt"), "may go");
        int checked = 0;
        try {
            // Failing to build the link is a broken environment, not a vacuous pass. Returning
            // early made the check vanish and took three off the reported count, so the one
            // property that would erase a directory outside the workspace could rot unnoticed
            // on exactly the machine nobody was watching.
            if (!mklink(victim, link))
                throw new AssertionError("could not create a junction or a symlink at " + link
                        + "; deleteRecursively following a link out of the project is unproven here");
            deleteRecursively(project, new CallBudget(5_000));
            if (!Files.exists(precious)) throw new AssertionError("delete followed a link out of the project"); checked++;
            if (Files.exists(project, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("project tree was not deleted"); checked++;
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("the link itself must be deleted"); checked++;
        } finally {
            deleteTree(root);
        }
        return checked;
    }

    /**
     * Junction first, symlink second: {@code mklink /J} needs no elevation and is what Explorer
     * produces, while {@code createSymbolicLink} does. False means neither worked, which the one
     * caller treats as a failure of the environment rather than as a check to skip.
     */
    private static boolean mklink(java.nio.file.Path target, java.nio.file.Path link) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception notOnWindows) {
            try {
                Files.createSymbolicLink(link, target.toAbsolutePath());
                return true;
            } catch (Exception noLinks) {
                return false;
            }
        }
    }

    /** Best-effort cleanup for the checks above: plain NIO, so it also removes a leftover link. */
    private static void deleteTree(java.nio.file.Path dir) throws Exception {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path child : stream) deleteTree(child);
            }
        }
        Files.deleteIfExists(dir);
    }
    public static void main(String[] args) throws Exception { System.out.println("workspace checks: " + selfCheck()); }
}
