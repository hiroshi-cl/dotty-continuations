package continuations.plugin.shared.types

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Types.*

trait CpsTypeOps extends CpsSymbols:

  private def isFunctionType(tpe: Type)(using Context): Boolean =
    val name = tpe.dealias.typeSymbol.fullName.toString
    name.startsWith("scala.Function") || name == "scala.runtime.FunctionXXL"

  /** Storage policy check for direct context-function values.
    *
    * `CpsTransform[R] ?=> A` itself, or containers such as `List[CpsTransform[R] ?=> A]`, are rejected as stored
    * values. Ordinary function values that produce CPS, such as `Int => (CpsTransform[R] ?=> A)`, remain supported
    * delayed providers, so this predicate intentionally does not recurse under FunctionN types. A polymorphic value
    * alone is not a delayed provider boundary; it must still take an ordinary value argument before producing CPS.
    */
  def hasDirectCpsContextFunctionStorageLeaf(tpe: Type)(using Context): Boolean =
    val t = tpe.dealias.widen
    if isCpsTransformFunctionType(t) then true
    else
      t match
        case rt: RefinedType =>
          (rt.refinedName == nme.apply && hasDirectCpsContextFunctionStorageLeaf(rt.refinedInfo)) ||
          hasDirectCpsContextFunctionStorageLeaf(rt.parent)
        case pt: PolyType =>
          hasDirectCpsContextFunctionStorageLeaf(pt.resType)
        case mt: MethodType =>
          false
        case et: ExprType =>
          hasDirectCpsContextFunctionStorageLeaf(et.resultType)
        case app: AppliedType if !isFunctionType(t) =>
          val sym = app.tycon.typeSymbol
          sym.isClass && !sym.is(Flags.Opaque) && app.args.exists(hasDirectCpsContextFunctionStorageLeaf)
        case _ =>
          false

  def isCpsValType(tpe: Type)(using Context): Boolean =
    isCpsValType0(tpe, Set.empty)

  // visited は apply member のシンボル id セット（scala.Symbol 名衝突を避けるため Int を使用）
  private def isCpsValType0(tpe: Type, visited: Set[Int])(using Context): Boolean =
    val t = tpe.dealias.widen
    !isControlContextType(t) && (
      isCpsTransformFunctionType(t) ||
        (t match
          case rt: RefinedType =>
            (rt.refinedName == nme.apply && isCpsValType0(rt.refinedInfo, visited)) ||
            isCpsValType0(rt.parent, visited)
          case pt: PolyType =>
            isCpsValType0(pt.resType, visited)
          case mt: MethodType =>
            isCpsValType0(mt.resType, visited)
          case et: ExprType =>
            isCpsValType0(et.resultType, visited)
          case _ =>
            false) ||
        cpsApplyMember0(t, visited).nonEmpty ||
        (isFunctionType(t) && t.argInfos.exists(isCpsValType0(_, visited))) ||
        (t match
          case app: AppliedType if !isFunctionType(t) =>
            val sym = app.tycon.typeSymbol
            sym.isClass && !sym.is(Flags.Opaque) && app.args.exists(isCpsValType0(_, visited))
          case _ =>
            false)
    )

  private def cpsApplyMember0(tpe: Type, visited: Set[Int])(using Context): Option[dotty.tools.dotc.core.Symbols.Symbol] =
    val tSym = tpe.typeSymbol
    if tSym.exists && visited.contains(tSym.id) then None
    else
      val newVisited = if tSym.exists then visited + tSym.id else visited
      val memberDenot = tpe.dealias.widen.member(nme.apply)
      val candidates =
        if !memberDenot.exists then Nil
        else
          val overloads = memberDenot.alternatives.map(_.symbol)
          if overloads.nonEmpty then overloads
          else List(memberDenot.symbol)
      candidates.find(sym => sym.exists && isCpsValType0(sym.info, newVisited))

  /** 値型中の CPS 型を ControlContext に変換する */
  def transformCpsValueType(tpe: Type)(using Context): Type =
    val t = tpe.dealias.widen
    if isCpsTransformFunctionType(t) then cpsTypeToCc(t)
    else
      t match
        case rt: RefinedType
            if rt.refinedName == nme.apply &&
              isCpsValType(rt.refinedInfo) =>
          RefinedType(rt.parent, rt.refinedName, transformCpsMethodType(rt.refinedInfo, directMarkerAsExpr = false))
        case rt: RefinedType =>
          RefinedType(transformCpsValueType(rt.parent), rt.refinedName, rt.refinedInfo)
        case pt: PolyType =>
          PolyType(pt.paramNames)(_ => pt.paramInfos, pt2 => transformCpsValueType(pt.resultType.subst(pt, pt2)))
        case mt: MethodType =>
          MethodType(mt.paramNames)(
            _ => mt.paramInfos.map(p => if isCpsValType(p) then transformCpsValueType(p) else p),
            mt2 => transformCpsValueType(mt.resultType.subst(mt, mt2))
          )
        case et: ExprType =>
          ExprType(transformCpsValueType(et.resultType))
        case _ if isFunctionType(t) && t.argInfos.exists(isCpsValType) =>
          t match
            case app: AppliedType =>
              app.derivedAppliedType(
                app.tycon,
                app.args.map(arg => if isCpsValType(arg) then transformCpsValueType(arg) else arg)
              )
            case _ =>
              tpe
        case app: AppliedType
            if !isFunctionType(t) &&
              app.tycon.typeSymbol.isClass && !app.tycon.typeSymbol.is(Flags.Opaque) &&
              app.args.exists(isCpsValType) =>
          app.derivedAppliedType(app.tycon, app.args.map(a => if isCpsValType(a) then transformCpsValueType(a) else a))
        case _ =>
          tpe

  /** CPS型を含むメソッド型を再帰的に変換。CpsTransform[R] ?=> A → ControlContext[A, R] */
  def transformCpsMethodType(tpe: Type)(using Context): Type =
    transformCpsMethodType(tpe, directMarkerAsExpr = true)

  private def transformCpsMethodType(tpe: Type, directMarkerAsExpr: Boolean)(using Context): Type = tpe match
    case pt: PolyType =>
      PolyType(pt.paramNames)(
        _ => pt.paramInfos,
        pt2 => transformCpsMethodType(pt.resultType.subst(pt, pt2), directMarkerAsExpr)
      )
    case mt: MethodType =>
      val keptParams = mt.paramNames.zip(mt.paramInfos).filterNot((_, p) => isCpsTransformType(p))
      val directCpsR = mt.paramInfos.collectFirst {
        case p if isCpsTransformType(p) => p.dealias.argInfos.head
      }
      def transformedResult(mt2: MethodType): Type =
        val resultType = mt.resultType.subst(mt, mt2)
        val transformedResult = transformCpsMethodType(resultType, directMarkerAsExpr = false)
        directCpsR match
          case Some(rType) if !isControlContextType(transformedResult) && !isCpsValType(resultType) =>
            controlContextClass.typeRef.appliedTo(List(transformedResult, rType))
          case _ =>
            transformedResult
      if mt.paramInfos.nonEmpty && keptParams.isEmpty then
        val result = transformedResult(mt)
        if directCpsR.nonEmpty && directMarkerAsExpr then ExprType(result) else result
      else
        MethodType(keptParams.map(_._1))(
          _ => keptParams.map((_, p) => if isCpsValType(p) then transformCpsValueType(p) else p),
          transformedResult
        )
    case et: ExprType =>
      ExprType(transformCpsMethodType(et.resultType, directMarkerAsExpr = false))
    case t if isCpsValType(t) => transformCpsValueType(t)
    case t if isCpsTransformFunctionType(t) => cpsTypeToCc(t)
    case _ => tpe

  /** ControlContext[?,?] 型かどうか */
  def isControlContextType(tpe: Type)(using Context): Boolean =
    tpe.widen.dealias.typeSymbol == controlContextClass

  /** CpsTransform を他の context parameter と併用する context function 型かどうか。
   *  (CpsTransform[R], String) ?=> A や (String, CpsTransform[R]) ?=> A を検出する。
   *  CpsTransform の位置に依存しないよう argInfos 全体を走査する。
   *  そのような型を保持するメソッドは変換未対応として診断する。
   */
  private[plugin] def isMultiArgCpsContextFunctionTpe(tpe: Type)(using Context): Boolean =
    val t = tpe.dealias
    val isContextFnWithMixedCps =
      t.typeSymbol.fullName.toString.startsWith("scala.ContextFunction") && {
        val params = t.argInfos.dropRight(1)  // 最後の要素は result type
        params.size > 1 && params.exists(isCpsTransformType)
      }
    isContextFnWithMixedCps ||
    (t match
      case pt: PolyType => isMultiArgCpsContextFunctionTpe(pt.resType)
      case mt: MethodType =>
        mt.paramInfos.exists(isMultiArgCpsContextFunctionTpe) ||
        isMultiArgCpsContextFunctionTpe(mt.resType)
      case et: ExprType => isMultiArgCpsContextFunctionTpe(et.resultType)
      case _ => false
    )
