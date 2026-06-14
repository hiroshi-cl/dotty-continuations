# dotty-continuations

A Scala 3 compiler plugin for **shift/reset** (delimited continuations).

> **Status: Proof of Concept.** This project is experimental and not intended for production use.

---

## Inspiration: scala-continuations

This project is inspired by the Scala 2 [scala-continuations](https://github.com/scala/scala-continuations) plugin by EPFL,
redesigned for Scala 3's type system and context abstractions.

### Key differences from the Scala 2 version

| Aspect | Scala 2 (scala-continuations) | Scala 3 (this project) |
|---|---|---|
| CPS context marker | Type annotation `@cpsParam[B,C]` | Return type `CpsTransform[R] ?=> A` **(type-driven)** |
| Marker propagation | `AnnotationChecker` + `AnalyzerPlugin` hooks | Return type stored in TASTy; no annotation hooks needed |
| CPS method detection | Syntactic annotation check | Pure type check on `finalResultType` |
| Cross-method continuations | Supported | Supported |
| Answer type modification | Supported (B≠C allowed) | Not supported (B=C only) |
| Runtime type | `ControlContext[A, B, C]` | `ControlContext[A, R]` |
| `AnnotationChecker` phase | Required | Not needed |

---

## Features

- `shift` / `reset` delimited continuations
- Cross-method continuations (`def foo(): CpsTransform[R] ?=> A` called from `reset`)
- `try/catch` blocks inside `reset` (via `flatMapCatch`)
- `try/finally` blocks inside `reset` (the `finally` block runs outside the continuation chain; `shift` is not allowed inside `finally`)
- Nested `reset`
- Filinski's reify/reflect encoding for cats, scalaz, and ZIO monads (see [sandbox/reify-reflect/](sandbox/reify-reflect/))

---

## How it works

The plugin registers three compiler phases:

1. **`SelectiveCPSStubPhase`** (before `pickler`) — Generates `$transformed` stub symbols so the CPS ABI is fixed in TASTy and visible across compilation units.
2. **`SelectiveANFTransform`** (after `pickler`) — Normalizes CPS expressions into let-bindings (ANF form).
3. **`SelectiveCPSTransform`** — Rewrites the ANF code into `ControlContext.flatMap` chains.

The runtime model is `ControlContext[A, R]`, which captures the delimited continuation
as a function `(A => R, Exception => R) => R`.

```scala
// Source
reset {
  val x = shift[Int, Int] { k => k(1) + k(2) }
  x * 10
}

// After CPS transform (conceptual)
shiftR[Int, Int] { k => k(1) + k(2) }
  .flatMap { x => shiftUnitR(x * 10) }
  .foreach(identity)
// => 1*10 + 2*10 = 30
```

### `$transformed` ABI

Every method whose return type is `CpsTransform[R] ?=> A` is compiled alongside a
synthetic companion `foo$transformed(...): ControlContext[A, R]` that contains the
actual CPS logic.  `reset(foo())` is then rewritten to
`foo$transformed().foreach(identity)`.

Because `CpsTransform[R] ?=> A` is an ordinary Scala 3 context function type stored
in TASTy, the plugin can detect and call `$transformed` companions across compilation
units without any annotation, macro, or special type-checker hook.

---

## Usage

### build.sbt

```scala
lazy val myProject = (project in file("my-project"))
  .dependsOn(library)
  .settings(
    scalacOptions ++= {
      val jar = (plugin / Compile / packageBin).value
      Seq(s"-Xplugin:${jar.getAbsolutePath}")
    }
  )
```

### Basic example

```scala
import continuations.*

// Simple shift/reset
val result = reset {
  val x = shift[Int, Int] { k => k(1) + k(2) }
  x * 10
}
// result: 30

// try/catch
val safe = reset {
  try {
    val x = shift[Int, Int] { k => k(1) }
    if x > 0 then throw new RuntimeException("boom")
    x
  } catch {
    case _: RuntimeException => 99
  }
}
// safe: 99

// Nested reset
val nested = reset {
  val x = reset {
    shift[Int, Int] { k => k(1) }
  }
  x + 10
}
// nested: 11

// Cross-method: CPS logic defined in a separate method
def ask(prompt: String): CpsTransform[Unit] ?=> String =
  shift { k => println(prompt); k("answer") }

reset {
  val name = ask("What is your name?")
  println(s"Hello, $name!")
}
```

---

## Examples

### Sandbox examples (`sandbox/shift-reset/`)

- **`abort.Escape`** — non-local exit by choosing whether to call the continuation
- **`suspend.Generator`** — `emit` suspends and accumulates generated values
- **`around.Tracing`** — logs values before resuming the computation
- **`around.Resource`** — registers cleanup logic that runs after the continuation

### Integration examples (`integration-tests/`)

- **`NonLocalExitSuite`** — `shift` body skips its continuation
- **`MultiCallSuite`** — same continuation invoked more than once
- **`ResultTransformSuite`** — transforms the result produced by the continuation
- **`CoroutineSuite`** — saves a continuation and resumes it later
- **`CheckpointSuite`** — replays a saved continuation with a different input
- **`LazyDemandSuite`** — delays continuation invocation behind a thunk
- **`WebContinuationSuite`** — models rendering a form and later submitting it

### Reify/reflect (`sandbox/reify-reflect/`)

Filinski's "Representing Monads" encoding: `reify`/`reflect` pairs for cats, scalaz, and ZIO.

---

## Limitations

The following are known unsupported cases (documented as negative compilation tests in `integration-tests-neg/`):

- **`shift` inside `while`** — not supported
- **`shift` inside `finally`** — not supported

---

## Project Structure

```
dotty-continuations/
├── library/                    # ControlContext[A,R], CpsTransform[R], shift, reset
├── plugin/                     # Compiler plugin (ANF + CPS transform phases)
├── integration-tests/          # Integration tests (positive cases)
├── integration-tests-neg/      # Negative compilation tests (expected compile errors)
└── sandbox/
    ├── shift-reset/            # shift/reset usage examples (abort / suspend / around)
    └── reify-reflect/          # Filinski reify/reflect for cats / scalaz / ZIO
        ├── core/
        ├── cats/
        ├── scalaz/
        └── zio/
```

---

## Development & Testing

```sh
# Run all unit and integration tests
sbt test

# Run negative compilation tests (expected to fail to compile)
sbt negTest

# Run both
sbt "test; negTest"

# Run sandbox examples
sbt "shiftReset/test"
```

---

## Design Documents

Detailed implementation specifications live in [spec/](spec/) (written in Japanese):

| File | Contents |
|---|---|
| [spec/README.md](spec/README.md) | Index and recommended reading order |
| [spec/06-architecture.md](spec/06-architecture.md) | Overall architecture (modules, dependency graph, plugin wiring) |
| [spec/00-overview.md](spec/00-overview.md) | Three-phase pipeline (stub → ANF → CPS), `$transformed` ABI |
| [spec/01-public-api.md](spec/01-public-api.md) | Public API (ControlContext / CpsTransform / CpsSym / shift / reset) |
| [spec/02-stub-phase.md](spec/02-stub-phase.md) | Stub phase (`$transformed` generation, ABI) |
| [spec/03-anf-phase.md](spec/03-anf-phase.md) | ANF phase (per-syntax normalization rules) |
| [spec/04-cps-phase.md](spec/04-cps-phase.md) | CPS phase (body transform, callsite & local-value rewrite) |
| [spec/05-shared-infrastructure.md](spec/05-shared-infrastructure.md) | Plugin registration & shared infrastructure |
| [spec/sandbox/shift-reset.md](spec/sandbox/shift-reset.md) | shift/reset usage examples spec |
| [spec/sandbox/reify-reflect.md](spec/sandbox/reify-reflect.md) | reify/reflect monad abstraction spec |
| [spec/reference/](spec/reference/) | Reference notes (scala-continuations diff, Scala 2 transform logic, TreeTypeMap) |

---

## Acknowledgements

Development of this project was assisted by **[Claude Code](https://claude.ai/code)** (Anthropic)
and **[Codex](https://openai.com/codex)** (OpenAI).

---

## License

[MIT License](LICENSE) — Copyright 2026 Hiroshi Yamaguchi @hiroshi-cl
