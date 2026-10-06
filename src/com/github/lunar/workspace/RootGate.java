package com.github.lunar.workspace;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.RequestError;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Where a project may live on disk, the one place that decides it, and the delete walk that is
 * only ever allowed inside a location this gate already approved.
 *
 * <p>Creation and deletion consult the same gate: {@code createProject} refuses a location before
 * it makes anything, and {@code deleteProject} refuses to destroy a tree it would not have
 * permitted to exist, so the two agree because they resolve the same roots. The read-side
 * containment check stays in {@code WorkspaceTools} -- it answers a different question, about one
 * resource inside a project rather than about a project's location.
 *
 * <p>The self-checks that assert this gate are co-located with it, which is what lets every rule
 * below stay private except the entry points callers in {@code WorkspaceTools} use.
 */
final class RootGate {
    private RootGate() { }

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
        var root = ResourcesPlugin.getWorkspace().getRoot();
        java.nio.file.Path workspaceRoot = root.getLocation() == null ? null
                : root.getLocation().toFile().toPath().toRealPath();
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
    private static void refuseAncestorRoots(List<java.nio.file.Path> roots, java.nio.file.Path home,
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
    private static List<java.nio.file.Path> configuredRoots(String property) throws Exception {
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
    private static void bounded(java.nio.file.Path target, List<java.nio.file.Path> roots,
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

    /**
     * Delete a tree without ever walking out of it through a link.
     *
     * <p>{@code Files.isDirectory} follows links, and a Windows junction is the case that
     * matters: it reports itself a directory even under {@link LinkOption#NOFOLLOW_LINKS} and
     * {@code isSymbolicLink} is false for it, so neither flag identifies one. Resolving each
     * child against the resolved root is the same ancestry test the file tools apply to every
     * other path, and it makes a link a leaf: the link is deleted, its target is never entered.
     * Ponytail ceiling: the resolved-root comparison, which is exact for symlinks and junctions
     * alike -- a mount point is out of scope and would need the volume API.
     *
     * <p>The root is the one link that mechanism cannot see, because the root is never walked.
     * It used to be resolved and adopted as its own boundary, which made every child satisfy
     * {@link #within} and deleted the target's entire tree. It is now refused outright.
     */
    static void deleteRecursively(java.nio.file.Path dir, CallBudget budget) throws Exception {
        // Asked of the filesystem rather than inferred by comparing paths: toRealPath canonicalises
        // the user profile to its 8.3 short form, so real.equals(normalized) is false for an
        // ordinary directory and the first version of this guard refused every legitimate delete.
        var attrs = Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attrs.isOther() || attrs.isSymbolicLink())
            throw new RequestError("location_not_allowed", "refusing to delete through a link: " + dir);
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

    /**
     * create_project must not be able to widen the sandbox every other file tool depends on.
     * Asserts against the pure rule, so it needs no Eclipse workspace: a parent of the home
     * directory is refused, and a location under a configured root is not, which is what keeps
     * the guard from simply disabling the tool.
     */
    static int projectRootIsBounded() throws Exception {
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
            // A root that is, or contains, the home directory or the workspace is the opposite
            // of a sandbox. bounded() only ever compared a root as a containment target, so
            // naming an ancestor used to hand the agent the whole drive, and projectRoots never
            // worked on Windows to do it until the separator was fixed. Four shapes: home itself,
            // the workspace itself, an ancestor of home, and an ancestor of the workspace.
            // Built by hand rather than with List.of for the same reason forbiddenRoots is:
            // a drive root has no parent, and List.of would throw before the assertion ran.
            List<java.nio.file.Path> tooBroad = new ArrayList<>();
            tooBroad.add(home);
            tooBroad.add(workspaceRoot);
            java.nio.file.Path homeParent = home.getParent();
            tooBroad.add(homeParent == null || homeParent.getParent() == null
                    ? homeParent == null ? home : homeParent : homeParent.getParent());
            java.nio.file.Path workspaceParent = workspaceRoot.getParent();
            tooBroad.add(workspaceParent == null ? workspaceRoot : workspaceParent);
            for (java.nio.file.Path root : tooBroad) {
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
    static int linkSafeDelete() throws Exception {
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
            // The one link the walk above cannot see is the root itself. Resolving it and adopting
            // the result as the boundary makes every child satisfy `within`, so the target's entire
            // tree is deleted. Make a second junction BE the project location and refuse instead.
            java.nio.file.Path alias = root.resolve("alias");
            if (!mklink(victim, alias))
                throw new AssertionError("could not create a second junction at " + alias
                        + "; deleteRecursively adopting a linked root as its own boundary is unproven here");
            boolean refused = false;
            try {
                deleteRecursively(alias, new CallBudget(5_000));
            } catch (RequestError refusedLocation) {
                refused = true;
            }
            if (!refused) throw new AssertionError("deleteRecursively followed a link that was the root"); checked++;
            if (!Files.exists(precious)) throw new AssertionError("deleting a linked root erased its target"); checked++;
            if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("a refused root must be left in place"); checked++;
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

    /** Best-effort cleanup for the checks above: removes a leftover link without entering it. */
    private static void deleteTree(java.nio.file.Path dir) throws Exception {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        // isDirectory(NOFOLLOW_LINKS) is true for a junction, so the old test recursed straight
        // through one and deleted the very target a check had just proved was safe -- and the
        // comment claimed the opposite. A junction reports isOther(), a symlink isSymbolicLink();
        // both are leaves here, exactly as in deleteRecursively.
        var attrs = Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attrs.isDirectory() && !attrs.isOther() && !attrs.isSymbolicLink()) {
            try (var stream = Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path child : stream) deleteTree(child);
            }
        }
        Files.deleteIfExists(dir);
    }
}
