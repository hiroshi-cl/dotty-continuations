package continuations.plugin.phase.anf

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.ExprType
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.core.Types.PolyType
import dotty.tools.dotc.report

import continuations.plugin.CPSUtils

class ANFBodyTransformer extends CPSUtils:

  // ネストした DefDef（別の CPS 関数等）には入らない
  def transform(tree: Tree)(using Context): Tree = tree match
    case _: DefDef => tree
    case _ =>
      transformTail(tree) match
        case (Nil, t) => t
        case (stmts, t) => tpd.Block(stmts, t)

  // fun.tpe から現在の Apply clause の paramInfos を取得する
  private def applyClauseParamInfos(funTpe: dotty.tools.dotc.core.Types.Type)(using Context): Option[List[dotty.tools.dotc.core.Types.Type]] =
    funTpe.widen match
      case mt: MethodType => Some(mt.paramInfos)
      case pt: PolyType   => applyClauseParamInfos(pt.resType)
      case _              => None

  // Boolean.&&/|| の CPS 引数を含む場合に If へ脱糖する
  private def tryDesugarBooleanShortCircuit(
      fun: Tree,
      args: List[Tree]
  )(using Context): Option[(List[Tree], Tree)] =
    fun match
      case Select(qual, _)
          if (fun.symbol == defn.Boolean_&& || fun.symbol == defn.Boolean_||) &&
            args.size == 1 &&
            containsCpsExpr(args.head) =>
        val rhs = args.head
        if fun.symbol == defn.Boolean_&& then
          val (qualStmts, newQual) = transformInline(qual)
          val (thenStmts, newThen) = transformTail(rhs)
          val falseLit = tpd.Literal(Constants.Constant(false))
          Some((qualStmts, tpd.If(newQual, mkBlock(thenStmts, newThen), falseLit)))
        else
          val (qualStmts, newQual) = transformInline(qual)
          val trueLit = tpd.Literal(Constants.Constant(true))
          val (elseStmts, newElse) = transformTail(rhs)
          Some((qualStmts, tpd.If(newQual, trueLit, mkBlock(elseStmts, newElse))))
      case _ => None

  // tail位置の変換: CPS式そのものは抽出しない、CPS引数は抽出する
  def transformTail(tree: Tree)(using ctx: Context): (List[Tree], Tree) = tree match

    case Block(stats, expr) =>
      val newStats = stats.flatMap(transformStat)
      val (exprStmts, newExpr) = transformTail(expr)
      (Nil, mkBlock(newStats ++ exprStmts, newExpr))

    case If(cond, thenp, elsep) =>
      val (condStmts, newCond) = transformInline(cond)
      val (thenStmts, newThen) = transformTail(thenp)
      val (elseStmts, newElse) = transformTail(elsep)
      (condStmts, cpy.If(tree)(newCond, mkBlock(thenStmts, newThen), mkBlock(elseStmts, newElse)))

    case Match(sel, cases) =>
      val hasGuardCps = cases.exists(cd => !cd.guard.isEmpty && containsCpsExpr(cd.guard))
      if hasGuardCps then
        cases.foreach { cd =>
          if !cd.guard.isEmpty && containsCpsExpr(cd.guard) then
            report.error("shift in match guard is not supported", cd.guard.srcPos)
        }
        (Nil, tree)
      else
        val (selStmts, newSel) = transformInline(sel)
        val newCases = cases.map { cd =>
          val (bodyStmts, newBody) = transformTail(cd.body)
          cpy.CaseDef(cd)(cd.pat, cd.guard, mkBlock(bodyStmts, newBody))
        }
        (selStmts, cpy.Match(tree)(newSel, newCases))

    case app @ Apply(fun, args) =>
      // Boolean.&&/|| は短絡評価が必要なため、CPS 引数を含む場合に If へ脱糖
      tryDesugarBooleanShortCircuit(fun, args).getOrElse {
        val (funStmts, newFun) = fun match
          case Select(qual, name) =>
            val (qualStmts, newQual) = transformInline(qual)
            (qualStmts, cpy.Select(fun)(newQual, name))
          case _ =>
            transformTail(fun)
        // by-name (ExprType) パラメータは遅延境界を保持し ValDef に抽出しない
        val paramInfos = applyClauseParamInfos(fun.tpe)
        val (argStmtss, newArgs) = args.zipWithIndex.map { (arg, i) =>
          val isByName = paramInfos.exists(ps => i < ps.size && ps(i).isInstanceOf[ExprType])
          if isByName then
            if containsCpsExpr(arg) then
              report.error(
                "shift inside a by-name argument is not supported; extract or rewrite the control flow explicitly",
                arg.srcPos
              )
              (Nil, arg)
            else
              val (argStmts, newArg) = transformTail(arg)
              (Nil, mkBlock(argStmts, newArg))
          else
            transformInline(arg)
        }.unzip
        (funStmts ++ argStmtss.flatten, cpy.Apply(tree)(newFun, newArgs))
      }

    case TypeApply(fun, targs) =>
      val (funStmts, newFun) = transformTail(fun)
      (funStmts, cpy.TypeApply(tree)(newFun, targs))

    case Select(qual, name) =>
      val (qualStmts, newQual) = transformInline(qual)
      (qualStmts, cpy.Select(tree)(newQual, name))

    case Typed(expr, tpt) =>
      val (exprStmts, newExpr) = transformTail(expr)
      (exprStmts, cpy.Typed(tree)(newExpr, tpt))

    case Try(body, cases, finalizer) =>
      if containsCpsExpr(tree) then
        if !finalizer.isEmpty && containsCpsExpr(finalizer) then
          report.error("shift in finally is not supported", finalizer.srcPos)
        val hasGuardCps = cases.exists(cd => !cd.guard.isEmpty && containsCpsExpr(cd.guard))
        if hasGuardCps then
          cases.foreach { cd =>
            if !cd.guard.isEmpty && containsCpsExpr(cd.guard) then
              report.error("shift in match guard is not supported", cd.guard.srcPos)
          }
          (Nil, tree)
        else
          val (bodyStmts, newBody) = transformTail(body)
          val newCases = cases.map { cd =>
            val (caseStmts, newCaseBody) = transformTail(cd.body)
            cpy.CaseDef(cd)(cd.pat, cd.guard, mkBlock(caseStmts, newCaseBody))
          }
          (Nil, cpy.Try(tree)(mkBlock(bodyStmts, newBody), newCases, finalizer))
      else (Nil, tree)

    case WhileDo(_, _) =>
      if containsCpsExpr(tree) then report.error("shift in while is not supported", tree.srcPos)
      (Nil, tree)

    case sl: SeqLiteral =>
      val (elemStmtss, newElems) = sl.elems.map(transformInline).unzip
      (elemStmtss.flatten, cpy.SeqLiteral(sl)(newElems, sl.elemtpt))

    case _ => (Nil, tree)

  // 引数位置の変換: CPS式なら @CpsSym 付き fresh ValDef に抽出して ref を返す
  def transformInline(tree: Tree)(using ctx: Context): (List[Tree], Tree) =
    val (stmts, tree2) = transformTail(tree)
    if isCpsExpr(tree2) then
      val sym = newSymbol(
        ctx.owner,
        SelectiveANFTransform.CpsTmpName.fresh(),
        Flags.Synthetic,
        tree2.tpe.widen,
        coord = tree2.span
      )
      addCpsAnnotation(sym)
      val vd = tpd.ValDef(sym, tree2)
      (stmts :+ vd, tpd.ref(sym))
    else (stmts, tree2)

  // Block 内の 1 文を処理
  def transformStat(stat: Tree)(using ctx: Context): List[Tree] = stat match
    case vd: ValDef =>
      val (stmts, newRhs) = transformTail(vd.rhs)
      stmts :+ cpy.ValDef(vd)(rhs = newRhs)
    case _ =>
      val (stmts, newStat) = transformTail(stat)
      if containsCpsExpr(newStat) then
        // CPS 式（If/Match/Try 等 CPS を含む場合も含む）がステートメント位置にある場合、
        // @CpsSym 付き ValDef に抽出して CPS 変換フェーズに渡す。
        val sym = newSymbol(
          ctx.owner,
          SelectiveANFTransform.CpsTmpName.fresh(),
          Flags.Synthetic,
          newStat.tpe.widen,
          coord = newStat.span
        )
        addCpsAnnotation(sym)
        stmts :+ tpd.ValDef(sym, newStat)
      else stmts :+ newStat

  /** CPS 式の判定: context function の auto-apply = Apply(Select(cfn, "apply"), List(ctx)) を args に CpsTransform[?]
    * 型の引数が含まれるかどうかで検出する。
    */
  def isCpsExpr(tree: Tree)(using Context): Boolean = tree match
    case Apply(_, args) => args.exists(a => isCpsTransformType(a.tpe))
    case Typed(expr, _) => isCpsExpr(expr)
    case _ => false
  def containsCpsExpr(tree: Tree)(using Context): Boolean =
    var found = false
    new TreeTraverser:
      def traverse(t: Tree)(using Context): Unit = t match
        case _: DefDef => ()
        case _ =>
          if isCpsExpr(t) then found = true
          else traverseChildren(t)
    .traverse(tree)
    found

  def mkBlock(stmts: List[Tree], expr: Tree)(using Context): Tree =
    if stmts.isEmpty then expr else tpd.Block(stmts, expr)
