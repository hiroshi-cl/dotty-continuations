package continuations.plugin.phase.cps

import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

trait TransformedImplBuilderOps:
  self: SelectiveCPSTransform =>

  protected[plugin] def mkTransformedImpl(orig: DefDef, transformedSym: TermSymbol)(using ctx: Context): DefDef =
    tpd.DefDef(
      transformedSym,
      paramss =>
        given Context = ctx.withOwner(transformedSym)
        val paramRemap = buildParamRemap(orig.symbol.paramSymss, paramss)
        val effectiveParamMapping = paramRemap.effectiveMapping
        val hasCpsParam = paramRemap.cpsMapping.nonEmpty
        val hasContainerCpsParam = paramRemap.containerCpsMapping.nonEmpty
        val directCpsParamR = cpsTransformParamR(orig.symbol)
        val hasCpsResult = isCpsValType(orig.symbol.info.finalResultType)
        if hasCpsResult then
          if isCpsTransformFunctionType(orig.symbol.info.finalResultType) then
            val rType = getRType(orig, transformedSym)
            val effectiveRhs = orig.rhs match
              case Block(List(anonfun: DefDef), _: Closure) =>
                new TreeTypeMap(
                  oldOwners = List(anonfun.symbol),
                  newOwners = List(transformedSym),
                  substFrom = paramRemap.substFrom,
                  substTo = paramRemap.substTo
                ).transform(anonfun.rhs)
              case _ => orig.rhs
            val rhsParamMapping = remapParamMappingForTree(effectiveRhs, effectiveParamMapping)
            transBody(effectiveRhs, rhsParamMapping, rType)
          else
            val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
            val rhsParamMapping = remapParamMappingForTree(effectiveRhs, effectiveParamMapping)
            rewriteLocalValRhsToTransformed(
              effectiveRhs,
              orig.symbol.info.finalResultType,
              transformedSym.info.finalResultType,
              rhsParamMapping
            )
        else if directCpsParamR.nonEmpty then
          val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
          val rhsParamMapping = remapParamMappingForTree(effectiveRhs, effectiveParamMapping)
          transBody(effectiveRhs, rhsParamMapping, directCpsParamR.get)
        else if hasCpsParam then
          val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
          val rhsParamMapping = remapParamMappingForTree(effectiveRhs, effectiveParamMapping)
          transPureBody(effectiveRhs, rhsParamMapping)
        else if hasContainerCpsParam then
          val effectiveRhs = remapPureRhs(orig, transformedSym, paramRemap)
          val rhsParamMapping = remapParamMappingForTree(effectiveRhs, effectiveParamMapping)
          transPureBody(effectiveRhs, rhsParamMapping)
        else orig.rhs
    )

  protected def remapPureRhs(orig: DefDef, transformedSym: TermSymbol, paramRemap: ParamRemap)(using Context): Tree =
    new TreeTypeMap(
      oldOwners = List(orig.symbol),
      newOwners = List(transformedSym),
      substFrom = paramRemap.substFrom,
      substTo = paramRemap.substTo
    ).transform(orig.rhs)

  protected def buildParamRemap(origParamss: List[List[Symbol]], newParamss: List[List[Tree]])(using
    Context
  ): ParamRemap =
    val paired = origParamss.flatten.zip(newParamss.flatten.map(_.symbol))
    val (cpsAll, purePairs) = paired.partition((origParam, _) => isCpsValType(origParam.info))
    val (containerPairs, cpsPairs) = cpsAll.partition((origParam, _) => isContainerOfCpsAppliedType(origParam.info))
    ParamRemap(
      substFrom = purePairs.map(_._1),
      substTo = purePairs.map(_._2),
      cpsMapping = cpsPairs.toMap,
      containerCpsMapping = containerPairs.toMap
    )

  protected[plugin] def buildParamMapping(origParamss: List[List[Symbol]], newParamss: List[List[Tree]])(using
    Context
  ): Map[Symbol, Symbol] =
    buildParamRemap(origParamss, newParamss).cpsMapping

  protected[plugin] def buildEffectiveParamMapping(origParamss: List[List[Symbol]], newParamss: List[List[Tree]])(using
    Context
  ): Map[Symbol, Symbol] =
    buildParamRemap(origParamss, newParamss).effectiveMapping

  protected[plugin] def remapParamMappingForTree(_rhs: Tree, pm: Map[Symbol, Symbol])(using
    Context
  ): Map[Symbol, Symbol] =
    val aliases = scala.collection.mutable.LinkedHashMap.empty[Symbol, Symbol]
    val mappedNames = pm.keysIterator.map(_.name).toSet
    new tpd.TreeTraverser {
      private var shadowedNames = Set.empty[Name]

      override def traverse(tree: Tree)(using Context): Unit =
        tree match
          case block: Block =>
            traverseBlock(block)
          case dd: DefDef =>
            traverseDefDef(dd)
          case vd: ValDef =>
            traverse(vd.rhs)
          case cd: CaseDef =>
            val saved = shadowedNames
            shadowedNames = shadowedNames ++ patternBoundMappedNames(cd.pat)
            traverse(cd.guard)
            traverse(cd.body)
            shadowedNames = saved
          case id: Ident
              if id.symbol.exists &&
                !pm.contains(id.symbol) &&
                !aliases.contains(id.symbol) &&
                mappedNames.contains(id.symbol.name) &&
                !shadowedNames.contains(id.symbol.name) =>
            pm.iterator.collectFirst {
              case (origSym, mappedSym) if origSym.name == id.symbol.name && (origSym.info =:= id.symbol.info) =>
                aliases(id.symbol) = mappedSym
            }
            traverseChildren(tree)
          case _ =>
            traverseChildren(tree)

      private def traverseBlock(block: Block)(using Context): Unit =
        val saved = shadowedNames
        block.stats.foreach {
          case vd: ValDef =>
            traverse(vd.rhs)
            addShadowedName(vd.symbol)
          case dd: DefDef =>
            traverseDefDef(dd)
            addShadowedName(dd.symbol)
          case stat =>
            traverse(stat)
        }
        traverse(block.expr)
        shadowedNames = saved

      private def traverseDefDef(dd: DefDef)(using Context): Unit =
        val saved = shadowedNames
        addShadowedName(dd.symbol)
        dd.symbol.paramSymss.flatten.foreach(addShadowedName)
        traverse(dd.rhs)
        shadowedNames = saved

      private def addShadowedName(sym: Symbol): Unit =
        if sym.exists && mappedNames.contains(sym.name) then shadowedNames = shadowedNames + sym.name

      private def patternBoundMappedNames(pat: Tree)(using Context): Set[Name] =
        val boundNames = scala.collection.mutable.LinkedHashSet.empty[Name]
        new tpd.TreeTraverser {
          override def traverse(tree: Tree)(using Context): Unit =
            tree match
              case bind: Bind if bind.symbol.exists && mappedNames.contains(bind.symbol.name) =>
                boundNames += bind.symbol.name
                traverse(bind.body)
              case id: Ident if id.symbol.exists && mappedNames.contains(id.symbol.name) =>
                boundNames += id.symbol.name
              case _ =>
                traverseChildren(tree)
        }.traverse(pat)
        boundNames.toSet
    }.traverse(_rhs)
    pm ++ aliases ++ pm.valuesIterator.map(sym => sym -> sym)

  protected def rehomeClosureArgToCurrentOwner(arg: Tree)(using Context): Tree = arg match
    case block @ Block(List(dd: DefDef), _: Closure) =>
      new TreeTypeMap(oldOwners = List(dd.symbol.owner), newOwners = List(summon[Context].owner)).transform(block)
    case _ =>
      arg

  protected def getRType(orig: DefDef, transformedSym: TermSymbol)(using ctx: Context): Type =
    val finalResult = transformedSym.info.finalResultType
    if isControlContextType(finalResult) then finalResult.argInfos(1)
    else
      orig.symbol.paramSymss.flatten
        .find(p => isCpsTransformFunctionType(p.info))
        .flatMap(p => cpsFunctionR(p.info))
        .getOrElse(ctx.definitions.NothingType)
