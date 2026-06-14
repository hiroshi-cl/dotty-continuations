package continuations.plugin.phase.stub

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

class SelectiveCPSStubTypeOpsSuite extends munit.FunSuite with SelectiveCPSStubPhaseSuiteBase:

  test("hasUnsupportedDirectCpsTransformParam: rejects non-contextual direct marker parameter") {
    val methType = MethodType(List(termName("ctx")))(_ => List(cpsType), _ => intType)
    val methSym = newSymbol(owner, termName("badPlainCpsParam"), Flags.Method, methType).asTerm

    assert(hasUnsupportedDirectCpsTransformParam(methSym), "plain CpsTransform parameter should be unsupported")
  }

  test("hasUnsupportedDirectCpsTransformParam: rejects non-trailing contextual marker clause") {
    val trailing = MethodType(List(termName("x")))(_ => List(intType), _ => intType)
    val methType = ContextualMethodType(List(termName("ctx")))(_ => List(cpsType), _ => trailing)
    val methSym = newSymbol(owner, termName("badNonTrailingCpsParam"), Flags.Method, methType).asTerm

    assert(hasUnsupportedDirectCpsTransformParam(methSym), "non-trailing using CpsTransform should be unsupported")
  }

  test("hasUnsupportedDirectCpsTransformParam: rejects multiple contextual direct markers") {
    val methType = ContextualMethodType(List(termName("ctx1"), termName("ctx2")))(
      _ => List(cpsType, cpsTransformClass.typeRef.appliedTo(ctx.definitions.StringType)),
      _ => intType
    )
    val methSym = newSymbol(owner, termName("badMultipleCpsParams"), Flags.Method, methType).asTerm

    assert(
      hasUnsupportedDirectCpsTransformParam(methSym),
      "multiple using CpsTransform parameters should be unsupported"
    )
  }

  // Test 6: 非 CPS メソッドは needsTransformedStub が false
  test("needsTransformedStub: pure method → false") {
    val methType = MethodType(List(termName("x")))(_ => List(intType), _ => intType)
    val methSym = newSymbol(owner, termName("pure"), Flags.Method, methType).asTerm
    assert(!needsTransformedStub(methSym), "pure method should not need stub")
  }

  test("needsTransformedStub: pure higher-order function value parameter → false") {
    val callbackType = defn.FunctionOf(List(intType), intType)
    val runnerType = defn.FunctionOf(List(callbackType, intType), intType)
    val methType = MethodType(List(termName("runner")))(_ => List(runnerType), _ => intType)
    val methSym = newSymbol(owner, termName("pureHigherOrder"), Flags.Method, methType).asTerm

    assert(!isCpsValType(runnerType), s"pure higher-order function value should not be CPS, got $runnerType")
    assert(!needsTransformedStub(methSym), "pure higher-order method should not need stub")
  }

  // Test 7: $transformed 名のシンボルは needsTransformedStub が false
  test("needsTransformedStub: already-transformed name → false") {
    val methSym = newSymbol(owner, termName("m$transformed"), Flags.Method, cfType).asTerm
    assert(!needsTransformedStub(methSym), "$transformed method should not need another stub")
  }

  test("needsTransformedStub: inline CPS provider is rejected before stub generation") {
    val methSym = newSymbol(
      owner,
      termName("inlineProvider"),
      Flags.Method | Flags.Inline,
      defn.FunctionOf(List(intType), cfType)
    ).asTerm

    assert(isUnsupportedInlineCpsProvider(methSym), "inline CPS provider should be an explicit unsupported shape")
    assert(!needsTransformedStub(methSym), "inline CPS provider should not get a transformed stub")
  }

  test("isUnsupportedResidualCpsValStorage: classifies supported and residual storage shapes") {
    val pureVal = newSymbol(owner, termName("pureVal"), Flags.Synthetic, intType).asTerm
    val cpsValType = defn.FunctionOf(List(intType), cfType)
    val residualVal = newSymbol(owner, termName("residualVal"), Flags.Synthetic, cpsValType).asTerm
    val transformedVal =
      newSymbol(owner, termName("residualVal$transformed"), Flags.Synthetic, cpsValType).asTerm

    val localOwnerType = MethodType(Nil)(_ => Nil, _ => intType)
    val localOwner = newSymbol(owner, termName("localOwner"), Flags.Method, localOwnerType).asTerm
    val localStrict = newSymbol(localOwner, termName("localStrict"), Flags.Synthetic, cpsValType).asTerm
    val localLazy = newSymbol(localOwner, termName("localLazy"), Flags.Synthetic | Flags.Lazy, cpsValType).asTerm
    val localMutable =
      newSymbol(localOwner, termName("localMutable"), Flags.Synthetic | Flags.Mutable, cpsValType).asTerm

    val classOwner = ctx.definitions.ListClass
    val memberStrict = newSymbol(classOwner, termName("memberStrict"), Flags.Synthetic, cpsValType).asTerm
    val memberLazy = newSymbol(classOwner, termName("memberLazy"), Flags.Synthetic | Flags.Lazy, cpsValType).asTerm
    val memberMutable =
      newSymbol(classOwner, termName("memberMutable"), Flags.Synthetic | Flags.Mutable, cpsValType).asTerm

    val traitOwner = requiredClass("scala.Product")
    val traitMember = newSymbol(traitOwner, termName("traitMember"), Flags.Synthetic, cpsValType).asTerm
    val directStorage = newSymbol(localOwner, termName("directStorage"), Flags.Synthetic, cfType).asTerm

    assert(!isUnsupportedResidualCpsValStorage(pureVal), "plain val should stay supported")
    assert(isUnsupportedResidualCpsValStorage(residualVal), "unclassified CPS storage should be rejected")
    assert(!isUnsupportedResidualCpsValStorage(transformedVal), "transformed sibling should be excluded")
    List(localStrict, localLazy, localMutable).foreach(sym =>
      assert(!isUnsupportedResidualCpsValStorage(sym), s"local storage should be supported: ${sym.name}")
    )
    List(memberStrict, memberLazy, memberMutable).foreach(sym =>
      assert(!isUnsupportedResidualCpsValStorage(sym), s"class member storage should be supported: ${sym.name}")
    )
    assert(isUnsupportedResidualCpsValStorage(traitMember), "trait member storage should remain unsupported")
    assert(isUnsupportedDirectCpsContextFunctionStorage(directStorage), "direct storage should use its dedicated error")
    assert(
      !isUnsupportedResidualCpsValStorage(directStorage),
      "direct storage should not also use the residual-storage diagnostic"
    )
  }

  test("isCpsValType/transformCpsValueType: polymorphic CPS value reaches CPS leaf through PolyType") {
    val polyValType = PolyType(List(typeName("T")))(
      _ => List(TypeBounds.empty),
      pt => {
        val tParam = pt.paramRefs.head
        defn.FunctionOf(
          List(tParam),
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(tParam)), tParam, isContextual = true)
        )
      }
    )

    assert(isCpsValType(polyValType), s"poly CPS value should be detected, got $polyValType")

    val transformed = transformCpsValueType(polyValType)
    val transformedPoly = transformed.asInstanceOf[PolyType]
    val transformedFun = transformedPoly.resType

    assert(defn.isFunctionType(transformedFun), s"poly result should remain function type, got $transformedFun")
    assert(
      isControlContextType(transformedFun.argInfos.last),
      s"poly CPS leaf should become ControlContext, got ${transformedFun.argInfos.last}"
    )
  }

  test("isCpsValType/transformCpsValueType: curried polymorphic CPS value reaches CPS leaf through MethodType") {
    val polyValType = PolyType(List(typeName("T")))(
      _ => List(TypeBounds.empty),
      pt => {
        val tParam = pt.paramRefs.head
        MethodType(List(termName("n")))(
          _ => List(intType),
          _ =>
            defn.FunctionOf(
              List(tParam),
              ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(tParam)), tParam, isContextual = true)
            )
        )
      }
    )

    assert(isCpsValType(polyValType), s"curried poly CPS value should be detected, got $polyValType")

    val transformed = transformCpsValueType(polyValType)
    val transformedPoly = transformed.asInstanceOf[PolyType]
    val transformedMethod = transformedPoly.resType.asInstanceOf[MethodType]
    val transformedFun = transformedMethod.resType

    assertEquals(
      transformedMethod.paramInfos.head,
      intType,
      s"curried prefix arg should stay Int, got ${transformedMethod.paramInfos.head}"
    )
    assert(
      defn.isFunctionType(transformedFun),
      s"curried method result should remain function type, got $transformedFun"
    )
    assert(
      isControlContextType(transformedFun.argInfos.last),
      s"curried poly CPS leaf should become ControlContext, got ${transformedFun.argInfos.last}"
    )
  }

  test("isCpsValType: generic List AppliedType with CPS function value type argument is detected") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType(using ctx))(using ctx)
    val outerSym = newSymbol(owner(using ctx), termName("listAppliedTypeOwner"), Flags.Method, outerType).asTerm
    {
      given freshCtx: Context = ctx.withOwner(outerSym)
      val listType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))

      assert(isCpsValType(listType), s"generic List CPS value should be detected, got $listType")
    }
  }

  test("isCpsValType: transformed ControlContext is not treated as a source CPS value") {
    val ccType = controlContextClass.typeRef.appliedTo(List(intType, intType))

    assert(!isCpsValType(ccType), s"ControlContext should already be transformed representation, got $ccType")
  }

  test("isCpsValType: nested generic AppliedType Option[List[F]] recurses") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType(using ctx))(using ctx)
    val outerSym = newSymbol(owner(using ctx), termName("nestedOptionAppliedTypeOwner"), Flags.Method, outerType).asTerm
    {
      given freshCtx: Context = ctx.withOwner(outerSym)
      val listType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
      val optionType = ctx.definitions.OptionClass.typeRef.appliedTo(List(listType))

      assert(isCpsValType(optionType), s"nested generic CPS value should be detected, got $optionType")
    }
  }

  test("isCpsValType: abstract tycon is NOT detected (unsupported)") {
    val abstractTycon = newSymbol(owner, typeName("F"), Flags.Deferred, TypeBounds.empty)
    val abstractApplied = AppliedType(abstractTycon.typeRef, List(cfType))

    assert(!isCpsValType(abstractApplied), s"abstract tycon AppliedType should stay unsupported, got $abstractApplied")
  }

  test(
    "transformCpsValueType: generic List AppliedType rewrites CPS function value type argument via derivedAppliedType"
  ) {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType(using ctx))(using ctx)
    val outerSym =
      newSymbol(owner(using ctx), termName("transformListAppliedTypeOwner"), Flags.Method, outerType).asTerm
    {
      given freshCtx: Context = ctx.withOwner(outerSym)
      val listType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))

      val transformed = transformCpsValueType(listType).asInstanceOf[AppliedType]

      assertEquals(transformed.tycon.typeSymbol, ctx.definitions.ListClass)
      assert(
        isControlContextType(transformed.argInfos.head),
        s"List CPS leaf should become ControlContext, got ${transformed.argInfos.head}"
      )
    }
  }

  test("transformCpsValueType: preserves non-CPS args in multi-arg container (Tuple2[String, F])") {
    val tupleType = requiredClass("scala.Tuple2").typeRef.appliedTo(List(ctx.definitions.StringType, cfType))

    val transformed = transformCpsValueType(tupleType).asInstanceOf[AppliedType]

    assertEquals(transformed.tycon.typeSymbol, requiredClass("scala.Tuple2"))
    assertEquals(
      transformed.argInfos.head,
      ctx.definitions.StringType,
      s"first arg should remain String, got ${transformed.argInfos.head}"
    )
    assert(
      isControlContextType(transformed.argInfos(1)),
      s"second arg should become ControlContext, got ${transformed.argInfos(1)}"
    )
  }

  test("hasCpsValueContainerParam: List[CPS fn] parameter is detected") {
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
    val methType = MethodType(List(termName("xs")))(_ => List(listCpsType), _ => intType)
    val methSym = newSymbol(owner, termName("consume"), Flags.Method, methType).asTerm
    assert(hasCpsValueContainerParam(methSym), "List[CPS fn] param should be detected")
  }

  test("hasCpsValueContainerResult: List[CPS fn] return type is detected") {
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
    val methType = MethodType(Nil)(_ => Nil, _ => listCpsType)
    val methSym = newSymbol(owner, termName("make"), Flags.Method, methType).asTerm
    assert(hasCpsValueContainerResult(methSym), "List[CPS fn] return should be detected")
  }

  test("needsTransformedStub: class member container def → true") {
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
    val consumeType = MethodType(List(termName("xs")))(_ => List(listCpsType), _ => intType)
    val makeType = MethodType(Nil)(_ => Nil, _ => listCpsType)
    val consumeMem = newSymbol(ctx.definitions.ListClass, termName("consume"), Flags.Method, consumeType).asTerm
    val makeMem = newSymbol(ctx.definitions.ListClass, termName("make"), Flags.Method, makeType).asTerm
    assert(needsTransformedStub(consumeMem), "class member consume should need stub")
    assert(needsTransformedStub(makeMem), "class member make should need stub")
  }

  test("isUnsupportedInlineCpsProvider: inline class member container def → true") {
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
    val makeType = MethodType(Nil)(_ => Nil, _ => listCpsType)
    val makeMem =
      newSymbol(ctx.definitions.ListClass, termName("makeInline"), Flags.Method | Flags.Inline, makeType).asTerm

    assert(isUnsupportedInlineCpsProvider(makeMem), "inline class member container provider should be rejected")
  }

  test("needsTransformedStub: roundTrip[A] with TypeParamRef → false") {
    val polyType = PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        val listA = ctx.definitions.ListClass.typeRef.appliedTo(List(a))
        MethodType(List(termName("box")))(_ => List(listA), _ => listA)
      }
    )
    val roundTripSym = newSymbol(owner, termName("roundTrip"), Flags.Method, polyType).asTerm
    assert(!needsTransformedStub(roundTripSym), "roundTrip[A] with TypeParamRef should not need stub")
  }

  test("transformCpsValueType: nested Option[List[F]] recurses to produce Option[List[ControlContext[A,R]]]") {
    val outerType = MethodType(Nil)(_ => Nil, _ => intType(using ctx))(using ctx)
    val outerSym =
      newSymbol(owner(using ctx), termName("transformNestedOptionAppliedTypeOwner"), Flags.Method, outerType).asTerm
    {
      given freshCtx: Context = ctx.withOwner(outerSym)
      val listType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType))
      val optionType = ctx.definitions.OptionClass.typeRef.appliedTo(List(listType))

      val transformed = transformCpsValueType(optionType).asInstanceOf[AppliedType]
      val transformedList = transformed.argInfos.head.asInstanceOf[AppliedType]

      assertEquals(transformed.tycon.typeSymbol, ctx.definitions.OptionClass)
      assertEquals(transformedList.tycon.typeSymbol, ctx.definitions.ListClass)
      assert(
        isControlContextType(transformedList.argInfos.head),
        s"nested CPS leaf should become ControlContext, got ${transformedList.argInfos.head}"
      )
    }
  }
