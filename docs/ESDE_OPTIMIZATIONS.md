# ES-DE optimization pass

Implementation order: **4 → 6 → 1 → 2 → 3 → 5**. This is a refactoring pass,
not a new synchronization format or a release. No NAS/handheld data, folder
selections, ignore rules, version numbers or native Syncthing sources are changed.

## Changes

4. **First Setup readiness:** `EsdeServiceReadiness` publishes API readiness,
   coordinator readiness and configuration revisions. Configuration reloads notify
   subscribers even when the service remains ACTIVE. First Setup observes that
   state and preference changes instead of forcing a Compose refresh every 500 ms
   for 40 seconds. Readiness remains observable after arbitrarily slow startup.
6. **File access and serialization:** Safe Launch and the deferred job use one
   process-wide journal repository with serial background disk access and an
   observable persisted snapshot. Compose never reads the journal file. Offline
   launch waits for journal persistence; SAFE_TO_SWITCH waits for successful
   journal removal. Persistence errors invalidate outstanding UI continuations.
   Required ES-DE settings are written through the existing coordinator queue,
   after the same ES-DE-closed check as imports, rather than a separate executor.
1. **Metadata import:** one parsed DOM supplies modifications and the final
   metadata snapshot. Backups happen only before an actual XML write. Missing
   fields keep their existing values; no-op imports/exports do not rewrite an
   unchanged snapshot. XML validation and atomic writes remain in place.
2. **Diagnostics:** cache compact counts per system, not XML documents or game
   maps. Import/export results update their system's counters; aggregation removes
   disappeared systems and inspects newly discovered systems. Initialization and
   explicit diagnostics still perform a full scan.
3. **Incoming events:** collect sidecar notifications per system in a 900 ms
   window on the coordinator's serial scheduler. A burst queues one import;
   notifications during that import queue at most one trailing pass. Root checks
   and all file access remain on the worker. Shutdown discards queued work.
5. **Safe Launch polling:** unchanged observations back off from 1.5 to 3, 6 and
   at most 12 seconds; progress and new runs reset the interval. Each round fetches
   fresh per-folder status/completion and, when needed, the remote pending list.
   Failed fresh requests cannot unlock play through stale aggregate data. Results
   are published only after the round completes and only for the same request
   generation, API, primary device and folder selection. Retry, launch, session
   completion and destruction invalidate old callbacks.

## Regression checks

- Readiness transitions and config revisions without a startup timeout.
- A thousand notifications coalesce, separate systems stay independent, changes
  during execution get one trailing pass, and shutdown/failure release the queue.
- Diagnostics refresh affected/new systems and remove old roots.
- Adaptive polling resets on progress; stale request generations are rejected.
- Applied DOM snapshots match committed XML and retain absent fields; failed
  backups leave the original XML untouched.
- Unchanged imports/exports preserve snapshot timestamps and avoid false backups.
- Journal operations execute off the caller thread in order; persisted snapshots
  survive failed mutations and a later successful retry.
- Late readiness cannot leave Idle or end an active play session; a failed initial
  journal read can be retried without an existing cached snapshot.

These checks supplement the existing ES-DE unit tests. Physical-device checks
remain necessary for Android process termination, Home-app transitions, battery
restrictions and actual NAS connectivity; unit tests do not simulate those.

## Local verification environment

Final result (2026-09-06): Kotlin and Java compilation succeeded; **87 JUnit tests
passed, 0 failed, 0 ignored**. `lintDebug` succeeded with **0 errors, 45 warnings
and 1 hint**. `git diff --check` passed. No physical-device tests were run.

Java 21 and SDK 37 are available in `.toolchains`. The standard Gradle Java worker
fails on this Windows host with `Unable to establish loopback connection`.
The existing local in-process verification scripts compile the actual Java/Kotlin
sources and run the JUnit suite with the current debug classpath:

```powershell
& ./.toolchains/Run-GradleLocal.ps1 compileDebugJavaInProcess testDebugUnitInProcess lintDebug '-Pkotlin.compiler.execution.strategy=in-process' '-I' .toolchains/inspect-javac.init.gradle '-I' .toolchains/disable-javac.init.gradle '-I' .toolchains/inprocess-tests.init.gradle
```

`compileDebugJavaWithJavac` is replaced by `compileDebugJavaInProcess`, not omitted
from verification. These local helper scripts are not required by normal CI,
which can use `testDebugUnitTest lintDebug` directly. A complete local APK build
also encountered the NDK's refusal of its Windows SDK path containing spaces;
this pass does not claim a newly built APK or a published release.
