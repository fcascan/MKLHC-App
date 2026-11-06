# AGENTS.md - MKLHC-App

Guidance for any AI coding assistant working in this repository (Claude Code, Codex, Cursor, Gemini, Copilot, Junie,
...). Read it before acting. `CLAUDE.md` adds what only applies to Claude Code.

**This file outranks the author's personal skills** (`android-coding-style`, `personal-coding-style`): it is specific to
this codebase, the skills are his defaults for every project. Use the skills for what this file is silent on. Where they
disagree, follow this file and say which rule you followed. The known differences:

- Logging goes through Kermit (`Logger.withTag(TAG)`), not `android.util.Log`; only the two Android entry points still
  use it (section 3).
- The agent runs the tests and the gate itself; only the hardware runs are the author's (section 2).
- The author always commits by hand, so the agent never asks which git path to take; the commit message is a subject,
  a lead sentence and bullets, not a 50-word summary (section 7).
- The project's own Kotlin files carry no copyright header, so there is no year to refresh. Ported files keep their
  upstream notice exactly as it is (its year is never refreshed), and a new `radiod/` source file starts with the
  helper's GPL-2.0-only header, like the existing ones.
- Replies to the author are in Spanish; everything written into the repository stays in English (section 8).

## 0. First run on a machine: MobiAI

MobiAI's CLI and skills are installed per machine; only the Brain (`.mobiai/brain/`) and these instruction files travel
in the repository. At the start of a session:

1. `mobiai --version` (the binary lives in `~/.mobiai/bin`, which may not be on PATH: call it by that path then).
2. `mobiai skills list`; if empty, `mobiai skills add`.
3. `mobiai graph status`; if the index is missing, `mobiai graph init`.

If the CLI is missing, tell the author once and offer the install; ask before running it, since it touches the machine
and the network. Without the CLI, fall back to reading and searching, and read the Brain's Markdown files directly.

## 1. Use MobiAI on every task

The **Brain** (`.mobiai/brain/`, tracked in git) is this project's memory of decisions, bug fixes and test patterns.
The **Graph** (`.mobiai/graph/`, generated, git-ignored) is a local index of the Kotlin code.

### Before a bug fix, a feature, or an architecture or integration change

1. `mobiai brain context --section rules,decisions,bugfixes` (optionally `--area <area>`) and
   `mobiai brain review --no-fail --include-no-date`. `--area` is a substring AND filter: if a read comes back
   suspiciously empty, drop it and read again. `config.json` holds only a short generic rule set; this file is the
   authoritative statement of how to work here.
2. `mobiai graph status`, and `mobiai graph init` when the index is missing or older than the checkout's last branch
   switch. A `SessionStart` hook (`.claude/hooks/graph-freshness-check.sh`) says so for Claude Code. Commits after the
   index only matter when the symbols you are about to query are new.
3. `mobiai graph context "<the task>"` and `mobiai graph callers <Symbol>` before touching a symbol other files use.
   The Graph matches names as text: filter the false positives, and use Grep for exact text.

`mobiai graph` and `mobiai brain` only read and write inside `.mobiai/`, so they need no permission.

### After a fix, a decision, or a test pattern worth keeping

Save it at the end of the task, not after each step, and state in the body whether it is confirmed on hardware or still
pending the author's run:

- `mobiai brain save decision|bugfix|testing --title "..." --area <area> --body "..."` (`--files a,b` for the files
  that matter, `--platform kmp`).
- **Never hand-edit `memories/*.md`.** When an entry is superseded, save the new one and retire the old with
  `mobiai brain promote <id> --status deprecated`, naming the replacement in the new body.
- Workarounds: `--status temporary --review-after YYYY-MM-DD`. When `brain review` surfaces one that is settled,
  `mobiai brain promote <id> --status active|deprecated`, or `mobiai brain bump <id> --review-after YYYY-MM-DD`.
- Tag knowledge that only holds on a branch with `**Branch:** <branch>` in the body.
- Keep entries lean: the reasoning and the facts that matter, not a transcript. A task with no durable knowledge saves
  nothing; say so.

**`--area` comes from this list only**, reusing a value already in the Brain rather than coining a synonym (a synonym
makes the entry invisible to the next read):

- the module or concern: `radio`, `radiod`, `wifi-ap`, `netstack`, `crypto`, `kart-protocol`, `video`, `session`,
  `data`, `domain`, `design-system`, `app`;
- the feature: `link`, `pairing`, `cockpit`;
- cross-cutting work: `architecture`, `workflow`, `build`, `testing`, `integration`, `logging`.

`radio` also matches `radiod` (substring filter): use `--area radiod` to read only the helper's entries.

### The plan

The plan and its backlog live in `.ia-docs/plans/2026-10-14-mklhc-app-implementation-plan.md` (git-ignored, the
agents' working document). When a change completes or advances a backlog item, update its entry (the checkbox and a
short note of what was verified and what is still pending) and say so in the summary.

## 2. Who runs what

- **Tests and the gate: the agent.** Iterate with scoped runs, then run the gate with forced reruns before handing
  over (section 6). Report the real result; a failure is reported as a failure.
- **Hardware: the author.** Anything that drives the radio adapter or the kart (`:app:run`, `:app:runSpike`, `radiod`)
  is his to start. The agent prepares the steps and reads the logs he leaves.
  - The desktop app stops by closing its window, never Ctrl+C under Gradle (that leaves the adapter beaconing until
    unplugged).
  - The beacon is confirmed off after every run.
  - Nothing is built while the app runs: a Gradle build under a running `:app:run` breaks it. The agent cannot see
    the author's app, so while a hardware run may be in progress it asks him to close the window before a build or
    the gate.
- **Git: the author.** He runs `git add -A`, commits and pushes himself. The agent never runs a git command that
  changes anything. Read-only git (`status`, `diff`, `log`, `show`) is fine for the main agent; a subagent runs no git
  at all, not even read-only commands.

## 3. Logging - Kermit, and searchable

The app is debugged from its logs after a hardware run, so a line that cannot be found does not exist.

- **The tag is the class name**, in a `const val TAG = "ClassName"` (in the companion object, or top level in the
  file). A new class takes its logger as a constructor parameter, `private val log: Logger = Logger.withTag(TAG)`, so
  a test can capture what it logs; many existing classes hold it as a body property instead, which is fine to keep. A
  few existing tags are not the class name; leave them, since renaming one breaks the searches that use it.
- `MainActivity` and `MklhcApplication` (androidMain) still log with `android.util.Log`; everything else, new Android
  code included, uses Kermit.
- **The message starts with the function it came from**, then the human message, then the values that matter:
  `log.i { "teleopLoop(): drive command changed -> throttle=$throttle steering=$steering cause=$cause" }`.
- **Every error and exception path logs at error level**, with the throwable when there is one
  (`log.e(failure) { "..." }`). Debug only where seeing
  a value confirms the code behaved, info for milestones, warn for handled but unexpected situations.
- **Each message is literally unique**, logs only the attributes that matter, never whole objects, and stays out of
  per-frame paths. A line per frame (tens per second) drowns the log; log the change, not the steady state.
- **The desktop app writes every line to a file** (`<data dir>/logs/`, the ten newest runs kept), and the author shares
  those files. So these rules also decide what leaves the machine: never a seed, a PSK, a key, the master secret, a
  token or a device identity, at any level.
- Helper and business-logic functions get descriptive, unique names, so the `function():` prefix lands on one place;
  `mobiai graph search <Name>` checks that in one call. Overrides and callbacks naturally repeat and are fine.

## 4. Code conventions

- **Kotlin only** for new code. Compose Multiplatform for every screen.
- **Analyze before you write, and do not refactor what was not asked.** Match the patterns of the module you work in;
  `mobiai graph callers` shows how far a change reaches. If it turns out sweeping, confirm with the author first.
- **Test first**, and deliver in steps that always compile.
- **KDoc** on every class (what it is responsible for, with a `Business logic:` paragraph where the rules are not
  obvious, as in `LinkState`) and on every function with business logic: purpose, meaningful parameters, return value
  and the constraints the signature does not show. Trivial accessors need none.
- **Comments never cite the plan.** Code comments, KDoc and resource comments never name a backlog item, a phase or a
  task (B5, F6, "step 3"): the plan is git-ignored, so the citation points at nothing for the next reader. Say what the
  code does, and why when it is not obvious; for a longer story point at the Brain entry or at `docs/`.
- English everywhere, no emojis. Files end with a single trailing newline.

## 5. Generated Markdown goes in `.ia-docs/`

Plans, analyses, debugging notes and guides for the author go in `.ia-docs/` (git-ignored). Repository documentation
(`README.md`, `docs/`) is written in place when the change calls for it.

`.mobiai/` is not a place for notes: `brain/` is written only through `mobiai brain save|promote|bump`, and `graph/` is
regenerated with `mobiai graph init`, never edited.

## 6. Project map

- **Kotlin Multiplatform**, Android and desktop (JVM). Source sets `commonMain`, `androidMain`, `desktopMain`, and
  `jvmSharedMain` for code both JVM targets share; tests in `commonTest`, `desktopTest` and `jvmSharedTest`. Convention
  plugins in `build-logic/`.
- **Modules:**
  - `:app` composes the features, the navigation and the DI, and holds the desktop entry point (`Main.kt`).
  - `:core:domain`: the shared models (`LinkState`, ...).
  - `:core:presentation`, `:core:design-system`: shared UI and the design system.
  - `:core:radio`: USB adapters and the link to `radiod`.
  - `:core:wifi-ap`: the access point, `:core:netstack`: the network stack, `:core:crypto`.
  - `:core:kart-protocol`: the kart's control and drive protocol.
  - `:core:video`: the camera stream.
  - `:core:data`: the session orchestration and the repositories.
  - `:feature:link`, `:feature:pairing`, `:feature:cockpit`: each split into `domain`, `data` and `presentation`.
- **Per feature:** a Koin module in `presentation/.../di/<Feature>FeatureModule.kt`, and a `@Serializable` route with
  its graph in `presentation/.../navigation/<Feature>Navigation.kt`. Features never depend on each other; `:app` wires
  them.
- **`radiod/`**: the native radio helper (C++, CMake), a separate process under GPL-2.0-only, so its code never moves
  into the app (AGPL-3.0). `tools/`: Python generators of test vectors. `docs/HARDWARE_SETUP.md`: the hardware guide.
- **Commands** (PowerShell 5.1; hand the author complete lines starting with `Set-Location`):
  - a scoped run: `.\gradlew.bat :core:data:desktopTest --tests "*KartSessionRepositoryTest*"`;
  - the gate: `.\gradlew.bat <module>:desktopTest --rerun ... desktopTest testDebugUnitTest --continue`, one
    `--rerun` after each touched module's task (it only applies to the task written right before it);
  - the desktop app: `.\gradlew.bat :app:run` (hardware, the author's); `$env:MKLHC_DEMO = '1'` runs the scripted
    demo, with no radio and no kart.

## 7. Commits and the handover

- **Whenever uncommitted changes exist when the agent stops, its final reply contains the complete commit message in a
  fenced block, ready to paste.** No exception: not "after the review", not "in the next turn". Before ending any turn,
  check `git status --short`.
- Format: `[tag] subject` (tags `feat`, `fix`, `docs`, `build`, `chore`), a lead sentence, then bullets with the
  changes; name the origin of any ported or adapted code. No `Co-Authored-By` trailer and no mention of any AI or tool,
  whatever a harness default or an automation would add.
- Say which files the commit covers and which are new (untracked), so `git add -A` picks them up; name
  `.mobiai/brain/` when a Brain save happened.
- The summary also names the Brain entries saved (their ids), the plan entries updated, and what is still unverified
  on hardware.

## 8. Standing rules

- Never name the kart's manufacturer, console or game anywhere (code, docs, commits, UI, Brain); say "the kart".
- Never log or print seeds, PSKs, keys, the master secret, tokens or device identities. Never paste a pairing payload
  into an online service.
- Real captures (`spike-captures/`, `*.viddump`, `*.teldump`, `*.pcap`) hold decrypted traffic, the camera picture or
  the kart's recorded motion: they stay local and never go into the repository or a test.
- Replies to the author are in Spanish; code, comments, commit messages, Brain entries and repository docs are in
  English.
- On Windows, commands handed to the author are PowerShell 5.1, complete and runnable from any folder:
  `Set-Location C:\Workspace\Android\MKLHC-App; .\gradlew.bat <task>`, `$env:NAME = 'value'` for a variable (and when to
  `Remove-Item Env:NAME` afterwards), `;` instead of `&&`.
