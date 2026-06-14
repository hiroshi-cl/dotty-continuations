package continuations.plugin.phase.cps

import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.report

trait LocalValueRewriteOps:
  self: SelectiveCPSTransform =>

  protected def mkTransformedLocalValClosure(dd: DefDef, closure: Closure, transformedValueType: Type)(using
    ctx: Context
  ): Tree =
    val transformedSym = newSymbol(
      dd.symbol.owner,
      localTransformedName(dd.symbol.asTerm),
      dd.symbol.flags &~ Flags.GivenOrImplicit | Flags.Synthetic,
      transformCpsMethodType(dd.symbol.info),
      coord = dd.symbol.coord
    ).asTerm
    val transformedDef = tpd.DefDef(
      transformedSym,
      paramss => {
        val rehomedBody = new TreeTypeMap(
          oldOwners = List(dd.symbol),
          newOwners = List(transformedSym),
          substFrom = dd.symbol.paramSymss.flatten,
          substTo = paramss.flatten.map(_.symbol)
        ).transform(dd.rhs)(using ctx)
        val transformedCtx = ctx.withOwner(transformedSym)
        rewriteLocalValRhsToTransformed(
          rehomedBody,
          dd.symbol.info.finalResultType,
          transformCpsValueType(dd.symbol.info.finalResultType)
        )(using transformedCtx)
      }
    )
    tpd.Block(
      List(transformedDef),
      tpd.Closure(closure.env, tpd.ref(transformedSym), tpd.TypeTree(transformedValueType))
    )

  protected def rewriteLocalValRhsToTransformed(
    tree: Tree,
    originalType: Type,
    transformedType: Type,
    pm: Map[Symbol, Symbol] = Map.empty
  )(using Context): Tree =
    val widenedOriginal = originalType.dealias.widen
    if isCpsTransformFunctionType(widenedOriginal) then transformCpsCallSiteArg(tree)
    else
      tree match
        case Apply(Select(id: Ident, _), _) if pm.contains(id.symbol) =>
          ref(pm(id.symbol).asTerm)
        case Apply(id: Ident, _) if pm.contains(id.symbol) =>
          ref(pm(id.symbol).asTerm)
        case id: Ident if pm.contains(id.symbol) =>
          ref(pm(id.symbol).asTerm)
        case id: Ident =>
          val sym = lookupTransformedSym(id.symbol)
          if sym.exists then ref(sym.asTerm) else id
        case app: Apply =>
          transformedTupleElementAlias(app, pm).getOrElse {
            val widened = app.tpe.widen
            if isCpsTransformFunctionType(widened) then transformCpsExpr(app, pm)
            else if isCpsValType(widened) && isContainerOfCpsAppliedType(widened) then
              normalizeCpsValuePosition(app, pm)
                .getOrElse(rewriteContainerApplyArgs(app, pm))
            else if isCpsValType(widened) then transformCpsExpr(app, pm)
            else
              normalizeCpsValuePosition(app, pm).getOrElse {
                cpy.Apply(app)(
                  rewriteLocalValRhsToTransformed(app.fun, NoType, NoType, pm),
                  app.args.map(rewriteLocalValRhsToTransformed(_, NoType, NoType, pm))
                )
              }
          }
        case sel: Select =>
          transformedTupleElementSelect(sel, pm).getOrElse {
            normalizeCpsValuePosition(sel, pm).getOrElse {
              if isContainerOfCpsAppliedType(sel.tpe.widen) &&
                sel.symbol.exists &&
                sel.symbol.owner.isClass &&
                !sel.symbol.owner.is(Flags.ModuleClass) &&
                sel.symbol.is(Flags.Method) &&
                !lookupTransformedSym(sel.symbol).exists
              then
                report.error(
                  "class member def with CPS container return type is not yet supported in CPS context",
                  sel.srcPos
                )
              cpy.Select(sel)(rewriteLocalValRhsToTransformed(sel.qualifier, NoType, NoType, pm), sel.name)
            }
          }
        case tapp: TypeApply =>
          transformedTupleElementAlias(tapp, pm).getOrElse {
            normalizeCpsValuePosition(tapp, pm).getOrElse {
              cpy.TypeApply(tapp)(rewriteLocalValRhsToTransformed(tapp.fun, NoType, NoType, pm), tapp.args)
            }
          }
        case typed: Typed =>
          cpy.Typed(typed)(rewriteLocalValRhsToTransformed(typed.expr, originalType, transformedType, pm), typed.tpt)
        case inlined @ Inlined(call, bindings, expansion) =>
          cpy.Inlined(inlined)(
            rewriteLocalValRhsToTransformed(call, NoType, NoType, pm),
            bindings.map(rewriteLocalValRhsToTransformed(_, NoType, NoType, pm).asInstanceOf[MemberDef]),
            rewriteLocalValRhsToTransformed(expansion, originalType, transformedType, pm)
          )
        case Block(stats, closure: Closure) if isCpsValType(widenedOriginal) =>
          stats.collectFirst { case dd: DefDef if dd.symbol == closure.meth.symbol => dd } match
            case Some(dd) => mkTransformedLocalValClosure(dd, closure, transformedType)
            case None =>
              val targetSym = lookupTransformedSym(closure.meth.symbol)
              if targetSym.exists then
                cpy.Block(tree.asInstanceOf[Block])(
                  Nil,
                  cpy.Closure(closure)(env = closure.env, meth = ref(targetSym.asTerm), tpt = TypeTree(transformedType))
                )
              else tree
        case vd: ValDef =>
          val newRhs =
            rewriteLocalValRhsToTransformed(vd.rhs, vd.symbol.info, transformCpsValueType(vd.symbol.info), pm)
          cpy.ValDef(vd)(rhs = newRhs)
        case If(cond, thenp, elsep) =>
          tpd.If(
            rewriteLocalValRhsToTransformed(cond, NoType, NoType, pm),
            rewriteLocalValRhsToTransformed(thenp, originalType, transformedType, pm),
            rewriteLocalValRhsToTransformed(elsep, originalType, transformedType, pm)
          )
        case Match(sel, cases) =>
          val tupleRhs = tupleRhsForSelector(sel)
          tpd.Match(
            rewriteLocalValRhsToTransformed(sel, NoType, NoType, pm),
            cases.map(cd =>
              val patternAliasPaths =
                if tupleRhs.nonEmpty then patternBindPaths(cd.pat)
                else Map.empty[Symbol, List[Int]]
              tpd.CaseDef(
                cd.pat,
                rewriteLocalValRhsToTransformed(cd.guard, NoType, NoType, pm),
                rewritePatternBoundBody(cd.body, tupleRhs, patternAliasPaths, originalType, transformedType, pm)
              )
            )
          )
        case td: TypeDef if td.symbol.isClass =>
          polyApplyPlansByClass.get(td.symbol) match
            case Some((applyDef, transformedApplySym)) =>
              td.rhs match
                case template: Template =>
                  val transformedApplyDef = mkTransformedImpl(applyDef, transformedApplySym)
                  cpy.TypeDef(td)(rhs = cpy.Template(template)(body = template.body :+ transformedApplyDef))
                case _ =>
                  td
            case None =>
              td
        case dd: DefDef =>
          dd
        case Try(body, cases, finalizer) =>
          tpd.Try(
            rewriteLocalValRhsToTransformed(body, originalType, transformedType, pm),
            cases.map(cd =>
              tpd.CaseDef(
                cd.pat,
                rewriteLocalValRhsToTransformed(cd.guard, NoType, NoType, pm),
                rewriteLocalValRhsToTransformed(cd.body, originalType, transformedType, pm)
              )
            ),
            rewriteLocalValRhsToTransformed(finalizer, NoType, NoType, pm)
          )
        case Block(stats, expr) =>
          val rewrittenStats = stats.map(rewriteLocalValRhsToTransformed(_, NoType, NoType, pm))
          val rewrittenExpr = rewriteLocalValRhsToTransformed(expr, originalType, transformedType, pm)
          cpy.Block(tree.asInstanceOf[Block])(rewrittenStats, rewrittenExpr)
        case sl: SeqLiteral =>
          val elemTpt =
            if isCpsValType(sl.elemtpt.tpe.widen) then TypeTree(transformCpsValueType(sl.elemtpt.tpe.widen))
            else sl.elemtpt
          val rewritten = cpy.SeqLiteral(sl)(
            sl.elems.map { elem =>
              val elemTpe = elem.tpe.widen
              if isCpsValType(elemTpe) then
                rewriteLocalValRhsToTransformed(elem, elemTpe, transformCpsValueType(elemTpe), pm)
              else elem
            },
            elemTpt
          )
          if isCpsValType(sl.tpe.widen) then rewritten.withType(transformCpsValueType(sl.tpe.widen))
          else rewritten
        case _ =>
          tree

  protected def isContainerOfCpsAppliedType(tpe: Type)(using Context): Boolean =
    tpe.dealias.widen match
      case app: AppliedType =>
        val sym = app.tycon.typeSymbol
        !defn.isFunctionType(tpe) && sym.isClass && !sym.is(Flags.Opaque) &&
        app.args.exists(a => isCpsValType(a))
      case _ =>
        false

  protected def rewriteContainerApplyArgs(app: Apply, pm: Map[Symbol, Symbol])(using Context): Tree =
    val newArgs = app.args.map { arg =>
      val argType = arg.tpe.widen
      if isCpsValType(argType) then rewriteLocalValRhsToTransformed(arg, argType, transformCpsValueType(argType), pm)
      else arg
    }
    val newFun = app.fun match
      case ta @ TypeApply(prefix, targs) =>
        val newTargs = targs.map { t =>
          if isCpsValType(t.tpe) then TypeTree(transformCpsValueType(t.tpe)) else t
        }
        cpy.TypeApply(ta)(prefix, newTargs).withType(transformCpsValueType(ta.tpe.widen))
      case other =>
        other
    cpy.Apply(app)(newFun, newArgs).withType(transformCpsValueType(app.tpe.widen))

  protected def normalizeCpsValuePosition(tree: Tree, pm: Map[Symbol, Symbol])(using Context): Option[Tree] =
    def containsRawLocalCpsValue(tree: Tree): Boolean =
      var found = false
      new tpd.TreeTraverser:
        override def traverse(tree: Tree)(using Context): Unit =
          if !found then
            tree match
              case id: Ident if lookupLocalValPlan(id.symbol).nonEmpty =>
                found = true
              case _ =>
                traverseChildren(tree)
      .traverse(tree)
      found

    def rewrite(tree: Tree): Option[Tree] = tree match
      case id: Ident if pm.contains(id.symbol) =>
        Some(ref(pm(id.symbol).asTerm))
      case id: Ident =>
        val tSym = lookupTransformedSym(id.symbol)
        Option.when(tSym.exists)(ref(tSym.asTerm))
      case sel @ Select(qual, _) =>
        val tSym = lookupTransformedSym(sel.symbol)
        if tSym.exists && isCpsValType(sel.tpe.widen) then Some(rewriteFunToTransformed(sel, tSym))
        else
          rewrite(qual).map { newQual =>
            if sel.name == nme.apply && isControlContextType(newQual.tpe.widen) then newQual
            else cpy.Select(sel)(newQual, sel.name)
          }
      case tapp: TypeApply =>
        rewrite(tapp.fun).map(newFun => cpy.TypeApply(tapp)(newFun, tapp.args))
      case app: Apply =>
        rewrite(app.fun).map { newFun =>
          if isControlContextType(newFun.tpe.widen) &&
            app.args.exists(arg => isCpsTransformType(arg.tpe)) &&
            newFun.isInstanceOf[Select] &&
            !containsRawLocalCpsValue(newFun)
          then newFun
          else
            val applied = cpy.Apply(app)(
              newFun,
              app.args.map(arg => if isCpsValType(arg.tpe) then transformCpsCallSiteArg(arg, pm) else arg)
            )
            if app.args.isEmpty then stripNullaryApplyToNonMethod(applied, newFun.symbol) else applied
        }
      case _ =>
        None

    rewrite(tree)

  protected def transformedTupleElementAlias(tree: Tree, pm: Map[Symbol, Symbol], reportUnsupported: Boolean = true)(
    using Context
  ): Option[Tree] =
    tree match
      case sel: Select =>
        transformedTupleElementSelect(sel, pm, reportUnsupported).orElse {
          transformedTupleElementAlias(sel.qualifier, pm, reportUnsupported).map(newQual =>
            cpy.Select(sel)(newQual, sel.name)
          )
        }
      case Typed(expr, _) =>
        transformedTupleElementAlias(expr, pm, reportUnsupported)
      case tapp: TypeApply =>
        transformedTupleElementAlias(tapp.fun, pm, reportUnsupported).map(newFun =>
          cpy.TypeApply(tapp)(newFun, tapp.args)
        )
      case app: Apply =>
        transformedTupleElementAlias(app.fun, pm, reportUnsupported).map(newFun =>
          cpy.Apply(app)(
            newFun,
            app.args.map(arg => if isCpsValType(arg.tpe) then transformCpsCallSiteArg(arg, pm) else arg)
          )
        )
      case _ =>
        None

  protected def replacePatternAliases(
    tree: Tree,
    tupleRhs: Option[Tree],
    aliasPaths: Map[Symbol, List[Int]],
    pm: Map[Symbol, Symbol]
  )(using Context): Tree =
    tree match
      case id: Ident if aliasPaths.contains(id.symbol) && tupleRhs.nonEmpty =>
        transformedTupleElementShell(tupleRhs.get, aliasPaths(id.symbol), pm, reportUnsupported = true).getOrElse(id)
      case sel: Select =>
        cpy.Select(sel)(replacePatternAliases(sel.qualifier, tupleRhs, aliasPaths, pm), sel.name)
      case app: Apply =>
        cpy.Apply(app)(
          replacePatternAliases(app.fun, tupleRhs, aliasPaths, pm),
          app.args.map(replacePatternAliases(_, tupleRhs, aliasPaths, pm))
        )
      case tapp: TypeApply =>
        cpy.TypeApply(tapp)(replacePatternAliases(tapp.fun, tupleRhs, aliasPaths, pm), tapp.args)
      case typed: Typed =>
        cpy.Typed(typed)(replacePatternAliases(typed.expr, tupleRhs, aliasPaths, pm), typed.tpt)
      case vd: ValDef =>
        cpy.ValDef(vd)(rhs = replacePatternAliases(vd.rhs, tupleRhs, aliasPaths, pm))
      case Block(stats, expr) =>
        cpy.Block(tree.asInstanceOf[Block])(
          stats.map(replacePatternAliases(_, tupleRhs, aliasPaths, pm)),
          replacePatternAliases(expr, tupleRhs, aliasPaths, pm)
        )
      case Match(sel, cases) =>
        tpd.Match(
          replacePatternAliases(sel, tupleRhs, aliasPaths, pm),
          cases.map(cd =>
            tpd.CaseDef(
              cd.pat,
              replacePatternAliases(cd.guard, tupleRhs, aliasPaths, pm),
              replacePatternAliases(cd.body, tupleRhs, aliasPaths, pm)
            )
          )
        )
      case dd: DefDef =>
        dd
      case _ =>
        tree

  protected def rewritePatternBoundBody(
    tree: Tree,
    tupleRhs: Option[Tree],
    aliasPaths: Map[Symbol, List[Int]],
    originalType: Type,
    transformedType: Type,
    pm: Map[Symbol, Symbol]
  )(using Context): Tree =
    val aliased =
      if aliasPaths.isEmpty then tree
      else replacePatternAliases(tree, tupleRhs, aliasPaths, pm)
    rewriteLocalValRhsToTransformed(aliased, originalType, transformedType, pm)

  protected def tupleRhsForSelector(sel: Tree)(using Context): Option[Tree] =
    lookupLocalValDef(sel.symbol).map(_.rhs)

  protected def isTupleApply(fun: Tree)(using Context): Boolean =
    fun match
      case TypeApply(prefix, _) =>
        isTupleApply(prefix)
      case _ =>
        fun.symbol.exists &&
        fun.symbol.name == nme.apply &&
        fun.symbol.owner.fullName.toString.startsWith("scala.Tuple")

  protected def isTupleUnapply(fun: Tree)(using Context): Boolean =
    fun match
      case TypeApply(prefix, _) =>
        isTupleUnapply(prefix)
      case _ =>
        fun.symbol.exists &&
        fun.symbol.name == nme.unapply &&
        fun.symbol.owner.fullName.toString.startsWith("scala.Tuple")

  protected def patternBindPaths(pat: Tree)(using Context): Map[Symbol, List[Int]] =
    val result = scala.collection.mutable.LinkedHashMap.empty[Symbol, List[Int]]

    def loop(tree: Tree, path: List[Int]): Unit =
      tree match
        case bind: Bind =>
          if bind.symbol.exists then result(bind.symbol) = path
          loop(bind.body, path)
        case UnApply(fun, _, patterns) if isTupleUnapply(fun) =>
          patterns.zipWithIndex.foreach((p, i) => loop(p, path :+ i))
        case Typed(expr, _) =>
          loop(expr, path)
        case _ =>
          ()

    loop(pat, Nil)
    result.toMap

  protected def transformedTupleElementSelect(sel: Select, pm: Map[Symbol, Symbol], reportUnsupported: Boolean = true)(
    using Context
  ): Option[Tree] =
    for
      index <- tupleSelectorIndex(sel.name)
      tupleVal <- lookupLocalValDef(sel.qualifier.symbol)
      transformed <- transformedTupleElementShell(tupleVal.rhs, List(index), pm, reportUnsupported)
      if isCpsValType(sel.tpe.widen) || isCpsValType(transformed.tpe.widen)
    yield transformed

  protected def tupleSelectorIndex(name: Name): Option[Int] =
    val s = name.toString
    if s.startsWith("_") then s.drop(1).toIntOption.map(_ - 1).filter(_ >= 0)
    else None

  protected def tupleElementAtPath(tree: Tree, path: List[Int])(using Context): Option[Tree] =
    tree match
      case Typed(expr, _) =>
        tupleElementAtPath(expr, path)
      case Block(stats, expr) =>
        tupleElementAtPath(expr, path).filter { elem =>
          referencedSymbols(elem).intersect(definedSymbols(stats)).isEmpty
        }
      case _ if path.isEmpty =>
        Some(tree)
      case Apply(fun, args) if isTupleApply(fun) =>
        path match
          case index :: rest if index >= 0 && index < args.length =>
            tupleElementAtPath(args(index), rest)
          case _ =>
            None
      case _ =>
        None

  protected def strippedTupleElementShell(tree: Tree)(using Context): Option[Tree] =
    tree match
      case Typed(expr, _) =>
        strippedTupleElementShell(expr)
      case Block(stats, expr) =>
        strippedTupleElementShell(expr).filter { shell =>
          referencedSymbols(shell).intersect(definedSymbols(stats)).isEmpty
        }
      case _ =>
        Some(tree)

  protected def stableTransformedTupleElement(tree: Tree, pm: Map[Symbol, Symbol])(using Context): Option[Tree] =
    tree match
      case Typed(expr, _) =>
        stableTransformedTupleElement(expr, pm)
      case id: Ident if pm.contains(id.symbol) =>
        Some(ref(pm(id.symbol).asTerm))
      case id: Ident =>
        val sym = lookupTransformedSym(id.symbol)
        if sym.exists then Some(ref(sym.asTerm)) else None
      case _ =>
        None

  protected def transformedTupleElementShell(
    tupleRhs: Tree,
    path: List[Int],
    pm: Map[Symbol, Symbol],
    reportUnsupported: Boolean
  )(using Context): Option[Tree] =
    tupleElementAtPath(tupleRhs, path) match
      case Some(elem) if isCpsValType(elem.tpe.widen) =>
        strippedTupleElementShell(elem).flatMap(stableTransformedTupleElement(_, pm)).orElse {
          if reportUnsupported then reportUnsupportedTupleElement(elem)
          None
        }
      case Some(_) =>
        None
      case None =>
        if reportUnsupported then reportUnsupportedTupleElement(tupleRhs)
        None

  protected def reportUnsupportedTupleElement(tree: Tree)(using Context): Unit =
    report.error(
      "tuple CPS value element cannot be safely transformed without re-evaluating its RHS; bind it to a local val first",
      tree.srcPos
    )
