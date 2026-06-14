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

class SelectiveCPSTransformApplyRewriteSuite extends munit.FunSuite with SelectiveCPSTransformSuiteBase:

  test("transformApply: no CPS args → returned unchanged") {
    val xSym = newSymbol(owner, termName("x"), Flags.Synthetic, intType).asTerm
    val pureSym = newSymbol(
      owner,
      termName("pure"),
      Flags.Synthetic,
      MethodType(List(termName("a")))(_ => List(intType), _ => intType)
    ).asTerm
    val call = tpd.Apply(tpd.ref(pureSym), List(tpd.ref(xSym)))
    val result = phase.transformApply(call)
    assert(result eq call, "no CPS args should return the same tree unchanged")
  }

  test("transformApply: cps result applied to context arg → unwraps to transformed call") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => cfType)
    val methSym = newSymbol(owner, termName("m_both_apply"), Flags.Method, methType).asTerm
    val stubSym = new SelectiveCPSStubPhase()
      .mkTransformedStub(tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined)))
      .symbol
      .asTerm
    stubSym.entered
    val bodyArg = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(5)))
    val cpsCall = tpd.Apply(tpd.ref(methSym), List(bodyArg))
    val cpsCtxSym = newSymbol(owner, termName("$ctx"), Flags.Synthetic, cpsType).asTerm
    val applyTree = Apply(cpsCall.select(cpsCall.tpe.dealias.typeSymbol.requiredMethod("apply")), List(ref(cpsCtxSym)))

    val result = phase.transformApply(applyTree)

    assert(
      isControlContextType(result.tpe.widen),
      s"cps result application should rewrite to ControlContext, got ${result.tpe.widen}"
    )
    assert(
      treeExists(result)(_.symbol eq stubSym),
      s"rewritten tree should call generated stub ${stubSym.name}, got $result"
    )
  }

  test("transformApply: local CPS arg + CPS result applied to context arg → local transformed call") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalBoth"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => cfType)(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerLocalBoth"), Flags.Method, methType).asTerm
    val bodyArg = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(5)))(using freshCtx)
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx)
    val cpsCall = tpd.Apply(tpd.ref(methSym), List(bodyArg))(using freshCtx)
    val cpsCtxSym = newSymbol(outerSym, termName("$ctxLocalBoth"), Flags.Synthetic, cpsType).asTerm
    val applyTree = Apply(cpsCall.select(cpsCall.tpe.dealias.typeSymbol.requiredMethod("apply")), List(ref(cpsCtxSym)))
    phase.prepareForUnit(tpd.Block(List(local), applyTree))(using freshCtx)

    val result = phase.transformApply(applyTree)(using freshCtx)

    assert(
      isControlContextType(result.tpe.widen),
      s"local cps result application should rewrite to ControlContext, got ${result.tpe.widen}"
    )
    assert(
      treeExists(result)(_.symbol.name.toString.startsWith("innerLocalBoth$transformed")),
      s"rewritten tree should call local transformed helper, got $result"
    )
  }

  test("transformApply: local CPS arg call rewrites to local transformed helper without immediate apply") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalArg"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerLocalArg"), Flags.Method, methType).asTerm
    val bodyArg = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(5)))(using freshCtx)
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx)
    val call = tpd.Apply(tpd.ref(methSym), List(bodyArg))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(local), call))(using freshCtx)

    val result = phase.transformApply(call)(using freshCtx)

    assert(
      treeExists(result)(_.symbol.name.toString.startsWith("innerLocalArg$transformed")),
      s"rewritten tree should call local transformed helper, got $result"
    )
    assert(
      treeExists(result) { case tree =>
        isControlContextType(tree.tpe.widen)
      },
      s"rewritten tree should pass ControlContext arg, got $result"
    )
  }

  test("transformApply: local CPS val forwarded as function value rewrites argument to transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalValForward"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val consumeType =
      MethodType(List(termName("f")))(_ => List(localValType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym =
      newSymbol(owner(using freshCtx), termName("consumeLocalValForward"), Flags.Method, consumeType).asTerm
    val consumeStub = new SelectiveCPSStubPhase()
      .mkTransformedStub(tpd.DefDef(consumeSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx))(using freshCtx)
      .symbol
      .asTerm
    val localValSym = newSymbol(outerSym, termName("innerForward"), Flags.Synthetic, localValType).asTerm
    val localVal =
      tpd.ValDef(localValSym, tpd.ref(defn.Predef_undefined).ensureConforms(localValType), inferred = false)(using
        freshCtx
      )
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(localValSym)))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(localVal), call)(using freshCtx))(using freshCtx)

    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun: RefTree, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeStub, s"callee should be transformed stub, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("innerForward$transformed"),
          s"forwarded local CPS val should be transformed sibling, got ${arg.name}"
        )
        val transformedParamType = consumeStub.info.asInstanceOf[MethodType].paramInfos.head
        assert(arg.tpe <:< transformedParamType, s"forwarded arg ${arg.tpe} should conform to $transformedParamType")
        assert(!treeExists(result)(_.symbol eq localValSym), s"raw local CPS val symbol should not remain, got $result")
      case other =>
        fail(s"expected transformed Apply with one forwarded arg, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformApply: typed alias chain forwards transformed local CPS val argument") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerAliasForward"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val consumeType =
      MethodType(List(termName("f")))(_ => List(localValType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym = newSymbol(owner(using freshCtx), termName("consumeAliasForward"), Flags.Method, consumeType).asTerm
    val consumeStub = new SelectiveCPSStubPhase()
      .mkTransformedStub(tpd.DefDef(consumeSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx))(using freshCtx)
      .symbol
      .asTerm
    val innerSym = newSymbol(outerSym, termName("innerAliasForward"), Flags.Synthetic, localValType).asTerm
    val alias1Sym = newSymbol(outerSym, termName("aliasForward1"), Flags.Synthetic, localValType).asTerm
    val alias2Sym = newSymbol(outerSym, termName("aliasForward2"), Flags.Synthetic, localValType).asTerm
    val innerVal =
      tpd.ValDef(innerSym, tpd.ref(defn.Predef_undefined).ensureConforms(localValType), inferred = false)(using
        freshCtx
      )
    val alias1Val = tpd.ValDef(alias1Sym, tpd.ref(innerSym), inferred = false)(using freshCtx)
    val alias2Val = tpd.ValDef(alias2Sym, tpd.ref(alias1Sym), inferred = false)(using freshCtx)
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(alias2Sym)))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(innerVal, alias1Val, alias2Val), call)(using freshCtx))(using freshCtx)

    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun: RefTree, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeStub, s"callee should be transformed stub, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("aliasForward2$transformed"),
          s"forwarded alias should use transformed alias sibling, got ${arg.name}"
        )
        val transformedParamType = consumeStub.info.asInstanceOf[MethodType].paramInfos.head
        assert(arg.tpe <:< transformedParamType, s"forwarded alias ${arg.tpe} should conform to $transformedParamType")
        assert(!treeExists(result)(_.symbol eq alias2Sym), s"raw alias CPS val symbol should not remain, got $result")
      case other =>
        fail(s"expected transformed Apply with one forwarded alias arg, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformApply: local lowered poly CPS val forwards with transformed formal-compatible type") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(baseOwner, termName("outerPolyForward"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val loweredPolyValueType = mkLoweredPolyValueType(using freshCtx)
    val loweredPolyApplyType = mkLoweredPolyApplyType(using freshCtx)
    val consumeType =
      MethodType(List(termName("f")))(_ => List(loweredPolyValueType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym = newSymbol(baseOwner, termName("consumePolyForward"), Flags.Method, consumeType).asTerm
    val consumeStub = new SelectiveCPSStubPhase()
      .mkTransformedStub(tpd.DefDef(consumeSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx))(using freshCtx)
      .symbol
      .asTerm
    val loweredApplySym = newSymbol(outerSym, nme.apply, Flags.Method | Flags.Synthetic, loweredPolyApplyType).asTerm
    val loweredApplyDef = tpd.DefDef(
      loweredApplySym,
      _ => tpd.ref(defn.Predef_undefined).ensureConforms(loweredApplySym.info.finalResultType)
    )(using freshCtx)
    val polySym = newSymbol(outerSym, termName("polyForward"), Flags.Synthetic, loweredPolyValueType).asTerm
    val polyVal = tpd.ValDef(
      polySym,
      tpd.Block(
        List(loweredApplyDef),
        tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(loweredPolyValueType))
      )(using freshCtx),
      inferred = false
    )(using freshCtx)
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(polySym)))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(polyVal), call)(using freshCtx))(using freshCtx)

    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun: RefTree, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeStub, s"callee should be transformed stub, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("polyForward$transformed"),
          s"forwarded poly val should use transformed sibling, got ${arg.name}"
        )
        val transformedParamType = consumeStub.info.asInstanceOf[MethodType].paramInfos.head
        assert(
          arg.tpe <:< transformedParamType,
          s"forwarded poly arg ${arg.tpe} should conform to $transformedParamType"
        )
        assert(
          !treeExists(result)(_.symbol eq polySym),
          s"raw poly local CPS val symbol should not remain, got $result"
        )
      case other =>
        fail(s"expected transformed Apply with one forwarded poly arg, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformApply: local lowered curried poly CPS val forwards with transformed formal-compatible type") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(baseOwner, termName("outerCurriedPolyForward"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val loweredPolyValueType = mkLoweredCurriedPolyValueType(using freshCtx)
    val loweredPolyApplyType = mkLoweredCurriedPolyApplyType(using freshCtx)
    val consumeType =
      MethodType(List(termName("f")))(_ => List(loweredPolyValueType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym = newSymbol(baseOwner, termName("consumeCurriedPolyForward"), Flags.Method, consumeType).asTerm
    val consumeStub = new SelectiveCPSStubPhase()
      .mkTransformedStub(tpd.DefDef(consumeSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx))(using freshCtx)
      .symbol
      .asTerm
    val loweredApplySym = newSymbol(outerSym, nme.apply, Flags.Method | Flags.Synthetic, loweredPolyApplyType).asTerm
    val loweredApplyDef = tpd.DefDef(
      loweredApplySym,
      _ => tpd.ref(defn.Predef_undefined).ensureConforms(loweredApplySym.info.finalResultType)
    )(using freshCtx)
    val polySym = newSymbol(outerSym, termName("curriedPolyForward"), Flags.Synthetic, loweredPolyValueType).asTerm
    val polyVal = tpd.ValDef(
      polySym,
      tpd.Block(
        List(loweredApplyDef),
        tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(loweredPolyValueType))
      )(using freshCtx),
      inferred = false
    )(using freshCtx)
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(polySym)))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(polyVal), call)(using freshCtx))(using freshCtx)

    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun: RefTree, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeStub, s"callee should be transformed stub, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("curriedPolyForward$transformed"),
          s"forwarded curried poly val should use transformed sibling, got ${arg.name}"
        )
        val transformedParamType = consumeStub.info.asInstanceOf[MethodType].paramInfos.head
        assert(
          arg.tpe <:< transformedParamType,
          s"forwarded curried poly arg ${arg.tpe} should conform to $transformedParamType"
        )
        assert(
          !treeExists(result)(_.symbol eq polySym),
          s"raw curried poly local CPS val symbol should not remain, got $result"
        )
      case other =>
        fail(
          s"expected transformed Apply with one forwarded curried poly arg, got ${other.getClass.getSimpleName}: $other"
        )
  }

  test(
    "transformCpsExpr: local CPS arg + CPS result call rewrites to local transformed helper without immediate apply"
  ) {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalStored"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => cfType)(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerLocalStored"), Flags.Method, methType).asTerm
    val bodyArg = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(8)))(using freshCtx)
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx)
    val call = tpd.Apply(tpd.ref(methSym), List(bodyArg))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(local), call))(using freshCtx)

    val result = phase.transformCpsExpr(call, Map.empty)(using freshCtx)

    assert(
      isControlContextType(result.tpe.widen),
      s"stored local cps-result call should rewrite to ControlContext, got ${result.tpe.widen}"
    )
    assert(
      treeExists(result)(_.symbol.name.toString.startsWith("innerLocalStored$transformed")),
      s"rewritten tree should call local transformed helper, got $result"
    )
  }

  test("transformStats: local CPS val alias over local CPS method rewrites transformed rhs to local helper") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalMethodAlias"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("x")))(_ => List(intType), _ => cfType)(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerAliasMethod"), Flags.Method, methType).asTerm
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined).ensureConforms(cfType))(using freshCtx)

    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val aliasSym = newSymbol(outerSym, termName("methodAlias"), Flags.Synthetic, localValType).asTerm
    val aliasVal = tpd.ValDef(aliasSym, tpd.ref(methSym), inferred = false)(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(local, aliasVal), tpd.ref(defn.Predef_undefined))(using freshCtx))(using
      freshCtx
    )
    val result = phase.transformStats(List(local, aliasVal))(using freshCtx)
    val transformedAlias = result
      .collectFirst { case vd: ValDef if vd.name.toString.startsWith("methodAlias$transformed") => vd }
      .getOrElse(fail(s"expected transformed alias val, got $result"))

    assert(
      treeExists(transformedAlias.rhs)(_.symbol.name.toString.startsWith("innerAliasMethod$transformed")),
      s"method alias transformed rhs should point at local helper, got ${transformedAlias.rhs}"
    )
  }

  test("mkTransformedImpl: CPS function value parameter identity returns mapped transformed parameter") {
    val returnedFunType = defn.FunctionOf(List(intType), cfType)(using ctx)
    val methType = MethodType(List(termName("f")))(_ => List(returnedFunType), _ => returnedFunType)
    val methSym = newSymbol(owner, termName("idFunctionValue"), Flags.Method, methType).asTerm
    val paramSym = methSym.paramSymss.head.head.asTerm
    val orig = tpd.DefDef(methSym, _ => tpd.ref(paramSym))
    val transformedSym = newSymbol(
      owner,
      termName("idFunctionValue$transformed"),
      Flags.Method | Flags.Synthetic,
      transformCpsMethodType(methSym.info),
      coord = methSym.coord
    ).asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)

    assert(
      !treeExists(result.rhs)(_.symbol eq paramSym),
      s"raw CPS function value parameter should not remain in transformed rhs, got ${result.rhs}"
    )
    assert(
      treeExists(result.rhs)(_.symbol.name == paramSym.name),
      s"transformed rhs should reference the remapped parameter, got ${result.rhs}"
    )
  }

  test("transformStats: if branches holding local CPS method value rewrite to local helper") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerIfMethodAlias"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("x")))(_ => List(intType), _ => cfType)(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerIfAliasMethod"), Flags.Method, methType).asTerm
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined).ensureConforms(cfType))(using freshCtx)

    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val aliasSym = newSymbol(outerSym, termName("ifMethodAlias"), Flags.Synthetic, localValType).asTerm
    val condSym = newSymbol(outerSym, termName("cond"), Flags.Synthetic, defn.BooleanType).asTerm
    val aliasVal =
      tpd.ValDef(aliasSym, tpd.If(tpd.ref(condSym), tpd.ref(methSym), tpd.ref(methSym)), inferred = false)(using
        freshCtx
      )

    phase.prepareForUnit(tpd.Block(List(local, aliasVal), tpd.ref(defn.Predef_undefined))(using freshCtx))(using
      freshCtx
    )
    val result = phase.transformStats(List(local, aliasVal))(using freshCtx)
    val transformedAlias = result
      .collectFirst { case vd: ValDef if vd.name.toString.startsWith("ifMethodAlias$transformed") => vd }
      .getOrElse(fail(s"expected transformed if alias val, got $result"))

    assert(
      treeExists(transformedAlias.rhs)(_.symbol.name.toString.startsWith("innerIfAliasMethod$transformed")),
      s"if branch aliases should point at local helper, got ${transformedAlias.rhs}"
    )
    assert(
      !treeExists(transformedAlias.rhs)(_.symbol eq methSym),
      s"raw local method should not remain in transformed if alias rhs, got ${transformedAlias.rhs}"
    )
  }

  test("transformCpsExpr: partial application of local CPS method rewrites base to local helper") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalPartial"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val methType = MethodType(List(termName("x")))(
      _ => List(intType),
      _ => MethodType(List(termName("y")))(_ => List(intType), _ => cfType)
    )(using freshCtx)
    val methSym = newSymbol(outerSym, termName("innerPartial"), Flags.Method, methType).asTerm
    val local = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx)
    val partial =
      tpd.Apply(tpd.ref(methSym), List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1))))(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(local), partial)(using freshCtx))(using freshCtx)

    val result = phase.transformCpsExpr(partial, Map.empty)(using freshCtx)

    assert(
      treeExists(result)(_.symbol.name.toString.startsWith("innerPartial$transformed")),
      s"partial application should call local transformed helper, got $result"
    )
  }

  test("lookupTransformedSym: local plan fallback does not cross same-shape owner scope") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerFallbackScope"), Flags.Method, outerType).asTerm
    val outerCtx = ctx.withOwner(outerSym)
    val midType = MethodType(Nil)(_ => Nil, _ => intType)(using outerCtx)
    val midSym = newSymbol(outerSym, termName("midFallbackScope"), Flags.Method, midType).asTerm
    val midCtx = outerCtx.withOwner(midSym)
    val methType = MethodType(List(termName("x")))(_ => List(intType), _ => cfType)(using midCtx)
    val innerSym = newSymbol(midSym, termName("innerFallbackScope"), Flags.Method, methType).asTerm
    val inner = tpd.DefDef(innerSym, _ => tpd.ref(defn.Predef_undefined).ensureConforms(cfType))(using midCtx)

    phase.prepareForUnit(
      tpd.Block(
        List(tpd.Block(List(inner), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))(using midCtx)),
        tpd.ref(defn.Predef_undefined)
      )(using outerCtx)
    )(using outerCtx)

    val sameShapeWrongOwner =
      newSymbol(outerSym, termName("innerFallbackScope"), Flags.Method, methType, coord = innerSym.coord).asTerm
    val wrongOwnerRef = tpd.ref(sameShapeWrongOwner)(using outerCtx)

    val result = phase.transformCpsExpr(wrongOwnerRef, Map.empty)(using outerCtx)

    assert(
      !treeExists(result)(_.symbol.name.toString.startsWith("innerFallbackScope$transformed")),
      s"fallback lookup must not reuse a local plan from another owner, got $result"
    )
    assert(result.symbol eq sameShapeWrongOwner, s"unplanned same-shape symbol should remain unchanged, got $result")
  }

  test("transformStats: nested local CPS methods emit helpers for each owner scope") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerNested"), Flags.Method, outerType).asTerm
    val outerCtx = ctx.withOwner(outerSym)

    val midType = MethodType(Nil)(_ => Nil, _ => intType)(using outerCtx)
    val midSym = newSymbol(outerSym, termName("mid"), Flags.Method, midType).asTerm
    val midCtx = outerCtx.withOwner(midSym)

    val innerType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)(using midCtx)
    val innerSym = newSymbol(midSym, termName("innerNested"), Flags.Method, innerType).asTerm
    val innerBodySym = innerSym.paramSymss.head.head.asTerm
    val inner = tpd.DefDef(
      innerSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(innerBodySym, ctxSym))(using midCtx)
    )(using midCtx)
    val nestedBlock = tpd.Block(List(inner), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))(using midCtx)

    phase.prepareForUnit(tpd.Block(List(nestedBlock), tpd.ref(defn.Predef_undefined))(using outerCtx))(using outerCtx)
    val nestedStats = phase.transformStats(nestedBlock.stats)(using midCtx)

    val helperNames = nestedStats.collect { case dd: DefDef if dd.symbol.is(Flags.Synthetic) => dd.name.toString }
    assertEquals(helperNames.size, 1)
    assert(
      helperNames.head.startsWith("innerNested$transformed"),
      s"nested local helper should be inserted in inner owner scope, got $helperNames"
    )
  }

  test("transformStats: multiple local CPS methods emit distinct transformed helpers") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerMany"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)

    val firstType = MethodType(List(termName("bodyA")))(_ => List(cfType), _ => intType)(using freshCtx)
    val firstSym = newSymbol(outerSym, termName("innerA"), Flags.Method, firstType).asTerm
    val firstBodySym = firstSym.paramSymss.head.head.asTerm
    val first = tpd.DefDef(
      firstSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(firstBodySym, ctxSym))(using freshCtx)
    )(using freshCtx)

    val secondType = MethodType(List(termName("bodyB")))(_ => List(cfType), _ => intType)(using freshCtx)
    val secondSym = newSymbol(outerSym, termName("innerB"), Flags.Method, secondType).asTerm
    val secondBodySym = secondSym.paramSymss.head.head.asTerm
    val second = tpd.DefDef(
      secondSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(secondBodySym, ctxSym))(using freshCtx)
    )(using freshCtx)

    val result = phase.transformStats(List(first, second))(using freshCtx)

    val helperNames = result.collect { case dd: DefDef if dd.symbol.is(Flags.Synthetic) => dd.name.toString }
    assertEquals(helperNames.size, 2)
    assert(helperNames(0).startsWith("innerA$transformed"), s"unexpected helper name: ${helperNames(0)}")
    assert(helperNames(1).startsWith("innerB$transformed"), s"unexpected helper name: ${helperNames(1)}")
  }

  test("transformStats: overloaded local CPS methods emit distinct transformed helpers") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerOverloaded"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)

    val firstType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)(using freshCtx)
    val firstSym = newSymbol(outerSym, termName("inner"), Flags.Method, firstType).asTerm
    val firstBodySym = firstSym.paramSymss.head.head.asTerm
    val first = tpd.DefDef(
      firstSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(firstBodySym, ctxSym))(using freshCtx)
    )(using freshCtx)

    val secondType = MethodType(List(termName("x")))(
      _ => List(intType),
      _ => MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    )(using freshCtx)
    val secondSym = newSymbol(outerSym, termName("inner"), Flags.Method, secondType).asTerm
    val second = tpd.DefDef(secondSym, _ => tpd.ref(defn.Predef_undefined))(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(first, second), tpd.ref(defn.Predef_undefined))(using freshCtx))(using freshCtx)
    val result = phase.transformStats(List(first, second))(using freshCtx)

    val helperNames = result.collect { case dd: DefDef if dd.symbol.is(Flags.Synthetic) => dd.name.toString }
    assertEquals(helperNames.distinct.size, 2, s"overloaded local helpers should get distinct names, got $helperNames")
  }

  // Test for transformApply — reset(Block+Closure) → reset$transformed(ControlContext)
  test("transformApply: reset(Block+Closure) → reset$transformed(ControlContext body)") {
    val resetCall = mkResetCall(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))
    val result = phase.transformApply(resetCall)
    result match
      case Apply(fun, List(body)) =>
        // fun は TypeApply(ref(reset$transformed), [Int]) または ref(reset$transformed)
        val funSym = fun match
          case TypeApply(inner, _) => inner.symbol
          case other => other.symbol
        assert(funSym eq resetTransformedMethod, s"fun should be reset$$transformed, got $funSym")
        assert(isControlContextType(body.tpe.widen), s"body should be ControlContext, got ${body.tpe.widen}")
      case other => fail(s"expected Apply(...), got ${other.getClass.getSimpleName}: $other")
  }

  // Test for transformApply — TypeApply ラッパーが保持される
  test("transformApply: TypeApply wrapper preserved in new fun") {
    val resetCall = mkResetCall(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
    val result = phase.transformApply(resetCall)
    result match
      case Apply(TypeApply(fun, targs), _) =>
        assert(fun.symbol eq resetTransformedMethod, s"TypeApply fun should be reset$$transformed, got ${fun.symbol}")
        assert(targs.nonEmpty, "type args should be preserved")
      case Apply(fun, _) =>
        fail(s"expected TypeApply wrapper, got plain ref: ${fun.symbol}")
      case other => fail(s"expected Apply, got ${other.getClass.getSimpleName}")
  }

  // Test for transformApply — $transformed シンボルなし → unchanged
  test("transformApply: no $transformed sym → unchanged") {
    // CPS引数型メソッドだが $transformed 版が存在しない synthetic method
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("orphan"), Flags.Method, methType).asTerm
    val bodyArg = mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
    val call = tpd.Apply(tpd.ref(methSym), List(bodyArg))
    val result = phase.transformApply(call)
    assert(result eq call, "no $transformed sym should return unchanged")
  }

  // Test for transformApply — transformCpsCallSiteArg の else branch（direct CPS expr）
  test("transformApply: transformCpsCallSiteArg with direct shift expr → shift$transformed") {
    // reset の引数として直接 shift 式（Block+Closure でない CPS 型 expr）を渡す
    // shift(k => k(1)) はすでに cfType を持つ Apply (TypeApply(ref(shift), [Int]), [f])
    val fType = defn.FunctionOf(List(defn.FunctionOf(List(intType), intType)), intType)
    val fSym = newSymbol(owner, termName("f"), Flags.Synthetic, fType).asTerm
    val shiftExpr = tpd.ref(shiftMethod).appliedToTypes(List(intType, intType)).appliedTo(tpd.ref(fSym))
    // shiftExpr の型は cfType（CpsTransform[Int] ?=> Int）
    assert(isCpsTransformFunctionType(shiftExpr.tpe), s"shiftExpr should have CPS fn type, got ${shiftExpr.tpe}")
    // reset[Int](shiftExpr) — mkResetArg を使わず直接 Apply を構築
    val resetCall = tpd.Apply(tpd.ref(resetSymbol).appliedToType(intType), List(shiftExpr))
    val result = phase.transformApply(resetCall)
    result match
      case Apply(fun, List(body)) =>
        val funSym = fun match
          case TypeApply(inner, _) => inner.symbol
          case other => other.symbol
        assert(funSym eq resetTransformedMethod, s"fun should be reset$$transformed, got $funSym")
        assert(
          isControlContextType(body.tpe.widen),
          s"transformed shift should be ControlContext, got ${body.tpe.widen}"
        )
      case other => fail(s"expected Apply, got ${other.getClass.getSimpleName}: $other")
  }

  // Test 18: reset block — TreeTypeMap でネスト DefDef オーナーが anonfunSym から更新される
  test("transformCpsExpr: reset(block) updates nested lambda owner away from anonfunSym") {
    val cpsCtxType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType2 = MethodType(List(termName("$ctx")))(_ => List(cpsCtxType), _ => intType)
    val anonfunSym2 = newSymbol(owner, termName("$anonfun2"), Flags.Synthetic | Flags.Method, anonfunType2).asTerm

    // anonfun2.rhs に inner2 lambda（owned by anonfunSym2）を埋め込む
    val inner2Type = MethodType(Nil)(_ => Nil, _ => intType)
    val inner2Sym = newSymbol(anonfunSym2, termName("$inner2"), Flags.Synthetic | Flags.Method, inner2Type).asTerm
    val inner2Def = tpd.DefDef(inner2Sym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
    val inner2Closure = tpd.Closure(Nil, tpd.ref(inner2Sym), tpd.EmptyTree)
    val anonfunBody2 = tpd.Block(List(inner2Def), inner2Closure)

    val anonfunDef2 = tpd.DefDef(anonfunSym2, _ => anonfunBody2)
    val closureTree2 = tpd.Closure(Nil, tpd.ref(anonfunSym2), tpd.TypeTree(cfType))
    val fnBlock2 = tpd.Block(List(anonfunDef2), closureTree2)
    val resetCall2 = Apply(ref(resetSymbol), List(fnBlock2))

    val result = phase.transformCpsExpr(resetCall2, Map.empty)

    // result ツリーを走査して "$inner2" DefDef のオーナーを確認
    var foundOwner2: Option[Symbol] = None
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString == "$inner2" =>
          foundOwner2 = Some(dd.symbol.owner)
        case _ => traverseChildren(t)
    }.traverse(result)

    foundOwner2 match
      case Some(o) =>
        assert(!(o eq anonfunSym2), s"owner must NOT be anonfunSym2 after TreeTypeMap (Bug 3 regression)")
      case None =>
        fail("inner DefDef '$inner2' not found in result tree")
  }
