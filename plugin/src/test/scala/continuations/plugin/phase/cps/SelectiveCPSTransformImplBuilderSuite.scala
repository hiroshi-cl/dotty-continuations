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

class SelectiveCPSTransformImplBuilderSuite extends munit.FunSuite with SelectiveCPSTransformSuiteBase:

  test("transformDefDef: CPS phase replaces the original concrete CPS method body") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("replaceOriginal"), Flags.Method, methType).asTerm
    val original = tpd.DefDef(methSym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))

    val result = phase.transformDefDef(original).asInstanceOf[DefDef]

    assert(
      treeExists(result.rhs)(_.symbol eq defn.Predef_undefined),
      s"CPS phase should disable the original body, got ${result.rhs}"
    )
  }

  test("mkTransformedImpl: CPS phase replaces the transformed placeholder with an implementation") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("replaceTransformedPlaceholder"), Flags.Method, methType).asTerm
    val orig = tpd.DefDef(methSym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))
    val stub = new SelectiveCPSStubPhase().mkTransformedStub(orig)

    val result = phase.mkTransformedImpl(orig, stub.symbol.asTerm)

    assert(
      !treeExists(result.rhs)(_.symbol eq defn.Predef_undefined),
      s"transformed implementation should replace the placeholder body, got ${result.rhs}"
    )
  }

  test("mkTransformedImpl: TreeTypeMap updates nested lambda owner to transformedSym") {
    // orig: def m_ownertest: CpsTransform[Int] ?=> Int
    val methSym = newSymbol(owner, termName("m_ownertest"), Flags.Method, cfType).asTerm
    val cpsParamType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunMethType = MethodType(List(termName("$ctx")))(_ => List(cpsParamType), _ => intType)
    val anonfunSym = newSymbol(methSym, termName("$anonfun"), Flags.Synthetic | Flags.Method, anonfunMethType).asTerm

    // anonfun.rhs に inner lambda（owned by anonfunSym）を埋め込む
    val innerType = MethodType(Nil)(_ => Nil, _ => intType)
    val innerSym = newSymbol(anonfunSym, termName("$inner"), Flags.Synthetic | Flags.Method, innerType).asTerm
    val innerDef = tpd.DefDef(innerSym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(42)))
    val innerClosure = tpd.Closure(Nil, tpd.ref(innerSym), tpd.EmptyTree)
    val anonfunBody = tpd.Block(List(innerDef), innerClosure)

    val anonfunDef = tpd.DefDef(anonfunSym, _ => anonfunBody)
    val outerClosure = tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.EmptyTree)

    val origDef = tpd.DefDef(methSym, _ => tpd.Block(List(anonfunDef), outerClosure))

    // $transformed スタブ生成（別シンボルで mkTransformedStub）
    val baseOrigDef = tpd.DefDef(
      newSymbol(owner, termName("m_ownertest"), Flags.Method, cfType).asTerm,
      _ => tpd.ref(defn.Predef_undefined)
    )
    val stub = new SelectiveCPSStubPhase().mkTransformedStub(baseOrigDef)
    val transformedSym = stub.symbol.asTerm

    val result = phase.mkTransformedImpl(origDef, transformedSym)

    // result ツリーを走査して "$inner" DefDef のオーナーを確認
    var foundOwner: Option[Symbol] = None
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString == "$inner" =>
          foundOwner = Some(dd.symbol.owner)
        case _ => traverseChildren(t)
    }.traverse(result)

    foundOwner match
      case Some(o) =>
        assert(!(o eq anonfunSym), s"owner must NOT be anonfunSym (TreeTypeMap not applied)")
        assert(o eq transformedSym, s"owner should be transformedSym, got $o")
      case None =>
        fail("inner DefDef '$inner' not found in result tree")
  }

  test("mkTransformedImpl: CPS arg pure return with reset rewrites to reset$transformed and mapped param") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("m_reset_arg"), Flags.Method, methType).asTerm
    val bodySym = methSym.paramSymss.head.head.asTerm
    val rhs = mkResetCallWithCtx { ctxSym =>
      val cpsValSym = newSymbol(methSym, termName("$cps0"), Flags.Synthetic, intType).asTerm
      addCpsAnnotation(cpsValSym)
      val cpsVal = tpd.ValDef(cpsValSym, mkCpsParamApply(bodySym, ctxSym))
      tpd.Block(List(cpsVal), tpd.ref(cpsValSym))
    }
    val orig = tpd.DefDef(methSym, _ => rhs)
    val transformedSym = new SelectiveCPSStubPhase().mkTransformedStub(orig).symbol.asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)

    assert(treeExists(result.rhs)(_.symbol == resetTransformedMethod), "reset$transformed should be called")
    assert(
      treeExists(result.rhs) {
        case ref: RefTree => isControlContextType(ref.tpe.widen)
        case _ => false
      },
      s"transformed body should reference a ControlContext-typed param, got ${result.rhs}"
    )
  }

  test("mkTransformedImpl: CPS arg pure return lifts pure reset body with ControlContext.map") {
    val pureSym = newSymbol(
      owner,
      termName("pureWrap"),
      Flags.Method,
      MethodType(List(termName("x")))(_ => List(intType), _ => intType)
    ).asTerm
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName("m_map_arg"), Flags.Method, methType).asTerm
    val bodySym = methSym.paramSymss.head.head.asTerm
    val rhs = mkResetCallWithCtx { ctxSym =>
      val cpsValSym = newSymbol(methSym, termName("$cps0"), Flags.Synthetic, intType).asTerm
      addCpsAnnotation(cpsValSym)
      val cpsVal = tpd.ValDef(cpsValSym, mkCpsParamApply(bodySym, ctxSym))
      val pureExpr = tpd.Apply(tpd.ref(pureSym), List(tpd.ref(cpsValSym)))
      tpd.Block(List(cpsVal), pureExpr)
    }
    val orig = tpd.DefDef(methSym, _ => rhs)
    val transformedSym = new SelectiveCPSStubPhase().mkTransformedStub(orig).symbol.asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)
    assert(treeExists(result.rhs)(_.symbol == resetTransformedMethod), "reset$transformed should be called")
    assert(
      treeExists(result.rhs)(_.symbol.name.toString == "map"),
      s"pure reset body should use ControlContext.map, got ${result.rhs}"
    )
  }

  test("mkTransformedImpl: multiple CPS args pure return rewrites both params to ControlContext") {
    val methType = MethodType(List(termName("b1"), termName("b2")))(_ => List(cfType, cfType), _ => intType)
    val methSym = newSymbol(owner, termName("m_multi_arg"), Flags.Method, methType).asTerm
    val b1Sym = methSym.paramSymss.head.head.asTerm
    val b2Sym = methSym.paramSymss.head(1).asTerm
    val rhs = mkResetCallWithCtx { ctxSym =>
      val cps0Sym = newSymbol(methSym, termName("$cps0"), Flags.Synthetic, intType).asTerm
      val cps1Sym = newSymbol(methSym, termName("$cps1"), Flags.Synthetic, intType).asTerm
      addCpsAnnotation(cps0Sym)
      addCpsAnnotation(cps1Sym)
      val cps0 = tpd.ValDef(cps0Sym, mkCpsParamApply(b1Sym, ctxSym))
      val cps1 = tpd.ValDef(cps1Sym, mkCpsParamApply(b2Sym, ctxSym))
      tpd.Block(List(cps0, cps1), tpd.ref(cps1Sym))
    }
    val orig = tpd.DefDef(methSym, _ => rhs)
    val transformedSym = new SelectiveCPSStubPhase().mkTransformedStub(orig).symbol.asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)
    assert(treeExists(result.rhs)(_.symbol == resetTransformedMethod), "reset$transformed should be called")
    assert(
      treeExists(result.rhs) {
        case ref: RefTree => isControlContextType(ref.tpe.widen)
        case _ => false
      },
      s"transformed body should reference rewritten ControlContext params, got ${result.rhs}"
    )
    assert(
      treeExists(result.rhs)(_.symbol.name.toString == "flatMap"),
      s"multiple CPS params should compose via ControlContext, got ${result.rhs}"
    )
  }

  test("mkTransformedImpl: CPS arg + CPS return keeps transBody and param mapping") {
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => cfType)
    val methSym = newSymbol(owner, termName("m_both"), Flags.Method, methType).asTerm
    val bodySym = methSym.paramSymss.head.head.asTerm
    val cpsCtxType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType = MethodType(List(termName("$ctx")))(_ => List(cpsCtxType), _ => intType)
    val anonfunSym = newSymbol(methSym, termName("$anonfun"), Flags.Synthetic | Flags.Method, anonfunType).asTerm
    val ctxSym = anonfunSym.paramSymss.head.head.asTerm
    val anonfunDef = tpd.DefDef(anonfunSym, _ => mkCpsParamApply(bodySym, ctxSym))
    val rhs = tpd.Block(List(anonfunDef), tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.TypeTree(cfType)))
    val orig = tpd.DefDef(methSym, _ => rhs)
    val transformedSym = new SelectiveCPSStubPhase().mkTransformedStub(orig).symbol.asTerm

    val result = phase.mkTransformedImpl(orig, transformedSym)
    assert(isControlContextType(result.rhs.tpe.widen), s"rhs should stay ControlContext, got ${result.rhs.tpe.widen}")
    assert(
      treeExists(result.rhs) {
        case ref: RefTree => isControlContextType(ref.tpe.widen)
        case _ => false
      },
      s"CPS return branch should still use the rewritten ControlContext param, got ${result.rhs}"
    )
  }

  test("mkMap: nested lambda body owner is remapped to outer lambda owner") {
    val result1Sym = newSymbol(owner, termName("result1"), Flags.Synthetic, intType).asTerm
    val result2Sym = newSymbol(owner, termName("result2"), Flags.Synthetic, intType).asTerm
    val cc1 = mkCcTree()
    val cc2 = mkCcTree()
    val innerBody = phase.mkMap(cc2, result2Sym, tpd.Literal(dotty.tools.dotc.core.Constants.Constant(400)))
    val outerBody = phase.mkMap(cc1, result1Sym, innerBody)

    var outerLambdaOwner: Option[Symbol] = None
    var innerLambdaOwner: Option[Symbol] = None
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString.startsWith("$anonfun") =>
          if outerLambdaOwner.isEmpty then outerLambdaOwner = Some(dd.symbol)
          else if innerLambdaOwner.isEmpty then innerLambdaOwner = Some(dd.symbol.owner)
          traverseChildren(t)
        case _ =>
          traverseChildren(t)
    }.traverse(outerBody)

    (outerLambdaOwner, innerLambdaOwner) match
      case (Some(outerOwner), Some(innerOwner)) =>
        assert(
          innerOwner eq outerOwner,
          s"inner lambda owner should be remapped to outer lambda owner, got $innerOwner expected $outerOwner"
        )
      case _ =>
        fail("expected nested lambda structure in mkMap result")
  }

  test("mkFlatMap: nested lambda body owner is remapped to outer lambda owner") {
    val result1Sym = newSymbol(owner, termName("result1"), Flags.Synthetic, intType).asTerm
    val result2Sym = newSymbol(owner, termName("result2"), Flags.Synthetic, intType).asTerm
    val cc1 = mkCcTree()
    val cc2 = mkCcTree()
    val innerBody = phase.mkFlatMap(cc2, result2Sym, mkCcTree())
    val outerBody = phase.mkFlatMap(cc1, result1Sym, innerBody)

    var outerLambdaOwner: Option[Symbol] = None
    var innerLambdaOwner: Option[Symbol] = None
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString.startsWith("$anonfun") =>
          if outerLambdaOwner.isEmpty then outerLambdaOwner = Some(dd.symbol)
          else if innerLambdaOwner.isEmpty then innerLambdaOwner = Some(dd.symbol.owner)
          traverseChildren(t)
        case _ =>
          traverseChildren(t)
    }.traverse(outerBody)

    (outerLambdaOwner, innerLambdaOwner) match
      case (Some(outerOwner), Some(innerOwner)) =>
        assert(
          innerOwner eq outerOwner,
          s"inner lambda owner should be remapped to outer lambda owner, got $innerOwner expected $outerOwner"
        )
      case _ =>
        fail("expected nested lambda structure in mkFlatMap result")
  }

  test("transBlock: sequential CPS ValDefs create nested lambda owner chain") {
    val result1Sym = newSymbol(owner, termName("result1"), Flags.Synthetic, intType).asTerm
    addCpsAnnotation(result1Sym)
    val result2Sym = newSymbol(owner, termName("result2"), Flags.Synthetic, intType).asTerm
    addCpsAnnotation(result2Sym)
    val vd1 = tpd.ValDef(result1Sym, mkCcTree())
    val vd2 = tpd.ValDef(result2Sym, mkCcTree())

    val result =
      phase.transBlock(List(vd1, vd2), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), Map.empty, intType)

    var lambdaOwners = List.empty[Symbol]
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString.startsWith("$anonfun") =>
          lambdaOwners = lambdaOwners :+ dd.symbol.owner
          traverseChildren(t)
        case _ =>
          traverseChildren(t)
    }.traverse(result)

    assert(lambdaOwners.nonEmpty, "expected nested lambdas in sequential CPS transBlock")
    assert(lambdaOwners.exists(_ != owner), s"expected non-top-level lambda owner chain, got $lambdaOwners")
  }

  test("rewriteFunToTransformed: qualified TypeApply preserves receiver") {
    val cc = mkCcTree()
    val mapSym = controlContextClass.requiredMethod("map")
    val flatMapSym = controlContextClass.requiredMethod("flatMap")

    val fun = cc.select(mapSym).appliedToType(intType)
    val result = phase.rewriteFunToTransformed(fun, flatMapSym)

    result match
      case TypeApply(sel: Select, targs) =>
        assert(sel.qualifier.eq(cc), "receiver should be preserved")
        assert(sel.symbol eq flatMapSym, s"rewritten symbol should be flatMap, got ${sel.symbol}")
        assertEquals(targs.length, 1, "type args should be preserved")
      case other =>
        fail(s"expected qualified TypeApply, got ${other.getClass.getSimpleName}: $other")
  }

  test("rewriteFunToTransformed: curried apply preserves prefix args and receiver") {
    val cc = mkCcTree()
    val mapSym = controlContextClass.requiredMethod("map")
    val flatMapSym = controlContextClass.requiredMethod("flatMap")
    val lambdaTpe = MethodType(List(termName("x")))(_ => List(intType), _ => intType)
    val lambda = tpd.Lambda(lambdaTpe, params => ref(params.head.symbol.asTerm))
    val appliedFun = Apply(cc.select(mapSym).appliedToType(intType), List(lambda))

    val result = phase.rewriteFunToTransformed(appliedFun, flatMapSym)

    result match
      case Apply(TypeApply(sel: Select, targs), List(arg)) =>
        assert(sel.qualifier.eq(cc), "receiver should be preserved")
        assert(sel.symbol eq flatMapSym, s"rewritten symbol should be flatMap, got ${sel.symbol}")
        assertEquals(targs.length, 1, "type args should be preserved")
        assert(arg eq lambda, "already-applied prefix args should be preserved")
      case other =>
        fail(s"expected curried Apply(TypeApply(Select(...))), got ${other.getClass.getSimpleName}: $other")
  }

  test("transBlock: CPS ValDef rhs local defs are remapped away from ValDef owner") {
    val vdOwner = newSymbol(owner, termName("result2"), Flags.Synthetic, intType).asTerm
    val localDefSym = newSymbol(
      vdOwner,
      termName("$eta"),
      Flags.Synthetic | Flags.Method,
      MethodType(Nil)(_ => Nil, _ => intType)
    ).asTerm
    val localDef = tpd.DefDef(localDefSym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(7)))
    val cc = mkCcTree()
    val blockRhs = tpd.Block(List(localDef), cc)
    val vd = tpd.ValDef(vdOwner, blockRhs)

    val result =
      phase.transBlock(List(vd), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(0)), Map.empty, intType)

    var foundOwner: Option[Symbol] = None
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit = t match
        case dd: DefDef if dd.name.toString == "$eta" =>
          foundOwner = Some(dd.symbol.owner)
          traverseChildren(t)
        case _ =>
          traverseChildren(t)
    }.traverse(result)

    foundOwner match
      case Some(o) =>
        assert(!(o eq vdOwner), s"local def owner must not remain eliminated ValDef owner: $o")
      case None =>
        fail("local def '$eta' not found in transformed tree")
  }

  // Test for transformApply — CPS引数なし → unchanged
