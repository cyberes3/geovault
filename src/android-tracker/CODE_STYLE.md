# Tracker App Code Style And Development Guide

This document defines how we write code and evolve the tracker app going forward.

## Goals

- Keep behavior deterministic and regression-resistant.
- Favor clear architecture boundaries over convenience shortcuts.
- Make changes easy to review, test, and reason about.
- Optimize for long-term maintainability, not short-term speed.

## Architecture Rules

- `app` is the Android host and composition shell (activities, services, UI wiring).
- Shared UI and utilities live in `android-common` and `android-common-maps`; reuse them instead of duplicating patterns.
- Keep domain and runtime logic out of fragments and composables; UI layers orchestrate state only.
- It is acceptable for large UI files to exist, but they must be UI/state orchestration only.
- Positioning runtime code belongs in `com.geovault.tracker.positioning` and its subpackages.

## Kotlin Style

- Use explicit, descriptive names for classes, methods, and state fields.
- Keep functions focused and small when possible.
- Prefer immutable values (`val`) over mutable (`var`).
- Avoid nullable state unless null is a true domain concept.
- Prefer sealed classes for state/results where branching matters.
- Use top-level imports only; avoid local/function-level imports.
- Avoid broad suppression annotations. Fix root causes instead.
- Do not use `@Suppress("DEPRECATION")`. Replace deprecated usage.

## Asynchronous Code

- Use suspend functions and coroutines for orchestration.
- Do not build new callback-led chains in UI or services.
- In UI, launch work from lifecycle-aware scopes (`viewLifecycleOwner.lifecycleScope`, `lifecycleScope`).
- Keep dispatcher decisions explicit when work is IO-heavy.
- Prefer structured concurrency over ad-hoc jobs.
- Ensure cancellation safety for long-running operations.

## State Management

- Keep UI state explicit and observable.
- Use ViewModels as the primary state owner for screens.
- Use `StateFlow` for runtime/service state snapshots.
- Update shared runtime state atomically and predictably.
- Avoid hidden global mutable state outside sanctioned stores.

## Dependency Injection

- New collaborators should be constructor-injected first.
- Use Hilt modules for bindings/provides, not ad-hoc singleton access.
- Keep DI graph explicit and stable.
- Avoid default constructor fallbacks that hide missing bindings.

## Repository And Data Access

- Repositories expose suspend-first APIs.
- Prefer typed result wrappers (`RepositoryResult` / domain errors) over implicit null/error callbacks.
- Keep mapping between API/network and domain models explicit.
- Do not leak Retrofit/transport details into UI classes.

## UI And UX Implementation

- Use Material Design 2 components and project common components where available.
- No shadows in UI styling.
- Use shared color palettes from `android-common`; avoid hard-coded hex values unless required.
- Keep titles in `Example Title` style.

## Services And Lifecycle

- Service start/stop semantics must be idempotent and deterministic.
- Handle null-intent restart paths intentionally.
- Guard reconnect/retry loops against invalid state.
- Keep service runtime state synchronized with published state stores.
- Avoid context leaks; prefer `applicationContext` for long-lived operations.

## What Not To Do

- Do not reintroduce callback pyramids for screen orchestration. Use suspend APIs and lifecycle scopes.
- Do not put repository/network/data policy logic inside fragments or activities.
- Do not use `runOnUiThread` handoffs to glue nested async callbacks for core flows.
- Do not instantiate critical collaborators ad hoc when they should come from Hilt.
- Do not add hidden mutable global state for runtime/service coordination.
- Do not create service start/stop paths with ambiguous behavior under rapid toggles.
- Do not bypass typed error/result handling with silent null/boolean-only failure paths.
- Do not mix unrelated cleanup into risky refactors; keep diffs focused and test-backed.
- Do not ship behavior changes without documenting exact UI impact for QA validation.

## Code Review Checklist

- Is logic in the right layer/module?
- Is the flow suspend/Flow-first and lifecycle-safe?
- Are state transitions explicit and testable?
- Are error paths handled consistently?
- Is DI explicit (no hidden construction)?
- Are user-visible behavior changes documented?
- Are compile and targeted tests green?
