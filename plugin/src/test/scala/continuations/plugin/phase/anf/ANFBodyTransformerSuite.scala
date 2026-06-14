package continuations.plugin.phase.anf

import continuations.plugin.CPSUtils
import continuations.plugin.phase.anf.ANFBodyTransformer

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.StoreReporter

class ANFBodyTransformerSuite extends munit.FunSuite with CPSUtils:

  val base = new ContextBase
  given ctx: Context = base.initialCtx.fresh
    .setSetting(base.settings.classpath, sys.props("java.class.path"))
    .setReporter(new StoreReporter(null))
  base.initialize()

  /** Apply(synthMethod, List(arg: CpsTransform[Int])) を構築する。 */
  def mkCpsApply()(using ctx: Context): Apply =
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val argSym = newSymbol(ctx.definitions.EmptyPackageClass, termName("$cpsArg"), Flags.Synthetic, cpsType)
    val methType = MethodType(List(termName("x")))(_ => List(cpsType), _ => ctx.definitions.UnitType)
    val methSym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$testMeth"), Flags.Synthetic | Flags.Method, methType)
    tpd.Apply(tpd.ref(methSym), List(tpd.ref(argSym)))

  def mkUnit()(using ctx: Context): Literal = tpd.Literal(Constants.Constant(()))
  def mkTrue()(using ctx: Context): Literal = tpd.Literal(Constants.Constant(true))

  // Test 1: transformInline 基本
  test("transformInline: CPS Apply → @CpsSym ValDef + ref") {
    val transformer = new ANFBodyTransformer()
    val cpsApply = mkCpsApply()
    val (stmts, result) = transformer.transformInline(cpsApply)

    assertEquals(stmts.length, 1)
    val vd = stmts.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol), "ValDef should have @CpsSym annotation")
    assertEquals(result.symbol, vd.symbol, "result should be ref to extracted ValDef")
  }

  // Test 2: transformStat: standalone CPS expr → @CpsSym ValDef
  test("transformStat: standalone CPS expr → @CpsSym ValDef") {
    val transformer = new ANFBodyTransformer()
    val cpsApply = mkCpsApply()
    val result = transformer.transformStat(cpsApply)

    assertEquals(result.length, 1)
    val vd = result.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol))
    assert(vd.rhs.isInstanceOf[Apply], "rhs should be the original CPS Apply")
  }

  // Test 3: 純粋文と CPS 式混在ブロック
  test("transformTail: Block with mixed pure/CPS statements") {
    val transformer = new ANFBodyTransformer()
    val pureStat = mkUnit()
    val cpsApply = mkCpsApply()
    val block = tpd.Block(List(pureStat, cpsApply), mkUnit())
    val (stmts, result) = transformer.transformTail(block)

    assertEquals(stmts, Nil)
    val resultBlock = result.asInstanceOf[Block]
    assertEquals(resultBlock.stats.length, 2)
    assertEquals(resultBlock.stats(0), pureStat)
    val extracted = resultBlock.stats(1).asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(extracted.symbol))
  }

  // Test 4: If 片方 CPS → statement 位置で @CpsSym ValDef
  test("transformStat: If with CPS branch → @CpsSym ValDef") {
    val transformer = new ANFBodyTransformer()
    val ifTree = tpd.If(mkTrue(), mkCpsApply(), mkUnit())
    val result = transformer.transformStat(ifTree)

    assertEquals(result.length, 1)
    val vd = result.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol))
  }

  // Test 5: Match 一部 case CPS → statement 位置で @CpsSym ValDef
  test("transformStat: Match with CPS case → @CpsSym ValDef") {
    val transformer = new ANFBodyTransformer()
    val scrutinee = tpd.Literal(Constants.Constant(42))
    val wildcard = untpd.Ident(nme.WILDCARD).withType(ctx.definitions.IntType)
    val caseDef = tpd.CaseDef(wildcard, tpd.EmptyTree, mkCpsApply())
    val matchTree = tpd.Match(scrutinee, List(caseDef))
    val result = transformer.transformStat(matchTree)

    assertEquals(result.length, 1)
    val vd = result.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol))
  }

  // Test 6: Try の body に CPS → statement 位置で @CpsSym ValDef
  test("transformStat: Try with CPS body → @CpsSym ValDef") {
    val transformer = new ANFBodyTransformer()
    val tryTree = tpd.Try(mkCpsApply(), Nil, tpd.EmptyTree)
    val result = transformer.transformStat(tryTree)

    assertEquals(result.length, 1)
    val vd = result.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol))
  }

  // Test 7: WhileDo 内 CPS → report.error
  test("transformTail: WhileDo with CPS → report.error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val transformer = new ANFBodyTransformer()
    val whileDo = tpd.WhileDo(mkTrue(), mkCpsApply())
    transformer.transformTail(whileDo)
    assert(freshReporter.hasErrors, "should report error for shift in while")
  }

  // Test 8: ネスト reset スコープ分離: nested DefDef の内部 CPS は外に漏れない
  test("containsCpsExpr: skips nested DefDef internals") {
    val transformer = new ANFBodyTransformer()
    val cpsApply = mkCpsApply()
    val innerMethType = MethodType(Nil)(_ => Nil, _ => ctx.definitions.UnitType)
    val innerSym = newSymbol(
      ctx.definitions.EmptyPackageClass,
      termName("$inner"),
      Flags.Synthetic | Flags.Method,
      innerMethType
    ).asTerm
    val innerDefDef = tpd.DefDef(innerSym, _ => cpsApply)
    val block = tpd.Block(List(innerDefDef), mkUnit())
    assert(!transformer.containsCpsExpr(block), "CPS inside nested DefDef should not be detected at outer level")
    val result = transformer.transformStat(innerDefDef)
    assertEquals(result.length, 1)
    assert(result.head.isInstanceOf[DefDef])
  }

  // Test 9: If の中に Try が入れ子の CPS 式 (tail position)
  test("transformTail: If with nested Try CPS stays intact") {
    val transformer = new ANFBodyTransformer()
    val tryWithCps = tpd.Try(mkCpsApply(), Nil, tpd.EmptyTree)
    val ifTree = tpd.If(mkTrue(), tryWithCps, mkUnit())
    val (stmts, result) = transformer.transformTail(ifTree)

    assertEquals(stmts, Nil)
    val resultIf = result.asInstanceOf[If]
    assert(
      resultIf.thenp.isInstanceOf[Try],
      s"then branch should be Try, got: ${resultIf.thenp.getClass.getSimpleName}"
    )
  }

  // Test 10: 複数 CPS ValDef 連続ブロック: ValDef rhs は抽出されない (@CpsSym なし)
  test("transformTail: Block with multiple CPS ValDefs stays intact") {
    val transformer = new ANFBodyTransformer()
    val xSym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$x"), Flags.Synthetic, ctx.definitions.UnitType).asTerm
    val ySym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$y"), Flags.Synthetic, ctx.definitions.UnitType).asTerm
    val vdX = tpd.ValDef(xSym, mkCpsApply())
    val vdY = tpd.ValDef(ySym, mkCpsApply())
    val block = tpd.Block(List(vdX, vdY), mkUnit())
    val (stmts, result) = transformer.transformTail(block)

    assertEquals(stmts, Nil)
    val resultBlock = result.asInstanceOf[Block]
    assertEquals(resultBlock.stats.length, 2)
    assert(
      !isCpsSymAnnotated(resultBlock.stats(0).asInstanceOf[ValDef].symbol),
      "user ValDef rhs stays CPS but symbol should NOT have @CpsSym"
    )
    assert(!isCpsSymAnnotated(resultBlock.stats(1).asInstanceOf[ValDef].symbol))
  }

  // Test 11: multiple parameter list 相当: Apply(Apply(f,args1),[cpsArg]) の CPS 検出
  test("transformStat: nested Apply (multi-param-list) with CPS arg → @CpsSym ValDef") {
    val transformer = new ANFBodyTransformer()
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val cpsArgSym = newSymbol(ctx.definitions.EmptyPackageClass, termName("$cpsCtx"), Flags.Synthetic, cpsType)
    // 2 parameter list method: (a: Int)(ctx: CpsTransform[Int]): Unit
    val secondParamList = MethodType(List(termName("ctx")))(_ => List(cpsType), _ => ctx.definitions.UnitType)
    val curriedType = MethodType(List(termName("a")))(_ => List(intType), _ => secondParamList)
    val methSym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$multiMeth"), Flags.Synthetic | Flags.Method, curriedType)
    val innerApply = tpd.Apply(tpd.ref(methSym), List(tpd.Literal(Constants.Constant(42))))
    val outerApply = tpd.Apply(innerApply, List(tpd.ref(cpsArgSym)))
    val result = transformer.transformStat(outerApply)

    assertEquals(result.length, 1)
    val vd = result.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(vd.symbol))
  }
