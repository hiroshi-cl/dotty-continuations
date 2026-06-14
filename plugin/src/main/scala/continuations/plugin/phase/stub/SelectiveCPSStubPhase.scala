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
    val stubs = tree.body.flatMap {
      case dd: DefDef if needsTransformedStub(dd.symbol) =>
        if hasUnsupportedDirectCpsTransformParam(dd.symbol) then
          report.error(UnsupportedDirectCpsTransformParamMessage, dd.srcPos)
          Nil
        else if !validateExistingTransformed(dd.symbol.asTerm) then List(mkTransformedStub(dd))
        else Nil
      case _ =>
        Nil
    }
    if !hasInlineStubs && stubs.isEmpty then tree
    else cpy.Template(tree)(body = bodyWithMemberValStubs ++ stubs)

  private[plugin] def hasExistingTransformed(origSym: TermSymbol)(using Context): Option[Symbol] =
    val stubName = (origSym.name.toString + "$transformed").toTermName
    val sym = transformedOwner(origSym).info.decl(stubName).symbol
    Option.when(sym.exists)(sym)

  private[plugin] def validateExistingTransformed(origSym: TermSymbol)(using Context): Boolean =
    hasExistingTransformed(origSym) match
      case Some(sym) if !sym.is(Flags.Synthetic) =>
        report.error("manual $transformed definition is not supported", sym.srcPos)
        true
      case Some(_) =>
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
