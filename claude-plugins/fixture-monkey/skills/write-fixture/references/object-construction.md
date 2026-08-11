# How the object actually gets constructed

Read this when a type will not generate, or when a `set` on it is silently ignored. Everything here is diagnosis and repair; the main procedure lives in `SKILL.md`.

Different class shapes are built in completely different ways, and this is the most common reason Fixture Monkey appears not to work. **Match the project's existing `FixtureMonkey` setup first** — if tests elsewhere in the codebase generate the same class fine, reuse their instance rather than configuring a new one.

When construction does fail, fix it at the narrowest scope that works.

## Level 1 — the introspector, global

`FixtureMonkey.create()` defaults to `BeanArbitraryIntrospector`, which needs a no-arg constructor and setters. That is why records and immutable classes fail out of the box.

| Class shape | Introspector | Requirement |
| :--- | :--- | :--- |
| JavaBeans | `BeanArbitraryIntrospector.INSTANCE` | No-arg constructor plus setters. The default |
| Records, immutable classes | `ConstructorPropertiesArbitraryIntrospector.INSTANCE` | A record, `@ConstructorProperties`, **any constructor whose parameter names survive compilation (`-parameters`)**, or a no-arg constructor. Lombok needs `lombok.anyConstructor.addConstructorProperties=true` in `lombok.config` |
| Accessible fields, no setters | `FieldReflectionArbitraryIntrospector.INSTANCE` | No-arg constructor |
| Builder pattern | `BuilderArbitraryIntrospector.INSTANCE` | A static `builder()` method |
| Any constructor, no annotations — a library class you cannot modify | `PriorityConstructorArbitraryIntrospector.INSTANCE` | Uses whatever constructor exists |
| Genuinely mixed shapes | `new FailoverIntrospector(List.of(...))` | Tries each in order, **first success wins**. Last resort — see below |

Kotlin, where `KotlinPlugin` already defaults to `PrimaryConstructorArbitraryIntrospector`:

| Situation | Introspector |
| :--- | :--- |
| Default | `PrimaryConstructorArbitraryIntrospector` — **primary-constructor parameters only**, so a property declared in the class body or inherited is not populated |
| Kotlin classes referencing Java classes in the same graph | `KotlinAndJavaCompositeArbitraryIntrospector()` — Kotlin introspector for Kotlin types, Java one for Java types |
| Properties beyond the primary constructor | `KotlinPropertyArbitraryIntrospector` |

From plugins: `JacksonObjectArbitraryIntrospector` (`JacksonPlugin`), `MockitoIntrospector.INSTANCE` (`fixture-monkey-mockito`), `DataFakerArbitraryIntrospector` (`DataFakerPlugin`). Interfaces, abstract classes, and sealed types need `InterfacePlugin` rather than an introspector.

These are the only introspectors you select. `objectIntrospector(...)` replaces **only** the one that builds ordinary objects — collections, maps, enums, `java.time`, and primitives have their own introspectors wired in by default and are unaffected. If one of those generates incorrectly, the object introspector is not the cause. The [API reference](https://naver.github.io/fixture-monkey/docs/agent-guide/api-reference.md) has the complete inventory.

## `FailoverIntrospector` is a last resort

Classify the types the test touches and pick the **one** introspector that matches — it either works or fails loudly, with no ordering to get wrong. When a few types do not fit, override those individually with `pushAssignableTypeArbitraryIntrospector` (level 2). Ten records and two JavaBeans is not a mixed codebase; it is `ConstructorProperties` globally plus two overrides.

Use a chain only when shapes are genuinely mixed and too numerous to enumerate, because it carries this trap:

**Failover stops at the first introspector that *succeeds*, and "succeeds" means "produced an object" — not "populated everything you pinned".** `ConstructorProperties` writes only the chosen constructor's parameters; a JPA `id`, `createdAt`, or audit column outside that list stays null, and a `set` targeting it is dropped with no exception and no warning. The failure then surfaces as an NPE inside production code, on a getter the test never mentioned.

It qualifies far more often than the annotation requirement suggests: a constructor also counts when its parameter names merely survive compilation, and Spring Boot's build plugins add `-parameters` by default.

```java
// Organization has a public 4-arg constructor (name, description, code, userId),
// so ConstructorProperties succeeds and failover never reaches FieldReflection.
new FailoverIntrospector(List.of(
    ConstructorPropertiesArbitraryIntrospector.INSTANCE,   // wins; id stays null
    FieldReflectionArbitraryIntrospector.INSTANCE))
builder.set(javaGetter(Organization::getId), 1L)           // silently ignored

// Fix: strictest first. FieldReflection needs a no-arg constructor, so records
// fall through correctly, and classes that have one get every field written.
new FailoverIntrospector(List.of(
    FieldReflectionArbitraryIntrospector.INSTANCE,
    ConstructorPropertiesArbitraryIntrospector.INSTANCE))
```

`useExpressionStrictMode()` does **not** catch this — the path resolves to a real property; the introspector simply never writes it.

## Level 2 — one type

Do not change the global introspector for a single class: `.pushAssignableTypeArbitraryIntrospector(Order.class, BuilderArbitraryIntrospector.INSTANCE)`, or `register` for broader per-type rules.

## Level 3 — one builder, with `instantiate`

For several constructors, a factory-method entry point, or one test needing a different path:

```java
import static com.navercorp.fixturemonkey.api.instantiator.Instantiator.constructor;
import static com.navercorp.fixturemonkey.api.instantiator.Instantiator.factoryMethod;

.instantiate(constructor())                                          // use a constructor
.instantiate(constructor().parameter(String.class, "name"))          // pick an overload, name the parameter
.instantiate(factoryMethod("create"))                                // static factory method
.instantiate(constructor().javaBeansProperty())                      // constructor, then setters for the rest
.instantiate(Address.class, constructor())                           // apply to a nested type
```

```kotlin
import com.navercorp.fixturemonkey.kotlin.instantiator.instantiateBy

.instantiateBy { constructor() }
.instantiateBy { factory("create") }
```

Naming a parameter — `.parameter(String.class, "name")` — is what makes `set` able to reach a constructor parameter whose name is not available at runtime.

## Diagnosing

| Symptom | Fix |
| :--- | :--- |
| **A `set` is silently ignored — some fields populated, others null, or an NPE in production code on a field the test pinned** | The introspector never writes that property. `ConstructorProperties` writes only constructor parameters. Do not hunt for an exception; suspect the introspector |
| All properties null or default, or fails on a record | Introspector does not fit the class shape — see level 1 |
| Works everywhere except one class | Override that type (level 2) or `instantiate` (level 3) |
| Wrong constructor picked | `constructor().parameter(...)` naming the signature |
| `set` on a constructor parameter ignored | `.parameter(Type.class, "name")`, or compile with `-parameters` |
