package continuations.plugin.phase.anf

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
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
      val (selStmts, newSel) = transformInline(sel)
      val newCases = cases.map { cd =>
        val (bodyStmts, newBody) = transformTail(cd.body)
        cpy.CaseDef(cd)(cd.pat, cd.guard, mkBlock(bodyStmts, newBody))
      }
      (selStmts, cpy.Match(tree)(newSel, newCases))

    case Apply(fun, args) =>
      val (funStmts, newFun) = fun match
        case Select(qual, name) =>
          val (qualStmts, newQual) = transformInline(qual)
          (qualStmts, cpy.Select(fun)(newQual, name))
        case _ =>
          transformTail(fun)
      val (argStmtss, newArgs) = args.map(transformInline).unzip
      (funStmts ++ argStmtss.flatten, cpy.Apply(tree)(newFun, newArgs))

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
