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

class SelectiveCPSTransformLocalValueSuite extends munit.FunSuite with SelectiveCPSTransformSuiteBase:

  test("mkTransformedImpl: produces DefDef with transformedSym") {
    val orig = mkCpsReturnDef("m0")
    val stubPhase = new SelectiveCPSStubPhase()
    val stub = stubPhase.mkTransformedStub(orig)
    val transformedSym = stub.symbol.asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)

    assertEquals(result.name, stub.name, "DefDef name should match transformedSym")
    assert(result.symbol eq transformedSym, "DefDef symbol should be transformedSym")
  }

  test("transformStats: local CPS method emits sibling transformed stub") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outer"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val innerType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)(using freshCtx)
    val innerSym = newSymbol(outerSym, termName("inner"), Flags.Method, innerType).asTerm
    val bodySym = innerSym.paramSymss.head.head.asTerm
    val local = tpd.DefDef(
      innerSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(bodySym, ctxSym))(using freshCtx)
    )(using freshCtx)

    val result = phase.transformStats(List(local))(using freshCtx)

    assertEquals(result.length, 2, s"local CPS def should gain a sibling stub, got $result")
    val stub = result(0).asInstanceOf[DefDef]
    assert(
      stub.name.toString.startsWith("inner$transformed"),
      s"local helper name should use transformed prefix, got ${stub.name}"
    )
    val paramType = stub.symbol.info.asInstanceOf[MethodType].paramInfos.head
    assert(isControlContextType(paramType), s"local transformed stub param should be ControlContext, got $paramType")
    assert(
      treeExists(stub.rhs)(_.symbol eq resetTransformedMethod),
      s"local transformed stub should already have CPS body, got ${stub.rhs}"
    )
  }

  test("mkTransformedImpl: local transformed stub gets CPS implementation") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerImpl"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val bodyType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)(using freshCtx)
    val localSym = newSymbol(outerSym, termName("innerImpl"), Flags.Method, bodyType).asTerm
    val bodySym = localSym.paramSymss.head.head.asTerm
    val local = tpd.DefDef(
      localSym,
      _ => mkResetCallWithCtx(ctxSym => mkCpsParamApply(bodySym, ctxSym))(using freshCtx)
    )(using freshCtx)
    val stats = phase.transformStats(List(local))(using freshCtx)
    val stub = stats(0).asInstanceOf[DefDef]

    val result = phase.mkTransformedImpl(local, stub.symbol.asTerm)(using freshCtx)

    assert(
      treeExists(result.rhs)(_.symbol eq resetTransformedMethod),
      s"local transformed stub should call reset$$transformed, got ${result.rhs}"
    )
    assert(
      treeExists(result.rhs) {
        case ref: RefTree => isControlContextType(ref.tpe.widen)
        case _ => false
      },
      s"local transformed stub should use rewritten ControlContext params, got ${result.rhs}"
    )
  }

  test("transformStats: local CPS val emits sibling transformed value") {
    val baseIntType = ctx.definitions.IntType
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => baseIntType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLocalVal"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localLambdaType =
      MethodType(List(termName("x")))(_ => List(intType(using freshCtx)), _ => cfType(using freshCtx))(using freshCtx)
    val localLambdaSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("$anonfunLocalVal"), Flags.Synthetic | Flags.Method, localLambdaType).asTerm
    }
    val localLambdaDef = tpd.DefDef(
      localLambdaSym,
      _ => mkResetArg(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)))(using freshCtx)
    )(using freshCtx)
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("f"), Flags.Synthetic, localValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Block(List(localLambdaDef), tpd.Closure(Nil, tpd.ref(localLambdaSym), tpd.TypeTree(localValType))),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(
      tpd.PackageDef(
        mkPid()(using freshCtx),
        List(
          tpd.DefDef(
            outerSym,
            _ => tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
          )(using freshCtx)
        )
      )(using freshCtx)
    )(using freshCtx)
    val result = phase.transformStats(List(localVal))(using freshCtx)

    assertEquals(result.length, 1, s"local CPS val should emit only transformed value, got $result")
    val transformed = result.head.asInstanceOf[ValDef]
    assert(
      transformed.name.toString.startsWith("f$transformed"),
      s"transformed local val should use transformed prefix, got ${transformed.name}"
    )
  }

  test("transformStats: lowered pattern binder CPS function val uses local transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerPatternFunctionVal"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val tupleType = requiredClass("scala.Tuple2").typeRef.appliedTo(List(localValType, localValType))
    val tmpSym = newSymbol(outerSym, termName("patternFunction$tmp"), Flags.Synthetic, tupleType).asTerm
    val tmpVal = tpd.ValDef(
      tmpSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(tupleType))
    )(using freshCtx)
    val fSym = newSymbol(outerSym, termName("patternF"), Flags.Synthetic, localValType).asTerm
    val fVal = tpd.ValDef(fSym, tpd.Select(tpd.ref(tmpSym), nme.selectorName(0)), inferred = false)(using freshCtx)
    val call =
      tpd.Apply(tpd.Select(tpd.ref(fSym), nme.apply), List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(6))))(
        using freshCtx
      )

    phase.prepareForUnit(tpd.Block(List(tmpVal, fVal), call)(using freshCtx))(using freshCtx)
    val stats = phase.transformStats(List(tmpVal, fVal))(using freshCtx)
    val rewrittenCall = phase.transformCpsExpr(call, Map.empty)(using freshCtx)

    val transformed = stats.collectFirst {
      case vd: ValDef if vd.name.toString.startsWith("patternF$transformed") => vd
    }
    assert(transformed.nonEmpty, s"lowered pattern binder should gain transformed sibling, got $stats")
    assert(
      treeExists(rewrittenCall) {
        case id: Ident => id.name.toString.startsWith("patternF$transformed")
        case _ => false
      },
      s"pattern binder call should use transformed sibling, got $rewrittenCall"
    )
    assert(
      !treeExists(rewrittenCall)(_.symbol eq fSym),
      s"raw pattern binder symbol should not remain, got $rewrittenCall"
    )
  }

  test("transformStats: local lazy CPS val emits lazy transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerLocalLazyVal"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValSym = newSymbol(outerSym, termName("lazyF"), Flags.Synthetic | Flags.Lazy, localValType).asTerm
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType)),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))))(using
      freshCtx
    )
    val result = phase.transformStats(List(localVal))(using freshCtx)

    assertEquals(result.length, 1, s"local lazy CPS val should emit only transformed value, got $result")
    val transformed = result.head.asInstanceOf[ValDef]
    assert(
      transformed.symbol.is(Flags.Lazy),
      s"transformed lazy val should preserve Lazy flag, got ${transformed.symbol}"
    )
    assert(
      transformed.name.toString.startsWith("lazyF$transformed"),
      s"transformed lazy val should use transformed prefix, got ${transformed.name}"
    )
  }

  test("transformStats: local lazy CPS val rejects transformed shell that captures prelude value") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalLazyCapturedPrelude"), Flags.Method, outerType).asTerm
    val ownerCtx = freshCtx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using ownerCtx)), cfType(using ownerCtx))(using ownerCtx)
    val localValSym = newSymbol(outerSym, termName("lazyCaptured"), Flags.Synthetic | Flags.Lazy, localValType).asTerm
    val baseSym = newSymbol(localValSym, termName("base"), Flags.Synthetic, intType(using ownerCtx)).asTerm
    val baseVal = tpd.ValDef(baseSym, tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)))(using ownerCtx)
    val anonType =
      MethodType(List(termName("x")))(_ => List(intType(using ownerCtx)), _ => cfType(using ownerCtx))(using ownerCtx)
    val anonSym = newSymbol(localValSym, termName("$anonfunCaptured"), Flags.Synthetic | Flags.Method, anonType).asTerm
    val anonDef = tpd.DefDef(
      anonSym,
      _ =>
        tpd.Block(List(tpd.ref(baseSym)(using ownerCtx)), tpd.ref(defn.Predef_undefined).ensureConforms(cfType))(using
          ownerCtx
        )
    )(using ownerCtx)
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Block(List(baseVal, anonDef), tpd.Closure(Nil, tpd.ref(anonSym), tpd.TypeTree(localValType)))(using ownerCtx),
      inferred = false
    )(using ownerCtx)

    phase.prepareForUnit(tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))))(using
      ownerCtx
    )
    phase.transformStats(List(localVal))(using ownerCtx)

    assert(freshReporter.hasErrors, "lazy transformed shell that captures prelude values should be rejected")
  }

  test("transformStats: local CPS var emits mutable transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerLocalVar"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValSym = newSymbol(outerSym, termName("varF"), Flags.Synthetic | Flags.Mutable, localValType).asTerm
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType)),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))))(using
      freshCtx
    )
    val result = phase.transformStats(List(localVal))(using freshCtx)

    assertEquals(result.length, 1, s"local CPS var should emit only transformed value, got $result")
    val transformed = result.head.asInstanceOf[ValDef]
    assert(
      transformed.symbol.is(Flags.Mutable),
      s"transformed var should preserve Mutable flag, got ${transformed.symbol}"
    )
  }

  test("transformAssign: local CPS var assignment updates transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerLocalVarAssign"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValSym = newSymbol(outerSym, termName("assignF"), Flags.Synthetic | Flags.Mutable, localValType).asTerm
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType)),
      inferred = false
    )(using freshCtx)
    phase.prepareForUnit(tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0))))(using
      freshCtx
    )
    phase.transformStats(List(localVal))(using freshCtx)

    val rhs =
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)), tpd.TypeTree(localValType))(using freshCtx)
    val assign = tpd.Assign(tpd.ref(localValSym), rhs)(using freshCtx)
    val result = phase.transformAssign(assign)(using freshCtx)

    result match
      case transformedAssign: Assign =>
        assert(
          transformedAssign.lhs.symbol.name.toString.startsWith("assignF$transformed"),
          s"assignment should target transformed sibling, got ${transformedAssign.lhs}"
        )
      case other =>
        fail(s"expected assignment, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformSelect: observable local CPS val use reports dedicated diagnostic") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalRuntimeUse"), Flags.Method, outerType).asTerm
    val ownerCtx = freshCtx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using ownerCtx)), cfType(using ownerCtx))(using ownerCtx)
    val localValSym = newSymbol(outerSym, termName("runtimeF"), Flags.Synthetic, localValType).asTerm
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType))(using ownerCtx),
      inferred = false
    )(using ownerCtx)
    val select = tpd.Select(tpd.ref(localValSym)(using ownerCtx), termName("hashCode"))(using ownerCtx)

    phase.prepareForUnit(tpd.Block(List(localVal), select)(using ownerCtx))(using ownerCtx)
    phase.transformSelect(select)(using ownerCtx)

    assert(
      freshReporter.allErrors.exists(_.message.contains(LocalCpsValueRuntimeMessage)),
      s"expected dedicated local CPS value diagnostic, got ${freshReporter.allErrors.map(_.message)}"
    )
  }

  test("transformSelect: container of local CPS value can access member without dedicated diagnostic") {
    val freshReporter = new StoreReporter(null)
    given freshCtx: Context = ctx.fresh.setReporter(freshReporter)
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalContainerSelect"), Flags.Method, outerType).asTerm
    val ownerCtx = freshCtx.withOwner(outerSym)
    val localValType =
      requiredClass("scala.Option").typeRef.appliedTo(
        List(defn.FunctionOf(List(intType(using ownerCtx)), cfType(using ownerCtx))(using ownerCtx))
      )
    val localValSym = newSymbol(outerSym, termName("runtimeOpt"), Flags.Synthetic, localValType).asTerm
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType))(using ownerCtx),
      inferred = false
    )(using ownerCtx)
    val select = tpd.Select(tpd.ref(localValSym)(using ownerCtx), termName("get"))(using ownerCtx)

    phase.prepareForUnit(tpd.Block(List(localVal), select)(using ownerCtx))(using ownerCtx)
    phase.transformSelect(select)(using ownerCtx)

    assert(
      !freshReporter.allErrors.exists(_.message.contains(LocalCpsValueRuntimeMessage)),
      s"container member access should not report dedicated local CPS value diagnostic, got ${freshReporter.allErrors.map(_.message)}"
    )
  }

  test("transformCpsExpr: local CPS val apply rewrites to transformed sibling") {
    val baseIntType = ctx.definitions.IntType
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => baseIntType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLocalValRewrite"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("f"), Flags.Synthetic, localValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localValType)),
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

    val applyTree =
      tpd.Apply(tpd.ref(localValSym), List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(10))))(using freshCtx)
    val result = phase.transformCpsExpr(applyTree, Map.empty)(using freshCtx)

    result match
      case Apply(fun: RefTree, _) =>
        assert(
          fun.name.toString.startsWith("f$transformed"),
          s"local CPS val call should target transformed sibling, got ${fun.name}"
        )
      case other =>
        fail(s"expected rewritten Apply, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformApply: non-tail CPS callback function value argument rewrites to transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerNonTailCallbackArg"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val callbackType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val runnerType =
      defn.FunctionOf(List(callbackType, intType(using freshCtx)), intType(using freshCtx))(using freshCtx)
    val consumeType =
      MethodType(List(termName("runner")))(_ => List(runnerType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym = newSymbol(owner, termName("consumeNonTailCallback"), Flags.Method, consumeType).asTerm
    val consumeTransformedSym = newSymbol(
      owner,
      termName("consumeNonTailCallback$transformed"),
      Flags.Method | Flags.Synthetic,
      transformCpsMethodType(consumeType),
      coord = consumeSym.coord
    ).asTerm.entered
    val runnerSym = newSymbol(outerSym, termName("runnerNonTail"), Flags.Synthetic, runnerType).asTerm
    val runnerVal = tpd.ValDef(
      runnerSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(runnerType)),
      inferred = false
    )(using freshCtx)
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(runnerSym)))(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(runnerVal), call)(using freshCtx))(using freshCtx)
    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeTransformedSym, s"callee should use transformed method, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("runnerNonTail$transformed"),
          s"function value argument should use transformed sibling, got ${arg.name}"
        )
      case other =>
        fail(s"expected rewritten call with one transformed argument, got ${other.getClass.getSimpleName}: $other")
  }

  test("transformApply: non-tail CPS callback alias argument rewrites to alias transformed sibling") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerNonTailCallbackAliasArg"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val callbackType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val runnerType =
      defn.FunctionOf(List(callbackType, intType(using freshCtx)), intType(using freshCtx))(using freshCtx)
    val consumeType =
      MethodType(List(termName("runner")))(_ => List(runnerType), _ => intType(using freshCtx))(using freshCtx)
    val consumeSym = newSymbol(owner, termName("consumeNonTailCallbackAlias"), Flags.Method, consumeType).asTerm
    val consumeTransformedSym = newSymbol(
      owner,
      termName("consumeNonTailCallbackAlias$transformed"),
      Flags.Method | Flags.Synthetic,
      transformCpsMethodType(consumeType),
      coord = consumeSym.coord
    ).asTerm.entered
    val runnerSym = newSymbol(outerSym, termName("runnerNonTailBase"), Flags.Synthetic, runnerType).asTerm
    val runnerVal = tpd.ValDef(
      runnerSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(runnerType)),
      inferred = false
    )(using freshCtx)
    val aliasSym = newSymbol(outerSym, termName("runnerNonTailAlias"), Flags.Synthetic, runnerType).asTerm
    val aliasVal = tpd.ValDef(aliasSym, tpd.ref(runnerSym), inferred = false)(using freshCtx)
    val call = tpd.Apply(tpd.ref(consumeSym), List(tpd.ref(aliasSym)))(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(runnerVal, aliasVal), call)(using freshCtx))(using freshCtx)
    val result = phase.transformApply(call)(using freshCtx)

    result match
      case Apply(fun, List(arg: RefTree)) =>
        assert(fun.symbol eq consumeTransformedSym, s"callee should use transformed method, got ${fun.symbol}")
        assert(
          arg.name.toString.startsWith("runnerNonTailAlias$transformed"),
          s"alias argument should use transformed sibling, got ${arg.name}"
        )
      case other =>
        fail(
          s"expected rewritten call with one transformed alias argument, got ${other.getClass.getSimpleName}: $other"
        )
  }

  test("transformCpsExpr: local non-tail callback consumer forwards mapped CPS parameter argument") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerNonTailCallbackMappedParam"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val callbackType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val runnerType =
      defn.FunctionOf(List(callbackType, intType(using freshCtx)), intType(using freshCtx))(using freshCtx)
    val consumerType = defn.FunctionOf(List(runnerType), intType(using freshCtx))(using freshCtx)
    val consumerSym = newSymbol(outerSym, termName("localNonTailConsumer"), Flags.Synthetic, consumerType).asTerm
    val consumerVal = tpd.ValDef(
      consumerSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(consumerType)),
      inferred = false
    )(using freshCtx)
    val runnerParamSym = newSymbol(outerSym, termName("runnerParam"), Flags.Synthetic, runnerType).asTerm
    val mappedRunnerSym =
      newSymbol(outerSym, termName("runnerParam"), Flags.Synthetic, transformCpsValueType(runnerType)).asTerm
    val call = tpd.Apply(tpd.ref(consumerSym), List(tpd.ref(runnerParamSym)))(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(consumerVal), call)(using freshCtx))(using freshCtx)
    val result = phase.transformCpsExpr(call, Map(runnerParamSym -> mappedRunnerSym))(using freshCtx)

    assert(
      treeExists(result)(_.symbol eq mappedRunnerSym),
      s"mapped runner parameter should be forwarded to transformed local consumer, got $result"
    )
    assert(
      !treeExists(result)(_.symbol eq runnerParamSym),
      s"original runner parameter should not remain after mapped forwarding, got $result"
    )
  }

  test("transformStats: local polymorphic CPS val emits sibling transformed value with PolyType") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLocalPolyVal"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val localPolyValType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        defn.FunctionOf(
          List(a),
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)(using
            freshCtx
          )
        )(using freshCtx)
      }
    )
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("poly"), Flags.Synthetic, localPolyValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localPolyValType)),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(
      tpd.PackageDef(
        mkPid()(using freshCtx),
        List(
          tpd.DefDef(
            outerSym,
            _ => tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
          )(using freshCtx)
        )
      )(using freshCtx)
    )(using freshCtx)
    val result = phase.transformStats(List(localVal))(using freshCtx)

    assertEquals(result.length, 1, s"local poly CPS val should emit only transformed value, got $result")
    val transformed = result.head.asInstanceOf[ValDef]
    assert(
      transformed.name.toString.startsWith("poly$transformed"),
      s"transformed poly local val should use transformed prefix, got ${transformed.name}"
    )
    assert(
      transformed.symbol.info.isInstanceOf[PolyType],
      s"transformed poly local val should preserve PolyType, got ${transformed.symbol.info}"
    )
    assert(isCpsValType(localValSym.info), s"poly local val should be detected as CPS value, got ${localValSym.info}")
    assert(
      isControlContextType(transformed.symbol.info.finalResultType.argInfos.last),
      s"transformed poly local val CPS leaf should be ControlContext, got ${transformed.symbol.info.finalResultType}"
    )
  }

  test("transformStats: nested local CPS val rewrites inner shift lambda owner away from original symbol") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerNestedLocalVal"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val innerValueType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValType = defn.FunctionOf(Nil, innerValueType)(using freshCtx)
    val localLambdaType = MethodType(Nil)(_ => Nil, _ => innerValueType)(using freshCtx)
    val innerLambdaType =
      MethodType(List(termName("x")))(_ => List(intType(using freshCtx)), _ => cfType(using freshCtx))(using freshCtx)
    val shiftFunType = MethodType(List(termName("k")))(
      _ => List(defn.FunctionOf(List(intType(using freshCtx)), intType(using freshCtx))(using freshCtx)),
      _ => intType(using freshCtx)
    )(using freshCtx)

    val outerLambdaSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("$outerLocalVal"), Flags.Synthetic | Flags.Method, localLambdaType).asTerm
    }
    val innerLambdaSym = {
      given Context = freshCtx
      newSymbol(outerLambdaSym, termName("$innerLocalVal"), Flags.Synthetic | Flags.Method, innerLambdaType).asTerm
    }
    val xSym = innerLambdaSym.paramSymss.head.head.asTerm
    val shiftLambdaSym = {
      given Context = freshCtx
      newSymbol(innerLambdaSym, termName("$shiftLambda"), Flags.Synthetic | Flags.Method, shiftFunType).asTerm
    }
    val kSym = shiftLambdaSym.paramSymss.head.head.asTerm
    val shiftApplySym = kSym.info.dealias.typeSymbol.requiredMethod("apply")
    val shiftLambdaDef = tpd.DefDef(
      shiftLambdaSym,
      _ => tpd.Apply(tpd.ref(kSym).select(shiftApplySym), List(tpd.ref(xSym)))(using freshCtx)
    )(using freshCtx)
    val shiftLambdaClosure = tpd.Closure(
      Nil,
      tpd.ref(shiftLambdaSym),
      tpd.TypeTree(defn.FunctionOf(List(intType(using freshCtx)), intType(using freshCtx))(using freshCtx))
    )(using freshCtx)
    val shiftCall = tpd
      .ref(shiftMethod)
      .appliedToTypes(List(intType(using freshCtx), intType(using freshCtx)))
      .appliedTo(tpd.Block(List(shiftLambdaDef), shiftLambdaClosure))(using freshCtx)
    val innerLambdaDef = tpd.DefDef(innerLambdaSym, _ => shiftCall)(using freshCtx)
    val innerClosure = tpd.Closure(Nil, tpd.ref(innerLambdaSym), tpd.TypeTree(innerValueType))(using freshCtx)
    val outerLambdaDef =
      tpd.DefDef(outerLambdaSym, _ => tpd.Block(List(innerLambdaDef), innerClosure)(using freshCtx))(using freshCtx)
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("nested"), Flags.Synthetic, localValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Block(
        List(outerLambdaDef),
        tpd.Closure(Nil, tpd.ref(outerLambdaSym), tpd.TypeTree(localValType))(using freshCtx)
      )(using freshCtx),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(
      tpd.PackageDef(
        mkPid()(using freshCtx),
        List(
          tpd.DefDef(
            outerSym,
            _ => tpd.Block(List(localVal), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)))
          )(using freshCtx)
        )
      )(using freshCtx)
    )(using freshCtx)
    val result = phase.transformStats(List(localVal))(using freshCtx)
    val transformed = result.head.asInstanceOf[ValDef]

    var foundOwner: Option[Symbol] = None
    new tpd.TreeTraverser {
      override def traverse(tree: Tree)(using Context): Unit =
        tree match
          case id: Ident if id.name.toString == "x" =>
            foundOwner = Some(summon[Context].owner)
          case _ =>
            traverseChildren(tree)
    }.traverse(transformed)(using freshCtx)

    foundOwner match
      case Some(ownerSym) =>
        assert(
          !(ownerSym eq shiftLambdaSym),
          s"inner shift lambda body should not stay owned by original shift lambda, got $ownerSym"
        )
      case None =>
        fail(s"expected to find x reference in transformed nested local val, got ${transformed.rhs}")
  }

  test("transformCpsExpr: local polymorphic CPS val TypeApply+Apply rewrites to transformed sibling") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerLocalPolyValRewrite"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val localPolyValType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        defn.FunctionOf(
          List(a),
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)(using
            freshCtx
          )
        )(using freshCtx)
      }
    )
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("poly"), Flags.Synthetic, localPolyValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localPolyValType)),
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

    val polyCall = tpd.Apply(
      tpd.TypeApply(tpd.ref(localValSym), List(tpd.TypeTree(intType(using freshCtx)))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(10)))
    )(using freshCtx)
    val result = phase.transformCpsExpr(polyCall, Map.empty)(using freshCtx)

    result match
      case Apply(TypeApply(fun: RefTree, _), _) =>
        assert(
          fun.name.toString.startsWith("poly$transformed"),
          s"local poly CPS val call should target transformed sibling, got ${fun.name}"
        )
      case other =>
        fail(s"expected rewritten Apply(TypeApply(...)), got ${other.getClass.getSimpleName}: $other")
  }

  test("transformCpsExpr: curried local polymorphic CPS val preserves apply chain and rewrites base callee") {
    val baseOwner = ctx.definitions.EmptyPackageClass
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = {
      given Context = ctx
      newSymbol(baseOwner, termName("outerCurriedLocalPolyValRewrite"), Flags.Method, outerType).asTerm
    }
    val freshCtx: Context = ctx.withOwner(outerSym)
    val localPolyValType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        val cpsLeaf =
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)(using
            freshCtx
          )
        val inner = defn.FunctionOf(List(a), cpsLeaf)(using freshCtx)
        defn.FunctionOf(List(intType(using freshCtx)), inner)(using freshCtx)
      }
    )
    val localValSym = {
      given Context = freshCtx
      newSymbol(outerSym, termName("polyCurried"), Flags.Synthetic, localPolyValType).asTerm
    }
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Typed(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), tpd.TypeTree(localPolyValType)),
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

    val firstApply = tpd.Apply(
      tpd.TypeApply(tpd.ref(localValSym), List(tpd.TypeTree(intType(using freshCtx)))),
      List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(1)))
    )(using freshCtx)
    val secondApply =
      tpd.Apply(firstApply, List(tpd.Literal(dotty.tools.dotc.core.Constants.Constant(2))))(using freshCtx)

    val result = phase.transformCpsExpr(secondApply, Map.empty)(using freshCtx)

    assert(
      treeExists(result) {
        case ref: RefTree => ref.name.toString.startsWith("polyCurried$transformed")
        case _ => false
      },
      s"curried local poly call should target transformed sibling, got $result"
    )
    result match
      case Apply(Apply(TypeApply(_, _), _), List(_)) =>
        assert(true, s"outer apply chain should be preserved, got $result")
      case other =>
        fail(s"expected curried Apply(Apply(TypeApply(...), ...), ...), got ${other.getClass.getSimpleName}: $other")
  }

  test("mkTransformedImpl: function-valued CPS result keeps function shape instead of shiftUnitR") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)(using ctx)
    val outerSym = newSymbol(owner, termName("outerCurriedHelper"), Flags.Method, outerType).asTerm
    val freshCtx: Context = ctx.withOwner(outerSym)
    val returnedFunType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val methodType =
      MethodType(List(termName("seed")))(_ => List(intType(using freshCtx)), _ => returnedFunType)(using freshCtx)
    val methodSym = newSymbol(outerSym, termName("curriedLocal"), Flags.Method, methodType).asTerm
    val innerLambdaType =
      MethodType(List(termName("x")))(_ => List(intType(using freshCtx)), _ => cfType(using freshCtx))(using freshCtx)
    val innerLambdaSym =
      newSymbol(methodSym, termName("$curriedInner"), Flags.Synthetic | Flags.Method, innerLambdaType).asTerm
    val shiftArgType = defn.FunctionOf(
      List(defn.FunctionOf(List(intType(using freshCtx)), intType(using freshCtx))(using freshCtx)),
      intType(using freshCtx)
    )(using freshCtx)
    val shiftArgSym = newSymbol(innerLambdaSym, termName("kBody"), Flags.Synthetic, shiftArgType).asTerm
    val shiftCall = ref(shiftMethod)
      .appliedToTypes(List(intType(using freshCtx), intType(using freshCtx)))
      .appliedTo(ref(shiftArgSym))
    val innerLambdaDef = tpd.DefDef(innerLambdaSym, _ => shiftCall)(using freshCtx)
    val methodRhs =
      tpd.Block(List(innerLambdaDef), tpd.Closure(Nil, tpd.ref(innerLambdaSym), tpd.TypeTree(returnedFunType)))
    val orig = tpd.DefDef(methodSym, _ => methodRhs)(using freshCtx)
    val transformedSym = newSymbol(
      outerSym,
      termName("curriedLocal$transformed"),
      Flags.Method | Flags.Synthetic,
      transformCpsMethodType(methodSym.info),
      coord = methodSym.coord
    ).asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)(using freshCtx)

    assert(
      treeExists(result.rhs)(_.symbol eq shiftTransformedMethod),
      s"function-valued CPS result should rewrite leaf with shift$$transformed, got ${result.rhs}"
    )
    assert(
      !treeExists(result.rhs)(_.symbol eq shiftUnitRMethod),
      s"function-valued CPS result should keep function shape instead of shiftUnitR, got ${result.rhs}"
    )
  }

  test("transformStats: local CPS val eta over local CPS method rewrites transformed rhs to local helper") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType)
    val outerSym = newSymbol(owner, termName("outerLocalEtaVal"), Flags.Method, outerType).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val innerType = MethodType(List(termName("x")))(_ => List(intType), _ => cfType)(using freshCtx)
    val innerSym = newSymbol(outerSym, termName("innerEta"), Flags.Method, innerType).asTerm
    val inner = tpd.DefDef(innerSym, _ => tpd.ref(defn.Predef_undefined).ensureConforms(cfType))(using freshCtx)

    val localValType = defn.FunctionOf(List(intType(using freshCtx)), cfType(using freshCtx))(using freshCtx)
    val localValSym = newSymbol(outerSym, termName("fEta"), Flags.Synthetic, localValType).asTerm
    val anonType = MethodType(List(termName("x")))(_ => List(intType), _ => cfType)(using freshCtx)
    val anonSym = newSymbol(outerSym, termName("$anonfunEta"), Flags.Synthetic | Flags.Method, anonType).asTerm
    val xSym = anonSym.paramSymss.head.head.asTerm
    val anonDef = tpd.DefDef(anonSym, _ => tpd.Apply(tpd.ref(innerSym), List(tpd.ref(xSym))))(using freshCtx)
    val localVal = tpd.ValDef(
      localValSym,
      tpd.Block(List(anonDef), tpd.Closure(Nil, tpd.ref(anonSym), tpd.TypeTree(localValType))),
      inferred = false
    )(using freshCtx)

    phase.prepareForUnit(tpd.Block(List(inner, localVal), tpd.ref(defn.Predef_undefined))(using freshCtx))(using
      freshCtx
    )
    val result = phase.transformStats(List(inner, localVal))(using freshCtx)
    val transformedVal = result
      .collectFirst {
        case vd: ValDef if vd.name.toString.startsWith("fEta$transformed") => vd
      }
      .getOrElse(fail(s"expected transformed local val, got $result"))

    assert(
      treeExists(transformedVal.rhs)(_.symbol.name.toString.startsWith("innerEta$transformed")),
      s"eta-expanded transformed val should call local transformed helper, got ${transformedVal.rhs}"
    )
  }

  test("memberVarAssignShape") {
    val assignShapes = scala.collection.mutable.ListBuffer.empty[String]
    val setterApplyShapes = scala.collection.mutable.ListBuffer.empty[String]

    class MemberVarAssignShapeProbe extends dotty.tools.dotc.plugins.PluginPhase:
      val phaseName = "memberVarAssignShapeProbe"
      override val runsBefore: Set[String] = Set("pickler")

      private def isLiteral42(tree: Tree): Boolean =
        tree match
          case Literal(dotty.tools.dotc.core.Constants.Constant(value: Int)) => value == 42
          case _ => false

      override def transformAssign(tree: Assign)(using Context): Tree =
        tree match
          case Assign(Select(_, name), rhs) if name.toString == "xs" && isLiteral42(rhs) =>
            assignShapes += tree.show(using ctx)
          case _ =>
        tree

      override def transformApply(tree: Apply)(using Context): Tree =
        tree match
          case Apply(Select(_, name), List(rhs)) if name.toString == "xs_=" && isLiteral42(rhs) =>
            setterApplyShapes += tree.show(using ctx)
          case _ =>
        tree

    val probe = new MemberVarAssignShapeProbe
    val compiler = new dotty.tools.dotc.Compiler:
      override def picklerPhases: List[List[dotty.tools.dotc.core.Phases.Phase]] =
        List(List(probe)) ::: super.picklerPhases
    val driver = new dotty.tools.dotc.Driver:
      override def newCompiler(using Context): dotty.tools.dotc.Compiler = compiler

    val outDir = java.nio.file.Files.createTempDirectory("member-var-assign-shape-out")
    val sourceFile = java.nio.file.Files.createTempFile("MemberVarAssignShape", ".scala")
    java.nio.file.Files.writeString(
      sourceFile,
      """class C:
        |  var xs: Int = 0
        |  def selfAssign(): Unit =
        |    this.xs = 42
        |
        |object MemberVarAssignShape:
        |  def objectAssign(): Unit =
        |    val c = new C
        |    c.xs = 42
        |""".stripMargin
    )

    val reporter = driver.process(
      Array("-color:never", "-classpath", sys.props("java.class.path"), "-d", outDir.toString, sourceFile.toString)
    )
    assert(!reporter.hasErrors, s"member var assignment probe source should compile, got ${reporter.allErrors}")

    if setterApplyShapes.nonEmpty then
      // Finding note: member var assignment was typed as setter Apply form, so keep this probe non-failing.
      println(s"memberVarAssignShape observed setter Apply form: ${setterApplyShapes.mkString(", ")}")
    else
      assertEquals(
        assignShapes.length,
        2,
        s"expected self and object member var assignments as Assign(Select(...), Literal(42)), got $assignShapes"
      )
  }
