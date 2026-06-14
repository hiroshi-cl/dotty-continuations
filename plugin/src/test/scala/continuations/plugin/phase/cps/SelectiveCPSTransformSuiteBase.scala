package continuations.plugin.phase.cps

import continuations.plugin.CPSUtils
import continuations.plugin.phase.cps.SelectiveCPSTransform
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

trait SelectiveCPSTransformSuiteBase extends CPSUtils:

  def shiftMethod(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("shift")

  def shiftTransformedMethod(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("shift$transformed")

  def resetSymbol(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("reset")

  def resetTransformedMethod(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("reset$transformed")

  val base = new ContextBase
  given ctx: Context = base.initialCtx.fresh
    .setSetting(base.settings.classpath, sys.props("java.class.path"))
    .setReporter(new StoreReporter(null))
  base.initialize()

  val phase = new SelectiveCPSTransform()

  def intType(using Context): Type = ctx.definitions.IntType
  def cpsType(using Context): Type = cpsTransformClass.typeRef.appliedTo(intType)
  def cfType(using Context): Type = ctx.definitions.FunctionOf(List(cpsType), intType, isContextual = true)
  def owner(using Context): Symbol = ctx.definitions.EmptyPackageClass

  /** CPS 戻り値メソッド (cfType) の DefDef を構築 */
  def mkCpsReturnDef(name: String)(using ctx: Context): DefDef =
    val methSym = newSymbol(owner, termName(name), Flags.Method, cfType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))

  /** CPS 引数メソッドの DefDef を構築 */
  def mkCpsArgDef(name: String)(using ctx: Context): DefDef =
    val methType = MethodType(List(termName("body")))(_ => List(cfType), _ => intType)
    val methSym = newSymbol(owner, termName(name), Flags.Method, methType).asTerm
    tpd.DefDef(methSym, _ => tpd.ref(defn.Predef_undefined))

  def mkLoweredPolyApplyType(using ctx: Context): Type =
    PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        MethodType(List(termName("x")))(
          _ => List(a),
          _ => ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)
        )
      }
    )

  def mkLoweredPolyValueType(using ctx: Context): Type =
    RefinedType(requiredClass("scala.PolyFunction").typeRef, nme.apply, mkLoweredPolyApplyType)

  def mkLoweredCurriedPolyApplyType(using ctx: Context): Type =
    PolyType(List(typeName("A")))(
      _ => List(TypeBounds.empty),
      pt => {
        val a = pt.paramRefs.head
        val cpsLeaf = ctx.definitions.FunctionOf(List(cpsTransformClass.typeRef.appliedTo(a)), a, isContextual = true)
        val returnedFun = defn.FunctionOf(List(a), cpsLeaf)
        MethodType(List(termName("x")))(_ => List(intType), _ => returnedFun)
      }
    )

  def mkLoweredCurriedPolyValueType(using ctx: Context): Type =
    RefinedType(requiredClass("scala.PolyFunction").typeRef, nme.apply, mkLoweredCurriedPolyApplyType)

  def mkCpsValDef(name: String, rhs: Tree)(using ctx: Context): ValDef =
    val vSym = newSymbol(owner, termName(name), Flags.Synthetic, intType).asTerm
    addCpsAnnotation(vSym)
    tpd.ValDef(vSym, rhs)

  /** ControlContext[Int, Int] 型のダミー式 */
  def mkCcTree()(using ctx: Context): Tree =
    val ccType = controlContextClass.typeRef.appliedTo(List(intType, intType))
    tpd.ref(newSymbol(owner, termName("$cc"), Flags.Synthetic, ccType).asTerm)

  def mkPid()(using Context): RefTree =
    val sym = newSymbol(owner, termName("$testpkg"), Flags.Synthetic, ctx.definitions.UnitType)
    tpd.ref(sym).asInstanceOf[RefTree]

  def mkResetArg(body: Tree)(using ctx: Context): Block =
    val cpsCtxType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType = MethodType(List(termName("$ctx")))(_ => List(cpsCtxType), _ => intType)
    val anonfunSym = newSymbol(owner, termName("$anonfun"), Flags.Synthetic | Flags.Method, anonfunType).asTerm
    val anonfunDef = tpd.DefDef(anonfunSym, _ => body)
    val closure = tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.TypeTree(cfType))
    tpd.Block(List(anonfunDef), closure)

  /** reset の Apply ノードを構築するヘルパー */
  def mkResetCall(body: Tree)(using ctx: Context): Apply =
    val tyApp = tpd.ref(resetSymbol).appliedToType(intType)
    tpd.Apply(tyApp, List(mkResetArg(body)))

  def mkResetCallWithCtx(body: TermSymbol => Tree)(using ctx: Context): Apply =
    val cpsCtxType = cpsTransformClass.typeRef.appliedTo(intType)
    val anonfunType = MethodType(List(termName("$ctx")))(_ => List(cpsCtxType), _ => intType)
    val anonfunSym = newSymbol(owner, termName("$anonfunCtx"), Flags.Synthetic | Flags.Method, anonfunType).asTerm
    val ctxSym = anonfunSym.paramSymss.head.head.asTerm
    val anonfunDef = tpd.DefDef(anonfunSym, _ => body(ctxSym))
    val closure = tpd.Closure(Nil, tpd.ref(anonfunSym), tpd.TypeTree(cfType))
    val tyApp = tpd.ref(resetSymbol).appliedToType(intType)
    tpd.Apply(tyApp, List(tpd.Block(List(anonfunDef), closure)))

  def mkCpsParamApply(bodySym: TermSymbol, ctxSym: TermSymbol)(using ctx: Context): Tree =
    val applySym = bodySym.info.dealias.typeSymbol.requiredMethod("apply")
    tpd.Apply(tpd.ref(bodySym).select(applySym), List(tpd.ref(ctxSym)))

  def treeExists(tree: Tree)(p: Tree => Boolean)(using Context): Boolean =
    var found = false
    new tpd.TreeTraverser {
      def traverse(t: Tree)(using Context): Unit =
        if !found then
          if p(t) then found = true
          else traverseChildren(t)
    }.traverse(tree)
    found

  def mkCpsApplyExpr()(using ctx: Context): Tree =
    val fType = defn.FunctionOf(List(defn.FunctionOf(List(intType), intType)), intType)
    val fSym = newSymbol(owner, termName("f"), Flags.Synthetic, fType).asTerm
    val cfExpr = ref(shiftMethod).appliedToTypes(List(intType, intType)).appliedTo(ref(fSym))
    val cpsCtxSym = newSymbol(owner, termName("$ctx"), Flags.Synthetic, cpsType).asTerm
    val applySel = cfExpr.select(cfExpr.tpe.dealias.typeSymbol.requiredMethod("apply"))
    Apply(applySel, List(ref(cpsCtxSym)))
