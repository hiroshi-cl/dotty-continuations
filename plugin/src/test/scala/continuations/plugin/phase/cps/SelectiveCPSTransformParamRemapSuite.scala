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

class SelectiveCPSTransformParamRemapSuite extends munit.FunSuite with SelectiveCPSTransformSuiteBase:

  test("buildParamMapping: only CPS params are mapped") {
    // def foo(a: Int, body: CpsTransform[Int] ?=> Int): Int
    val aSym = newSymbol(owner, termName("a"), Flags.Synthetic, intType).asTerm
    val bodySym = newSymbol(owner, termName("body"), Flags.Synthetic, cfType).asTerm
    val origParamss = List(List(aSym, bodySym))

    // 対応する $transformed 版パラメータ（ControlContext 型）
    val aNew = newSymbol(owner, termName("a"), Flags.Synthetic, intType).asTerm
    val ccType = cpsTypeToCc(cfType)
    val bodyNew = newSymbol(owner, termName("body"), Flags.Synthetic, ccType).asTerm
    val newParamss: List[List[Tree]] = List(List(tpd.ref(aNew), tpd.ref(bodyNew)))

    val mapping = phase.buildParamMapping(origParamss, newParamss)

    assertEquals(mapping.size, 1, "only CPS param should be mapped")
    assert(mapping.contains(bodySym), "body (CPS) should be in mapping")
    assert(!mapping.contains(aSym), "a (pure) should not be in mapping")
    assertEquals(mapping(bodySym), bodyNew)
  }

  // Test 2: buildParamMapping — 全引数が純粋なら空マップ
  test("buildParamMapping: no CPS params → empty map") {
    val xSym = newSymbol(owner, termName("x"), Flags.Synthetic, intType).asTerm
    val origParamss = List(List(xSym))
    val xNew = newSymbol(owner, termName("x"), Flags.Synthetic, intType).asTerm
    val newParamss: List[List[Tree]] = List(List(tpd.ref(xNew)))

    val mapping = phase.buildParamMapping(origParamss, newParamss)
    assert(mapping.isEmpty, "pure params → empty mapping")
  }

  test("buildParamMapping: polymorphic CPS function param is mapped") {
    val polyParamType = PolyType(List(typeName("T")))(
      _ => List(TypeBounds.empty),
      pt => {
        val tParam = pt.paramRefs.head
        defn.FunctionOf(
          List(tParam),
          ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(tParam)), tParam, isContextual = true)
        )
      }
    )
    val origSym = newSymbol(owner, termName("f"), Flags.Synthetic, polyParamType).asTerm
    val mappedSym = newSymbol(owner, termName("f"), Flags.Synthetic, transformCpsValueType(polyParamType)).asTerm

    val mapping = phase.buildParamMapping(List(List(origSym)), List(List(tpd.ref(mappedSym))))

    assertEquals(mapping.size, 1, s"poly CPS parameter should be mapped, got $mapping")
    assertEquals(mapping(origSym), mappedSym)
  }

  test("buildParamMapping: container CPS param is kept out of direct CPS mapping") {
    val outerSym =
      newSymbol(
        owner,
        termName("outerContainerMappingParam"),
        Flags.Method,
        MethodType(Nil)(_ => Nil, _ => intType)
      ).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType(using freshCtx)))
    val origSym = newSymbol(outerSym, termName("xs"), Flags.Synthetic, listCpsType).asTerm
    val mappedSym =
      newSymbol(outerSym, termName("xs"), Flags.Synthetic, transformCpsValueType(listCpsType)(using freshCtx)).asTerm

    val mapping =
      phase.buildParamMapping(List(List(origSym)), List(List(tpd.ref(mappedSym)(using freshCtx))))(using freshCtx)

    assert(mapping.isEmpty, s"container CPS param should not be in direct CPS mapping, got $mapping")
  }

  test("buildEffectiveParamMapping: container CPS param is available with self entry") {
    val outerSym =
      newSymbol(
        owner,
        termName("outerContainerEffectiveParam"),
        Flags.Method,
        MethodType(Nil)(_ => Nil, _ => intType)
      ).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType(using freshCtx)))
    val transformedListType = transformCpsValueType(listCpsType)(using freshCtx)
    val origSym = newSymbol(outerSym, termName("xs"), Flags.Synthetic, listCpsType).asTerm
    val mappedSym = newSymbol(outerSym, termName("xs"), Flags.Synthetic, transformedListType).asTerm

    val mapping =
      phase.buildEffectiveParamMapping(List(List(origSym)), List(List(tpd.ref(mappedSym)(using freshCtx))))(using
        freshCtx
      )

    assertEquals(
      mapping.get(origSym),
      Some(mappedSym),
      s"container original should map to transformed param, got $mapping"
    )
    assertEquals(mapping.get(mappedSym), Some(mappedSym), s"container transformed param should self-map, got $mapping")
  }

  test("rewritePureBlockStats: container param alias is recovered through effective mapping") {
    val outerSym =
      newSymbol(
        owner,
        termName("outerContainerAliasParam"),
        Flags.Method,
        MethodType(Nil)(_ => Nil, _ => intType)
      ).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val listCpsType = ctx.definitions.ListClass.typeRef.appliedTo(List(cfType(using freshCtx)))
    val transformedListType = transformCpsValueType(listCpsType)(using freshCtx)
    val origSym = newSymbol(outerSym, termName("xs"), Flags.Synthetic, listCpsType).asTerm
    val mappedSym = newSymbol(outerSym, termName("xs"), Flags.Synthetic, transformedListType).asTerm
    val aliasSym = newSymbol(outerSym, termName("ys"), Flags.Synthetic, listCpsType).asTerm
    val aliasVal = tpd.ValDef(aliasSym, tpd.ref(origSym))(using freshCtx)
    val pm = phase.buildEffectiveParamMapping(List(List(origSym)), List(List(tpd.ref(mappedSym)(using freshCtx))))(using
      freshCtx
    )

    val (_, effectivePm) = phase.rewritePureBlockStats(List(aliasVal), pm)(using freshCtx)

    assertEquals(
      effectivePm.get(aliasSym),
      Some(mappedSym),
      s"container alias should map to transformed param, got $effectivePm"
    )
  }

  test("remapParamMappingForTree: same-name same-type shadowed CPS param is not inferred as alias") {
    val outerSym =
      newSymbol(owner, termName("outerShadowParam"), Flags.Method, MethodType(Nil)(_ => Nil, _ => intType)).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val outerBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val mappedBodySym =
      newSymbol(outerSym, termName("body"), Flags.Synthetic, cpsTypeToCc(cfType(using freshCtx))).asTerm
    val innerSym = {
      given Context = freshCtx
      val innerType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
      newSymbol(outerSym, termName("innerShadowParam"), Flags.Method, innerType).asTerm
    }
    val innerBodySym = innerSym.paramSymss.head.head.asTerm
    val shadowedRef = tpd.ref(innerBodySym)(using freshCtx)
    val innerDef = tpd.DefDef(innerSym, _ => shadowedRef)(using freshCtx)
    val pm = Map[Symbol, Symbol](outerBodySym -> mappedBodySym)

    val effectivePm = phase.remapParamMappingForTree(innerDef, pm)(using freshCtx)
    val rewritten = phase.transformCpsExpr(shadowedRef, effectivePm)(using freshCtx)

    assert(!effectivePm.contains(innerBodySym), s"shadowed CPS param must not be inferred as alias, got $effectivePm")
    assert(
      rewritten.symbol eq innerBodySym,
      s"shadowed CPS param should stay on the inner symbol, got ${rewritten.symbol}"
    )
  }

  test("remapParamMappingForTree: shadowed CPS param does not hide unrelated outer aliases") {
    val outerSym =
      newSymbol(owner, termName("outerMixedShadowParam"), Flags.Method, MethodType(Nil)(_ => Nil, _ => intType)).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val outerBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val outerOtherSym = newSymbol(outerSym, termName("other"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val mappedBodySym =
      newSymbol(outerSym, termName("body"), Flags.Synthetic, cpsTypeToCc(cfType(using freshCtx))).asTerm
    val mappedOtherSym =
      newSymbol(outerSym, termName("other"), Flags.Synthetic, cpsTypeToCc(cfType(using freshCtx))).asTerm
    val innerSym = {
      given Context = freshCtx
      val innerType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
      newSymbol(outerSym, termName("innerMixedShadowParam"), Flags.Method, innerType).asTerm
    }
    val innerBodySym = innerSym.paramSymss.head.head.asTerm
    val copiedOtherSym = newSymbol(innerSym, termName("other"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val innerDef = tpd.DefDef(innerSym, _ => tpd.ref(copiedOtherSym))(using freshCtx)
    val pm = Map[Symbol, Symbol](outerBodySym -> mappedBodySym, outerOtherSym -> mappedOtherSym)

    val effectivePm = phase.remapParamMappingForTree(innerDef, pm)(using freshCtx)

    assert(!effectivePm.contains(innerBodySym), s"shadowed CPS param must not be inferred as alias, got $effectivePm")
    assertEquals(
      effectivePm.get(copiedOtherSym),
      Some(mappedOtherSym),
      s"unshadowed outer CPS param copy should still be recovered, got $effectivePm"
    )
  }

  test("remapParamMappingForTree: same-name same-type local val is not inferred as alias") {
    val outerSym = newSymbol(
      owner,
      termName("outerLocalValShadowParam"),
      Flags.Method,
      MethodType(Nil)(_ => Nil, _ => intType)
    ).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val outerBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val mappedBodySym =
      newSymbol(outerSym, termName("body"), Flags.Synthetic, cpsTypeToCc(cfType(using freshCtx))).asTerm
    val localBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val localBody = tpd.ValDef(localBodySym, tpd.ref(outerBodySym))(using freshCtx)
    val rhs = tpd.Block(List(localBody), tpd.ref(localBodySym))(using freshCtx)
    val pm = Map[Symbol, Symbol](outerBodySym -> mappedBodySym)

    val effectivePm = phase.remapParamMappingForTree(rhs, pm)(using freshCtx)
    val rewritten = phase.transformCpsExpr(tpd.ref(localBodySym), effectivePm)(using freshCtx)

    assert(!effectivePm.contains(localBodySym), s"local val must not be inferred as alias, got $effectivePm")
    assert(rewritten.symbol eq localBodySym, s"local val reference should stay local, got ${rewritten.symbol}")
  }

  test("remapParamMappingForTree: same-name same-type pattern binder is not inferred as alias") {
    val outerSym = newSymbol(
      owner,
      termName("outerPatternShadowParam"),
      Flags.Method,
      MethodType(Nil)(_ => Nil, _ => intType)
    ).asTerm
    val freshCtx = ctx.withOwner(outerSym)
    val outerBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val mappedBodySym =
      newSymbol(outerSym, termName("body"), Flags.Synthetic, cpsTypeToCc(cfType(using freshCtx))).asTerm
    val patternBodySym = newSymbol(outerSym, termName("body"), Flags.Synthetic, cfType(using freshCtx)).asTerm
    val caseDef =
      tpd.CaseDef(tpd.BindTyped(patternBodySym, cfType(using freshCtx)), tpd.EmptyTree, tpd.ref(patternBodySym))(using
        freshCtx
      )
    val pm = Map[Symbol, Symbol](outerBodySym -> mappedBodySym)

    val effectivePm = phase.remapParamMappingForTree(caseDef, pm)(using freshCtx)
    val rewritten = phase.transformCpsExpr(tpd.ref(patternBodySym), effectivePm)(using freshCtx)

    assert(!effectivePm.contains(patternBodySym), s"pattern binder must not be inferred as alias, got $effectivePm")
    assert(rewritten.symbol eq patternBodySym, s"pattern binder reference should stay local, got ${rewritten.symbol}")
  }

  // Test 3: mkTransformedImpl — $transformed シンボルの DefDef が生成される
