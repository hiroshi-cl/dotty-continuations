package continuations.plugin.shared.types

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*

trait CpsSymbols:

  def cpsTransformClass(using Context): ClassSymbol =
    requiredClass("continuations.CpsTransform")

  def controlContextClass(using Context): ClassSymbol =
    requiredClass("continuations.ControlContext")

  def shiftUnitRMethod(using Context): Symbol =
    requiredPackage("continuations").requiredMethod("shiftUnitR")

  def cpsSymClass(using Context): ClassSymbol =
    requiredClass("continuations.CpsSym")

  /** ANF抽出ValDefシンボルにCPSマーカーアノテーションを付与する */
  def addCpsAnnotation(sym: Symbol)(using Context): Unit =
    sym.addAnnotation(Annotation(tpd.New(cpsSymClass.typeRef, Nil)))

  /** symがCPSマーカーアノテーションを持つか（Phase 3からも使用） */
  def isCpsSymAnnotated(sym: Symbol)(using Context): Boolean =
    sym.hasAnnotation(cpsSymClass)

  /** tpe が CpsTransform[?] かどうか */
  def isCpsTransformType(tpe: Type)(using Context): Boolean =
    tpe.widen.dealias.typeSymbol == cpsTransformClass

  private def isContextFunctionType(tpe: Type)(using Context): Boolean =
    val name = tpe.dealias.typeSymbol.fullName.toString
    name.startsWith("scala.ContextFunction")

  /** tpe が CpsTransform[R] ?=> A 形式の context function かどうか */
  def isCpsTransformFunctionType(tpe: Type)(using Context): Boolean =
    val t = tpe.dealias
    isContextFunctionType(t) &&
    t.argInfos.nonEmpty &&
    isCpsTransformType(t.argInfos.head)

  /** CpsTransform[R] ?=> A から R を取り出す */
  def cpsFunctionR(tpe: Type)(using Context): Option[Type] =
    if isCpsTransformFunctionType(tpe) then tpe.dealias.argInfos.head.dealias.argInfos.headOption
    else None

  def isCpsValType(tpe: Type)(using Context): Boolean

  protected def cpsApplyMember(tpe: Type)(using Context): Option[Symbol] =
    val memberDenot = tpe.dealias.widen.member(nme.apply)
    val candidates =
      if !memberDenot.exists then Nil
      else
        val overloads = memberDenot.alternatives.map(_.symbol)
        if overloads.nonEmpty then overloads
        else List(memberDenot.symbol)
    candidates.find(sym => sym.exists && isCpsValType(sym.info))

  /** sym が CpsTransform[R] 型のパラメータを持つかどうか context function の $anonfun に対して true になる
    */
  def hasCpsTransformParam(sym: Symbol)(using Context): Boolean =
    sym.paramSymss.exists(_.exists(p => isCpsTransformType(p.info)))

  private[plugin] val UnsupportedDirectCpsTransformParamMessage =
    "unsupported direct CpsTransform parameter shape"

  private[plugin] val UnsupportedInlineCpsProviderMessage =
    "inline CPS providers are not supported; move the CPS provider body to a non-inline def"

  private def termMethodTypes(tpe: Type)(using Context): List[MethodType] =
    tpe match
      case pt: PolyType => termMethodTypes(pt.resType)
      case mt: MethodType => mt :: termMethodTypes(mt.resType)
      case et: ExprType => termMethodTypes(et.resultType)
      case _ => Nil

  private[plugin] def hasUnsupportedDirectCpsTransformParam(sym: Symbol)(using Context): Boolean =
    val methodTypes = termMethodTypes(sym.info)
    val cpsClauses = sym.paramSymss
      .zip(methodTypes)
      .zipWithIndex
      .filter { case ((params, _), _) => params.exists(p => isCpsTransformType(p.info)) }

    cpsClauses match
      case Nil =>
        false
      case List(((params, mt), index)) =>
        val isLastTermClause = index == sym.paramSymss.size - 1
        val hasSingleMarker = params.size == 1 && params.forall(p => isCpsTransformType(p.info))
        val hasSingleMarkerType = mt.paramInfos.size == 1 && mt.paramInfos.forall(isCpsTransformType)
        !(isLastTermClause && mt.isContextualMethod && hasSingleMarker && hasSingleMarkerType)
      case _ =>
        true

  def cpsTransformParamR(sym: Symbol)(using Context): Option[Type] =
    sym.paramSymss.flatten
      .find(p => isCpsTransformType(p.info))
      .flatMap(_.info.dealias.argInfos.headOption)

  /** sym が CPS context function 型のパラメータを持つかどうか（非関数 AppliedType は除外） */
  def hasCpsTransformFunctionParam(sym: Symbol)(using Context): Boolean =
    sym.paramSymss.exists(_.exists { p =>
      val tp = p.info.dealias.widen
      isCpsValType(p.info) && !(tp.isInstanceOf[AppliedType] && !defn.isFunctionType(tp))
    })

  /** sym が container 内 CPS function value 型のパラメータを持つかどうか（List[CPS fn] 等） */
  def hasCpsValueContainerParam(sym: Symbol)(using Context): Boolean =
    sym.paramSymss.exists(_.exists { p =>
      val tp = p.info.dealias.widen
      tp.isInstanceOf[AppliedType] && !defn.isFunctionType(tp) && isCpsValType(p.info)
    })

  /** sym の戻り値型が container 内 CPS function value 型かどうか（Box[CPS fn] 等） */
  def hasCpsValueContainerResult(sym: Symbol)(using Context): Boolean =
    val tp = sym.info.finalResultType.dealias.widen
    tp.isInstanceOf[AppliedType] && !defn.isFunctionType(tp) && isCpsValType(sym.info.finalResultType)

  /** CpsTransform[R] ?=> A → ControlContext[A, R] */
  def cpsTypeToCc(t: Type)(using Context): Type =
    val a = t.dealias.argInfos.last
    val r = cpsFunctionR(t).get
    controlContextClass.typeRef.appliedTo(List(a, r))
