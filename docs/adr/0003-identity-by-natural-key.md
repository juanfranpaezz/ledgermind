# ADR-0003: Entity identity by natural key, no generic BaseEntity

## Status
Accepted — 2026-06-12

## Context
The question came up of whether to adopt a generic base entity (in the style of `MyObject<T>` / `BaseEntity<ID>`)
to share id/auditing and CRUD across entities. Also, neither
`Account` nor `Posting` overrode `equals`/`hashCode`: a latent bug, because with the identity
inherited from `Object` an entity can "disappear" from a `HashSet` after persist/merge (the
IDENTITY id is null before persisting and changes afterwards).

## Decision
1. **Identity by NATURAL KEY**: `Account` implements `equals`/`hashCode` by `address`; `Posting`
   by `idempotency_key`. Both are unique, immutable and assigned at construction. The IDENTITY id
   is NOT used for identity. (Strategy recommended by Vlad Mihalcea.)
2. **No generic BaseEntity** (base-less): there are only two entities, with DELIBERATELY
   divergent life cycles (`Account` mutable + `@Version`; `Posting` append-only, immutable). By the Rule of Three,
   extracting a superclass is not justified, and a `BaseEntity<ID>` with `version`/`updated_at` forced
   onto `Posting` would be a *leaky* abstraction.

## Consequences
- (+) Correct, stable `equals`/`hashCode` → safe in collections and after persist/merge.
- (+) Each entity expresses its own identity and invariants; no coupling through inheritance.
- (−) Some repetition (id/timestamps in two classes): acceptable at this scale.
- In the future, if more mutable entities with auditing appear, a two-level `@MappedSuperclass`
  will be evaluated (`AbstractEntity` with id; `AbstractAuditableEntity` with version+updated_at), leaving
  `Posting` outside the versioned base.

## Alternatives considered
- **Generic mutable `MyObject`/BaseEntity**: its core (generic update via
  `CriteriaUpdate`, mass assignment by field name, reflection-based validation "no `Number<0`")
  BREAKS optimistic locking and allows mutating money outside `applyDebit`/`applyCredit`, and the
  no-negatives rule is false here (stornos and `allow_negative`). Rejected for a payments core.
- **`equals`/`hashCode` by generated id**: breaks identity before/after persisting. Rejected.
