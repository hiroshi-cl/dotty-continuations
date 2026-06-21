package continuations.plugin.phase.stub

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Decorators.toTermName
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

import continuations.plugin.CPSUtils

class SelectiveCPSStubPhase extends PluginPhase with CPSUtils:
  val phaseName = SelectiveCPSStubPhase.name
  override val runsAfter: Set[String] = Set("posttyper")
  override val runsBefore: Set[String] = Set("pickler")

  override def transformTemplate(tree: Template)(using ctx: Context): Tree =
    // 非 synthetic $transformed 定義をあらかじめ検証する
    tree.body.foreach {
      case dd: DefDef
          if dd.symbol.name.toString.endsWith("$transformed") &&
            !dd.symbol.is(Flags.Synthetic) =>
        val origName = dd.symbol.name.toString.stripSuffix("$transformed").toTermName
        val correspondingOriginals =
          dd.symbol.owner.info.decl(origName).alternatives
            .filter(alt => needsTransformedStub(alt.symbol) || needsTransformedMemberValStub(alt.symbol))
        if correspondingOriginals.isEmpty then
          report.error(
            "manual $transformed definition requires a corresponding original CPS method",
            dd.srcPos
          )
        else
          val hasMatchingSignature = correspondingOriginals.exists(alt =>
            dd.symbol.info =:= transformCpsMethodType(alt.symbol.info)
          )
          if !hasMatchingSignature then
            val expected = correspondingOriginals
              .map(alt => transformCpsMethodType(alt.symbol.info).show)
              .mkString(" or ")
            report.error(
              s"manual $$transformed definition has incompatible signature; expected: $expected",
              dd.srcPos
            )
      case _ => ()
    }
    var hasInlineStubs = false
    var generatedMemberValStubNames = Set.empty[String]
    val bodyWithMemberValStubs = tree.body.flatMap {
      case vd: ValDef if needsTransformedMemberValStub(vd.symbol) =>
        val origSym = vd.symbol.asTerm
        val stubName = (origSym.name.toString + "$transformed").toTermName
        if generatedMemberValStubNames.contains(stubName.toString) || validateExistingTransformed(origSym) then List(vd)
        else
          hasInlineStubs = true
          generatedMemberValStubNames += stubName.toString
          val stubType = transformCpsValueType(origSym.info)
          val stubFlags = origSym.flags &~ Flags.GivenOrImplicit | Flags.Synthetic
          val stubSym =
            newSymbol(transformedOwner(origSym), stubName, stubFlags, stubType, coord = origSym.coord).asTerm.entered
          val stub = tpd.ValDef(stubSym, ref(defn.Predef_undefined))
          if isMutableMemberCpsVal(origSym) then
            // The CPS phase rewrites assignments to direct field Assigns, so this setter body is not expected to
            // run. It exists to keep the mutable transformed sibling ABI/accessor shape consistent for pickling and
            // later compiler phases that expect a setter symbol for a mutable field.
            val setterType = MethodType(List("x".toTermName))(_ => List(stubType), _ => defn.UnitType)
            val setterFlags = (stubFlags &~ Flags.Mutable) | Flags.Method | Flags.Accessor
            val setterSym =
              newSymbol(
                transformedOwner(origSym),
                stubName.setterName,
                setterFlags,
                setterType,
                coord = origSym.coord
              ).asTerm.entered
            List(vd, stub, tpd.DefDef(setterSym, _ => tpd.Literal(dotty.tools.dotc.core.Constants.Constant(()))))
          else List(vd, stub)
      case other =>
        List(other)
    }
    // (stubName, transformedType) ペアを追跡し erased signature 衝突を検出する。
    // =:= による比較は Scala 型レベルの重複を捉える。JVM erasure 後のみ衝突するケース
    // (例: List[ControlContext[Int,Int]] と List[ControlContext[String,String]] が同じ erased signature になる場合)
    // はここでは検出できないが、Dotty のバックエンドが ClassFormatError として報告する。
    var generatedStubs = List.empty[(String, dotty.tools.dotc.core.Types.Type)]
    val stubs = tree.body.flatMap {
      case dd: DefDef if needsTransformedStub(dd.symbol) =>
        if hasUnsupportedDirectCpsTransformParam(dd.symbol) then
          report.error(UnsupportedDirectCpsTransformParamMessage, dd.srcPos)
          Nil
        else if isMultiArgCpsContextFunctionTpe(dd.symbol.info) then
          report.error(
            "context function types with CpsTransform alongside other context parameters are not yet supported",
            dd.srcPos
          )
          Nil
        else
          val stubName = dd.symbol.name.toString + "$transformed"
          val expectedType = transformCpsMethodType(dd.symbol.info)
          if generatedStubs.exists((n, t) => n == stubName && t =:= expectedType) then
            report.error(
              "transformed signature collides with another overload after CPS transformation; rename one of the original methods",
              dd.srcPos
            )
            Nil
          else
            generatedStubs = (stubName, expectedType) :: generatedStubs
            if !validateExistingTransformed(dd.symbol.asTerm) then List(mkTransformedStub(dd))
            else Nil
      case _ =>
        Nil
    }
    if !hasInlineStubs && stubs.isEmpty then tree
    else cpy.Template(tree)(body = bodyWithMemberValStubs ++ stubs)

  private[plugin] def hasExistingTransformed(origSym: TermSymbol)(using Context): Option[Symbol] =
    val stubName = (origSym.name.toString + "$transformed").toTermName
    val candidates = transformedOwner(origSym).info.decl(stubName).alternatives.map(_.symbol)
    val expectedType = transformCpsMethodType(origSym.info)
    candidates.find(_.info =:= expectedType).orElse(
      candidates.filter(_.coord == origSym.coord) match
        case single :: Nil => Some(single)
        case _             => None
    )

  private[plugin] def validateExistingTransformed(origSym: TermSymbol)(using Context): Boolean =
    hasExistingTransformed(origSym) match
      case Some(sym) if sym.is(Flags.Synthetic) =>
        true
      case Some(sym) =>
        val expectedType = transformCpsMethodType(origSym.info)
        if sym.info =:= expectedType then true
        else
          report.error(
            s"manual $$transformed definition has incompatible signature; expected: ${expectedType.show}",
            sym.srcPos
          )
          true
      case None =>
        false

  private[plugin] def mkTransformedStub(dd: DefDef)(using ctx: Context): DefDef =
    val origSym = dd.symbol.asTerm
    val stubName = (origSym.name.toString + "$transformed").toTermName
    val stubType = transformCpsMethodType(origSym.info)
    val stubFlags =
      if origSym.is(Flags.Deferred) then origSym.flags &~ Flags.GivenOrImplicit | Flags.Synthetic
      else origSym.flags &~ (Flags.Deferred | Flags.GivenOrImplicit) | Flags.Synthetic
    val stubSym = newSymbol(origSym.owner, stubName, stubFlags, stubType, coord = origSym.coord).asTerm.entered
    if origSym.is(Flags.Deferred) then tpd.DefDef(stubSym, _ => EmptyTree)
    else tpd.DefDef(stubSym, _ => ref(defn.Predef_undefined))

  private def transformedOwner(origSym: TermSymbol)(using Context): Symbol =
    // In the normal stub/ANF path, member val symbols are class-owned because the Constructors phase has not moved
    // field initialization yet. Keep the constructor-owner fallback defensive for later tree shapes that expose a
    // constructor-owned field but still need the transformed sibling entered on the enclosing class.
    if origSym.owner.isConstructor && origSym.owner.owner.exists then origSym.owner.owner
    else origSym.owner

object SelectiveCPSStubPhase:
  val name = "selectivecpsstub"
