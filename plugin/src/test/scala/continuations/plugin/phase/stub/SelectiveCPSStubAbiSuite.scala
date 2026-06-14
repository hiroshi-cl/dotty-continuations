package continuations.plugin.phase.stub

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.{termName, typeName}
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

class SelectiveCPSStubAbiSuite extends munit.FunSuite with SelectiveCPSStubPhaseSuiteBase:

  test("mkTransformedStub: preserves the original body and creates only a sibling placeholder") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("preserveOriginal"), Flags.Method, methType).asTerm
    val originalRhs = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42))
    val original = tpd.DefDef(methSym, _ => originalRhs)

    val stub = phase.mkTransformedStub(original)

    assert(original.rhs eq originalRhs, "stub generation must not replace the original RHS")
    assert(stub.symbol.is(Flags.Synthetic), "generated sibling should be synthetic")
    assert(
      stub.rhs.symbol eq defn.Predef_undefined,
      s"generated sibling should contain only the placeholder body, got ${stub.rhs}"
    )
  }

  // Test 1: CPS 戻り値メソッド → $transformed スタブが生成される
  test("mkTransformedStub: CPS return type → stub name and ControlContext result type") {
    val dd = mkCpsReturnDef("m0")
    val stub = phase.mkTransformedStub(dd)

    assertEquals(stub.name.toString, "m0$transformed")
    assert(
      isControlContextType(stub.symbol.info.finalResultType),
      s"stub result type should be ControlContext, got ${stub.symbol.info.finalResultType}"
    )
  }

  // Test 2: CPS 引数メソッド → $transformed スタブのパラメータ型が ControlContext
  test("mkTransformedStub: CPS arg type → stub param type is ControlContext") {
    val dd = mkCpsArgDef("foo")
    assert(needsTransformedStub(dd.symbol), "CPS context-function arg method should need a stub")
    val stub = phase.mkTransformedStub(dd)

    assertEquals(stub.name.toString, "foo$transformed")
    val paramType = stub.symbol.info.asInstanceOf[MethodType].paramInfos.head
    assert(isControlContextType(paramType), s"stub param type should be ControlContext, got $paramType")
    assertEquals(stub.symbol.info.finalResultType, intType, "result type should remain Int (non-CPS)")
  }

  // Test 3: CPS 引数 + CPS 戻り値両方 → 全 CPS 型が ControlContext に変換される
  test("mkTransformedStub: CPS arg + CPS return → both converted to ControlContext") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => cfType)
    val methSym = newSymbol(owner, termName("bar"), Flags.Method, methType).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    val stub = phase.mkTransformedStub(dd)

    val stubMt = stub.symbol.info.asInstanceOf[MethodType]
    assert(isControlContextType(stubMt.paramInfos.head), "param should be ControlContext")
    assert(isControlContextType(stubMt.resType), "result should be ControlContext")
  }

  // Test 4: 型パラメータ付きメソッド → PolyType 内の CPS 型が変換される
  test("mkTransformedStub: type-parameterized CPS method → PolyType CPS types converted") {
    val polyType = PolyType(List(typeName("T")))(
      _ => List(TypeBounds.empty),
      pt => {
        val tParam = pt.paramRefs.head
        MethodType(List(termName("x")))(_ => List(cfType), _ => tParam)
      }
    )
    val methSym = newSymbol(owner, termName("polyFoo"), Flags.Method, polyType).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    val stub = phase.mkTransformedStub(dd)

    assertEquals(stub.name.toString, "polyFoo$transformed")
    val stubPoly = stub.symbol.info.asInstanceOf[PolyType]
    val innerMt = stubPoly.resType.asInstanceOf[MethodType]
    assert(
      isControlContextType(innerMt.paramInfos.head),
      s"inner param should be ControlContext, got ${innerMt.paramInfos.head}"
    )
  }

  // Test 5: multiple parameter list → 各リストの CPS 引数が変換される
  test("mkTransformedStub: multiple param lists → CPS params converted in each list") {
    val inner = MethodType(List(termName("b")))(_ => List(cfType), _ => intType)
    val outer = MethodType(List(termName("a")))(_ => List(intType), _ => inner)
    val methSym = newSymbol(owner, termName("multi"), Flags.Method, outer).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    val stub = phase.mkTransformedStub(dd)

    val stubOuter = stub.symbol.info.asInstanceOf[MethodType]
    assertEquals(stubOuter.paramInfos.head, intType, "first param list: a stays Int")
    val stubInner = stubOuter.resType.asInstanceOf[MethodType]
    assert(
      isControlContextType(stubInner.paramInfos.head),
      s"second param list: b should be ControlContext, got ${stubInner.paramInfos.head}"
    )
  }

  test("mkTransformedStub: polymorphic CPS function parameter → parameter type is transformed recursively") {
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
    val methType = MethodType(List(termName("f")))(_ => List(polyParamType), _ => intType)
    val methSym = newSymbol(owner, termName("polyParam"), Flags.Method, methType).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    val stub = phase.mkTransformedStub(dd)

    val stubMt = stub.symbol.info.asInstanceOf[MethodType]
    val transformedParam = stubMt.paramInfos.head.asInstanceOf[PolyType].resType
    assert(
      defn.isFunctionType(transformedParam),
      s"poly parameter should remain a function value, got $transformedParam"
    )
    assert(
      isControlContextType(transformedParam.argInfos.last),
      s"poly parameter CPS leaf should become ControlContext, got ${transformedParam.argInfos.last}"
    )
  }

  test("mkTransformedStub: non-tail CPS callback parameter in function value is transformed") {
    val callbackType = defn.FunctionOf(List(intType), cfType)
    val runnerType = defn.FunctionOf(List(callbackType, intType), intType)
    val methType = MethodType(List(termName("runner")))(_ => List(runnerType), _ => intType)
    val methSym = newSymbol(owner, termName("nonTailCallback"), Flags.Method, methType).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    val stub = phase.mkTransformedStub(dd)

    assert(needsTransformedStub(methSym), "function value with CPS callback parameter should need a stub")
    val stubMt = stub.symbol.info.asInstanceOf[MethodType]
    val transformedRunner = stubMt.paramInfos.head
    assert(defn.isFunctionType(transformedRunner), s"runner should stay a function value, got $transformedRunner")
    assert(
      isControlContextType(transformedRunner.argInfos.head.argInfos.last),
      s"callback parameter leaf should become ControlContext, got ${transformedRunner.argInfos.head}"
    )
    assertEquals(
      transformedRunner.argInfos(1),
      intType,
      s"non-CPS runner parameter should remain Int, got ${transformedRunner.argInfos(1)}"
    )
    assertEquals(stub.symbol.info.finalResultType, intType, "pure method result should remain Int")
  }

  test("mkTransformedStub: abstract CPS function value method keeps abstract transformed ABI") {
    val methSym = newSymbol(owner, termName("abstractMake"), Flags.Method | Flags.Deferred, cfType).asTerm
    val dd = tpd.DefDef(methSym, _ => EmptyTree)
    val stub = phase.mkTransformedStub(dd)

    assert(stub.symbol.is(Flags.Deferred), "abstract original should produce abstract transformed stub")
    assert(stub.rhs.isEmpty, "abstract transformed stub should not get a placeholder body")
    assert(
      isControlContextType(stub.symbol.info.finalResultType),
      s"abstract stub result should be ControlContext, got ${stub.symbol.info.finalResultType}"
    )
  }

  test("mkTransformedStub: direct using CpsTransform parameter is dropped and makes result ControlContext") {
    val dd = mkUsingCpsDef("usingCps")
    assert(needsTransformedStub(dd.symbol), "direct CpsTransform using parameter should need a stub")
    assert(!hasUnsupportedDirectCpsTransformParam(dd.symbol), "single trailing using CpsTransform should be supported")
    val stub = phase.mkTransformedStub(dd)

    assertEquals(stub.name.toString, "usingCps$transformed")
    assert(
      isControlContextType(stub.symbol.info.finalResultType),
      s"stub result type should be ControlContext, got ${stub.symbol.info}"
    )
    assert(
      !stub.symbol.info.isInstanceOf[MethodType],
      s"nullary using marker should drop to a value, got ${stub.symbol.info}"
    )
  }

  test("mkTransformedStub: empty-paren direct using CpsTransform keeps empty MethodType") {
    val dd = mkUsingCpsEmptyParenDef("usingCpsEmpty")
    assert(needsTransformedStub(dd.symbol), "empty-paren using CpsTransform method should need a stub")
    assert(!hasUnsupportedDirectCpsTransformParam(dd.symbol), "empty-paren trailing using marker should be supported")
    val stub = phase.mkTransformedStub(dd)

    val stubMt = stub.symbol.info.asInstanceOf[MethodType]
    assertEquals(stubMt.paramInfos, Nil, "empty-paren parameter clause should be preserved")
    assert(isControlContextType(stubMt.resType), s"stub result should be ControlContext, got ${stubMt.resType}")
  }

  test("mkTransformedStub: curried direct using CpsTransform parameter is dropped after regular params") {
    val inner = ContextualMethodType(List(termName("ctx")))(_ => List(cpsType), _ => intType)
    val outer = MethodType(List(termName("x")))(_ => List(intType), _ => inner)
    val methSym = newSymbol(owner, termName("usingCpsCurried"), Flags.Method, outer).asTerm
    val dd = tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
    assert(
      !hasUnsupportedDirectCpsTransformParam(dd.symbol),
      "trailing using marker after regular params should be supported"
    )
    val stub = phase.mkTransformedStub(dd)

    val stubMt = stub.symbol.info.asInstanceOf[MethodType]
    assertEquals(stubMt.paramInfos.head, intType, "regular parameter should remain")
    assert(isControlContextType(stubMt.resType), s"stub result should be ControlContext, got ${stubMt.resType}")
  }
