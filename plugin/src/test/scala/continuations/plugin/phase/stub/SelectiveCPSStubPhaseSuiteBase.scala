package continuations.plugin.phase.stub

import continuations.plugin.CPSUtils
import continuations.plugin.phase.stub.SelectiveCPSStubPhase
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.reporting.StoreReporter

trait SelectiveCPSStubPhaseSuiteBase extends CPSUtils:

  val base = new ContextBase
  given ctx: Context = base.initialCtx.fresh
    .setSetting(base.settings.classpath, sys.props("java.class.path"))
    .setReporter(new StoreReporter(null))
  base.initialize()

  val phase = new SelectiveCPSStubPhase()

  def intType(using Context): Type = ctx.definitions.IntType
  def cpsType(using Context): Type = cpsTransformClass.typeRef.appliedTo(intType)
  def cfType(using Context): Type = ctx.definitions.FunctionOf(List(cpsType), intType, isContextual = true)
  def owner(using Context): Symbol = ctx.definitions.EmptyPackageClass

  /** CPS 戻り値型メソッドの DefDef を構築 */
  def mkCpsReturnDef(name: String)(using ctx: Context): DefDef =
    val methSym = newSymbol(owner, termName(name), Flags.Method, cfType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))

  /** CPS 引数型メソッドの DefDef を構築 */
  def mkCpsArgDef(name: String)(using ctx: Context): DefDef =
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName(name), Flags.Method, methType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))

  def mkUsingCpsDef(name: String)(using ctx: Context): DefDef =
    val methType = ContextualMethodType(List(termName("ctx")))(_ => List(cpsType), _ => intType)
    val methSym = newSymbol(owner, termName(name), Flags.Method, methType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))

  def mkUsingCpsEmptyParenDef(name: String)(using ctx: Context): DefDef =
    val inner = ContextualMethodType(List(termName("ctx")))(_ => List(cpsType), _ => intType)
    val methType = MethodType(Nil)(_ => Nil, _ => inner)
    val methSym = newSymbol(owner, termName(name), Flags.Method, methType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))
