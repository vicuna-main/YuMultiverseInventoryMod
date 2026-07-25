# Dimension-transition persistence audit

## State and identities

- `ProfileKey` is `(player UUID, scope, game-mode bucket, share id)`. A scope is either a world
  identity or a configured group. It is the ordering and overwrite boundary for one isolated share.
- `WorldIdentity` is the stable dimension/world name used by group resolution and world backups;
  it is deliberately separate from live `ServerLevel` references.
- `revision` is a per-player, monotonically increasing logical sequence. The first allocation after
  startup reconciles the counter with revision journals and stored profile documents; subsequent
  allocations are memory-only. A completed I/O transaction advances the durable counter before its
  journal is removed.
- A pending transaction is a checksummed, compressed roll-forward manifest containing every profile
  document and the optional completed transition journal for one revision. Its presence means the
  logical batch may be partially installed and must be replayed idempotently at startup.

## Read/write state machine

```text
PlayerChangedDimensionEvent (server thread)
  -> dimensionChanged -> withPlayerLock -> reconcile
  -> resolve old/new ProfileKey values from immutable config
  -> capture outgoing live adapters (detached NBT)
  -> read incoming values from the login-preloaded in-memory working set
  -> clear/apply/synchronize the live player state
  -> capture only missing/new final payloads; reuse applied payloads for backups/journal
  -> allocate the next in-memory revision
  -> publish the detached batch to the bounded ProfilePersistenceQueue
  -> update the in-memory working set and session; release the player lock

ProfilePersistenceQueue (I/O worker, ordered per player)
  -> canonical payload encoding + SHA-256
  -> construct checksummed profile envelopes and transaction manifest
  -> compress/write/fsync transaction temp; atomic rename; directory fsync
  -> install profile documents through temp + fsync + atomic rename
  -> write completed revision journal and durable revision counter
  -> delete transaction journal durably
  -> record latency/failure/queue metrics
```

The player lock never waits for an I/O future. A rejected full queue causes the live mutation to be
rolled back and the transition to fail; it never falls back to synchronous disk I/O.

## Synchronous versus deferred state

Inventory, ender chest, health, effects, statistics, advancements, equipment synchronization, and
other adapter state must be read or applied on the server thread. The selected target profile must
also be applied before the transition session is advanced. Profile lookup itself is memory-only:
the player's complete stored working set is loaded during login and immediately updated when a
new batch is accepted.

Compression, canonical traversal, SHA-256, transaction construction, filesystem writes, force,
rename, pruning, and write verification are deferred. No `ServerPlayer`, registry, world, inventory,
capability, or adapter is retained by an I/O closure.

## Ordering and lifecycle audit

- A->B->A: accepted batches share one per-player future chain. The in-memory working set is updated
  at enqueue time, so the return to A observes the A snapshot from the first transition even while
  its disk write is delayed. Revisions cannot be committed out of order.
- Multiple players: independent player chains may use separate configured workers. Storage uses a
  per-player commit lock, so one player's slow disk work does not impose a logical ordering edge on
  another player.
- Same UUID: login finishes any earlier chain before replacing ownership. Nested or simultaneous
  live transactions are rejected by the player lock.
- Coalescing: only not-yet-started batches without a transition journal or archive operation are
  mergeable. Newer values replace the same keys while older distinct world keys remain in the
  merged batch. Transition journals are never coalesced.
- Logout: reconciliation and a final detached snapshot are enqueued; no unbounded server-thread
  wait is introduced. Server shutdown stops accepting new work and performs the configured bounded
  flush. A timeout marks shutdown unclean and leaves any durable transaction for replay.
- Failure reporting: the first observed asynchronous failure is surfaced through the queue health
  check and quarantines the player. The same stored exception is consumed once rather than thrown
  every tick. Queue rejection is explicit and counted/visible rather than silently dropping data.

## Crash consistency

Atomic files retain compressed temp files, file `force(true)`, atomic replace where supported, and a
directory force where the platform permits it. The transaction manifest is forced before any
destination profile is replaced. It is removed only after profiles, the completed revision, and the
revision counter are durable. A crash at any profile force/rename boundary therefore leaves the
manifest to roll forward idempotently. A failure before the manifest rename leaves the previous
state authoritative. Checksums are verified during startup recovery; the normal trusted write path
reuses the canonical digest and byte count already produced instead of immediately decoding and
hashing the just-written profile again.

The on-disk schema remains version 1 and legacy checksum migration remains supported. Strict
durability is the only/default mode; no force or verification barrier is disabled for performance.

## Advancement-index optimizer cooperation

Vanilla `PlayerAdvancements` remains the only owner of criterion listeners. A protected live-state
replacement calls vanilla `stopListening()`, mutates the audited adapter set, and calls vanilla
`registerListeners(ServerAdvancementManager)` exactly once after the listeners have already been
removed. No optimizer type, reflection hook, or private index is referenced by this mod.

`invadvopt` observes that normal vanilla lifecycle. Its inventory index is a disposable derived
cache: removal drops the published index, individual additions do not materialize a partial index,
and the completed vanilla registration queues a bounded server-thread warmup. Until a complete
source-set and registry-generation check succeeds, inventory advancement triggers run through
vanilla. A failed or unavailable optimizer therefore changes only performance, never listener,
reward, transition, rollback, or persistence semantics.
