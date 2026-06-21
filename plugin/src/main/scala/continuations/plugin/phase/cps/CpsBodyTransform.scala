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

trait CpsBodyTransformOps:
  self: SelectiveCPSTransform =>

  private def applySpineHasContainerCpsArg(tree: Tree)(using Context): Boolean =
    tree match
      case Apply(fun, args) =>
        args.exists(a => isContainerOfCpsAppliedType(a.tpe)) || applySpineHasContainerCpsArg(fun)
      case TypeApply(fun, _) => applySpineHasContainerCpsArg(fun)
      case _ => false

  private def applySpineRootSym(tree: Tree)(using Context): Symbol =
    tree match
      case Apply(fun, _)    => applySpineRootSym(fun)
      case TypeApply(fun, _) => applySpineRootSym(fun)
      case t                => t.symbol

  protected[plugin] def transBody(rhs: Tree, pm: Map[Symbol, Symbol], rType: Type)(using Context): Tree =
    rhs match
      case Block(stmts, expr) => transBlock(stmts, expr, pm, rType)
      case _ => transTailValue(rhs, pm, rType)

  protected[plugin] def transPureBody(rhs: Tree, pm: Map[Symbol, Symbol])(using Context): Tree =
    rhs match
      case Block(stmts, expr) =>
        val (rewrittenStats, bodyMapping) = rewritePureBlockStats(stmts, pm)
        tpd.Block(rewrittenStats, transPureBody(expr, bodyMapping))
      case If(cond, thenp, elsep) =>
        tpd.If(rewritePurePosition(cond, pm), transPureBody(thenp, pm), transPureBody(elsep, pm))
      case Match(sel, cases) =>
        tpd.Match(
          rewritePurePosition(sel, pm),
          cases.map(cd => tpd.CaseDef(cd.pat, rewritePurePosition(cd.guard, pm), transPureBody(cd.body, pm)))
        )
      case Try(body, cases, finalizer) =>
        tpd.Try(
          transPureBody(body, pm),
          cases.map(cd => tpd.CaseDef(cd.pat, rewritePurePosition(cd.guard, pm), transPureBody(cd.body, pm))),
          rewritePurePosition(finalizer, pm)
        )
      case _ =>
        transformCpsExpr(rhs, pm)

  protected def rewritePurePosition(tree: Tree, pm: Map[Symbol, Symbol])(using Context): Tree =
    rewriteLocalValRhsToTransformed(tree, NoType, NoType, pm)

  protected[plugin] def rewritePureBlockStats(stmts: List[Tree], pm: Map[Symbol, Symbol])(using
    Context
  ): (List[Tree], Map[Symbol, Symbol]) =
    val rewritten = scala.collection.mutable.ListBuffer.empty[Tree]
    var currentMapping = pm
    stmts.foreach {
      case vd: ValDef
          if isCpsValType(vd.symbol.info) &&
            !vd.symbol.is(Flags.Mutable) &&
            !vd.symbol.is(Flags.Lazy) =>
        normalizeCpsValuePosition(vd.rhs, currentMapping) match
          case Some(id: Ident) if id.symbol.exists =>
            currentMapping = currentMapping + (vd.symbol -> id.symbol)
          case _ =>
            rewritten += transformCpsExpr(
              rewriteLocalValRhsToTransformed(
                vd,
                vd.symbol.info,
                transformCpsValueType(vd.symbol.info),
                currentMapping
              ),
              currentMapping
            )
      case stat =>
        rewritten += transformCpsExpr(
          rewriteLocalValRhsToTransformed(stat, NoType, NoType, currentMapping),
          currentMapping
        )
    }
    (rewritten.toList, currentMapping)

  protected[plugin] def transBlock(stmts: List[Tree], expr: Tree, pm: Map[Symbol, Symbol], rType: Type)(using
    Context
  ): Tree =
    stmts match
      case Nil =>
        transTailValue(expr, pm, rType)
      case (vd: ValDef) :: rest if isCpsSymAnnotated(vd.symbol) =>
        val vSym = vd.symbol.asTerm
        val rhs1 = rehomeFromValDefOwner(transformCpsRhs(vd.rhs, pm, rType), vSym)
        val bodyExpr = transBlock(rest, expr, pm, rType)
        liftedPureBody(bodyExpr) match
          case Some(pureBody) => mkMap(rhs1, vSym, pureBody)
          case None if isControlContextType(bodyExpr.tpe.widen) => mkFlatMap(rhs1, vSym, bodyExpr)
          case None => mkMap(rhs1, vSym, bodyExpr)
      case (vd: ValDef) :: rest =>
        val vSym = vd.symbol.asTerm
        val rhs1 = rehomeFromValDefOwner(transformCpsRhs(vd.rhs, pm, rType), vSym)
        if isControlContextType(rhs1.tpe.widen) then
          val bodyExpr = transBlock(rest, expr, pm, rType)
          liftedPureBody(bodyExpr) match
            case Some(pureBody) => mkMap(rhs1, vSym, pureBody)
            case None if isControlContextType(bodyExpr.tpe.widen) => mkFlatMap(rhs1, vSym, bodyExpr)
            case None => mkMap(rhs1, vSym, bodyExpr)
        else
          val stm1 = transformCpsExpr(vd, pm)
          val body = transBlock(rest, expr, pm, rType)
          tpd.Block(List(stm1), body)
      case stm :: rest =>
        val stm1 = transformCpsExpr(stm, pm)
        val body = transBlock(rest, expr, pm, rType)
        tpd.Block(List(stm1), body)

  protected[plugin] def transTailValue(expr: Tree, pm: Map[Symbol, Symbol], rType: Type)(using Context): Tree =
    val t = transformCpsRhs(expr, pm, rType)
    if isControlContextType(t.tpe.widen) then t
    else
      val t2 = transformCpsExpr(t, pm)
      if isControlContextType(t2.tpe.widen) then t2
      else mkShiftUnitR(t2, rType)

  protected[plugin] def mkFlatMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
    val A1 = body.tpe.widen.argInfos(0)
    val flatMapSym = controlContextClass.requiredMethod("flatMap")
    val enclosingOwner = summon[Context].owner
    val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => body.tpe.widen)
    val lambda = tpd.Lambda(
      lambdaTpe,
      params =>
        val paramSym = params.head.symbol
        new TreeTypeMap(
          oldOwners = List(enclosingOwner),
          newOwners = List(paramSym.owner),
          substFrom = List(vSym),
          substTo = List(paramSym)
        ).transform(body)
    )
    cc.select(flatMapSym).appliedToType(A1).appliedTo(lambda)

  protected[plugin] def mkMap(cc: Tree, vSym: TermSymbol, body: Tree)(using Context): Tree =
    val A1 = body.tpe.widen
    val mapSym = controlContextClass.requiredMethod("map")
    val enclosingOwner = summon[Context].owner
    val lambdaTpe = MethodType(List(vSym.name))(_ => List(vSym.info), _ => A1)
    val lambda = tpd.Lambda(
      lambdaTpe,
      params =>
        val paramSym = params.head.symbol
        new TreeTypeMap(
          oldOwners = List(enclosingOwner),
          newOwners = List(paramSym.owner),
          substFrom = List(vSym),
          substTo = List(paramSym)
        ).transform(body)
    )
    cc.select(mapSym).appliedToType(A1).appliedTo(lambda)

  protected[plugin] def mkShiftUnitR(expr: Tree, rType: Type)(using Context): Tree =
    val A = expr.tpe.widen
    ref(shiftUnitRMethod).appliedToTypes(List(A, rType)).appliedTo(expr)

  protected def liftedPureBody(tree: Tree)(using Context): Option[Tree] = tree match
    case Apply(TypeApply(fun, _), List(expr)) if fun.symbol == shiftUnitRMethod => Some(expr)
    case Apply(fun, List(expr)) if fun.symbol == shiftUnitRMethod => Some(expr)
    case _ => None

  protected def exceptionType(using Context): Type =
    requiredClass("java.lang.Exception").typeRef

  protected def mkFlatMapCatch(cc: Tree, transformedCases: List[CaseDef])(using Context): Tree =
    val ccType = cc.tpe.widen
    val flatMapCatchSym = controlContextClass.requiredMethod("flatMapCatch")
    val handlerType = MethodType(List("ex".toTermName))(_ => List(exceptionType), _ => ccType)
    val handler = tpd.Lambda(
      handlerType,
      params =>
        val exRef = ref(params.head.symbol.asTerm)
        val defaultCase = tpd.CaseDef(tpd.Underscore(exceptionType), tpd.EmptyTree, tpd.Throw(exRef))
        tpd.Match(exRef, transformedCases :+ defaultCase)
    )
    cc.select(flatMapCatchSym).appliedToType(ccType.argInfos.head).appliedTo(handler)

  protected def rehomeFromValDefOwner(tree: Tree, oldOwner: Symbol)(using Context): Tree =
    new TreeTypeMap(oldOwners = List(oldOwner), newOwners = List(summon[Context].owner)).transform(tree)

  protected[plugin] def transformCpsRhs(tree: Tree, pm: Map[Symbol, Symbol], rType: Type)(using Context): Tree =
    tree match
      case Apply(Select(id: Ident, _), _) if pm.contains(id.symbol) =>
        ref(pm(id.symbol).asTerm)
      case Apply(id: Ident, _) if pm.contains(id.symbol) =>
        ref(pm(id.symbol).asTerm)
      case app: Apply if isCpsTransformFunctionType(app.tpe.widen) =>
        transformCpsExpr(app, pm)
      case Apply(Select(qual, _), _) if isCpsTransformFunctionType(qual.tpe) =>
        transformCpsExpr(qual, pm)
      case app @ Apply(_, args) if args.exists(a => isCpsTransformType(a.tpe)) =>
        transformCpsExpr(app, pm)
      case Typed(expr, tpt) =>
        val expr1 = transformCpsRhs(expr, pm, rType)
        if isControlContextType(expr1.tpe.widen) then expr1
        else tpd.Typed(expr1, tpt)
      case Block(stmts, expr) =>
        transBlock(stmts, expr, pm, rType)
      case If(cond, thenp, elsep) =>
        tpd.If(rewritePurePosition(cond, pm), transTailValue(thenp, pm, rType), transTailValue(elsep, pm, rType))
      case Match(sel, cases) =>
        val newCases = cases.map { cd =>
          tpd.CaseDef(cd.pat, rewritePurePosition(cd.guard, pm), transTailValue(cd.body, pm, rType))
        }
        tpd.Match(rewritePurePosition(sel, pm), newCases)
      case Try(body, cases, finalizer) =>
        val transformedCases = cases.map { cd =>
          tpd.CaseDef(cd.pat, rewritePurePosition(cd.guard, pm), transTailValue(cd.body, pm, rType))
        }
        val newBody = tpd.Try(transTailValue(body, pm, rType), transformedCases, rewritePurePosition(finalizer, pm))
        if finalizer.isEmpty then mkFlatMapCatch(newBody, transformedCases)
        else newBody
      case _ =>
        tree match
          case app: Apply
              if applySpineHasContainerCpsArg(app) &&
                applySpineRootSym(app).exists &&
                applySpineRootSym(app).owner.isClass &&
                !applySpineRootSym(app).owner.is(Flags.ModuleClass) =>
            val transformed = transformCpsExpr(app, pm)
            if transformed ne app then transformed
            else
              report.error(
                "class member def with CPS container parameter is not yet supported in CPS context",
                app.srcPos
              )
              tpd.ref(defn.Predef_undefined).ensureConforms(app.tpe)
          case _ =>
            tree
