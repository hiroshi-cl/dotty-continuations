package continuations.plugin.phase.stub

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.reporting.StoreReporter

class SelectiveCPSStubExistingTransformedSuite extends munit.FunSuite with SelectiveCPSStubPhaseSuiteBase:

  test("hasExistingTransformed: manual $transformed definition reports error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val orig = mkCpsArgDef("manual")(using freshCtx)
    val manualSym = newSymbol(
      owner,
      termName("manual$transformed"),
      Flags.Method,
      transformCpsMethodType(orig.symbol.info)
    ).asTerm.entered

    val result = phase.validateExistingTransformed(orig.symbol.asTerm)(using freshCtx)

    assert(result, "manual $transformed should be treated as existing")
    assert(freshReporter.hasErrors, "manual $transformed should be rejected")
    assertEquals(phase.hasExistingTransformed(orig.symbol.asTerm)(using freshCtx), Some(manualSym))
  }

  test("hasExistingTransformed: synthetic $transformed definition is preserved") {
    val orig = mkCpsArgDef("synthetic")
    val stubSym = newSymbol(
      owner,
      termName("synthetic$transformed"),
      Flags.Method | Flags.Synthetic,
      transformCpsMethodType(orig.symbol.info)
    ).asTerm.entered

    val result = phase.validateExistingTransformed(orig.symbol.asTerm)

    assert(result, "synthetic $transformed should be treated as existing")
    assertEquals(phase.hasExistingTransformed(orig.symbol.asTerm), Some(stubSym))
  }

  test("hasExistingTransformed: missing $transformed definition returns false") {
    val orig = mkCpsArgDef("generated")

    val result = phase.validateExistingTransformed(orig.symbol.asTerm)

    assert(!result, "missing $transformed should not block stub generation")
    assertEquals(phase.hasExistingTransformed(orig.symbol.asTerm), None)
  }
