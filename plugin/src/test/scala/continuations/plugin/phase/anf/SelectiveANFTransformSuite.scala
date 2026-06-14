package continuations.plugin.phase.anf

import continuations.plugin.CPSUtils
import continuations.plugin.phase.anf.{ANFBodyTransformer, SelectiveANFTransform}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.StoreReporter

class SelectiveANFTransformSuite extends munit.FunSuite with CPSUtils:

  val base = new ContextBase
  given ctx: Context = base.initialCtx.fresh
    .setSetting(base.settings.classpath, sys.props("java.class.path"))
    .setReporter(new StoreReporter(null))
  base.initialize()

  val phase = new SelectiveANFTransform()

  def resetSymbol(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("reset")

  def mkCpsApply()(using ctx: Context): Apply =
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val argSym = newSymbol(ctx.definitions.EmptyPackageClass, termName("$cpsArg"), Flags.Synthetic, cpsType)
    val methType = MethodType(List(termName("x")))(_ => List(cpsType), _ => ctx.definitions.UnitType)
    val methSym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$testMeth"), Flags.Synthetic | Flags.Method, methType)
    tpd.Apply(tpd.ref(methSym), List(tpd.ref(argSym)))

  def mkUnit()(using ctx: Context): Literal = tpd.Literal(Constants.Constant(()))

  def mkDefDefWithCpsParam(body: Tree)(using ctx: Context): DefDef =
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val methType = MethodType(List(termName("ctx")))(_ => List(cpsType), _ => ctx.definitions.UnitType)
    val methSym = newSymbol(
      ctx.definitions.EmptyPackageClass,
      termName("$anonfun"),
      Flags.Synthetic | Flags.Method,
      methType
    ).asTerm
    tpd.DefDef(methSym, _ => body)

  def mkPid()(using ctx: Context): RefTree =
    val sym =
      newSymbol(ctx.definitions.EmptyPackageClass, termName("$testpkg"), Flags.Synthetic, ctx.definitions.UnitType)
    tpd.ref(sym).asInstanceOf[RefTree]

  def mkCfType()(using ctx: Context): Type =
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    ctx.definitions.FunctionOf(List(cpsType), intType, isContextual = true)

  // Test 1: CPS param + CPS 文 → ANF 適用、@CpsSym ValDef 抽出
  test("transformDefDef: CPS param + CPS stmt → @CpsSym ValDef extracted") {
    val defdef = mkDefDefWithCpsParam(tpd.Block(List(mkCpsApply()), mkUnit()))
    val result = phase.transformDefDef(defdef).asInstanceOf[DefDef]

    val block = result.rhs.asInstanceOf[Block]
    assertEquals(block.stats.length, 1)
    val extracted = block.stats.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(extracted.symbol), "extracted ValDef should have @CpsSym")
  }

  // Test 2: CPS param なし → 同一インスタンス返却
  test("transformDefDef: no CPS param → tree unchanged") {
    val methType = MethodType(Nil)(_ => Nil, _ => ctx.definitions.UnitType)
    val methSym = newSymbol(
      ctx.definitions.EmptyPackageClass,
      termName("$normalMeth"),
      Flags.Synthetic | Flags.Method,
      methType
    ).asTerm
    val defdef = tpd.DefDef(methSym, _ => mkUnit())
    val result = phase.transformDefDef(defdef)
    assert(result eq defdef, "should return same tree instance")
  }

  // Test 3: CPS param + empty rhs → 同一インスタンス返却
  test("transformDefDef: CPS param + empty rhs → tree unchanged") {
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val methType = MethodType(List(termName("ctx")))(_ => List(cpsType), _ => ctx.definitions.UnitType)
    val methSym = newSymbol(
      ctx.definitions.EmptyPackageClass,
      termName("$abstract"),
      Flags.Synthetic | Flags.Method | Flags.Deferred,
      methType
    ).asTerm
    val defdef = tpd.DefDef(methSym, _ => tpd.EmptyTree)
    val result = phase.transformDefDef(defdef)
    assert(result eq defdef, "abstract DefDef (empty rhs) should be unchanged")
  }

  // Test 4: CPS 式なし → エラーなし
  test("transformPackageDef: pure PackageDef → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val pkgDef = tpd.PackageDef(mkPid(), List(mkUnit()))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "no CPS expr → no error expected")
  }

  // Test 5: CPS context function 型が不正位置 → report.error
  test("transformPackageDef: CPS context function at invalid pos → error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val badExpr = tpd.Typed(mkUnit(), tpd.TypeTree(mkCfType()))
    val pkgDef = tpd.PackageDef(mkPid(), List(badExpr))
    phase.transformPackageDef(pkgDef)
    assert(freshReporter.hasErrors, "CPS context function expr should trigger error")
  }

  // Test 6: @CpsSym ValDef の rhs はスキップ → エラーなし
  test("transformPackageDef: @CpsSym ValDef rhs skipped → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val valSym = newSymbol(
      freshCtx.definitions.EmptyPackageClass,
      termName("$extracted"),
      Flags.Synthetic,
      freshCtx.definitions.UnitType
    ).asTerm
    addCpsAnnotation(valSym)
    val badRhs = tpd.Typed(mkUnit(), tpd.TypeTree(mkCfType()))
    val valDef = tpd.ValDef(valSym, badRhs)
    val pkgDef = tpd.PackageDef(mkPid(), List(valDef))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "@CpsSym ValDef rhs should be skipped")
  }

  // Test 7: TypeTree with CPS 型 → エラーなし
  test("transformPackageDef: TypeTree with CPS type → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val typeTree = tpd.TypeTree(mkCfType())
    val pkgDef = tpd.PackageDef(mkPid(), List(typeTree))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "TypeTree should not trigger error")
  }

  /** reset呼び出し相当の Block+Closure を構築するヘルパー */
  def mkResetArg(body: Tree)(using ctx: Context): Block =
    val intType = ctx.definitions.IntType
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType = MethodType(List(termName("$ctx")))(_ => List(cpsType), _ => intType)
    val anonfunSym = newSymbol(
      ctx.definitions.EmptyPackageClass,
      termName("$anonfun"),
      Flags.Synthetic | Flags.Method,
      anonfunType
    ).asTerm
    val anonfunDef = tpd.DefDef(anonfunSym, _ => body)
    val closure = tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.EmptyTree)
    tpd.Block(List(anonfunDef), closure)

  /** reset の Apply ノードを構築するヘルパー（TypeApply付き = typer後の正しい形式） */
  def mkResetCall(body: Tree)(using ctx: Context): Apply =
    val intType = ctx.definitions.IntType
    // TypeApply(ref(reset), [Int]) → MethodType([CPS arg], Int)
    val tyApp = tpd.ref(resetSymbol).appliedToType(intType)
    tpd.Apply(tyApp, List(mkResetArg(body)))

  // Test 8: reset呼び出しの CPS 引数（合法）→ エラーなし
  test("transformPackageDef: reset call with CPS arg (legal) → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val resetCall = mkResetCall(tpd.Literal(Constants.Constant(42)))
    val pkgDef = tpd.PackageDef(mkPid(), List(resetCall))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "reset CPS arg is legal, should not trigger error")
  }

  // Test 9: CPS型を戻り値に持つ DefDef（cross-method、合法）→ エラーなし
  test("transformPackageDef: DefDef with CPS return type (cross-method, legal) → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val intType = freshCtx.definitions.IntType
    val m0Type = mkCfType()
    val m0Sym =
      newSymbol(freshCtx.definitions.EmptyPackageClass, termName("m0"), Flags.Synthetic | Flags.Method, m0Type).asTerm
    // rhs: Block+Closure（合法なCPS戻り値メソッド本体）
    val body = mkResetArg(tpd.Literal(Constants.Constant(0)))(using freshCtx)
    val m0Def = tpd.DefDef(m0Sym, _ => body)
    val pkgDef = tpd.PackageDef(mkPid(), List(m0Def))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "CPS return type DefDef body is legal")
  }

  // Test 10: ネスト reset（合法）→ エラーなし
  test("transformPackageDef: nested reset calls (legal) → no error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    // reset { reset { 42 } }
    val inner = mkResetCall(tpd.Literal(Constants.Constant(42)))(using freshCtx)
    val outer = mkResetCall(inner)(using freshCtx)
    val pkgDef = tpd.PackageDef(mkPid(), List(outer))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "nested reset calls are legal")
  }

  // Test 11: 非CPS引数位置のCPS式（不正）→ エラー（既存ケースの補強）
  test("transformPackageDef: CPS expr in non-CPS arg position → error") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    // f(cfExpr) where f takes Int (not CPS type)
    val intType = freshCtx.definitions.IntType
    val fType = MethodType(List(termName("x")))(_ => List(intType), _ => intType)
    val fSym =
      newSymbol(freshCtx.definitions.EmptyPackageClass, termName("f"), Flags.Synthetic | Flags.Method, fType).asTerm
    val cfExpr = tpd.Typed(mkUnit(), tpd.TypeTree(mkCfType()))
    val badCall = tpd.Apply(tpd.ref(fSym), List(cfExpr))
    val pkgDef = tpd.PackageDef(mkPid(), List(badCall))
    phase.transformPackageDef(pkgDef)
    assert(freshReporter.hasErrors, "CPS expr in non-CPS position should trigger error")
  }

  test("transformPackageDef: local named CPS val is allowed") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val outerType = MethodType(Nil)(_ => Nil, _ => freshCtx.definitions.UnitType)
    val outerSym = newSymbol(
      freshCtx.definitions.EmptyPackageClass,
      termName("outerLocalVal"),
      Flags.Synthetic | Flags.Method,
      outerType
    ).asTerm
    val localValType = defn.FunctionOf(List(freshCtx.definitions.IntType), mkCfType()(using freshCtx))
    val localValSym = newSymbol(outerSym, termName("f"), Flags.Synthetic, localValType).asTerm
    val localVal =
      tpd.ValDef(localValSym, tpd.Typed(mkUnit()(using freshCtx), tpd.TypeTree(localValType)))(using freshCtx)
    val outerDef = tpd.DefDef(outerSym, _ => tpd.Block(List(localVal), mkUnit()(using freshCtx)))(using freshCtx)
    val pkgDef = tpd.PackageDef(mkPid()(using freshCtx), List(outerDef))
    phase.transformPackageDef(pkgDef)
    assert(!freshReporter.hasErrors, "local named CPS val should be permitted")
  }

  test("ANFBodyTransformer: Typed CPS selector in Match is extracted before matching") {
    val transformer = new ANFBodyTransformer()
    val intType = ctx.definitions.IntType
    val owner = ctx.definitions.EmptyPackageClass
    val cpsType = cpsTransformClass.typeRef.appliedTo(intType)
    val ctxSym = newSymbol(owner, termName("$ctx"), Flags.Synthetic, cpsType).asTerm
    val methType = MethodType(List(termName("x")))(_ => List(cpsType), _ => intType)
    val methSym = newSymbol(owner, termName("$typedSelector"), Flags.Synthetic | Flags.Method, methType).asTerm
    val cpsApply = tpd.Apply(tpd.ref(methSym), List(tpd.ref(ctxSym)))
    val typedSel = tpd.Typed(cpsApply, tpd.TypeTree(intType))
    val matchTree =
      tpd.Match(typedSel, List(tpd.CaseDef(tpd.Underscore(intType), tpd.EmptyTree, tpd.Literal(Constants.Constant(1)))))

    val (stmts, transformed) = transformer.transformTail(matchTree)

    assertEquals(stmts.length, 1, s"typed CPS selector should be extracted, got $stmts")
    val extracted = stmts.head.asInstanceOf[ValDef]
    assert(isCpsSymAnnotated(extracted.symbol), "selector temp should be marked with @CpsSym")
    transformed match
      case Match(sel: RefTree, _) =>
        assertEquals(sel.symbol, extracted.symbol, s"match selector should use extracted temp, got $sel")
      case other =>
        fail(s"expected Match after ANF extraction, got ${other.getClass.getSimpleName}: $other")
  }
