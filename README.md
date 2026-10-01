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
- **A bearer token** in the `ECLIPSE_MCP_TOKEN` environment variable, set before Eclipse starts. The server refuses to bind when the variable is missing or blank, and requires the token on every request including from `127.0.0.1`. A request without it gets HTTP 401.

## Install and setup

1. Set the token in the environment Eclipse will inherit, then restart any running Eclipse:

   ```powershell
   [Environment]::SetEnvironmentVariable('ECLIPSE_MCP_TOKEN', '<a long random string>', 'User')
   ```

   Open a new terminal afterwards. A terminal that was already running keeps the old environment, and a client started from it sends an unexpanded `${ECLIPSE_MCP_TOKEN}` that lunar rejects with 401.

2. Build. From the repository root, with JDK 21 on `PATH`:

   ```powershell
   build.cmd
   ```

   `build.cmd` delegates to `build.ps1`, which compiles every `.java` file under `src` with `javac --release 21` against pinned Eclipse jars from a p2 pool, packages five jars into a timestamped directory under `out`, then runs four check classes (`SelfCheck`, `ProtocolCheck`, `FrameworkCheck`, `IntegrationCheck`) and aborts if any fails. It records jar paths, sizes and SHA-256 hashes in `build-state.json`. Maven, Gradle, Ant and Tycho are not used.

3. Install. Stop Eclipse first, then:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Install
   ```

   The script refuses to run while an Eclipse process from that installation is alive. It copies the five lunar jars currently in `dropins` to `backup\installed-<timestamp>`, then copies the five newly built jars into `<eclipse>\dropins`.

4. Start Eclipse with `-clean`. The server binds as an OSGi Declarative Services immediate component when its bundle resolves, so there is no preference page to open.

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

### Paths you have to change

The build is pinned to one machine's layout. On any other machine, edit these four locations before the first build:

| File and line | Value | Meaning |
| --- | --- | --- |
| `build.ps1:4` | `D:\EclipseIDE\.p2\pool\plugins` | directory holding the pinned Eclipse jars the build compiles against |
| `build.ps1:96` | `D:\EclipseIDE\eclipse\` | Eclipse installation whose running processes block an install |
| `build.ps1:98` | `D:\EclipseIDE\eclipse\dropins` | dropins folder the jars are copied into |
| `src/com/github/lunar/SelfCheck.java:360` | `D:\EclipseIDE\lunar\smoke` | existing directory the build-time check uses as a temporary parent |

The jar file names in `build.ps1` are pinned to one Eclipse release train too. If your installation ships other bundle versions, the script fails with `Missing pinned jar: <name>`; update the list to match your pool.

## Connect your client

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

`opencode mcp add lunar --url http://127.0.0.1:8124/mcp --header "Authorization=Bearer {env:ECLIPSE_MCP_TOKEN}"` writes the entry to the project config for you. Add `--global` to write to `~/.config/opencode/opencode.json` instead; omit it to keep lunar project-scoped.

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

## Status and known limitations

- There is no p2 update site and no Tycho build. You produce jars with `build.ps1` and drop them into `dropins`.
- Tools act on saved files on disk, not on unsaved editor buffers. Mutating calls reject dirty text and JDT buffers rather than overwrite them.
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

Run `build.ps1` before you open a pull request. It fails on any compile error or any of its four check classes.

## License

MIT. See [LICENSE](LICENSE).

## Credits

lunar contains no copied third-party source and derives from no other open source project. It compiles against Eclipse platform bundles (`org.eclipse.*`), published under the Eclipse Public License, and against the JDK. Those bundles are not redistributed here.