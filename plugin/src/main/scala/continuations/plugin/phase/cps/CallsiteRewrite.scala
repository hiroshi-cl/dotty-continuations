package continuations.plugin.phase.cps

import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Decorators.toTermName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

trait CallsiteRewriteOps:
  self: SelectiveCPSTransform =>

  protected[plugin] def transformCpsExpr(qual: Tree, pm: Map[Symbol, Symbol])(using Context): Tree = qual match
    case _ if rewriteMappedParamCallChain(qual, pm).nonEmpty =>
      rewriteMappedParamCallChain(qual, pm).get
    case _ if rewriteLocalValCallChainToTransformed(qual, pm).nonEmpty =>
      rewriteLocalValCallChainToTransformed(qual, pm).get
    case TypeApply(id: Ident, targs) =>
      val tSym = lookupTransformedSym(id.symbol)
      if tSym.exists then TypeApply(ref(tSym.asTerm), targs) else qual
    case Apply(TypeApply(fun, targs), args) =>
      transformedTupleElementAlias(qual, pm).getOrElse {
        val tSym = lookupTransformedSym(fun.symbol)
        if tSym.exists then
          Apply(
            rewriteFunToTransformed(TypeApply(fun, targs), tSym),
            args.map(a => if isCpsValType(a.tpe) then transformCpsCallSiteArg(a, pm) else rehomeClosureArgToCurrentOwner(a))
          )
        else qual
      }
    case Apply(Select(id: Ident, _), _) if pm.contains(id.symbol) =>
      ref(pm(id.symbol).asTerm)
    case id: Ident if pm.contains(id.symbol) =>
      ref(pm(id.symbol).asTerm)
    case id: Ident =>
      val tSym = lookupTransformedSym(id.symbol)
      if tSym.exists then ref(tSym.asTerm) else qual
    case Apply(id: Ident, _) if pm.contains(id.symbol) =>
      ref(pm(id.symbol).asTerm)
    case Apply(fun, args) =>
      transformedTupleElementAlias(qual, pm).getOrElse {
        val directUsingRewrite =
          if args.exists(arg => isCpsTransformType(arg.tpe)) ||
            (args.exists(arg => isCpsValType(arg.tpe)) && lookupAnyLocalPlan(fun.symbol).isEmpty)
          then rewriteDirectCpsUsingCall(fun, args, pm)
          else None
        directUsingRewrite
          .orElse(rewriteLocalMethodCallChainToTransformed(qual, pm))
          .orElse(rewriteMethodCallToTransformed(qual, fun, args, pm))
          .orElse(normalizeCpsValuePosition(qual, pm))
          .getOrElse(qual)
      }
    case sel: Select =>
      transformedTupleElementSelect(sel, pm).orElse(normalizeCpsValuePosition(sel, pm)).getOrElse(qual)
    case _ => qual

  protected def rewriteMappedParamCallChain(tree: Tree, pm: Map[Symbol, Symbol])(using Context): Option[Tree] =
    def rewrite(tree: Tree): Option[Tree] = tree match
      case id: Ident if pm.contains(id.symbol) =>
        Some(ref(pm(id.symbol).asTerm))
      case sel @ Select(qual, selName) =>
        rewrite(qual).map(newQual => cpy.Select(sel)(newQual, selName))
      case tapp: TypeApply =>
        rewrite(tapp.fun).map(newFun => cpy.TypeApply(tapp)(newFun, tapp.args))
      case app: Apply =>
        rewrite(app.fun).map(newFun =>
          val newArgs = app.args.collect {
            case arg if isCpsTransformType(arg.tpe) => None
            case arg if isCpsValType(arg.tpe) => Some(transformCpsCallSiteArg(arg, pm))
            case arg => Some(arg)
          }.flatten
          if newArgs.isEmpty then stripNullaryApplyToNonMethod(newFun, newFun.symbol)
          else cpy.Apply(app)(newFun, newArgs)
        )
      case _ =>
        None

    rewrite(tree)

  protected def rewriteDirectCpsUsingCall(fun: Tree, trailingArgs: List[Tree], pm: Map[Symbol, Symbol])(using
    Context
  ): Option[Tree] =
    val tSym = lookupTransformedSym(fun.symbol)
    Option.when(tSym.exists) {
      val newFun = rewriteFunToTransformed(fun, tSym)
      val newArgs = trailingArgs.collect {
        case a if isCpsTransformType(a.tpe) => None
        case a if isCpsValType(a.tpe) => Some(transformCpsCallSiteArg(a, pm))
        case a => Some(a)
      }.flatten
      if newArgs.isEmpty then newFun else Apply(newFun, newArgs)
    }

  protected def rewriteMethodCallToTransformed(tree: Tree, fun: Tree, args: List[Tree], pm: Map[Symbol, Symbol])(using
    Context
  ): Option[Tree] =
    val tSym = lookupTransformedSym(fun.symbol)
    Option.when(tSym.exists) {
      val applied = cpy.Apply(tree.asInstanceOf[Apply])(
        rewriteFunToTransformed(fun, tSym),
        args.map(arg => if isCpsValType(arg.tpe) then transformCpsCallSiteArg(arg, pm) else arg)
      )
      if args.isEmpty then stripNullaryApplyToNonMethod(applied, tSym) else applied
    }

  protected def rewriteLocalValCallChainToTransformed(tree: Tree, pm: Map[Symbol, Symbol] = Map.empty)(using
    Context
  ): Option[Tree] =
    def baseRef(plan: LocalValTransformPlan, invoked: Boolean): Tree =
      plan.polyApplyPlan match
        case Some((_, transformedApplySym)) if invoked =>
          ref(plan.transformedSym).select(transformedApplySym)
        case _ =>
          ref(plan.transformedSym)

    def rewrite(tree: Tree, invoked: Boolean): Option[Tree] = tree match
      case id: Ident =>
        lookupLocalValPlan(id.symbol).map(plan => baseRef(plan, invoked))
      case sel @ Select(id: Ident, selName) if selName == nme.apply =>
        lookupLocalValPlan(id.symbol).map { plan =>
          plan.polyApplyPlan match
            case Some((_, transformedApplySym)) =>
              ref(plan.transformedSym).select(transformedApplySym)
            case None =>
              ref(plan.transformedSym).select(selName)
        }
      case sel @ Select(qual, selName) =>
        rewrite(qual, selName == nme.apply).map(newQual => cpy.Select(sel)(newQual, selName))
      case tapp: TypeApply =>
        rewrite(tapp.fun, invoked = true).map(newFun => cpy.TypeApply(tapp)(newFun, tapp.args))
      case app: Apply =>
        rewrite(app.fun, invoked = true).map(newFun =>
          cpy.Apply(app)(
            newFun,
            app.args.map(arg => if isCpsValType(arg.tpe) then transformCpsCallSiteArg(arg, pm) else arg)
          )
        )
      case _ =>
        None

    rewrite(tree, invoked = false)

  protected def rewriteLocalMethodCallChainToTransformed(tree: Tree, pm: Map[Symbol, Symbol] = Map.empty)(using
    Context
  ): Option[Tree] =
    def rewrite(tree: Tree): Option[Tree] = tree match
      case id: Ident =>
        lookupOriginalLocalPlan(id.symbol).map(plan => ref(plan.transformedSym))
      case sel @ Select(qual, selName) =>
        rewrite(qual).map(newQual => cpy.Select(sel)(newQual, selName))
      case tapp: TypeApply =>
        rewrite(tapp.fun).map(newFun => cpy.TypeApply(tapp)(newFun, tapp.args))
      case app: Apply =>
        rewrite(app.fun).map(newFun =>
          val applied = cpy.Apply(app)(
            newFun,
            app.args.map(arg => if isCpsValType(arg.tpe) then transformCpsCallSiteArg(arg, pm) else arg)
          )
          if app.args.isEmpty then stripNullaryApplyToNonMethod(applied, newFun.symbol) else applied
        )
      case _ =>
        None

    rewrite(tree)

  protected[plugin] def rewriteFunToTransformed(fun: Tree, transformedSym: Symbol)(using Context): Tree = fun match
    case Apply(prefix, args) =>
      Apply(rewriteFunToTransformed(prefix, transformedSym), args)
    case TypeApply(prefix, targs) =>
      TypeApply(rewriteFunToTransformed(prefix, transformedSym), targs)
    case Select(qual, _) =>
      qual.select(transformedSym)
    case _ =>
      ref(transformedSym.asTerm)

  protected def stripNullaryApplyToNonMethod(tree: Tree, transformedSym: Symbol)(using Context): Tree =
    tree match
      case Apply(prefix, Nil) if !transformedSym.info.isInstanceOf[MethodType] =>
        prefix
      case _ =>
        tree

  protected def lookupTransformedSym(sym: Symbol)(using Context): Symbol =
    if !sym.exists then NoSymbol
    else
      lookupAnyLocalPlan(sym) match
        case Some(local) => local.transformedSym
        case None =>
          lookupLocalValPlan(sym) match
            case Some(localVal) => localVal.transformedSym
            case None =>
              val tname = (sym.name.toString + "$transformed").toTermName
              val ownerSym =
                // Stub/ANF normally see member vals as class-owned because Constructors has not run yet. Keep this
                // fallback aligned with SelectiveCPSStubPhase.transformedOwner for defensive lookup of later tree
                // shapes that expose a constructor-owned field symbol.
                if sym.owner.isConstructor && sym.owner.owner.exists then sym.owner.owner
                else sym.owner
              val candidates = ownerSym.info.decl(tname).alternatives.map(_.symbol)
              val expectedType = transformCpsMethodType(sym.info)
              candidates.find(c => c.info =:= expectedType).getOrElse {
                candidates.filter(_.coord == sym.coord) match
                  case single :: Nil => single
                  case _ =>
                    candidates match
                      case List(single) => single
                      case _            => NoSymbol
              }

  protected def findPolyApplyDef(tree: Tree)(using Context): Option[DefDef] =
    var result: Option[DefDef] = None
    new tpd.TreeTraverser:
      override def traverse(tree: Tree)(using Context): Unit =
        if result.isEmpty then
          tree match
            case dd: DefDef
                if dd.name == nme.apply &&
                  isCpsTransformFunctionType(dd.symbol.info.finalResultType) =>
              result = Some(dd)
            case _ =>
              traverseChildren(tree)
    .traverse(tree)
    result

  protected def findAllPolyApplyDefs(tree: Tree)(using Context): List[DefDef] =
    val result = scala.collection.mutable.ListBuffer.empty[DefDef]
    var classDepth = 0
    new tpd.TreeTraverser:
      override def traverse(tree: Tree)(using Context): Unit =
        tree match
          case td: TypeDef if td.isClassDef =>
            classDepth += 1
            traverseChildren(td)
            classDepth -= 1
          case dd: DefDef
              if classDepth == 1 &&
                dd.name == nme.apply &&
                isCpsTransformFunctionType(dd.symbol.info.finalResultType) =>
            result += dd
          case _: DefDef => ()
          case _ =>
            traverseChildren(tree)
    .traverse(tree)
    result.toList

  protected def transformCpsCallSiteArg(arg: Tree, pm: Map[Symbol, Symbol] = Map.empty)(using ctx: Context): Tree =
    val argType = arg.tpe.widen
    if isCpsTransformFunctionType(argType) then
      arg match
        case Block(List(dd: DefDef), _: Closure) =>
          val rType = dd.symbol.info.finalResultType
          val cleanRhs = new TreeTypeMap(oldOwners = List(dd.symbol), newOwners = List(ctx.owner)).transform(dd.rhs)
          transBody(cleanRhs, pm, rType)
        case _ =>
          transformCpsExpr(arg, pm)
    else rewriteLocalValRhsToTransformed(arg, argType, transformCpsValueType(argType), pm)

