package continuations.plugin.phase.cps
import continuations.plugin.phase.stub.SelectiveCPSStubPhase
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.StoreReporter

class SelectiveCPSTransformBodySuite extends munit.FunSuite with SelectiveCPSTransformSuiteBase:
  test("transTailValue: pure expr → shiftUnitR wrapped") {
    val expr = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42))
    val result = phase.transTailValue(expr, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), s"result should be ControlContext, got ${result.tpe.widen}")
  }
  test("transTailValue: ControlContext expr → returned as-is") {
    val cc = mkCcTree()
    val result = phase.transTailValue(cc, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), "ControlContext should pass through")
  }
  test("transBlock: empty stmts + pure expr → shiftUnitR") {
    val expr = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1))
    val result = phase.transBlock(Nil, expr, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), "should produce ControlContext")
  }
  test("transBlock: CPS ValDef + pure tail → map chain") {
    val cc = mkCcTree()
    val vd = mkCpsValDef("$cps0", cc)
    val tailExpr = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))
    val result = phase.transBlock(List(vd), tailExpr, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), "result should be ControlContext via map")
  }
  test("transBlock: CPS ValDef + ControlContext tail → flatMap chain") {
    val cc1 = mkCcTree()
    val vd = mkCpsValDef("$cps0", cc1)
    val cc2 = mkCcTree()
    val result = phase.transBlock(List(vd), cc2, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), "result should be ControlContext via flatMap")
  }
  test("transBlock: lowered pattern val with CPS rhs maps the whole binder tail once") {
    val tupleType = requiredClass("scala.Tuple2").typeRef.appliedTo(List(intType, intType))
    val tmpSym = newSymbol(owner, termName("pattern$tmp"), Flags.Synthetic, tupleType).asTerm
    val tmpVal = tpd.ValDef(tmpSym, mkCcTree())(using ctx)
    val aSym = newSymbol(owner, termName("a"), Flags.Synthetic, intType).asTerm
    val bSym = newSymbol(owner, termName("b"), Flags.Synthetic, intType).asTerm
    val aVal = tpd.ValDef(aSym, tpd.Select(tpd.ref(tmpSym), nme.selectorName(0)))(using ctx)
    val bVal = tpd.ValDef(bSym, tpd.Select(tpd.ref(tmpSym), nme.selectorName(1)))(using ctx)
    val tail = tpd.Apply(tpd.Select(tpd.ref(aSym), nme.ADD), List(tpd.ref(bSym)))(using ctx)
    val result = phase.transBlock(List(tmpVal, aVal, bVal), tail, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), "result should stay in ControlContext")
    assert(
      treeExists(result) {
        case id: Ident => id.name.toString == "pattern$tmp"
        case _ => false
      },
      s"mapped body should still bind through the lowered temp, got $result"
    )
  }
  test("mkShiftUnitR: wraps pure expr in ControlContext") {
    val expr = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(99))
    val result = phase.mkShiftUnitR(expr, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"shiftUnitR result should be ControlContext, got ${result.tpe.widen}"
    )
  }
  test("transformPackageDef: no CPS expr → no error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val pkg = tpd.PackageDef(mkPid(), List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1))))
    phase.transformPackageDef(pkg)
    assert(!freshReporter.hasErrors, "no CPS expr → no error")
  }
  test("transformPackageDef: leftover CPS expr → error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val badExpr = tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)), tpd.TypeTree(cfType))
    val pkg = tpd.PackageDef(mkPid(), List(badExpr))
    phase.transformPackageDef(pkg)
    assert(freshReporter.hasErrors, "leftover CPS expr should trigger error")
  }
  test("transformPackageDef: TypeTree with CPS type → no error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val typeTree = tpd.TypeTree(cfType)
    val pkg = tpd.PackageDef(mkPid(), List(typeTree))
    phase.transformPackageDef(pkg)
    assert(!freshReporter.hasErrors, "TypeTree should be excluded")
  }
  test("transformCpsExpr: CPS param ref → mapped ControlContext symbol") {
    val origSym = newSymbol(owner, termName("body"), Flags.Synthetic, cfType).asTerm
    val ccSym = newSymbol(owner, termName("body"), Flags.Synthetic, cpsTypeToCc(cfType)).asTerm
    val pm = Map[Symbol, Symbol](origSym -> ccSym)
    val ident = tpd.ref(origSym)
    val result = phase.transformCpsExpr(ident, pm)
    assert(result.symbol eq ccSym, s"should map to ccSym, got ${result.symbol}")
    assert(isControlContextType(result.tpe.widen), s"result should be ControlContext, got ${result.tpe.widen}")
  }
  test("transformCpsExpr: mapped polymorphic CPS parameter preserves TypeApply+Apply chain") {
    val polyParamType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        defn.FunctionOf(
          List(a),
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)
        )
      }
    )
    val origSym = newSymbol(owner, termName("polyParam"), Flags.Synthetic, polyParamType).asTerm
    val mappedSym =
      newSymbol(owner, termName("polyParam"), Flags.Synthetic, transformCpsValueType(polyParamType)).asTerm
    val pm = Map[Symbol, Symbol](origSym -> mappedSym)
    val call = tpd.Apply(
      tpd.TypeApply(tpd.ref(origSym), List(tpd.TypeTree(intType))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(10)))
    )
    val result = phase.transformCpsExpr(call, pm)
    result match
      case Apply(TypeApply(fun: RefTree, _), _) =>
        assert(
          fun.symbol eq mappedSym,
          s"mapped poly parameter should stay at the base of the call chain, got ${fun.symbol}"
        )
      case other =>
        fail(s"expected rewritten Apply(TypeApply(...)), got ${other.getClass.getSimpleName}: $other")
  }
  test("transformCpsExpr: mapped curried polymorphic CPS parameter preserves apply chain") {
    val polyParamType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        val cpsLeaf = ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)
        val inner = defn.FunctionOf(List(a), cpsLeaf)
        defn.FunctionOf(List(intType), inner)
      }
    )
    val origSym = newSymbol(owner, termName("polyCurriedParam"), Flags.Synthetic, polyParamType).asTerm
    val mappedSym =
      newSymbol(owner, termName("polyCurriedParam"), Flags.Synthetic, transformCpsValueType(polyParamType)).asTerm
    val pm = Map[Symbol, Symbol](origSym -> mappedSym)
    val firstApply = tpd.Apply(
      tpd.TypeApply(tpd.ref(origSym), List(tpd.TypeTree(intType))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)))
    )
    val secondApply = tpd.Apply(firstApply, List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(2))))
    val result = phase.transformCpsExpr(secondApply, pm)
    result match
      case Apply(Apply(TypeApply(fun: RefTree, _), _), List(_)) =>
        assert(
          fun.symbol eq mappedSym,
          s"mapped curried poly parameter should stay at the base of the call chain, got ${fun.symbol}"
        )
      case other =>
        fail(s"expected rewritten curried Apply(Apply(TypeApply(...))), got ${other.getClass.getSimpleName}: $other")
  }
  test(
    "transformApply: actual lowered local polymorphic CPS val apply[T](x).apply(ctx) rewrites to transformed apply helper"
  ) {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLoweredLocalPolyApply"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val loweredPolyValueType = mkLoweredPolyValueType(using freshCtx)
    val loweredPolyApplyType = mkLoweredPolyApplyType(using freshCtx)
    val loweredApplySym = {
      given Context = freshCtx
      newSymbol(outerSym, nme.apply, Flags.Method | Flags.Synthetic, loweredPolyApplyType).asTerm
    }
    val loweredApplyDef = tpd.DefDef(
      loweredApplySym,
      _ => tpd.ref(defn.Predef_undefined).ensureConforms(loweredApplySym.info.finalResultType)
    )(using freshCtx)
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("polyLowered"), Flags.Synthetic, loweredPolyValueType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Block(
        List(loweredApplyDef),
        tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(loweredPolyValueType))
      )(using freshCtx),
      inferred = false
    )(using freshCtx)
    val pkg = tpd.PackageDef(
      mkPid()(using freshCtx),
      List(
        tpd.DefDef(outerSym, _ => tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))))(
          using freshCtx
        )
      )
    )(using freshCtx)
    phase.prepareForUnit(pkg)(using freshCtx)
    val loweredCall = tpd.Apply(
      tpd.TypeApply(tpd.ref(localValSym).select(nme.apply), List(tpd.TypeTree(intType(using freshCtx)))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(10)))
    )(using freshCtx)
    val cpsCtxSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("$ctxPolyLowered"), Flags.Synthetic, cpsType(using freshCtx)).asTerm
    }
    val applyTree = tpd.Apply(
      loweredCall.select(loweredCall.tpe.dealias.typeSymbol.requiredMethod("apply")),
      List(tpd.ref(cpsCtxSym))
    )(using freshCtx)
    val result = phase.transformApply(applyTree)(using freshCtx)
    assert(
      isControlContextType(result.tpe.widen),
      s"actual lowered poly call should rewrite to ControlContext, got ${result.tpe.widen}"
    )
    result match
      case Apply(TypeApply(Select(base: RefTree, selName), _), _) =>
        assert(
          base.name.toString.startsWith("polyLowered$transformed"),
          s"rewritten lowered poly call should target transformed sibling, got ${base.name}"
        )
        assertEquals(
          selName.toString,
          "apply$transformed",
          s"rewritten lowered poly call should target apply$$transformed, got $selName"
        )
      case other =>
        fail(s"expected rewritten lowered Apply(TypeApply(Select(...))), got ${other.getClass.getSimpleName}: $other")
    assert(
      !treeExists(result) {
        case id: Ident => id.symbol eq cpsCtxSym
        case _ => false
      },
      s"context apply should be eliminated from lowered poly call, got $result"
    )
  }
  test(
    "transformApply: actual lowered aliased local polymorphic CPS val apply[T](x).apply(ctx) rewrites through alias transformed value"
  ) {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLoweredAliasPolyApply"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val loweredPolyValueType = mkLoweredPolyValueType(using freshCtx)
    val loweredPolyApplyType = mkLoweredPolyApplyType(using freshCtx)
    val loweredApplySym = {
      given Context = freshCtx
      newSymbol(outerSym, nme.apply, Flags.Method | Flags.Synthetic, loweredPolyApplyType).asTerm
    }
    val loweredApplyDef = tpd.DefDef(
      loweredApplySym,
      _ => tpd.ref(defn.Predef_undefined).ensureConforms(loweredApplySym.info.finalResultType)
    )(using freshCtx)
    val polySym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("polyAliasSource"), Flags.Synthetic, loweredPolyValueType).asTerm
    }
    val polyVal = tpd.ValDef(
      polySym,
      tpd.Block(
        List(loweredApplyDef),
        tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(loweredPolyValueType))
      )(using freshCtx),
      inferred = false
    )(using freshCtx)
    val aliasSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("polyAlias"), Flags.Synthetic, loweredPolyValueType).asTerm
    }
    val aliasVal = tpd.ValDef(aliasSym, tpd.ref(polySym), inferred = false)(using freshCtx)
    val pkg = tpd.PackageDef(
      mkPid()(using freshCtx),
      List(
        tpd.DefDef(
          outerSym,
          _ => tpd.Block(List(polyVal, aliasVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
        )(using freshCtx)
      )
    )(using freshCtx)
    phase.prepareForUnit(pkg)(using freshCtx)
    val loweredCall = tpd.Apply(
      tpd.TypeApply(tpd.ref(aliasSym).select(nme.apply), List(tpd.TypeTree(intType(using freshCtx)))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(10)))
    )(using freshCtx)
    val cpsCtxSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("$ctxPolyAlias"), Flags.Synthetic, cpsType(using freshCtx)).asTerm
    }
    val applyTree = tpd.Apply(
      loweredCall.select(loweredCall.tpe.dealias.typeSymbol.requiredMethod("apply")),
      List(tpd.ref(cpsCtxSym))
    )(using freshCtx)
    val result = phase.transformApply(applyTree)(using freshCtx)
    assert(
      isControlContextType(result.tpe.widen),
      s"actual lowered aliased poly call should rewrite to ControlContext, got ${result.tpe.widen}"
    )
    result match
      case Apply(TypeApply(Select(base: RefTree, selName), _), _) =>
        assert(
          base.name.toString.startsWith("polyAlias$transformed"),
          s"rewritten lowered alias poly call should target alias transformed value, got ${base.name}"
        )
        assertEquals(
          selName.toString,
          "apply",
          s"rewritten lowered alias poly call should keep apply on transformed alias value, got $selName"
        )
      case other =>
        fail(
          s"expected rewritten lowered alias Apply(TypeApply(Select(...))), got ${other.getClass.getSimpleName}: $other"
        )
    assert(
      !treeExists(result) {
        case id: Ident => id.symbol eq cpsCtxSym
        case _ => false
      },
      s"context apply should be eliminated from lowered aliased poly call, got $result"
    )
  }
  test("transformCpsExpr: shift call → shift$transformed call") {
    val fType = defn.FunctionOf(List(defn.FunctionOf(List(intType), intType)), intType)
    val fSym = newSymbol(owner, termName("f"), Flags.Synthetic, fType).asTerm
    val shiftCall = ref(shiftMethod).appliedToTypes(List(intType, intType)).appliedTo(ref(fSym))
    val result = phase.transformCpsExpr(shiftCall, Map.empty)
    val innerFun = result match
      case Apply(TypeApply(fun, _), _) => fun
      case other => fail(s"expected Apply(TypeApply(...)), got ${other.getClass.getSimpleName}: $other")
    assert(innerFun.symbol eq shiftTransformedMethod, s"fun should be shift$$transformed, got ${innerFun.symbol}")
    assert(isControlContextType(result.tpe.widen), s"result should be ControlContext, got ${result.tpe.widen}")
  }
  test("transformCpsExpr: reset(block) → reset$transformed(ControlContext body)") {
    val cpsCtxType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType = MethodType(List(termName("$ctx")))(_ => List(cpsCtxType), _ => intType)
    val anonfunSym = newSymbol(owner, termName("$anonfun"), Flags.Synthetic | Flags.Method, anonfunType).asTerm
    val cc = mkCcTree()
    val anonfunDef = tpd.DefDef(anonfunSym, _ => cc)
    val closureTree = tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.TypeTree(cfType))
    val fnBlock = tpd.Block(List(anonfunDef), closureTree)
    val resetCall = Apply(ref(resetSymbol), List(fnBlock))
    val result = phase.transformCpsExpr(resetCall, Map.empty)
    result match
      case Apply(fun, List(body)) =>
        assert(fun.symbol eq resetTransformedMethod, s"fun should be reset$$transformed, got ${fun.symbol}")
        assert(isControlContextType(body.tpe.widen), s"body should be ControlContext, got ${body.tpe.widen}")
      case other => fail(s"expected Apply(...), got ${other.getClass.getSimpleName}: $other")
  }

  /** reset呼び出し相当の Block+Closure を構築するヘルパー。 Closure の tpt に cfType を明示して Block.tpe = CpsTransform[Int] ?=> Int にする。
    */
  test("transformPackageDef: reset call with CPS arg (legal) → no error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val resetCall = mkResetCall(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))
    val pkg = tpd.PackageDef(mkPid(), List(resetCall))
    phase.transformPackageDef(pkg)
    assert(!freshReporter.hasErrors, "reset CPS arg is legal, should not trigger error")
  }
  test("transformPackageDef: DefDef with CPS return type (cross-method, legal) → no error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val m0Sym =
      newSymbol(freshCtx.definitions.EmptyPackageClass, termName("m0"), Flags.Synthetic | Flags.Method, cfType).asTerm
    val body = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))(using freshCtx)
    val m0Def = tpd.DefDef(m0Sym, _ => body)
    val pkg = tpd.PackageDef(mkPid(), List(m0Def))
    phase.transformPackageDef(pkg)
    assert(!freshReporter.hasErrors, "CPS return type DefDef body is legal")
  }
  test("transformPackageDef: nested reset calls (legal) → no error") {
    val freshReporter = new dotty.tools.dotc.reporting.StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val inner = mkResetCall(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))(using freshCtx)
    val outer = mkResetCall(inner)(using freshCtx)
    val pkg = tpd.PackageDef(mkPid(), List(outer))
    phase.transformPackageDef(pkg)
    assert(!freshReporter.hasErrors, "nested reset calls are legal")
  }
  test("transformCpsRhs: unwraps Apply(Select(cpsExpr, _), _) and delegates to transformCpsExpr") {
    val fType = defn.FunctionOf(List(defn.FunctionOf(List(intType), intType)), intType)
    val fSym = newSymbol(owner, termName("f"), Flags.Synthetic, fType).asTerm
    val shiftCall = ref(shiftMethod).appliedToTypes(List(intType, intType)).appliedTo(ref(fSym))
    assert(isCpsTransformFunctionType(shiftCall.tpe), s"shiftCall.tpe should be CPS fn type, got ${shiftCall.tpe}")
    val cpsCtxSym = newSymbol(owner, termName("$ctx"), Flags.Synthetic, cpsType).asTerm
    val applySel = shiftCall.select(shiftCall.tpe.dealias.typeSymbol.requiredMethod("apply"))
    val rhsTree = Apply(applySel, List(ref(cpsCtxSym)))
    val result = phase.transformCpsRhs(rhsTree, Map.empty, intType)
    assert(isControlContextType(result.tpe.widen), s"result should be ControlContext, got ${result.tpe.widen}")
  }

  /** shift(f) を ANF が生成する Apply(Select(cfn, "apply"), [ctx]) 形式で構築するヘルパー */
  test("transformCpsRhs: If with both CPS branches → ControlContext If") {
    val cond = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(true))
    val thenCps = mkCpsApplyExpr()
    val elseCps = mkCpsApplyExpr()
    val ifTree = tpd.If(cond, thenCps, elseCps)
    val result = phase.transformCpsRhs(ifTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"If with CPS branches should produce ControlContext, got ${result.tpe.widen}"
    )
    result match
      case If(_, t, e) =>
        assert(isControlContextType(t.tpe.widen), s"then branch should be ControlContext, got ${t.tpe.widen}")
        assert(isControlContextType(e.tpe.widen), s"else branch should be ControlContext, got ${e.tpe.widen}")
      case other => fail(s"expected If, got ${other.getClass.getSimpleName}")
  }
  test("transformCpsRhs: If with one pure branch → pure branch lifted with shiftUnitR") {
    val cond = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(true))
    val thenCps = mkCpsApplyExpr()
    val elsePure = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42))
    val ifTree = tpd.If(cond, thenCps, elsePure)
    val result = phase.transformCpsRhs(ifTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"If with one pure branch should produce ControlContext, got ${result.tpe.widen}"
    )
    result match
      case If(_, _, e) =>
        assert(isControlContextType(e.tpe.widen), s"pure else should be lifted to ControlContext, got ${e.tpe.widen}")
      case other => fail(s"expected If, got ${other.getClass.getSimpleName}")
  }
  test("transformCpsRhs: Match with CPS case body → ControlContext Match") {
    val sel = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1))
    val caseSym = newSymbol(owner, termName("_"), Flags.Synthetic, intType).asTerm
    val caseBody = mkCpsApplyExpr()
    val cd = tpd.CaseDef(tpd.Underscore(intType), tpd.EmptyTree, caseBody)
    val matchTree = tpd.Match(sel, List(cd))
    val result = phase.transformCpsRhs(matchTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"Match with CPS body should produce ControlContext, got ${result.tpe.widen}"
    )
  }
  test("transformCpsRhs: Typed CPS expr unwraps to ControlContext") {
    val tupleType = requiredClass("scala.Tuple2").typeRef.appliedTo(List(intType, intType))
    val ccTupleType = controlContextClass.typeRef.appliedTo(List(tupleType, intType))
    val ccSel = tpd.ref(newSymbol(owner, termName("$typedCcTuple"), Flags.Synthetic, ccTupleType).asTerm)
    val typedTree = tpd.Typed(ccSel, tpd.TypeTree(tupleType))
    val result = phase.transformCpsRhs(typedTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"Typed CPS expr should unwrap to ControlContext, got ${result.tpe.widen}"
    )
  }
  test("transformCpsRhs: Try with CPS body → ControlContext Try") {
    val tryBody = mkCpsApplyExpr()
    val exSym = newSymbol(owner, termName("_"), Flags.Synthetic, ctx.definitions.ThrowableType).asTerm
    val catchBody = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))
    val catchCase = tpd.CaseDef(tpd.Underscore(ctx.definitions.ThrowableType), tpd.EmptyTree, catchBody)
    val tryTree = tpd.Try(tryBody, List(catchCase), tpd.EmptyTree)
    val result = phase.transformCpsRhs(tryTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"Try with CPS body should produce ControlContext, got ${result.tpe.widen}"
    )
  }
  test("transTailValue: raw CPS Apply in tail → shift$transformed ControlContext") {
    val cpsApply = mkCpsApplyExpr()
    val result = phase.transTailValue(cpsApply, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"raw CPS Apply in tail should produce ControlContext, got ${result.tpe.widen}"
    )
    result match
      case Apply(TypeApply(fun, _), _) =>
        assert(fun.symbol eq shiftTransformedMethod, s"should call shift$$transformed, got ${fun.symbol}")
      case other =>
        fail(s"expected Apply(TypeApply(shift$$transformed,...)), got ${other.getClass.getSimpleName}: $other")
  }
  test("transTailValue: If with CPS branches in tail position → ControlContext If") {
    val cond = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(true))
    val thenCps = mkCpsApplyExpr()
    val elsePure = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))
    val ifTree = tpd.If(cond, thenCps, elsePure)
    val result = phase.transTailValue(ifTree, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"If with CPS branches in tail should produce ControlContext, got ${result.tpe.widen}"
    )
  }
  test("transformCpsRhs: Block with @CpsSym ValDef → transBlock result is ControlContext") {
    val cc = mkCcTree()
    val innerVd = mkCpsValDef("$cps0", cc)
    val tailExpr = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42))
    val blockRhs = tpd.Block(List(innerVd), tailExpr)
    val result = phase.transformCpsRhs(blockRhs, Map.empty, intType)
    assert(
      isControlContextType(result.tpe.widen),
      s"Block with @CpsSym inside should produce ControlContext via transBlock, got ${result.tpe.widen}"
    )
  }
