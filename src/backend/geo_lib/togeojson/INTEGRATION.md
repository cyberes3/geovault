# Integration Guide: Wiring `geo_lib.togeojson` Into The Import Pipeline

## Why This Document Exists

`geo_lib/togeojson/` is a complete, tested, standalone Python port of the
KML/GPX conversion logic GeoVault used to shell out to Node.js
(`@tmcw/togeojson` v7.1.2) for. It was built and validated in isolation on
purpose: the old Node.js wrapper directory
(`src/backend/geo_lib/processing/togeojson/` — `index.js`, `convert.js`,
`package.json`, `package-lock.json`, `node_modules/`) has already been
**deleted**, but every caller of it (`base_processor.py`, `kml_processor.py`,
`gpx_processor.py`, `kmz_processor.py`, `startup_checks.py`, the `Dockerfile`,
`installation/README.md`) was deliberately left untouched.

That means, as of this port landing, **every KML/GPX/KMZ import is broken**.
`_convert_via_nodejs` still points at the now-gone `index.js` path, so it
fails immediately with a clean `FileNotFoundError` ("Node.js converter script
not found at .../geo_lib/processing/togeojson/index.js") — logged correctly
before it's raised, so the root cause is visible in the logs even though (see
the "Known pre-existing bug" note below) the exception that actually
propagates up is a masking `UnboundLocalError`, not that `FileNotFoundError`
itself. `test_processing/test_processors.py::test_kml_processor_convert` /
`test_gpx_processor_convert` and all three KML/GPX/KMZ cases in
`test_api/test_e2e_import.py` are red right now, and will stay red until you
complete the steps below.

This is intentional, not a regression to investigate — it's a deliberate
hand-off point. This document is the concrete, step-by-step guide for
finishing that hand-off.

## Step 1: Replace `_convert_via_nodejs`/`_convert_to_geojson` In `base_processor.py`

The two methods being replaced
(`src/backend/geo_lib/processing/processors/base_processor.py`,
`_convert_to_geojson` at lines 916-946 and `_convert_via_nodejs` at lines
948-1053) currently do: write `content` to a temp file → shell out to
`node --max-old-space-size=8192 index.js <path>` → parse the subprocess's
JSON stdout → clean up the temp file. All of that — the tempfile, the
subprocess, the JSON round-trip, the timeout calculation — goes away.

Replace both methods with a single in-process helper:

```python
from xml.parsers.expat import ExpatError
import defusedxml.minidom as minidom

from geo_lib.togeojson import togeojson


def _convert_to_geojson(self, content: str, file_type_name: str) -> Dict[str, Any]:
    """
    Convert KML/GPX content to GeoJSON in-process via geo_lib.togeojson.

    `content` must already be fully prepared (decoded, namespace-stripped
    for KML) by the caller -- this method just parses and converts it.
    `file_type_name` (e.g. "KML", "GPX", "KMZ") is used only for log/error
    messages; togeojson() auto-detects KML vs GPX from the parsed root
    element, so this one method serves all three processors with no
    format branching.
    """
    try:
        document = minidom.parseString(content)
    except ExpatError as e:
        error_msg = f"{file_type_name} file contains invalid XML: {e}"
        _logger.error(error_msg)
        self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
        raise Exception(f"XML parsing error: {e}")

    try:
        return togeojson(document)
    except Exception as e:
        error_msg = f"{file_type_name} file conversion failed: {e}"
        _logger.error(f"{error_msg}\n{traceback.format_exc()}")
        self.import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
        raise
```

Adjust the exact `import_log`/exception message text to match whatever
`test_processing/test_processors.py`/`test_api/test_e2e_import.py` currently
assert on for the error paths (e.g. any test asserting on an "XML parsing
error" substring) — the goal is that those tests' *success*-path assertions
just work again, and their (if any) deliberate error-path assertions keep
passing with equivalent message shapes.

Once this is in, drop `_calculate_timeout` (`base_processor.py` lines
822-838) — it has no other callers — and drop the `subprocess`/`tempfile`
imports from `base_processor.py` if nothing else in the file still uses them
(check first; `tempfile` in particular may be used elsewhere in the class for
unrelated file-handling).

Do this as a **single, atomic cutover** — no feature flag, no dual codepath.
After this step, `base_processor.py` has exactly one KML/GPX conversion path.

### Known pre-existing bug you'll incidentally fix here

`_convert_via_nodejs`'s `except FileNotFoundError:` branch (line 1043)
references a local variable `filename` that is only assigned later in the
`try` block (line 976) — so the specific failure mode this port's deletion
step exercises (the `index.js` existence check at line 967, which runs
*before* `filename` is ever assigned) raises the `FileNotFoundError`, and
then the handler for it raises `UnboundLocalError: cannot access local
variable 'filename'` instead of the intended clean error message. You can see
this today by running the tests in Step 6 before making any changes. This
bug is specific to the code being deleted in this step, so it disappears on
its own once `_convert_via_nodejs` is gone — no separate fix needed.

## Step 2: Update The Three Processor Call Sites

`kml_processor.py`/`gpx_processor.py` keep their existing preprocessing
exactly as-is — `_prepare_kml_content()`/`_remove_namespaces()` and
`_decode_content()` are GeoVault pipeline concerns (BOM stripping,
namespace-prefix normalization for legacy prefixed KML), completely
independent of the converter itself. Only the final call changes:

```python
# kml_processor.py, in KMLProcessor.convert_to_geojson():
content = self._prepare_kml_content()
geojson_data = self._convert_to_geojson(content, 'KML')  # was: self._convert_to_geojson(content, '.kml', 'KML', is_text=True)
```

```python
# gpx_processor.py, in GPXProcessor.convert_to_geojson():
content = self._decode_content()
return self._convert_to_geojson(content, 'GPX')  # was: self._convert_to_geojson(content, '.gpx', 'GPX', is_text=True)
```

`kmz_processor.py` needs **no change** — `KMZProcessor.convert_to_geojson()`
already extracts KML via `secure_kmz_to_kml()` and then calls
`self._convert_to_geojson(kml_content, '.kml', 'KML', is_text=True)`, which
becomes `self._convert_to_geojson(kml_content, 'KML')` under the same
signature change made in Step 1 — no logic in this file depends on the old
suffix/`is_text` parameters.

## Step 3: Remove The Now-Dead Cruft

- **`src/backend/website/startup_checks.py`**: **already done.** `check_togeojson_installation()` was a *critical* startup check (registered in `critical_checks`, which calls `sys.exit(1)` on any failure) that unconditionally failed the instant the Node.js wrapper directory was deleted — meaning the entire server (both `wsgi.py` and `asgi.py`, which call `run_startup_checks()` at module import time) could no longer boot at all, not just KML/GPX conversion. Because of that severity, this one piece was fixed immediately rather than left for this integration: the function, its `critical_checks` registration, its line in the docstring's numbered list, and its remediation hint have all been removed already. Nothing left to do here.
- **`Dockerfile`**: remove the togeojson-specific block (lines 30-33: the `COPY .../togeojson/package.json` + `WORKDIR .../togeojson` + `RUN npm install`). **Keep** the Node.js 22.x install (lines 18-21, `curl -fsSL https://deb.nodesource.com/setup_22.x | bash -`) — it's still required for the frontend build (see the frontend `npm install`/build steps later in the file). (Unlike the startup check above, this doesn't block anything today — it only runs during a Docker image build — so it's left for this integration pass.)
- **`installation/README.md`**: in the `## NodeJS` section (around lines 80-92), remove the "Install the GeoJSON parser" step (`npm install --prefix backend/geo_lib/processing/togeojson`) and reword the intro sentence from "NodeJS is required for the frontend as well as the internal GeoJSON converter." to "NodeJS is required for the frontend."

## Step 4: Comments That Do *Not* Need To Change

These three "from togeojson" comments describe the **output data shape**
that this Python port intentionally and faithfully reproduces, not the
now-removed JS implementation. Leave them exactly as they are — they're
still accurate:

- `src/backend/geo_lib/types/feature.py` line 22 — `# Allow additional properties from togeojson`
- `src/backend/geo_lib/validation/geojson/models.py` lines 80, 86 — description-field handling for "dictionary format from togeojson (KML HTML descriptions)"
- `src/backend/geo_lib/processing/geo.py` line 30 — `# Handle dictionary format from togeojson`

## Step 5: Why There's No Subprocess Isolation Or Memory-Ceiling Flag

The old `_convert_via_nodejs` ran `node --max-old-space-size=8192 ...` — a
deliberately raised heap ceiling, in its own OS subprocess. The new in-process
design has neither. This was a deliberate decision made during the port, not
an oversight — the reasoning, so you don't have to redo this research or
second-guess it:

- **Root cause of the original 8GB flag** (`git log -S "max-old-space-size"`
  → commit `a2522bd3`, "fix nodejs out of memory", 2025-12-29): at the time,
  a ≤5MB KMZ upload could decompress into an arbitrarily large KML with zero
  guardrail — `MAX_KMZ_KML_DECOMPRESSED_BYTES` (the 200MB streaming cap in
  `geo_lib/security/zip_utils.py`) wasn't added until 2026-07-02, months
  later. Node's V8 engine has its own internal heap ceiling, independent of
  the OS/cgroup limit, so a large-enough document tripped `FATAL ERROR:
  JavaScript heap out of memory` well before real memory pressure. The 8GB
  flag overrides that V8-internal ceiling; it was never evidence the
  workload itself needs 8GB.
- **Empirical repro**: the largest real-world corpus file (`Grey Harbor
  County/NWI_Wetlands.kmz`, 103MB compressed → 322MB decompressed KML —
  itself already over today's 200MB cap) peaked at ~1.95GB RSS in Node
  (DOM parse → convert → `JSON.stringify`, ~38k features) and ~1.05GB RSS in
  Python's `defusedxml.minidom` for the equivalent DOM-parse stage. Real
  memory need, even for content beyond today's ceiling, is ~2GB, not 8GB.
- **The dangerous case is already closed, independent of this port**: the
  200MB `MAX_KMZ_KML_DECOMPRESSED_BYTES` cap and the 5MB direct-upload cap
  already bound worst-case content before it ever reaches a converter — both
  comfortably inside the ~1-2GB measured above.
- **The port is structurally lower-memory than the old pipeline anyway**:
  Node's subprocess model required a full `JSON.stringify()` (one more
  complete copy of the output) crossing a pipe to be `json.loads()`'d again
  in the Python parent. The in-process port eliminates that round-trip
  entirely — `togeojson()` returns a `dict` directly.
- **Trade-off actually being accepted**: `queue_worker.py`'s `QueueWorker`
  runs the import pipeline on a Python *thread*, not a process, so going
  in-process removes the isolation boundary that let a memory spike hit only
  a disposable Node subprocess rather than the shared Django/Daphne process.
  This is accepted because: (a) the dangerous unbounded-growth case is
  already closed at the validation layer, not something this port needs to
  re-defend against; (b) `geojson_processor.py` already does unbounded-ish
  `json.loads()` in-process on this exact same thread today, for a file type
  that can be equally large — precedent already exists for this risk shape;
  (c) a genuine per-job memory ceiling would need process-level isolation
  anyway (`RLIMIT_AS` is process-wide, not thread-scoped, so it can't safely
  bound one `QueueWorker` thread without risking other concurrent work
  sharing that process) — there's no partial/in-process mitigation available,
  only "isolate via subprocess" or "don't"; and (d) subprocess isolation
  would resurrect exactly the tempfile/IPC-marshaling/timeout-calculation
  complexity this port was designed to eliminate, to defend against a threat
  that's already substantially mitigated upstream.

**Do not reintroduce a subprocess or a memory-ceiling flag as part of this
integration** — wire the converter in-process, as designed.

## Step 5b: This Also Removes The Only Timeout On Conversion

A consequence of Step 1 that's easy to miss because it's an absence rather
than a change: `subprocess.run(..., timeout=self._calculate_timeout())` was
not just a memory/isolation boundary, it was **the only timeout enforcement
anywhere in the import pipeline** for the conversion step. Once it's gone,
a KML/GPX/KMZ conversion runs to completion (however long that takes) with
no cap — nothing else in `process_job.py` imposes one (there's no Celery
`time_limit`/`soft_time_limit`; `queue_worker.py`'s `QueueWorker` is a plain
Python thread with no per-job wall-clock limit of its own).

This is an accepted trade-off, not an oversight, for the same reasons as the
memory ceiling above — the dangerous unbounded-size case is already closed
at the validation layer (200MB KMZ-decompressed cap, 5MB direct-upload cap),
this port's own stress testing showed roughly linear scaling with input size
(a synthetic 1.2GB file — 6x over the KMZ cap — converted in ~2 minutes), and
`GeoJSONProcessor.convert_to_geojson()` already sets the precedent of zero
timeout enforcement on an equally-unbounded-by-code-only-by-upload-limits
`json.loads()` call on this exact same thread today. **Do not add a new
timeout mechanism as part of this integration** — it would be solving a
problem the codebase has already decided, elsewhere, is adequately mitigated
by upload-size limits instead.

What this does mean for cleanup, beyond what Step 1 already covers:

- `PROCESSING_TIMEOUT_BASE_SECONDS`/`PROCESSING_TIMEOUT_PER_MB_SECONDS`
  (`website/settings.py` lines 585-586, backed by `processing.timeout_base_seconds`/
  `processing.timeout_per_mb_seconds` in `config.yaml`) have no reader once
  `_calculate_timeout()` is deleted. Remove the settings and the config keys
  (check `config.yaml`/`config.example.yaml` and any deployment configs for
  the latter).
- `process_job.py` line 508, `except (TimeoutError, subprocess.TimeoutExpired):`
  — the `subprocess.TimeoutExpired` half is only reachable via the code being
  deleted in Step 1; every other timeout-prone call in the pipeline (elevation
  API requests in `elevation_service.py`, icon fetches in
  `processing/icons/get.py`) already catches its own timeout internally and
  never lets it propagate this far, so in practice this whole `except` clause
  is already effectively dead today except for that one case. Narrow it to
  `except TimeoutError:` and drop `process_job.py`'s `import subprocess` (its
  only other use).
- `PROCESSING_TIMEOUT` in `geo_lib/processing/messages.py` ("File processing
  timed out: file may be too large or complex") becomes unreachable dead
  string once the above lands — leave the constant in place only if you have
  reason to think something will need it again soon, otherwise remove it too.

## Architectural Note (Out Of Scope For This Integration): The Import Pipeline Should Run On Celery, Not Ad-Hoc Threads

While investigating Step 5b's timeout question, a bigger fact fell out of the
codebase. GeoVault already runs a fully configured Celery deployment in
production — `website/celery_app.py` defines a real `Celery("website")` app,
`startup_checks.py` health-checks both a `geovault-celery` worker and a
`geovault-celery-beat` scheduler (`send_task("api.celery_health.ping_worker",
queue="maintenance")` / a periodic beat heartbeat), and there are systemd
services for both (`sudo systemctl restart geovault-celery
geovault-celery-beat`). But the entire KML/GPX/KMZ/GeoJSON import pipeline
(`ProcessJob`, `QueueWorker`/`WorkerRegistry` in `geo_lib/processing/`) is a
separate, hand-rolled, Redis-backed, per-user single-thread job queue that
runs **instead of** Celery, not on top of it. Today Celery only carries a
handful of small maintenance tasks (`api/tasks.py`'s health-check
ping/heartbeat, `replacement_cleanup_service.py`'s orphaned-replacement
cleanup, and the PWA-mint extension's periodic check) — none of them the
actual import/conversion work this document is about.

Concretely, today: an upload request calls into `ProcessJob`, which enqueues
job metadata to a Redis list and then calls `start_worker_for_user()`
(`queue_worker.py`), which spawns a **daemon thread inside whatever process
handled that HTTP request** (one `QueueWorker` thread per user, staying alive
across requests until its queue has been empty for `IDLE_TIMEOUT = 60`s) to
process jobs sequentially off that Redis queue. Consequences:

- Conversion (and every other pipeline step — elevation filling, tagging,
  reverse geocoding, DB writes) runs inside the same OS process that also
  serves live HTTP traffic for the whole app, not in an isolated worker
  process and not even in the Celery worker process that already exists.
  Step 5's in-process-vs-subprocess tradeoff analysis above actually
  understates the exposure slightly by talking about it in terms of
  `QueueWorker` threads sharing a process with each other — the process
  they're all sharing is (or can be) the same one answering other users'
  HTTP requests.
- Step 5b's "accept no timeout, rely on upload-size caps instead" is a
  reasonable call given today's architecture, but it's a workaround for
  missing infrastructure, not a good long-term end state. Real Celery tasks
  (running in the `geovault-celery` worker process(es), which already exist
  and are already health-checked) get `time_limit`/`soft_time_limit` — a
  proper, first-class per-job timeout — for free.
- The pre-existing "can't cancel mid-conversion" limitation (`_is_canceled()`
  is only checked *between* pipeline steps, never during one) is a symptom of
  the same gap: a Python thread genuinely cannot be forced to stop mid-call
  from outside itself. A Celery task, running in its own OS process, *can* be
  killed with `AsyncResult.revoke(terminate=True)` (SIGTERM/SIGKILL) — real
  hard cancellation, not just a cooperative check between steps.

Migrating `ProcessJob`/`QueueWorker`/`WorkerRegistry` and the Redis queue
helpers onto real `@shared_task`s (and working out the Celery equivalent of
today's strict per-user FIFO ordering — e.g. per-user queues plus
`worker_prefetch_multiplier=1`, or a chained/chord-based primitive — plus
whatever job-status/progress tracking needs to change to work across a real
distributed task queue) is a much larger change than this integration and is
**explicitly out of scope for wiring in this converter**. It's recorded here
because it surfaced directly from this integration's timeout analysis, and it
materially changes how much weight Step 5b's tradeoffs should carry — treat
that section as a provisional stopgap pending this migration, not a settled
architectural decision.

## Step 6: Verification

After completing Steps 1-3:

1. `./run-tests.sh test_processing/test_processors.py` — the KML/GPX
   conversion cases should go green.
2. `./run-tests.sh test_api/test_e2e_import.py` — the KML/GPX/KMZ E2E import
   cases should go green.
3. Manually spot-check a couple of real-world files (e.g. anything under the
   `geovault-tests` corpus's `Grey Harbor County/`) end-to-end through the
   actual import UI/API, comparing against what the old Node.js pipeline
   used to produce for the same file (if you have output saved from before
   this port, or against `geovault-tests/expected-geojson/` directly — the
   golden-master cache already proves the *converter* is correct, so this
   spot-check is specifically about proving the *wiring* — error-message
   shape, `import_log` entries, logging around the timing that
   `_calculate_timeout` used to handle — didn't regress).
4. Re-run the port's own isolated test suite once more for good measure:
   `./run-tests.sh test_geo_lib/test_togeojson` (requires the
   `geovault-tests` symlink/env var — see `src/tests/README.md`).
