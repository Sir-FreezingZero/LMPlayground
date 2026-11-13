# Autonomous agent — development log

Branch: `autonomous-agent`. Started 2026-11-12 from upstream `1687457`
("UI: add edit, regenerate and branch to the website"). LM Playground is MIT
licensed; this work keeps the license and the existing attribution.

## Goal

Turn LM Playground into an on-device autonomous research/engineering agent
that keeps making evidence-backed progress on a long objective for hours or
days with a 1–4B model, survives process death, and never claims success it
can't prove, without removing any existing feature.

## Design in one paragraph

The model is treated as a **stateless, unreliable step function**. All
project state lives in a crash-safe store outside the model (objective,
strategy, task graph, experiments, memory, artifacts, checkpoints,
blockers). Each step rebuilds a small prompt (budgeted, ~2k tokens) from
that state, asks the model for exactly **one action** in a forgiving text
format, executes one tool, classifies what *actually* happened (not what
the model says happened), and commits one atomic snapshot. Long-horizon
competence comes from the system around the model: hierarchical plan →
task → step decomposition, evidence-gated completion, a watchdog that
escalates warn → reflect/change strategy → pause-for-user, bounded retries,
memory consolidation, and restart recovery.

## Where things are

| Piece | File |
|---|---|
| Persistent model (all fields the spec lists) | `agent/src/main/kotlin/com/druk/agent/model/Project.kt` |
| Crash-safe store, checkpoints, step log, in-flight marker | `store/ProjectStore.kt` |
| Task graph: priorities, deps, retries/backoff, blocked propagation, cycle check, interruption cap | `scheduler/TaskScheduler.kt` |
| Memory retrieval (BM25 + importance/recency), consolidation | `memory/Memory.kt` |
| Small-model action/plan/reflection parsers | `llm/ActionParser.kt` |
| Tools (files, code, experiments, compare, web, memory, tasks, criteria, finish) | `tools/AgentTools.kt`, `tools/Workspace.kt`, `tools/Runtimes.kt` |
| Evidence checking & completion verification | `verify/Evidence.kt` |
| Progress classification & stagnation watchdog | `engine/Watchdog.kt` |
| Bounded prompt construction | `engine/ContextBuilder.kt` |
| Step loop, merge with concurrent user edits, recovery | `engine/AgentEngine.kt` |
| Multi-project runner, run policy, crash-loop guard | `engine/AgentRunner.kt` |
| User controls (pause/resume/stop/objective/instructions) | `engine/ProjectControl.kt` |
| Android: llama.cpp backend | `app/.../agent/AgentLlmBackend.kt` |
| Android: V8 sandbox code runner | `app/.../agent/SandboxJsRunner.kt` |
| Android: web bridge, settings, battery/thermal policy | `app/.../agent/AgentPlatform.kt` |
| Android: background worker + periodic resume | `app/.../agent/AgentWorker.kt` |
| Android: manager, UI | `app/.../agent/AgentManager.kt`, `ProjectsFragment.kt`, `ProjectsScreen.kt` |
| Chat ↔ agent CPU yielding | `app/.../agent/ChatActivityGate.kt` (+2 lines in `GenerationCoordinator`) |

## Log

### Phase 1 — repository analysis
- Cloned with submodules (llama.cpp fork). Read `ARCHITECTURE.md`, the AIDL
  engine path, `GenerationSession`, `ToolCallLoop`, `ToolRegistry`, the
  existing web/JS tools, WorkManager download worker, navigation and drawer.
- Integration points chosen: the shared `GenerationSession`/`LlamaCpp`
  proxies (no engine changes), the existing `JavaScriptSandbox` usage, the
  existing web tools via their JSON contract, the WorkManager foreground
  pattern + `SystemForegroundService` (dataSync) already declared, and the
  conversation drawer for navigation.
- Environment limits: GitHub reachable; Google Maven, Maven Central, Gradle
  distributions, the Android SDK and Hugging Face are **blocked** by the
  sandbox's network policy. So: no `./gradlew` build, no APK, no real GGUF.
  Workaround for the core: compile and test with the Kotlin 2.0.21
  compiler, coroutines and JUnit jars bundled inside the local Gradle
  install (`agent/scripts/offline-test.sh`).

### Phase 2–3 — agent core
- New pure-JVM module `:agent` (same pattern as `:llamacpp`), no third-party
  deps (own JSON codec, because org.json only exists on Android).
- Store: temp → fsync → atomic rename; previous snapshot kept; rolling
  checkpoints; JSONL step log tolerant of a torn last line; in-flight marker
  written before a step's side effects and cleared after commit.
- Commit order snapshot → log → clear marker, so recovery can tell whether
  the step in flight committed. Missing log lines are re-appended from the
  snapshot.
- All commits are `update(fn)` on the *latest* state; step results are
  merged so user edits made while the model was thinking (pause, objective,
  instructions, criteria, task retry, blocker dismissal) win.

### Bugs found by the tests (and fixed)
1. **Evidence checker rejected all valid evidence.** `x?.let { problem(it) } ?: "not found"`
   turned "no problem" (null) into "not found". Every experiment/artifact
   citation was refused.
2. **Concurrent-edit merge silently skipped.** "Did anything change during
   the step" compared `updatedAt` timestamps; two writes in the same ms
   looked identical and a user's objective change was overwritten. Replaced
   with a monotonic revision counter.
3. **Identical experiment reruns created new records** (e9, e11, e13… all
   "identical to e6"). Now a rerun reuses the original record.
4. **Gameable progress:** re-running near-identical code with trivially
   different output (`print n`) counted as meaningful progress. Now
   near-duplicate programs (shingle similarity > 0.9 vs the last 6) are
   groundwork, not progress.
5. **Planner could re-add tasks that had already failed.** Only tasks
   cancelled by a strategy change may be re-planned now.
6. **Failure history overwritten** when a strategy change cancelled a task
   that had already failed an attempt; reasons now accumulate.
7. Parser: `**ACTION:** \`tool\`` (markdown-bolded) wasn't recognized.

### Phase 4 — long-horizon reliability (simulated)
`LongHorizonTest`: 3 projects × 3000 step attempts sharing one model that
is honest 60% of the time and otherwise emits prose, busywork, failing code,
fake "breakthroughs", unsupported discoveries and bogus evidence; 2% chance
of a process kill per attempt at a random phase; random model-unload
windows; one project paused by the user for the first half; a simulated user
who resumes stall-paused projects. Invariants checked **after every attempt**.

Latest run (seeded, deterministic):
```
attempts=3000 transitions=1436 crashes=53 unloads=26 llmCalls=1564
ALPHA:   COMPLETED steps=528 tasks=138 done=129 exps=131 mem=171 (archived 120) strategies=4
BRAVO:   COMPLETED steps=475 tasks=134 done=129 exps=130 mem=168 (archived 120) strategies=2
CHARLIE: ACTIVE    steps=449 tasks=102 done=95  exps=96  mem=145 (archived 80)  strategies=6
```
(These are simulation numbers about the *control system*, not about any
real model's ability. The "codec" is scripted.)

### Phase 5 — LM Playground integration (written, not compiled)
- Drawer gets a **Projects** row (phone + tablet); `nav_projects` destination.
- Projects list grouped Active / Paused & stopped / Completed, with progress,
  current task, last achievement, latest checkpoint, blockers.
- Detail: Overview (objective + edit, blockers + dismiss, strategy, next,
  criteria + add, instructions + add, model picker, summary, final report),
  Tasks (retry/cancel), Activity (every step: thought, action, output,
  progress label), Findings (discoveries, experiments, findings, failed
  approaches, consolidated memory), Files (viewer + share). Pause / Resume /
  Stop / Export report / Delete.
- Background: `AgentWorker` (CoroutineWorker → foreground dataSync), unique
  KEEP; `AgentResumeWorker` every 15 min restarts the runner if ACTIVE
  projects exist; `App.onCreate` does the same after process start. Paused
  projects are never resumed automatically.
- Battery/thermal/power-saver policy; "only while charging" option; agent
  yields to chat (`ChatActivityGate`).
- Existing features untouched apart from: one new drawer row, one nav
  action, two lines in `GenerationCoordinator`, one dependency, startup hook.

### Phase 6 — small-model optimisation (what's in place)
- Prompt budget derived from the configured context size; sections shrink
  by priority; reply format last (where small models attend most).
- One model call per step; reflection only when the watchdog asks;
  memory summaries ≈ one call per 20 entries (extractive fallback).
- Fresh session per call → no KV-cache growth across days; model stays
  loaded between steps, unloaded when the runner stops.
- Tool outputs clipped; only the last step's output gets real room.
- Previous web pages cached in `research/`; `recall` searches archived memory.

## Test ledger

### Performed (in this sandbox, JVM)
`agent/scripts/offline-test.sh` → **64 tests, all passing**:
- Persistence (9): JSON round-trips, torn snapshot → previous, both
  corrupt → checkpoint, checkpoint rotation, torn log line, project
  isolation, in-flight marker.
- Scheduling (8): priority/age ordering, dependencies, resume RUNNING,
  retry/backoff/fail, blocked propagation without stalling unrelated
  tasks, cycles/unknown deps rejected, interruption cap, 20-task chains.
- Parsers (11): fenced/unfenced blocks, markdown noise, `<think>`
  stripping, JSON tool-call style, unknown tools, plans with deps,
  numbered-list fallback, reflections.
- Tools (18): workspace escape attempts, write/read/edit/dup detection,
  structured errors, experiments + repeat detection, sandbox-written files,
  evidence-gated completion, stale evidence after file change, discoveries
  need evidence, verified finish, failed web requests, page cache,
  comparison, recall of archived memory; **real Python** sandbox: success
  + metrics, **timeout kill**, **invalid code**, blocked outside-file
  read/write, sockets, subprocess; **real Node** sandbox helpers and
  `child_process` / path-escape denial.
- Engine (11): end-to-end verified completion, pause during inference,
  concurrent instruction/objective edits, model unavailable → backoff →
  pause (no fabricated steps) → explicit resume, invalid replies, watchdog
  warn → reflect → strategy change ×3 → pause, per-task step budget,
  premature finish rejected, paused projects never run, 3-project
  round-robin without prompt contamination, policy stop/throttle.
- Crash recovery (5): kill at before-tool / after-tool / before-commit /
  after-commit × 3 occurrences each (no duplicated experiments or tasks,
  contiguous log), poisoned task failed after 3 kills (no loop), crash-loop
  guard, runner recovery with paused project, cancelled step commits nothing.
- Long horizon (2): the simulation above; prompt bounded for a project with
  5000 memories / 3000 experiments / 300 tasks (7000 and 2500 char budgets).
- The JS sandbox prelude used on-device was executed under Node's `vm`
  (file I/O, metrics, syntax error, missing file, path escape, return value,
  placeholder-safe splicing).

### NOT performed (needs things this sandbox doesn't have)
- `./gradlew :agent:test` / `assembleDebug` — Android SDK and Maven repos
  unreachable. The `:app` changes have **never been compiled**. A partial
  compile against the real `:agent` classes showed only cascades from
  missing Android symbols, which is weak evidence. Expect to fix small
  compile errors on first build.
- Installing on a device; local inference through `AgentLlmBackend`; the
  V8 isolate on a real WebView; foreground notification; WorkManager
  restart after `adb shell am kill`; reboot; battery/thermal policy;
  model download flow from the Projects screen; multi-hour runs.
- Any run with a **real small model**. How often a 1–4B model produces a
  valid action, and whether it makes real progress on e.g. compression
  research, is unmeasured. No benchmark numbers exist and none are claimed.

## Known limitations / next steps
1. **Python on device.** Stock Android has no Python. The device runner is
   JavaScript (V8). Options: Chaquopy (adds ~15–30 MB and a Gradle plugin)
   or a WASM CPython inside the same isolate. The tool layer is ready
   (`CodeRunner.languages`); prompts already adapt to what's available.
2. **Android 15+ dataSync limit** (~6 h per 24 h). The worker is stopped
   when it's reached; the periodic check resumes later. `specialUse` would
   need Play policy justification.
3. **Background start of foreground work** from the periodic check can be
   refused on Android 12+; the worker then runs an 8-minute slice and the
   next check continues.
4. Chat and agent can load two models at once; the backend checks free RAM
   first and backs off with a clear blocker instead of OOM-ing.
5. If the `:llama` engine crashes while the chat UI is showing, the agent
   may acknowledge the crash before the chat surfaces it. Verify on device.
6. Snapshot size grows with experiments (~170 KB at ~500 steps in the
   simulation). Fine for weeks of steps; compaction of old experiment
   records is a straightforward follow-up.
7. Strings are English only.

## How to run the tests
```
./gradlew :agent:test                       # normal environment
GRADLE_LIB=/opt/gradle/lib agent/scripts/offline-test.sh   # no Maven access
```

## First things to do on a dev machine
1. `./gradlew :agent:test :app:assembleDebug` and fix any compile errors
   in `app/.../agent/`.
2. Install, download a 1–4B instruct model (Qwen3 1.7B / Gemma 3 1B class),
   create a small project ("write and benchmark RLE vs. delta+RLE on a
   generated corpus; done when a measured ratio < 0.5"), watch Activity.
3. `adb shell am kill com.druk.lmplayground` mid-step → reopen → verify the
   step count resumes and the interrupted step is recorded.
4. Note the valid-action rate per model in this log; tune `ContextBuilder`
   and `ActionParser` from real transcripts.
