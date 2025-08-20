# Conduit

A tiny, fast, annotation-driven event bus for Java.

- Simple API: `@Subscribe`, `EventBus.post(...)`
- Cancellable events: opt-in via `Cancellable`
- Priorities & once-only handlers
- Subtype-aware dispatch (handlers for supertypes receive subtypes)
- Sync by default, optional async with an `Executor`
- Zero external dependencies

> Package: `com.dractical.conduit`

---

## Quickstart

For a quick demo, check `src/test/java/test/Demo.java`

---

## Features

* `@Subscribe(priority, ignoreCancelled, receiveSubtypes, once)`
* Deterministic ordering: priority, then registration order
* `DeadEvent` published when an event has no listeners (if someone listens for `DeadEvent`)
* Optional `postAsync(event)` when constructed with an `Executor`
* Error hook via `EventBus.ErrorHandler`

---

## Installation

Conduit is a small library. Drop the source under `src/main/java/com/dractical/conduit/` or publish it to your internal
repo.

---

Contributions welcome! Open issues/PRs with tests.