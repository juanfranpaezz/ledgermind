# ADR-0003: Identidad de entidad por clave natural, sin BaseEntity genérica

## Estado
Aceptada — 2026-06-12

## Contexto
Surgió la pregunta de si adoptar una entidad base genérica (estilo `MyObject<T>` / `BaseEntity<ID>`,
como en el repo Caprish del autor) para compartir id/auditoría y CRUD entre entidades. Además, ni
`Account` ni `Posting` sobreescribían `equals`/`hashCode`: un bug latente, porque con la identidad
heredada de `Object` una entidad puede "desaparecer" de un `HashSet` tras persistir/merge (el id
IDENTITY es null antes de persistir y cambia después).

## Decisión
1. **Identidad por CLAVE NATURAL**: `Account` implementa `equals`/`hashCode` por `address`; `Posting`
   por `idempotency_key`. Ambas son únicas, inmutables y se asignan en construcción. NO se usa el id
   IDENTITY para identidad. (Estrategia recomendada por Vlad Mihalcea.)
2. **Sin BaseEntity genérica** (base-less): solo hay dos entidades con ciclos de vida DELIBERADAMENTE
   divergentes (`Account` mutable + `@Version`; `Posting` append-only inmutable). Por la Rule of Three,
   extraer una superclase no se justifica, y una `BaseEntity<ID>` con `version`/`updated_at` forzada
   sobre `Posting` sería una abstracción *leaky*.

## Consecuencias
- (+) `equals`/`hashCode` correctos y estables → seguros en colecciones y tras persist/merge.
- (+) Cada entidad expresa su propia identidad e invariantes; sin acoplamiento por herencia.
- (−) Algo de repetición (id/timestamps en dos clases): aceptable a esta escala.
- A futuro, si aparecen más entidades mutables con auditoría, se evaluará una `@MappedSuperclass` de
  DOS niveles (`AbstractEntity` con id; `AbstractAuditableEntity` con version+updated_at), dejando
  `Posting` fuera de la base versionada.

## Alternativas consideradas
- **`MyObject`/BaseEntity genérica mutable (estilo Caprish)**: su núcleo (update genérico vía
  `CriteriaUpdate`, mass-assignment por nombre de campo, validación por reflexión "ningún `Number<0`")
  ROMPE el optimistic locking y permite mutar dinero fuera de `applyDebit`/`applyCredit`, y la regla
  de negativos es falsa aquí (stornos y `allow_negative`). Rechazada para un core de pagos.
- **`equals`/`hashCode` por id generado**: rompe la identidad antes/después de persistir. Rechazada.
