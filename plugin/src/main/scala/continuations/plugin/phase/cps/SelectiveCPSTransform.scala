package continuations.plugin.phase.cps

import continuations.plugin.CPSUtils
import continuations.plugin.phase.anf.SelectiveANFTransform
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameKinds.UniqueNameKind
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

class SelectiveCPSTransform
    extends PluginPhase
    with CPSUtils
    with LocalTransformRegistryOps
    with LocalValueRewriteOps
    with TransformedImplBuilderOps
    with CpsBodyTransformOps
    with CallsiteRewriteOps:
  val phaseName = SelectiveCPSTransform.name
  override val runsAfter: Set[String] = Set(SelectiveANFTransform.name)
  override val runsBefore: Set[String] = Set("elimByName")
  override def relaxedTypingInGroup: Boolean = true

  override def prepareForUnit(tree: Tree)(using ctx: Context): Context =
    clearLocalTransformState()
    collectTransformedPlans(tree)
    ctx

  override def transformDefDef(tree: DefDef)(using ctx: Context): Tree =
    if isTransformedStub(tree.symbol) &&
      tree.symbol.is(Flags.Synthetic) &&
      !tree.symbol.is(Flags.Deferred)
    then
      lookupTransformedLocalPlan(tree.symbol) match
        case Some(plan) => mkTransformedImpl(plan.originalDef, plan.transformedSym)
        case None =>
          findOriginalDefDef(tree.symbol.asTerm) match
            case Some(orig) => mkTransformedImpl(orig, tree.symbol.asTerm)
            case None => tree
    else if needsTransformedStub(tree.symbol) && !tree.symbol.is(Flags.Deferred) then
      if needsLocalTransformedStub(tree.symbol) then replaceOriginalBody(tree)
      else replaceOriginalBody(tree)
    else if rawLocalCpsValueTail(tree.rhs) then
      reportLocalCpsRuntimeUse(tree.rhs)
      tree
    else tree

  override def transformSelect(tree: Select)(using ctx: Context): Tree =
    if tree.name.toString == "apply" &&
      tree.symbol.exists &&
      tree.symbol.name.toString == "apply" &&
      isControlContextType(tree.qualifier.tpe.widen)
    then tree.qualifier
    else if tree.name != nme.apply &&
      rawLocalCpsValueRef(tree.qualifier) &&
      !isContainerOfCpsAppliedType(tree.qualifier.tpe.widen)
    then
      reportLocalCpsRuntimeUse(tree)
      tree
    else tree

  override def transformStats(trees: List[Tree])(using ctx: Context): List[Tree] =
    val localDefHelpers = trees.flatMap {
      case dd: DefDef if needsLocalTransformedStub(dd.symbol) =>
        val plan = localPlanFor(dd.symbol.asTerm).orElse {
          registerLocalTransform(dd)
          localPlanFor(dd.symbol.asTerm)
        }
        plan.map(plan => mkTransformedImpl(plan.originalDef, plan.transformedSym))
      case _ =>
        None
    }
    val rewrittenTrees = trees.flatMap {
      case vd: ValDef if isLocalCpsVal(vd.symbol) =>
        if isLocalMutableCpsVal(vd.symbol) &&
          storageRhsPreludeHasImmediateCps(vd.rhs)
        then emitMutableLocalCpsValThroughTmp(vd)
        else if isLocalLazyCpsVal(vd.symbol) &&
          storageRhsPreludeHasImmediateCps(vd.rhs)
        then
          report.error(
            "local lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def",
            vd.srcPos
          )
          emitLocalCpsValPair(vd)
        else emitLocalCpsValPair(vd)
      case vd: ValDef if rawLocalCpsValueTail(vd.rhs) =>
        reportLocalCpsRuntimeUse(vd.rhs)
        List(vd)
      case dd: DefDef
          if needsLocalTransformedStub(dd.symbol) &&
            hasCpsTransformParam(dd.symbol) &&
            !hasUnsupportedDirectCpsTransformParam(dd.symbol) =>
        Nil
      case other =>
        List(other)
    }
    localDefHelpers ++ rewrittenTrees

  protected def needsLocalTransformedStub(sym: Symbol)(using Context): Boolean =
    needsTransformedStub(sym) &&
      !isTransformedStub(sym) &&
      !sym.owner.isClass &&
      !sym.owner.is(Flags.Package)

  override def transformTemplate(tree: Template)(using ctx: Context): Tree =
    val newBody = tree.body.flatMap {
      case vd: ValDef if isStrictMemberCpsVal(vd.symbol) || isMutableMemberCpsVal(vd.symbol) =>
        val tSym = lookupTransformedSym(vd.symbol.asTerm)
        if !tSym.exists then List(vd)
        else
          val nullRhs = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(null)).cast(vd.symbol.info)
          val nullStub = cpy.ValDef(vd)(rhs = nullRhs)
          val transformedRhs = rewriteLocalValRhsToTransformed(vd.rhs, vd.symbol.info, tSym.info)
          val transformedVd = tpd.ValDef(tSym.asTerm, transformedRhs)
          List(nullStub, transformedVd)
      case vd: ValDef if isLazyMemberCpsVal(vd.symbol) =>
        val tSym = lookupTransformedSym(vd.symbol.asTerm)
        if !tSym.exists then List(vd)
        else
          val (prefix, strippedRhs) = splitStorageRhsPrelude(vd.rhs)
          if storageRhsPreludeHasImmediateCps(vd.rhs) then
            report.error(
              "member lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def",
              vd.srcPos
            )
          val capturedPrefixSyms = referencedSymbols(strippedRhs).intersect(definedSymbols(prefix))
          if capturedPrefixSyms.nonEmpty then
            report.error(
              "member lazy val CPS storage RHS cannot capture initializer prelude values; use a strict val or def",
              vd.srcPos
            )
          val nullRhs = tpd.Literal(dotty.tools.dotc.core.Constants.Constant(null)).cast(vd.symbol.info)
          val nullStub = cpy.ValDef(vd)(rhs = nullRhs)
          val transformedRhs = rewriteLocalValRhsToTransformed(vd.rhs, vd.symbol.info, tSym.info)
          val transformedVd = tpd.ValDef(tSym.asTerm, transformedRhs)
          List(nullStub, transformedVd)
      case vd: ValDef if isTransformedStub(vd.symbol) && vd.symbol.is(Flags.Synthetic) =>
        Nil
      case dd: DefDef if isConcreteSyntheticTransformedStub(dd.symbol) =>
        List(findOriginalDefDef(dd.symbol.asTerm) match
          case Some(orig) => mkTransformedImpl(orig, dd.symbol.asTerm)
          case None => dd)
      case other =>
        List(other)
    }
    cpy.Template(tree)(body = newBody)

  private def isConcreteSyntheticTransformedStub(sym: Symbol)(using Context): Boolean =
    isTransformedStub(sym) &&
      sym.is(Flags.Synthetic) &&
      !sym.is(Flags.Deferred)

  private def emitStoragePrefix(prefix: List[Tree])(using Context): List[Tree] =
    prefix.flatMap {
      case vd: ValDef if isLocalCpsVal(vd.symbol) =>
        emitLocalCpsValPair(vd)
      case other =>
        List(other)
    }

  private def stripEmptyBlocks(tree: Tree)(using Context): Tree =
    tree match
      case Block(Nil, expr) => stripEmptyBlocks(expr)
      case typed: Typed => cpy.Typed(typed)(stripEmptyBlocks(typed.expr), typed.tpt)
      case other => other

  private def rewriteStorageAssignmentRhs(rhs: Tree, originalType: Type, transformedType: Type)(using Context): Tree =
    val stripped = stripEmptyBlocks(rhs)
    // Direct context-function storage is rejected in ANF. This branch remains for function-valued CPS storage
    // assignments where the RHS prelude has already produced a transformed ControlContext-shaped value.
    if isCpsTransformFunctionType(originalType.dealias.widen) &&
      !isCpsTransformFunctionType(stripped.tpe.widen)
    then transformCpsExpr(stripped, Map.empty)
    else rewriteLocalValRhsToTransformed(stripped, originalType, transformedType)

  private def wrapAssignmentWithPrefix(prefix: List[Tree], transformedAssign: Assign)(using Context): Tree =
    if prefix.isEmpty then transformedAssign
    else tpd.Block(prefix ++ List(transformedAssign), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(())))

  override def transformApply(tree: Apply)(using ctx: Context): Tree =
    tree match
      case Apply(qual, List(arg)) if isControlContextType(qual.tpe.widen) && isCpsTransformType(arg.tpe) =>
        qual
      case Apply(Select(qual, selName), List(arg))
          if selName.toString == "apply" &&
            isControlContextType(qual.tpe.widen) &&
            isCpsTransformType(arg.tpe) =>
        qual
      case Apply(Select(qual, _), List(arg)) if isCpsTransformFunctionType(qual.tpe) && isCpsTransformType(arg.tpe) =>
        transformCpsExpr(qual, Map.empty)
      case _ =>
        val args = tree.args
        if !args.exists(a => isCpsValType(a.tpe)) then tree
        else if isCpsValType(tree.tpe) then tree
        else
          val tSym = lookupTransformedSym(tree.fun.symbol)
          if !tSym.exists then
            if rawLocalCpsValueArgs(args) then reportLocalCpsRuntimeUse(tree)
            tree
          else
            val newArgs = args.map { a =>
              if isCpsValType(a.tpe) then transformCpsCallSiteArg(a)
              else a
            }
            val newFun = rewriteFunToTransformed(tree.fun, tSym)
            cpy.Apply(tree)(newFun, newArgs)

  override def transformAssign(tree: Assign)(using ctx: Context): Tree =
    lookupLocalValPlan(tree.lhs.symbol).filter(plan => isLocalMutableCpsVal(plan.originalVal.symbol)) match
      case Some(plan) =>
        if storageRhsPreludeHasImmediateCps(tree.rhs) then
          val tmpVal = mkStorageTmpVal(tree.rhs, plan.originalVal.symbol.info)
          val tmpTrees = emitLocalCpsValPair(tmpVal)
          val transformedRhs =
            rewriteLocalValRhsToTransformed(
              ref(tmpVal.symbol.asTerm),
              plan.originalVal.symbol.info,
              plan.transformedSym.info
            )
          val transformedAssign = tpd.Assign(tpd.ref(plan.transformedSym), transformedRhs)
          tpd.Block(tmpTrees ++ List(transformedAssign), tpd.Literal(dotty.tools.dotc.core.Constants.Constant(())))
        else
          val (prefix, strippedRhs) = splitStorageRhsPrelude(tree.rhs)
          val transformedRhs =
            rewriteStorageAssignmentRhs(strippedRhs, plan.originalVal.symbol.info, plan.transformedSym.info)
          val transformedAssign = tpd.Assign(tpd.ref(plan.transformedSym), transformedRhs)
          wrapAssignmentWithPrefix(emitStoragePrefix(prefix), transformedAssign)
      case None if isMutableMemberCpsVal(tree.lhs.symbol) =>
        val sym = tree.lhs.symbol.asTerm
        val tSym = lookupTransformedSym(sym)
        if !tSym.exists then tree
        else
          val (receiverPrefix, newLhs) = tree.lhs match
            case sel: Select =>
              sel.qualifier match
                case _: This =>
                  (Nil, sel.qualifier.select(tSym.asTerm))
                case id: Ident if id.symbol.exists && id.symbol.isStableMember =>
                  (Nil, id.select(tSym.asTerm))
                case qual =>
                  val receiverSym = newSymbol(
                    ctx.owner,
                    SelectiveCPSTransform.StorageTmpName.fresh(),
                    Flags.Synthetic,
                    qual.tpe.widen,
                    coord = qual.span
                  ).asTerm.entered
                  val receiverVal = tpd.ValDef(receiverSym, qual)
                  (List(receiverVal), tpd.ref(receiverSym).select(tSym.asTerm))
            case _: Ident =>
              (Nil, tpd.This(ctx.owner.enclosingClass.asClass).select(tSym.asTerm))
            case _ =>
              (Nil, tree.lhs)
          if storageRhsPreludeHasImmediateCps(tree.rhs) then
            val tmpVal = mkStorageTmpVal(tree.rhs, sym.info)
            val tmpTrees = emitLocalCpsValPair(tmpVal)
            val transformedRhs = rewriteLocalValRhsToTransformed(ref(tmpVal.symbol.asTerm), sym.info, tSym.info)
            val transformedAssign = tpd.Assign(newLhs, transformedRhs)
            tpd.Block(
              receiverPrefix ++ tmpTrees ++ List(transformedAssign),
              tpd.Literal(dotty.tools.dotc.core.Constants.Constant(()))
            )
          else
            val (prefix, strippedRhs) = splitStorageRhsPrelude(tree.rhs)
            val transformedRhs = rewriteStorageAssignmentRhs(strippedRhs, sym.info, tSym.info)
            val transformedAssign = tpd.Assign(newLhs, transformedRhs)
            wrapAssignmentWithPrefix(receiverPrefix ++ emitStoragePrefix(prefix), transformedAssign)
      case None =>
        tree

  override def transformPackageDef(tree: PackageDef)(using ctx: Context): Tree =
    new tpd.TreeTraverser:
      private def traverseNamedCpsValRhs(tree: Tree)(using Context): Unit = tree match
        case Block(stats, expr) =>
          stats.foreach(traverseNamedCpsValRhs)
          traverseNamedCpsValRhs(expr)
        case _: Closure =>
          traverseChildren(tree)
        case _ =>
          traverse(tree)

      private def isSupportedMemberCpsVal(vd: ValDef)(using Context): Boolean =
        vd.symbol.exists && (
          isStrictMemberCpsVal(vd.symbol) ||
            isLazyMemberCpsVal(vd.symbol) ||
            isMutableMemberCpsVal(vd.symbol)
        )

      private def isSupportedMemberCpsValSym(sym: Symbol)(using Context): Boolean =
        isStrictMemberCpsVal(sym) || isLazyMemberCpsVal(sym) || isMutableMemberCpsVal(sym)

      def traverse(t: Tree)(using Context): Unit = t match
        case _: TypeTree => ()
        case dd: DefDef =>
          if isCpsTransformFunctionType(dd.symbol.info.finalResultType) || needsTransformedStub(dd.symbol) then ()
          else traverse(dd.rhs)
        case vd: ValDef if isLocalCpsVal(vd.symbol) =>
          ()
        case vd: ValDef if isSupportedMemberCpsVal(vd) =>
          ()
        case id: Ident if isSupportedMemberCpsValSym(id.symbol) =>
          report.error("CPS value not transformed", id.srcPos)
        case sel: Select if isSupportedMemberCpsValSym(sel.symbol) =>
          report.error("CPS value not transformed", sel.srcPos)
        case vd: ValDef =>
          traverse(vd.rhs)
        case Apply(fun, args) =>
          traverse(fun)
          val paramInfos = fun.tpe.widen match
            case mt: MethodType => mt.paramInfos
            case _ => List.fill(args.size)(ctx.definitions.AnyType)
          args.zip(paramInfos).foreach { (arg, paramTpe) =>
            if isCpsValType(paramTpe) then traverseChildren(arg)
            else traverse(arg)
          }
        case _: Closure =>
          traverseChildren(t)
        case _ =>
          if isCpsTransformFunctionType(t.tpe.widen) then report.error("CPS expression not transformed", t.srcPos)
          traverseChildren(t)
    .traverse(tree)
    tree

object SelectiveCPSTransform:
  val name = "selectivecps"
  val StorageTmpName = new UniqueNameKind("$cpsStorageTmp")
