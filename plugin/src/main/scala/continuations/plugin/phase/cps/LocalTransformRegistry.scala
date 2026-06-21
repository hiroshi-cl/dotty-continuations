package continuations.plugin.phase.cps

import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Decorators.toTermName
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.report

trait LocalTransformRegistryOps:
  self: SelectiveCPSTransform =>

  protected val PolyApplyTransformedName = "apply$transformed".toTermName

  protected case class LocalTransformPlan(originalDef: DefDef, transformedSym: TermSymbol)
  protected case class LocalValTransformPlan(
    originalVal: ValDef,
    transformedSym: TermSymbol,
    polyApplyPlan: Option[(DefDef, TermSymbol)]
  )
  protected case class ParamRemap(
    substFrom: List[Symbol],
    substTo: List[Symbol],
    // Direct CPS leaves map to ControlContext; container mappings retain their transformed outer type.
    cpsMapping: Map[Symbol, Symbol],
    containerCpsMapping: Map[Symbol, Symbol] = Map.empty
  ):
    def effectiveMapping: Map[Symbol, Symbol] =
      cpsMapping ++
        cpsMapping.valuesIterator.map(sym => sym -> sym) ++
        containerCpsMapping ++
        containerCpsMapping.valuesIterator.map(sym => sym -> sym)

  protected val localPlansByOriginal = scala.collection.mutable.HashMap.empty[Symbol, LocalTransformPlan]
  protected val localPlansByTransformed = scala.collection.mutable.HashMap.empty[Symbol, LocalTransformPlan]
  protected val originalDefsByTransformed = scala.collection.mutable.HashMap.empty[Symbol, DefDef]
  protected val localValPlansByOriginal = scala.collection.mutable.HashMap.empty[Symbol, LocalValTransformPlan]
  protected val localValPlansByTransformed = scala.collection.mutable.HashMap.empty[Symbol, LocalValTransformPlan]
  protected val localValDefsBySymbol = scala.collection.mutable.HashMap.empty[Symbol, ValDef]
  protected val polyApplyPlansByClass = scala.collection.mutable.HashMap.empty[Symbol, (DefDef, TermSymbol)]

  protected def clearLocalTransformState()(using Context): Unit =
    localPlansByOriginal.clear()
    localPlansByTransformed.clear()
    originalDefsByTransformed.clear()
    localValPlansByOriginal.clear()
    localValPlansByTransformed.clear()
    localValDefsBySymbol.clear()
    polyApplyPlansByClass.clear()

  protected def collectTransformedPlans(tree: Tree)(using ctx: Context): Unit =
    new tpd.TreeTraverser:
      override def traverse(tree: Tree)(using Context): Unit =
        tree match
          case dd: DefDef if needsTransformedStub(dd.symbol) && !isTransformedStub(dd.symbol) =>
            if hasUnsupportedDirectCpsTransformParam(dd.symbol) then
              report.error(UnsupportedDirectCpsTransformParamMessage, dd.srcPos)
            else if needsLocalTransformedStub(dd.symbol) then
              // reset body closure ($anonfun + Synthetic + CpsTransform param) 内の local def は未サポート
              val ownerIsResetClosure =
                dd.symbol.owner.name.toString.startsWith("$anonfun") &&
                  dd.symbol.owner.is(Flags.Synthetic) &&
                  hasCpsTransformParam(dd.symbol.owner)
              if ownerIsResetClosure then
                report.error(
                  "local CPS def inside reset body is not yet supported; move it outside the reset block",
                  dd.srcPos
                )
              else if isMultiArgCpsContextFunctionTpe(dd.symbol.info) then
                report.error(
                  "context function types with CpsTransform alongside other context parameters are not yet supported",
                  dd.srcPos
                )
              else
                registerLocalTransform(dd)
                localPlanFor(dd.symbol.asTerm).foreach(plan => originalDefsByTransformed(plan.transformedSym) = dd)
            else
              val tname = (dd.symbol.name.toString + "$transformed").toTermName
              val candidates = dd.symbol.owner.info.decl(tname).alternatives.map(_.symbol)
              val expectedType = transformCpsMethodType(dd.symbol.info)
              val transformedSym =
                candidates.find(_.info =:= expectedType).getOrElse(
                  candidates.filter(_.coord == dd.symbol.coord) match
                    case single :: Nil => single
                    case _ =>
                      candidates match
                        case List(single) => single
                        case _            => NoSymbol
                )
              if transformedSym.exists then originalDefsByTransformed(transformedSym) = dd
            traverseChildren(tree)
          case vd: ValDef =>
            if vd.symbol.exists && !vd.symbol.owner.isClass && !vd.symbol.owner.is(Flags.Package) then
              localValDefsBySymbol(vd.symbol) = vd
            if isLocalCpsVal(vd.symbol) then registerLocalValTransform(vd)
            traverseChildren(tree)
          case _ =>
            traverseChildren(tree)
    .traverse(tree)

  protected def registerLocalTransform(originalDef: DefDef)(using Context): Unit =
    val origSym = originalDef.symbol.asTerm
    if !localPlansByOriginal.contains(origSym) then
      val transformedSym = newSymbol(
        origSym.owner,
        localTransformedName(origSym),
        origSym.flags &~ (Flags.Deferred | Flags.GivenOrImplicit) | Flags.Synthetic,
        transformCpsMethodType(origSym.info),
        coord = origSym.coord
      ).asTerm.entered
      val plan = LocalTransformPlan(originalDef, transformedSym)
      localPlansByOriginal(origSym) = plan
      localPlansByTransformed(transformedSym) = plan
      originalDefsByTransformed(transformedSym) = originalDef

  protected def localPlanFor(origSym: TermSymbol)(using Context): Option[LocalTransformPlan] =
    localPlansByOriginal.get(origSym)

  protected def lookupOriginalLocalPlan(sym: Symbol)(using Context): Option[LocalTransformPlan] =
    if !sym.exists then None
    else
      localPlansByOriginal.get(sym).orElse {
        localPlansByOriginal.valuesIterator.find { plan =>
          plan.originalDef.symbol.exists &&
          plan.originalDef.symbol.owner == sym.owner &&
          plan.originalDef.symbol.coord == sym.coord &&
          plan.originalDef.symbol.name == sym.name &&
          (plan.originalDef.symbol.info =:= sym.info)
        }
      }

  protected def lookupTransformedLocalPlan(sym: Symbol)(using Context): Option[LocalTransformPlan] =
    if !sym.exists then None
    else localPlansByTransformed.get(sym)

  protected def lookupAnyLocalPlan(sym: Symbol)(using Context): Option[LocalTransformPlan] =
    lookupOriginalLocalPlan(sym).orElse(lookupTransformedLocalPlan(sym))

  protected def rawLocalCpsValueRef(tree: Tree)(using Context): Boolean =
    tree match
      case id: Ident =>
        lookupLocalValPlan(id.symbol).nonEmpty
      case inlined: Inlined =>
        rawLocalCpsValueRef(inlined.expansion)
      case typed: Typed =>
        rawLocalCpsValueRef(typed.expr)
      case Block(_, expr) =>
        rawLocalCpsValueRef(expr)
      case _ =>
        false

  protected def reportLocalCpsRuntimeUse(tree: Tree)(using Context): Unit =
    report.error(LocalCpsValueRuntimeMessage, tree.srcPos)

  protected def rawLocalCpsValueArgs(args: List[Tree])(using Context): Boolean =
    args.exists(rawLocalCpsValueRef)

  protected def rawLocalCpsValueTail(tree: Tree)(using Context): Boolean =
    tree match
      case Block(_, expr) =>
        rawLocalCpsValueTail(expr)
      case inlined: Inlined =>
        rawLocalCpsValueTail(inlined.expansion)
      case typed: Typed =>
        rawLocalCpsValueTail(typed.expr)
      case If(_, thenp, elsep) =>
        rawLocalCpsValueTail(thenp) || rawLocalCpsValueTail(elsep)
      case Match(_, cases) =>
        cases.exists(cd => rawLocalCpsValueTail(cd.body))
      case Try(body, cases, _) =>
        rawLocalCpsValueTail(body) || cases.exists(cd => rawLocalCpsValueTail(cd.body))
      case _ =>
        rawLocalCpsValueRef(tree)

  protected def registerLocalValTransform(originalVal: ValDef)(using Context): Unit =
    val origSym = originalVal.symbol.asTerm
    if !localValPlansByOriginal.contains(origSym) then
      val polyApplyPlan = findPolyApplyDef(originalVal.rhs).flatMap { applyDef =>
        val allApplyDefs = findAllPolyApplyDefs(originalVal.rhs)
        if allApplyDefs.size > 1 then
          report.error(
            "multiple CPS-valued apply overloads on a single class are not yet supported; use a single apply overload",
            originalVal.srcPos
          )
          None
        else
          val transformedApplySym = newSymbol(
            applyDef.symbol.owner,
            PolyApplyTransformedName,
            applyDef.symbol.flags | Flags.Synthetic,
            transformCpsMethodType(applyDef.symbol.info),
            coord = applyDef.symbol.coord
          ).asTerm.entered
          polyApplyPlansByClass(applyDef.symbol.owner) = (applyDef, transformedApplySym)
          Some((applyDef, transformedApplySym))
      }
      val transformedSym = newSymbol(
        origSym.owner,
        localTransformedName(origSym),
        origSym.flags | Flags.Synthetic,
        transformCpsValueType(origSym.info),
        coord = origSym.coord
      ).asTerm.entered
      val plan = LocalValTransformPlan(originalVal, transformedSym, polyApplyPlan)
      localValPlansByOriginal(origSym) = plan
      localValPlansByTransformed(transformedSym) = plan

  protected def emitLocalCpsValPair(vd: ValDef)(using Context): List[Tree] =
    localValDefsBySymbol(vd.symbol) = vd
    val plan = localValPlanFor(vd.symbol.asTerm).orElse {
      registerLocalValTransform(vd)
      localValPlanFor(vd.symbol.asTerm)
    }
    plan match
      case Some(plan) =>
        val (prefix, storedVal) =
          if isStrictLocalCpsVal(vd.symbol) || isLocalMutableCpsVal(vd.symbol) then
            val (hoisted, strippedRhs) = splitStorageRhsPrelude(vd.rhs)
            (hoisted, cpy.ValDef(vd)(rhs = strippedRhs))
          else (Nil, vd)
        val transformed =
          if isLocalLazyCpsVal(vd.symbol) then mkLocalLazyValTransformed(vd, plan.transformedSym)
          else mkLocalValTransformed(storedVal, plan.transformedSym)
        prefix ++ List(transformed)
      case None =>
        List(vd)

  protected def mkStorageTmpVal(rhs: Tree, valueType: Type)(using ctx: Context): ValDef =
    val tmpSym = newSymbol(
      ctx.owner,
      SelectiveCPSTransform.StorageTmpName.fresh(),
      Flags.Synthetic,
      valueType,
      coord = rhs.span
    ).asTerm.entered
    tpd.ValDef(tmpSym, rhs)

  protected def emitMutableLocalCpsValThroughTmp(vd: ValDef)(using Context): List[Tree] =
    val tmpVal = mkStorageTmpVal(vd.rhs, vd.symbol.info)
    val tmpTrees = emitLocalCpsValPair(tmpVal)
    val storedVar = cpy.ValDef(vd)(rhs = ref(tmpVal.symbol.asTerm))
    val varTrees = emitLocalCpsValPair(storedVar)
    tmpTrees ++ varTrees

  protected def localValPlanFor(origSym: TermSymbol)(using Context): Option[LocalValTransformPlan] =
    localValPlansByOriginal.get(origSym)

  protected def lookupLocalValPlan(sym: Symbol)(using Context): Option[LocalValTransformPlan] =
    if !sym.exists then None
    else
      localValPlansByOriginal.get(sym).orElse {
        localValPlansByOriginal.valuesIterator.find { plan =>
          plan.originalVal.symbol.exists &&
          plan.originalVal.symbol.owner == sym.owner &&
          plan.originalVal.symbol.coord == sym.coord &&
          plan.originalVal.symbol.name == sym.name &&
          (plan.originalVal.symbol.info =:= sym.info)
        }
      }

  protected def localTransformedName(origSym: TermSymbol)(using Context): dotty.tools.dotc.core.Names.TermName =
    s"${origSym.name}$$transformed$$${origSym.id}".toTermName

  protected def mkLocalValTransformed(orig: ValDef, transformedSym: TermSymbol)(using ctx: Context): ValDef =
    mkLocalValTransformedFromRhs(orig, transformedSym, orig.rhs)

  protected def mkLocalValTransformedFromRhs(orig: ValDef, transformedSym: TermSymbol, rhs: Tree)(using
    ctx: Context
  ): ValDef =
    val rehomedRhs =
      new TreeTypeMap(oldOwners = List(orig.symbol), newOwners = List(transformedSym)).transform(rhs)
    val rewrittenRhs =
      given Context = ctx.withOwner(transformedSym)
      rewriteLocalValRhsToTransformed(rehomedRhs, orig.symbol.info, transformedSym.info)
    tpd.ValDef(transformedSym, rewrittenRhs)

  protected def mkLocalLazyValTransformed(orig: ValDef, transformedSym: TermSymbol)(using ctx: Context): ValDef =
    val (prefix, strippedRhs) = splitStorageRhsPrelude(orig.rhs)
    val capturedPrefixSyms = referencedSymbols(strippedRhs).intersect(definedSymbols(prefix))
    if capturedPrefixSyms.nonEmpty then
      report.error(
        "local lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def",
        orig.srcPos
      )
    if prefix.isEmpty then mkLocalValTransformed(orig, transformedSym)
    else mkLocalValTransformed(orig, transformedSym)

  protected def definedSymbols(trees: List[Tree])(using Context): Set[Symbol] =
    val result = scala.collection.mutable.LinkedHashSet.empty[Symbol]
    trees.foreach {
      new tpd.TreeTraverser:
        override def traverse(tree: Tree)(using Context): Unit =
          tree match
            case vd: ValDef =>
              if vd.symbol.exists then result += vd.symbol
              traverseChildren(tree)
            case dd: DefDef =>
              if dd.symbol.exists then result += dd.symbol
            case _ =>
              traverseChildren(tree)
      .traverse(_)
    }
    result.toSet

  protected def referencedSymbols(tree: Tree)(using Context): Set[Symbol] =
    val result = scala.collection.mutable.LinkedHashSet.empty[Symbol]
    new tpd.TreeTraverser:
      override def traverse(tree: Tree)(using Context): Unit =
        tree match
          case id: Ident if id.symbol.exists =>
            result += id.symbol
            traverseChildren(tree)
          case sel: Select if sel.symbol.exists =>
            result += sel.symbol
            traverseChildren(tree)
          case _ =>
            traverseChildren(tree)
    .traverse(tree)
    result.toSet

  protected def lookupLocalValDef(sym: Symbol)(using Context): Option[ValDef] =
    if !sym.exists then None
    else
      localValDefsBySymbol.get(sym).orElse {
        localValDefsBySymbol.valuesIterator.find { vd =>
          vd.symbol.exists &&
          vd.symbol.owner == sym.owner &&
          vd.symbol.coord == sym.coord &&
          vd.symbol.name == sym.name &&
          (vd.symbol.info =:= sym.info)
        }
      }

  protected def replaceOriginalBody(dd: DefDef)(using Context): DefDef =
    if isCpsTransformFunctionType(dd.symbol.info.finalResultType) then
      dd.rhs match
        case block @ Block(List(anonfun: DefDef), _: Closure) =>
          val cleanAnonfun = cpy.DefDef(anonfun)(rhs = tpd.ref(defn.Predef_undefined).ensureConforms(anonfun.tpe.widen))
          cpy.DefDef(dd)(rhs = cpy.Block(block)(List(cleanAnonfun), block.expr))
        case _ =>
          cpy.DefDef(dd)(rhs = tpd.ref(defn.Predef_undefined).ensureConforms(dd.tpe.widen))
    else if isCpsValType(dd.symbol.info.finalResultType) then dd
    else cpy.DefDef(dd)(rhs = tpd.ref(defn.Predef_undefined).ensureConforms(dd.tpe.widen))

  protected def findOriginalDefDef(transformedSym: TermSymbol)(using ctx: Context): Option[DefDef] =
    originalDefsByTransformed.get(transformedSym)
