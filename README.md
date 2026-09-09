# BKE DNA Logger

BKE DNA Logger is a local-first conversation archaeology system.

## Core evidence rules

- Ordinary manual ChatGPT browsing is the trigger; Playwright is not the primary capture design.
- Browser application response payloads are primary evidence.
- DOM inspection is verification/reconciliation only.
- RAW evidence is never silently replaced by normalized data.
- RAW means the captured conversation evidence in context, including user/assistant messages, tool calls/results and unknown application fields that were actually present in the captured payload.
- There is no separate owner-facing TOOLS view. Tool evidence belongs inside RAW because tools without conversation context are not useful archaeology.
- CLEAN is a derived human view containing only actual `JAN` user turns and `RIGHT-HAND` assistant turns. Tools, tool results, system/developer messages, diagnostics, IDs and archaeology metadata are excluded from CLEAN.
- Native conversation/message identifiers and graph relationships are preserved when exposed.
- Authentication secrets, cookies, authorization headers, request-header collections and response-header collections are outside the capture scope.

## Accepted target architecture: SQLite = DNA

The target Android architecture is one self-contained Working Data SQLite database per generation.

```text
GeckoView
   ↓
streamed capture
   ↓
working.sqlite
├── exact RAW capture chunks / source identity
├── capture observations / metadata
├── processing queue
├── processing checkpoints
├── conversations
├── messages / revisions / provenance
├── titles / searchable metadata
└── indexes
   ↓
CLEAN / RAW reader and exports are generated on request
```

The important rule is:

> BKE DNA Logger captures into SQLite, processes from SQLite, recovers processing from SQLite, reads from SQLite, searches SQLite, backs up SQLite, and may later package SQLite into `.dna`.

Consequences of this decision:

- Exact RAW evidence remains non-negotiable and must be reconstructable byte-for-byte.
- SQLite is the live source of truth for Working Data.
- Long-lived loose `normalized/*.json`, `classifications/*.json`, giant conversation-state JSON, duplicate observation JSON and permanent `.body` evidence files are transitional implementation details, not the target storage model.
- Temporary capture/processing files may exist only as crash-safe scratch. Once the equivalent RAW evidence and required metadata are durably committed to SQLite and verified, the temporary copy should be removed.
- Normalized/interpreted rows are indexes/projections over RAW evidence inside the same database; they do not replace RAW.
- CLEAN is not pre-generated and stored forever. It is queried/paged from SQLite when the owner presses `READ CLEAN` or exports CLEAN.
- RAW is not re-rendered into a giant in-memory string. It is streamed/paged from the RAW evidence stored in SQLite when the owner presses `READ RAW` or exports RAW.
- `.dna` is deferred as a future portable packaging/archive/export route. It is not required for the current Working Data source-of-truth model.

## Processing model: queue + breathing, not a worker farm

Processing must never make GeckoView browsing feel blocked.

- Capture has priority over semantic processing.
- Completed captures enter a durable SQLite processing queue.
- Processing is serial/cooperative by default rather than a multi-worker CPU/RAM race.
- The processor performs a bounded unit of work, commits a checkpoint, yields, rests, then resumes.
- If capture becomes active, semantic processing yields promptly and continues afterward.
- Processing is always enabled; there is no `Capture Only` mode because an indefinitely growing unprocessed backlog is not a healthy steady state.
- Owner-facing processing profiles are `Slow`, `Balanced`, and `Fast`. These tune work/rest budgets and batch sizes, not truth semantics.
- Exact timings are device-profiled rather than permanently assumed in architecture docs.
- Queue state and progress must be visible through a compact dropdown/monitor: waiting, processing stage, progress/checkpoint, failed/retry state and selected processing profile.
- The queue is crash/restart recoverable. If the app dies halfway through a large source, SQLite checkpoints allow processing to resume instead of restarting the entire source.
- Newer/currently opened conversations may be promoted in queue priority for responsiveness, while older RAW evidence remains preserved and can finish processing later.

## Reader and export rules

- Owner-facing primary actions are `READ CLEAN` and `READ RAW`.
- CLEAN and RAW remain two views of the same SQLite DNA, not separate permanent duplicate files.
- The interactive conversation reader must be paged/streamed and must not construct the whole conversation in one `TextView` or one giant `String`.
- CLEAN should read lightweight normalized message/revision rows from SQLite in pages.
- RAW should stream the selected conversation/source evidence incrementally from SQLite while preserving full context.
- Exports are requests: `CLEAN.md`, RAW JSON/Markdown as appropriate, and Working Data SQLite backup are generated/written on demand rather than permanently cached.
- Human export generation must stream directly to the destination for large conversations.

## Working Data lifecycle

- `Latest — Active` is the only live/writable generation.
- `Save & Start New Working Data` checkpoints the active SQLite, creates a verified timestamped SQLite generation, then starts a fresh Latest database.
- Historical Working Data generations are read-only recovery sources.
- The unified conversation library may federate multiple SQLite generations and deduplicate visible conversations by native conversation identity.
- Listing/search must use lightweight indexed SQLite metadata only. It must not scan RAW evidence or rebuild giant conversation state when opening the library.
- Saving/exporting a Working Data SQLite database is the first-class current backup path.
- Destructive cleanup is owner-directed at the Working Data generation level. The existing exact case-sensitive confirmation `jan2x` is sufficient owner confirmation; no additional `.dna` eligibility gate is part of the accepted target model.
- The product does not need a primary `delete conversation` workflow. The owner manages Working Data generations instead.
- Active handles/transactions must be closed or safely checkpointed before deleting a selected generation.

## Lightweight goals

BKE DNA Logger must remain bounded as history grows.

- Capture transport is streamed and backpressured.
- Semantic processing yields between bounded units of work.
- Readers page/stream instead of loading entire conversations.
- Library/search screens query indexed metadata only.
- Processing derivatives are not retained as redundant permanent files after SQLite has absorbed their durable meaning.
- RAW remains exact and durable, but storage representation may later use chunking, compression and content-aware deduplication as long as exact source bytes can be reconstructed and SHA-256 verified.
- APK-size optimization is useful, but embedded GeckoView is an accepted fixed cost; unbounded conversation-data duplication is the higher-priority storage problem.

## Current Android alpha.2 transition state

Android `0.1.0-alpha.2` is a transitional implementation on the path above.

Already proven/implemented:

- Native Kotlin + GeckoView.
- Gecko response clones stream before the old whole-body `arrayBuffer()` memory spike.
- Streamed chunks use bounded backpressure into Android.
- Raw capture writes are incrementally SHA-256 hashed and content-addressed.
- GeckoSession stays alive while Working Data UI is open.
- Semantic derivation is moved off the capture-end acknowledgement path.
- Exact RAW is stored as independently compressed, byte-for-byte verifiable chunks inside Working Data SQLite after crash-safe staging.
- Classification and normalized derivative JSON for new sources are stored inside Working Data SQLite rather than as permanent loose files.
- Derivative reads/reconciliation federate Latest plus saved read-only Working Data generations, with loose-file fallback for pre-migration generations.
- CLEAN is paged from SQLite normalized conversation rows and RAW is read through a bounded SQLite-first source pager.
- Unified multi-SQLite conversation listing/search exists.
- Manual `.dna` export remains portable by materializing SQLite RAW/derivatives only into export-temporary files.
- Working Data & Conversations uses compact utility actions, tappable conversation rows, immediate CLEAN/RAW actions and a secondary MORE menu.
- The existing durable derivation queue is visible through a live compact monitor with current stage/counts and persisted `Slow` / `Balanced` / `Fast` breathing controls.

Still transitional and scheduled for refactor:

- Capture observation JSON, logical conversation-state JSON, title-catalog JSON and some reconciliation/witness metadata still exist outside SQLite.
- SQLite still contains transitional JSON derivative envelopes alongside normalized logical rows; later work may collapse more derivative structure into direct relational projections/checkpoints.
- Deep semantic parsing still has a conservative large-body boundary in alpha.2.
- Working Data backup/generation deletion and the old `.dna`-gated raw purge UI still need to converge on the accepted generation-level SQLite lifecycle.
- Ordinary Gecko photo/camera/file selection support remains scheduled follow-up work.

## UI direction

- Working Data & Conversations is a compact utility surface, not a wall of full-width buttons.
- Conversation rows prioritize title, date/status and compact actions; tapping the row opens the reader.
- Primary actions remain CLEAN and RAW; secondary `.dna`/purge actions belong in the compact MORE menu.
- Processing queue/status and `Slow` / `Balanced` / `Fast` controls are visible without dominating the conversation list.
- GeckoView should support normal ChatGPT user actions such as photo/gallery, camera and file selection without coupling those user-selected inputs to automatic DNA evidence duplication.

## Legacy checkpoint

The pre-unified Working Data implementation is frozen at:

- branch: `legacy/v1`
- SHA: `435750717d52cfc921794549e26e1cdd8abae97c`

That checkpoint preserves the earlier cross-platform/Desktop lineage for possible future Windows/macOS work. Active Android development continues on the unified library architecture.

## Platform ownership

- Windows and macOS are implemented in .NET.
- Android is implemented natively in Kotlin.
- Cross-platform compatibility is defined by DNA wire/evidence identifiers, hashing and archaeology semantics — not by forcing every platform to share one implementation language.
- GeckoView is consumed directly from Kotlin on Android. BKE does not generate a managed C# binding for the full Mozilla API surface.

## Historical first gate: POC-0

Manual ChatGPT browsing -> MAIN-world response interception -> extension bridge -> Chrome Native Messaging -> .NET 10 host -> exact captured application body on disk + metadata + SHA-256.

The first proof deliberately avoided parser assumptions, SQLite, Notion and `.dna` packaging. It proved the evidence source first.
