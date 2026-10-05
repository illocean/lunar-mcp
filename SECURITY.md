# Security policy

## Supported versions

Only the latest commit on `main` is supported. There are no tagged releases yet, so no
other revision is guaranteed to be current.

| Version | Supported |
| --- | --- |
| `main` | yes |
| any earlier commit or future tag | no |

## Reporting a vulnerability

Report it privately at
<https://github.com/illocean/lunar-mcp/security/advisories/new>.

That link needs **Private vulnerability reporting** enabled under
Settings → Code security and analysis. If it will not open, open an issue that says only
that you have found something, and ask for an advisory first — put no details in a public
issue.

Please include the commit hash and what you observed.

### What counts as an issue here

The endpoint binds to loopback, but the tools it exposes read, write, move and delete
files in the workspace and launch processes. Treat as security issues anything that:

- lets a request reach the server without a valid `Authorization: Bearer` token;
- weakens the `Origin` header validation that stops a browser-driven cross-origin
  request on port 8124;
- weakens the `expectedHash` guard that makes `write_file`, `move_file`, `delete_file`,
  `apply_edit` and `apply_quick_fix` require the current contents first;
- weakens the root gate that keeps project creation *and* deletion inside
  `lunar.projectRoots` (falling back to the workspace root), so either can be pointed at a
  directory outside them — including the agent's own home directory;
- weakens the delete walk that resolves every child against the tree root, so a symlink or
  a Windows junction inside a tree can be followed out of the tree being deleted, including
  when the tree root is itself a link; or
- weakens the 32-character minimum on the token, or lets a short token be accepted; or
- lets a request reach the listener on anything other than `127.0.0.1`.

Out of scope: a local process that already holds `ECLIPSE_MCP_TOKEN`. It is a bearer
token, so possession of it is authorization by design.

Known and unfixed: a program launched through `launch` or `run_tests` inherits Eclipse's
own environment, so it can read `ECLIPSE_MCP_TOKEN` out of its own environment. The token
is already readable from `HKCU\Environment` by any process of the same user, so this adds
no privilege, but it is worth knowing before running untrusted code from a project.
