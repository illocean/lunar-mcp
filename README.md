# lunar

Eclipse IDE MCP server: let AI coding agents build, run, debug and test Java projects inside a live Eclipse workspace.

lunar speaks the Model Context Protocol over one authenticated HTTP endpoint inside Eclipse. It gives an agent the IDE's own JDT compiler, problem markers, launch configurations, debugger, JUnit runner and workspace model, so the agent reads the same feedback a developer reads in Eclipse instead of guessing from files and a shell.

## What it is

Coding agents normally work on files and a shell. They rewrite text with no way to know whether it compiles, what JDT thinks of a type, which launch configuration exists, or what the stack looks like two frames into a failure. lunar runs inside Eclipse as five OSGi bundles and exposes the IDE's own state over MCP: real JDT symbols and reference results, the real incremental build with its real compiler markers, real launch configurations, real breakpoints and stack frames, and real JUnit counts from the native JUnit runner. It registers with the running workspace instead of reimplementing it, and adds no third-party runtime dependency.

## Works with

Codex, OpenCode, Claude Code, and any other MCP client, as long as it speaks streamable HTTP and can send a custom `Authorization` header. lunar is not a stdio server: there is no command to launch, only a URL to connect to.

## Features

- **Workspace and files**: `list_projects`, `create_project`, `delete_project`, `project_info`, `list_files`, `read_file`, `create_file`, `write_file`, `move_file`, `delete_file`, `apply_edit`, `refresh_project`
- **Code search and JDT navigation**: `search_text`, `get_java_symbols`, `find_references`
- **Build and problems**: `build_project`, `build_workspace`, `get_problems`, `clear_markers`, `wait_until_quiet`, `apply_quick_fix`
- **Run, console and logs**: `list_launch_configs`, `list_launches`, `create_launch_config`, `delete_launch_config`, `launch`, `terminate`, `get_console_output`, `get_log_entries`
- **JUnit**: `run_tests`
- **Debugging**: `list_debug_targets`, `list_breakpoints`, `set_breakpoint`, `remove_breakpoint`, `get_frames`, `get_variables`, `continue_execution`, `step_into`, `step_over`, `step_return`
- **Discovery, batching and paging**: `find_tools`, `load_toolset`, `get_session_info`, `resume_result`, `capture_baseline`, `get_delta`, `batch`

47 tools in total. A newly initialized session sees 18 of them; `load_toolset` with `workspace`, `run`, `debug` or `all` exposes the remaining 29 in that session. Every published schema carries an `x-lunar-risk-tier` of `read`, `build`, `mutate` or `destructive`, so you can allow-list tools per client. Oversized results are truncated with a session cursor that `resume_result` pages.

## Requirements

- **Windows.** The build pins `org.eclipse.swt.win32.win32.x86_64`, and the scripts are PowerShell.
- **JDK 21** on `PATH`. Every bundle declares `Bundle-RequiredExecutionEnvironment: JavaSE-21`.
- **Eclipse 2025-11 (4.38).** The bundles require `org.eclipse.core.runtime` 3.34+, `org.eclipse.core.resources` 3.23+, `org.eclipse.jdt.core` 3.44+, `org.eclipse.jdt.debug` 3.25+, `org.eclipse.debug.core` 3.23+, `org.eclipse.jdt.launching` 3.24+ and `org.eclipse.jdt.junit.core` 3.14+.
- **A bearer token**, in `ECLIPSE_MCP_TOKEN` or as the `token` key in `%USERPROFILE%\.lunar\config.json`, present before Eclipse starts. The server refuses to bind when neither carries one, and requires the token on every request including from `127.0.0.1`. A request without it gets HTTP 401.

## Install and setup

### The quick path

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 setup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Install
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 connect
```

Then start Eclipse with `-clean -consoleLog` and check with `.\lunar.ps1 status`, which reports the resolved settings, whether the token variable and the config file agree, where lunar is installed, and whether the port is answering.

`setup` writes `%USERPROFILE%\.lunar\config.json`, generates a token if you do not already have one, and copies it to the user-scope `ECLIPSE_MCP_TOKEN` so clients can expand it. `connect` registers the endpoint with your client. Both print the commands they ran, so nothing they do is invisible — and [Doing it by hand](#doing-it-by-hand) below is the same thing written out one step at a time, for when you would rather not run a script at all.

### Doing it by hand

Every step below is the manual equivalent of what the scripts do. None of it requires the scripts.

1. Set the token in the environment Eclipse will inherit, then restart any running Eclipse:

   ```powershell
   [Environment]::SetEnvironmentVariable('ECLIPSE_MCP_TOKEN', '<a long random string>', 'User')
   ```

   A token is any unguessable string. One way to make one without typing it:

   ```powershell
   $bytes = New-Object byte[] 32
   [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   $token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
   [Environment]::SetEnvironmentVariable('ECLIPSE_MCP_TOKEN', $token, 'User')
   ```

   Open a new terminal afterwards. A terminal that was already running keeps the old environment, and a client started from it sends an unexpanded `${ECLIPSE_MCP_TOKEN}` that lunar rejects with 401.

   To keep the token in a file instead, create the directory and the file by hand — the server reads `%USERPROFILE%\.lunar\config.json`, and the environment still wins if both are set:

   ```powershell
   New-Item -ItemType Directory -Path "$env:USERPROFILE\.lunar" -Force | Out-Null
   ```

   ```json
   {
     "token": "<a long random string>",
     "host": "127.0.0.1",
     "port": 8124
   }
   ```

2. Build. From the repository root, with JDK 21 on `PATH`:

   ```powershell
   build.cmd
   ```

   `build.cmd` delegates to `build.ps1`, which locates your Eclipse and its p2 pool (see [Finding your Eclipse](#finding-your-eclipse)), compiles every `.java` file under `src` with `javac --release 21`, packages five jars into a timestamped directory under `out`, then runs four check classes (`SelfCheck`, `ProtocolCheck`, `FrameworkCheck`, `IntegrationCheck`) and aborts if any fails. It records jar paths, sizes and SHA-256 hashes in `build-state.json`. Maven, Gradle, Ant and Tycho are not used.

   The check script has no Eclipse dependency of its own and is worth running if you change `lunar-env.ps1`. It also asserts that the minimum versions in `build.ps1` still match the ones the bundle manifests declare, so the two cannot drift apart:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\check-env.ps1
   ```

3. Install. Stop Eclipse first, then:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Install
   ```

   The script refuses to run while an Eclipse process from that installation is alive. It copies the five lunar jars currently in `dropins` to `backup\installed-<timestamp>`, then copies the five newly built jars into `<eclipse>\dropins`.

   To install as a p2 update site instead, see [Installing from the update site](#installing-from-the-update-site). The two are mutually exclusive.

4. Start Eclipse with `-clean -consoleLog`. The server binds as an OSGi Declarative Services immediate component when its bundle resolves, so there is no preference page to open. `-consoleLog` is what makes a startup failure visible in the console as well as the Error Log.

5. Confirm the server is up. Three checks should agree: the endpoint descriptor exists, port 8124 is listening, and an authenticated request answers.

   ```text
   <workspace>\.metadata\plugins\com.github.lunar\server\endpoint.json
   ```

   The descriptor records the URL, protocol version, process id and start time, and carries no token. A descriptor left by an abnormal exit does not prove the server is live, which is why the other two checks matter:

   ```powershell
   Test-Path '<workspace>\.metadata\plugins\com.github.lunar\server\endpoint.json'
   Get-NetTCPConnection -LocalPort 8124 -State Listen -ErrorAction SilentlyContinue
   ```

   Startup errors land in `<workspace>\.metadata\.log`.

### Finding your Eclipse

The build finds Eclipse itself. Nothing has to be edited.

| What | How it is found | Override |
| --- | --- | --- |
| Eclipse installation | `eclipse.exe` on `PATH`, when exactly one is there | `-EclipseHome <dir>`, or `LUNAR_ECLIPSE_HOME` |
| p2 pool | `configuration/config.ini`'s `osgi.framework` entry, then `<eclipse home>/plugins`, then a sibling `.p2\pool\plugins` | `-PoolDir <dir>`, or `LUNAR_POOL_DIR` |
| dropins folder | `<eclipse home>\dropins`, created if absent | derived from `-EclipseHome` |

If Eclipse is not on `PATH` — the usual case, because Eclipse does not install itself there — the build stops and tells you to set one variable once:

```powershell
[Environment]::SetEnvironmentVariable('LUNAR_ECLIPSE_HOME', 'C:\path\to\eclipse', 'User')
```

After that `build.cmd` works with no arguments. The pool is derived from that installation, so `LUNAR_POOL_DIR` is only needed for an unusual or relocated pool.

Two Eclipse installations on `PATH` is an error rather than a choice: lunar refuses rather than risk installing into the one you did not mean.

The classpath is resolved by bundle id and minimum version, not by exact file name, so a pool from any recent Eclipse release works. A bundle that is absent, or older than its minimum, is reported by name with the version found:

```text
Missing or too-old bundles in C:\path\to\.p2\pool\plugins:
  org.eclipse.jdt.core (found 3.30.0, need >= 3.44.0)
```

To bypass discovery entirely, pass both explicitly:

```powershell
.\build.ps1 -EclipseHome 'C:\path\to\eclipse' -PoolDir 'C:\path\to\.p2\pool\plugins'
```

### Configuration

Three settings: a token, a host and a port. Each has an environment variable and a key in `%USERPROFILE%\.lunar\config.json`, read in that order — **the environment wins**, because it is per-process and lets two Eclipse installations on one machine differ without either editing a shared file. A variable set to a blank value counts as unset and falls through to the file, so a variable cleared in one shell does not silently shadow it.

| Variable | Config key | Default | Notes |
| --- | --- | --- | --- |
| `ECLIPSE_MCP_TOKEN` | `token` | none, required | Bearer token for every request. A blank value makes the server refuse to bind. |
| `LUNAR_MCP_HOST` | `host` | `127.0.0.1` | Must resolve to a loopback address. A routable address is refused, because lunar drives a local IDE workspace. |
| `LUNAR_MCP_PORT` | `port` | `8124` | Anything that is not a port in 1-65535 is ignored with a warning in the Error Log, and the default is used. A port may be written as a number or as a quoted string in the file. |

`lunar.ps1 setup` keeps the file and the variable in step, and `lunar.ps1 status` tells you when they have drifted apart. Nothing else rewrites either one; the server only ever reads the file.

The server resolves all three once at startup, so a change has no effect until Eclipse restarts. If the port is already taken, the server fails loudly and prints both the port and the variable to change; it does not silently pick a different one.

A config file that cannot be parsed — or that is valid JSON but not an object, such as a bare array — is reported in the Error Log and the defaults are used, so a typo is never the reason the endpoint is missing. A missing file is normal for anyone who set the variables by hand.

## Connect your client

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 connect
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 connect -Client codex
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 connect -Global
```

`connect` uses the client's own `add` command when it has one, so you end up with an entry the client wrote itself rather than a file lunar edited — which matters, because a JSON file lunar merges into by hand is a JSON file lunar can corrupt. It falls back to printing the snippet when there is no command to run, and the sections below are those snippets. Nothing here needs the script.

The endpoint is `http://127.0.0.1:8124/mcp`, the advertised MCP protocol version is `2025-06-18`, and the client must send `Authorization: Bearer <value of ECLIPSE_MCP_TOKEN>` on every request.

Keep the token in the environment rather than pasting it into a client config, so the value never lands in a file. Every snippet below references it as a variable.

### Codex

Add to `~/.codex/config.toml`, or to `.codex/config.toml` in a project:

```toml
[mcp_servers.lunar]
url = "http://127.0.0.1:8124/mcp"
bearer_token_env_var = "ECLIPSE_MCP_TOKEN"
```

`bearer_token_env_var` sends that variable in `Authorization`. Source: <https://learn.chatgpt.com/docs/extend/mcp>

### OpenCode

Add to `opencode.json` in your project:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "lunar": {
        "type": "remote",
        "url": "http://127.0.0.1:8124/mcp",
        "oauth": false,
        "headers": {
          "Authorization": "Bearer {env:ECLIPSE_MCP_TOKEN}"
        }
      }
    }
  }
}
```

`{env:NAME}` is expanded by OpenCode, so the token stays in the environment. `oauth: false` stops OpenCode from starting an OAuth flow against a 401, because lunar uses a static bearer token.

OpenCode V2 nests servers under `mcp.servers`. V1 placed them directly under `mcp`; if your client is on V1, move the `lunar` object up one level.

`lunar.ps1 connect` runs exactly that command. Run it by hand from the project you want lunar in:

```bash
opencode mcp add lunar --url http://127.0.0.1:8124/mcp --header "Authorization=Bearer {env:ECLIPSE_MCP_TOKEN}"
```

Add `--global` to write to `~/.config/opencode/opencode.json` instead; omit it to keep lunar project-scoped, which is the default because a shared repository should not decide what its contributors' agents can reach.

Project config is loaded by traversing up from the working directory to the nearest Git directory, and it overrides the global config for keys they both set.
Source: <https://opencode.ai/v2/docs/mcp-servers/> and <https://opencode.ai/docs/config/>

### Claude Code

From the command line:

```bash
claude mcp add --transport http lunar http://127.0.0.1:8124/mcp --header "Authorization: Bearer $ECLIPSE_MCP_TOKEN" --scope user
```

That writes the resolved token into your Claude Code configuration. To keep the token in the environment, use the project file form instead. Put this in `.mcp.json`:

```json
{
  "mcpServers": {
    "lunar": {
      "type": "http",
      "url": "http://127.0.0.1:8124/mcp",
      "headers": {
        "Authorization": "Bearer ${ECLIPSE_MCP_TOKEN}"
      }
    }
  }
}
```

`type` is required; Claude Code reads an entry without it as a stdio server. `${VAR}` is expanded in `url` and `headers`.
Source: <https://code.claude.com/docs/en/mcp>

### Any other MCP client

Point it at the URL, declare the transport as HTTP, and add the `Authorization` header. Nothing else is needed: no launch command, no working directory, no environment of its own.

## Verify it works

Ask your agent: "List the projects in the Eclipse workspace." Expect a `list_projects` call whose data field carries one entry per workspace project with its name, its workspace path, whether it is open and how many natures it has.

You can check the endpoint yourself, without an agent:

```powershell
curl.exe -s -X POST http://127.0.0.1:8124/mcp -H "Authorization: Bearer $env:ECLIPSE_MCP_TOKEN" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Expect HTTP 200 and a JSON-RPC result listing 18 tools. Drop the `Authorization` header and you get 401, which confirms the token is enforced.

## Tool reference

`read`, `build`, `mutate` and `destructive` are the `x-lunar-risk-tier` values published in each schema. This server does not enforce them; your client's allow list does.

### Workspace, files and JDT navigation

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `list_projects` | List every workspace project with its path, open state and nature count | none |
| `create_project` | Create a project Eclipse knows about, optionally importing from a location | `project` |
| `delete_project` | Close a project and delete it and its files from disk | `project`, `confirm` |
| `project_info` | Read natures, builders, auto-build state and JDT configuration | `project` |
| `list_files` | Page project files in sorted path order, derived files excluded by default | `project` |
| `read_file` | Read saved text, or binary hash metadata; UTF-16 offsets, 2 MiB cap | `project`, `path` |
| `create_file` | Create a UTF-8 file and missing folders; never overwrites | `project`, `path`, `content` |
| `write_file` | Replace saved text, preserving charset and local history | `project`, `path`, `content`, `expectedHash` |
| `move_file` | Move a project file without overwriting the destination | `project`, `path`, `destination`, `expectedHash` |
| `delete_file` | Delete one project file, retaining local history | `project`, `path`, `expectedHash` |
| `apply_edit` | Replace a UTF-16 character range in saved text | `project`, `path`, `expectedHash`, `text` |
| `refresh_project` | Refresh the project's Eclipse resource model from disk | `project` |
| `search_text` | Literal text search over saved project files, with explicit skipped-file counts | `project`, `query` |
| `get_java_symbols` | Page JDT types, fields and methods in one compilation unit | `project`, `path` |
| `find_references` | Resolve the symbol at a UTF-16 offset and page sorted JDT references | `project`, `path` |

### Build and problems

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `build_project` | Refresh, build synchronously, join native builds, then read markers | `project` |
| `build_workspace` | Refresh and build every open project and read the real markers | none |
| `get_problems` | Page existing Eclipse problem markers; triggers no build | `project` |
| `clear_markers` | Delete matching problem markers under a project path | `project` |
| `wait_until_quiet` | Wait for auto and manual builds and a resource-change quiet window | none |
| `apply_quick_fix` | Persist a current unused or duplicate import correction | `project`, `path`, `markerId`, `expectedHash` |

### Run, console, logs and tests

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `list_launch_configs` | List launch configuration ids, types and modes | none |
| `list_launches` | List running and retained launches with process and target state | none |
| `create_launch_config` | Create a Java application or JUnit launch configuration | `name`, `project`, `mainType` |
| `delete_launch_config` | Delete a launch configuration when `confirm` is the exact name | `configuration`, `confirm` |
| `launch` | Build, then launch an existing configuration in run or debug mode | `configuration` |
| `terminate` | Terminate a launch and its debug targets | `launchId` |
| `get_console_output` | Read a character page of process output, falling back to the console document | `launchId` |
| `get_log_entries` | Read a UTF-8 byte page of the Eclipse platform log | none |
| `run_tests` | Build and run an existing JUnit configuration, returning native counts and traces | `configuration` |

### Debugging

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `list_debug_targets` | Page debug targets and their threads | none |
| `list_breakpoints` | List registered breakpoint ids, source locations and enabled state | none |
| `set_breakpoint` | Create or enable a Java line breakpoint; workspace path, one-based line | `path`, `typeName`, `line` |
| `remove_breakpoint` | Remove one registered breakpoint by its returned id | `breakpointId` |
| `get_frames` | Page suspended-thread frames and issue session-bound frame handles | `threadId` |
| `get_variables` | Expand one variable level, with index paths and array paging | `frameId` |
| `continue_execution` | Resume one suspended thread and invalidate its frame handles | `threadId` |
| `step_into` | Step into and wait for suspension or termination | `threadId` |
| `step_over` | Step over and wait for suspension or termination | `threadId` |
| `step_return` | Step out and wait for suspension or termination | `threadId` |

### Discovery, batching and paging

| Tool | Purpose | Required arguments |
| --- | --- | --- |
| `find_tools` | Rank tools by query and return their toolset names | `query` |
| `load_toolset` | Load `workspace`, `run`, `debug` or `all` into this session | `name` |
| `get_session_info` | Report loaded tools and the remaining output allowance | none |
| `resume_result` | Read a retained truncated result from a character offset | `cursor` |
| `capture_baseline` | Capture a loaded read tool's result for a later comparison | `tool` |
| `get_delta` | Compare a retained baseline with a fresh read | `baselineId` |
| `batch` | Run up to 16 loaded tools sequentially, stopping on the first failure | `calls` |

## Removing it

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\lunar.ps1 uninstall
```

That removes the lunar bundles from `dropins` and uninstalls the p2 feature if it was installed that way. It refuses to touch anything while an Eclipse from that installation is running, because deleting a jar from under a live Eclipse leaves the one state nobody wants: lunar still answering in the running instance and gone from disk for the next one. Stop Eclipse first.

Without `-Force` the config file and `ECLIPSE_MCP_TOKEN` are left alone, so `status` still works afterwards. With `-Force` they go too — the config file is renamed to `config.json.bak` rather than deleted, because the token inside it is the one thing here that cannot be handed back to whatever client was using it.

By hand: delete `com.github.lunar.*.jar` from `<eclipse>\dropins`, and if lunar came from the update site, run the director's own uninstall:

```powershell
.\eclipse\eclipsec.exe -nosplash -consoleLog -application org.eclipse.equinox.p2.director -repository file:/D:/path/to/lunar/site -destination D:\path\to\eclipse -profile <profile> -uninstallIU com.github.lunar.feature.feature.group
```

p2 leaves the files on disk at uninstall by design, so check the pool before and after. `uninstall` leaves the client registration alone as well, because it cannot know which client you used and in which scope — remove that too with your client's own command (`opencode mcp remove lunar`, or delete the `lunar` entry from the file under [Connect your client](#connect-your-client)). To remove the settings as well:

```powershell
[Environment]::SetEnvironmentVariable('ECLIPSE_MCP_TOKEN', $null, 'User')
Remove-Item "$env:USERPROFILE\.lunar\config.json"
```

## Installing from the update site

`-Install` copies loose jars into `dropins`. That is the simplest thing that works and it needs no p2 at all. If you would rather have lunar managed as an installed feature, `build.ps1 -InstallP2` publishes a repository into `site\` and installs it with the p2 director.

```powershell
# Eclipse must be closed.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -InstallP2
```

What it does: generates `features\com.github.lunar.feature\feature.xml` and `category.xml` from the bundle manifests, runs `org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher` and then `CategoryPublisher` into `site\`, resolves the plan with a `-verifyOnly` director run, and only then installs `com.github.lunar.feature.feature.group`.

There is no Tycho or PDE build here, so `feature.xml` is generated rather than compiled. It is written from `Bundle-SymbolicName` and `Bundle-Version` in the manifests, and each jar is named `<id>_<version>.jar` to match. That matters because p2 reads a feature entry's version as an **exact** match rather than a floor, so a stale value would publish and then install nothing.

To do the same by hand:

```powershell
# 1. Lay the jars out the way the publisher expects, and write the two definitions
#    beside them. feature.xml needs one <plugin> per jar, each at its exact
#    Bundle-Version; category.xml is optional and only names the site in the wizard.
mkdir site, plugins, features\com.github.lunar.feature
copy out\<build>\com.github.lunar.*.jar plugins\
#    features\com.github.lunar.feature\feature.xml   id="com.github.lunar.feature" version="1.0.0"
#    category.xml                                    <site><feature id="com.github.lunar.feature" version="1.0.0"/>

# 2. Publish the bundles and feature. -source must be the directory holding
#    plugins\ and features\, and eclipse.p2.data.area is where this run expects to
#    install from, so run it against your own installation.
cd C:\path\to\eclipse
.\eclipsec.exe -nosplash -consoleLog `
  -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
  -source C:\path\to\lunar -metadataRepository file:/C:/path/to/lunar/site `
  -artifactRepository file:/C:/path/to/lunar/site -append -compress

# 3. Publish the category into the same repository. Order matters: a category whose
#    IUs do not exist yet produces nothing at all, so skipping this or inverting the
#    two steps leaves a site with no entries.
.\eclipsec.exe -nosplash -consoleLog `
  -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
  -metadataRepository file:/C:/path/to/lunar/site `
  -artifactRepository file:/C:/path/to/lunar/site `
  -categoryDefinition file:/C:/path/to/lunar/category.xml -categoryQualifier lunar

# 4. Install. Add -profile <name> to target a non-default profile; the name is in
#    eclipse.p2.profile in <eclipse>\configuration\config.ini. .feature.group is part
#    of the IU id, not a version, so it must not be split on the slash.
.\eclipsec.exe -nosplash -consoleLog `
  -application org.eclipse.equinox.p2.director `
  -repository file:/C:/path/to/lunar/site `
  -destination C:\path\to\eclipse `
  -installIU com.github.lunar.feature.feature.group
```

Step 4 is `-verifyOnly`-able: add it and the director resolves the plan and changes
nothing. It is not optional-without-meaning though — without `-installIU` it prints its
usage and exits 0 without loading the repository.

Or, without touching the command line, add `file:/C:/path/to/lunar/site` under **Help ▸ Install New Software**, then install *Lunar*.

Two things worth knowing before you choose this route:

- **The two install routes are mutually exclusive.** A p2 install lands in the pool named by `eclipse.p2.data.area` while `dropins` jars are read separately, so both copies of `com.github.lunar.core` resolve to the same id and Eclipse reports the bundle as installed from two locations. `-InstallP2` refuses while lunar jars are in `dropins`, and tells you how to move them.
- **A rebuild at the same version will not reinstall.** p2 treats the same id and version as the same artifact and exits 0 having done nothing, so `-InstallP2` checks the jars actually landed in the pool and fails loudly when they did not. p2 also deliberately leaves files on disk at uninstall, so a restart is required either way. Bump `Bundle-Version` in `bundles\*\META-INF\MANIFEST.MF` to cut a new one. For iterating on code against your own Eclipse, use `-Install`.
- **The installed feature may not be listed in Help ▸ About ▸ Installation Details.** Only the `.feature.group` is installed, and listing installed features there is off by default. Add `-profileproperties org.eclipse.update.install.features=true` to the director arguments if you want it to show up. The bundles resolve either way.

## Status and known limitations

- `-InstallP2` publishes a p2 update site; there is still no Tycho build. The default `-Install` route produces jars and puts them in `dropins`.
- The publisher boots the full Eclipse product named in `eclipse.ini`, so bundles already in `dropins` are activated during the run. With lunar there, one harmless `LUNAR MCP endpoint failed to start` is logged, because a headless product has no workspace yet. The publish itself is unaffected.
- Tools act on saved files on disk, not on unsaved editor buffers. Mutating calls reject dirty text and JDT buffers rather than overwrite them.
- Every client sees the same catalog. Eighteen tools are visible from the start; the rest become visible when a session calls `load_toolset`, and no client name or version is special-cased to change that — a client that caches its tool list has to re-read it after a load.
- Writes are guarded: `write_file`, `move_file`, `delete_file`, `apply_edit` and `apply_quick_fix` all require the current `read_file` hash as `expectedHash`.
- `get_problems` reads markers that already exist and triggers no build. Call `build_project` for current diagnostics, and read its marker counts: `built=true` does not mean there were no errors.
- `run_tests` needs an existing native JUnit launch configuration and does not create one. A completed run with failing tests can return `ok=true` and `passed=false`: the tool succeeded at running the tests.
- `apply_quick_fix` persists only current unused or duplicate import corrections. Any other compiler problem returns `unsupported_quick_fix`.
- `delete_project` and `delete_launch_config` refuse to act unless `confirm` is the exact name.
- Retained stdout and stderr are concatenated rather than interleaved chronologically. Log offsets are UTF-8 bytes; other offsets are UTF-16 characters unless the tool says otherwise.
- Debug frame handles expire with debug state. Refresh frames after every resume, step or termination, and take every id from the tool that issued it.
- Output is bounded: 1 MiB request body, three concurrent tool calls returning HTTP 429 with `Retry-After: 1` when the slots are full, and at most eight retained results sharing 2 MiB. These are implementation bounds, not a load-test claim.
- There is no benchmark against any other Eclipse MCP server.

## Contributing

Run `build.ps1` before you open a pull request. It fails on any compile error or any of its four check classes. Run `check-env.ps1` too if you touched a script — it needs no Eclipse and asserts the things that only fail once someone else runs the build.

`lunar.ps1` covers the whole setup and teardown surface so there is one entry point, and it is loadable without running: `check-env.ps1` dot-sources it and runs all four subcommands against a temporary user profile with the environment accessors replaced, so no test writes to the registry or to your real `%USERPROFILE%\.lunar`.

## License

MIT. See [LICENSE](LICENSE).

## Credits

lunar contains no copied third-party source and derives from no other open source project. It compiles against Eclipse platform bundles (`org.eclipse.*`), published under the Eclipse Public License, and against the JDK. Those bundles are not redistributed here.