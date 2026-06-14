package continuations.plugin.phase.anf

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.NameKinds.UniqueNameKind
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

import continuations.plugin.CPSUtils

class SelectiveANFTransform extends PluginPhase with CPSUtils:
  val phaseName = SelectiveANFTransform.name
  override val runsAfter: Set[String] = Set("pickler")
  override val runsBefore: Set[String] = Set("firstTransform")

  /** CpsTransform[R] パラメータを持つ $anonfun (context function の本体) に ANF 変換を適用する。 reset の引数クロージャも、CPS
    * メソッドのクロージャも同じパターンで捕捉される。
    */
  override def transformDefDef(tree: DefDef)(using ctx: Context): Tree =
    if hasCpsTransformParam(tree.symbol) && !tree.rhs.isEmpty then
      given Context = ctx.withOwner(tree.symbol)
      val transformer = new ANFBodyTransformer()
      val (stmts, newRhs) = transformer.transformTail(tree.rhs)
      val fullRhs = transformer.mkBlock(stmts, newRhs)
      cpy.DefDef(tree)(rhs = fullRhs)
    else tree

  /** ANF フェーズ後の残留 CPS 式チェック。 目的: ユーザーコードの不正使用検出 (map { x => shift(...) } 等)。 除外: TypeTree（型アノテーション）、@CpsSym 付き ValDef の
    * rhs（正当抽出済み）。
    */
  override def transformPackageDef(tree: PackageDef)(using ctx: Context): Tree =
    val supportedLocalValSpans = scala.collection.mutable.ListBuffer.empty[dotty.tools.dotc.util.Spans.Span]
    new TreeTraverser:
      private def isSupportedLocalCpsVal(vd: ValDef)(using Context): Boolean =
        val tpe =
          if vd.symbol.exists then vd.symbol.info
          else vd.tpt.tpe
        vd.symbol.exists &&
        isLocalStoredCpsVal(vd.symbol) &&
        isCpsValType(tpe)

      private def isSupportedMemberCpsVal(vd: ValDef)(using Context): Boolean =
        vd.symbol.exists && (
          isStrictMemberCpsVal(vd.symbol) ||
            isLazyMemberCpsVal(vd.symbol) ||
            isMutableMemberCpsVal(vd.symbol)
        )

      override def traverse(tree: Tree)(using Context): Unit = tree match
        case vd: ValDef if isSupportedLocalCpsVal(vd) || isSupportedMemberCpsVal(vd) =>
          supportedLocalValSpans += vd.span
          traverseChildren(tree)
        case _ =>
          traverseChildren(tree)
    .traverse(tree)

    new TreeTraverser:
      private def isSupportedLocalCpsVal(vd: ValDef)(using Context): Boolean =
        val tpe =
          if vd.symbol.exists then vd.symbol.info
          else vd.tpt.tpe
        vd.symbol.exists &&
        isLocalStoredCpsVal(vd.symbol) &&
        isCpsValType(tpe)

      private def isSupportedMemberCpsVal(vd: ValDef)(using Context): Boolean =
        vd.symbol.exists && (
          isStrictMemberCpsVal(vd.symbol) ||
            isLazyMemberCpsVal(vd.symbol) ||
            isMutableMemberCpsVal(vd.symbol)
        )

      private def isSupportedCpsValAssignment(assign: Assign)(using Context): Boolean =
        isLocalMutableCpsVal(assign.lhs.symbol) || isMutableMemberCpsVal(assign.lhs.symbol)

      private def reportUnsupportedDirectCpsContextFunctionStorage(vd: ValDef)(using Context): Unit =
        report.error(
          "direct CPS context-function values cannot be stored; use def or a function returning CPS instead",
          vd.srcPos
        )

      private def traverseDirectStoragePolicyOnly(tree: Tree)(using Context): Unit =
        new TreeTraverser:
          override def traverse(t: Tree)(using Context): Unit =
            t match
              case _: TypeTree =>
                ()
              case vd: ValDef if isUnsupportedDirectCpsContextFunctionStorage(vd.symbol) =>
                reportUnsupportedDirectCpsContextFunctionStorage(vd)
                traverse(vd.rhs)
              case _ =>
                traverseChildren(t)
        .traverse(tree)

      private def isInsideSupportedLocalVal(tree: Tree): Boolean =
        val span = tree.span
        span.exists &&
        supportedLocalValSpans.exists(localSpan =>
          localSpan.exists &&
            span.start >= localSpan.start &&
            span.end <= localSpan.end
        )

      private def validateSupportedLocalCpsVal(vd: ValDef)(using Context): Unit =
        if isLocalLazyCpsVal(vd.symbol) &&
          storageRhsPreludeHasImmediateCps(vd.rhs)
        then
          report.error(
            "local lazy val CPS storage RHS cannot contain an immediately consumed CPS expression; use a strict val or def",
            vd.srcPos
          )
        reportNestedIllegalShiftInLocalCpsVal(vd.rhs)

      private def reportNestedIllegalShiftInLocalCpsVal(tree: Tree)(using Context): Unit =
        def isImmediateCpsExpr(tree: Tree): Boolean = tree match
          case Apply(_, args) => args.exists(a => isCpsTransformType(a.tpe))
          case Typed(expr, _) => isImmediateCpsExpr(expr)
          case _ => false

        def reportFirstImmediateCpsExpr(tree: Tree): Unit =
          var reported = false
          new TreeTraverser:
            override def traverse(t: Tree)(using Context): Unit =
              if !reported then
                t match
                  case _: TypeTree =>
                    ()
                  case dd: DefDef if hasCpsTransformParam(dd.symbol) =>
                    ()
                  case _ if isImmediateCpsExpr(t) =>
                    report.error("shift cannot be used in this position (not directly inside reset)", t.srcPos)
                    reported = true
                  case _ =>
                    traverseChildren(t)
          .traverse(tree)

        new TreeTraverser:
          override def traverse(t: Tree)(using Context): Unit =
            t match
              case _: TypeTree =>
                ()
              case dd: DefDef if !hasCpsTransformParam(dd.symbol) =>
                reportFirstImmediateCpsExpr(dd.rhs)
                traverseChildren(dd)
              case _ =>
                traverseChildren(t)
        .traverse(tree)

      override def traverse(tree: Tree)(using Context): Unit = tree match
        case _: TypeTree =>
          ()
        case vd: ValDef if isCpsSymAnnotated(vd.symbol) =>
          traverse(vd.tpt)
          // rhs はスキップ（ANF正当抽出のためCPS型のまま残るが正常）
        case vd: ValDef if isSupportedLocalCpsVal(vd) =>
          validateSupportedLocalCpsVal(vd)
          traverse(vd.tpt)
          traverseChildren(vd.rhs)
        case vd: ValDef if isSupportedMemberCpsVal(vd) =>
          ()
        case assign: Assign if isSupportedCpsValAssignment(assign) =>
          ()
        case vd: ValDef if isUnsupportedDirectCpsContextFunctionStorage(vd.symbol) =>
          reportUnsupportedDirectCpsContextFunctionStorage(vd)
          traverse(vd.rhs)
        case vd: ValDef if isUnsupportedResidualCpsValStorage(vd.symbol) =>
          report.error(UnsupportedResidualCpsValStorageMessage, vd.srcPos)
          traverse(vd.rhs)
        case dd: DefDef if isUnsupportedInlineCpsProvider(dd.symbol) =>
          report.error(UnsupportedInlineCpsProviderMessage, dd.srcPos)
        case dd: DefDef =>
          // CPS型を戻り値に持つメソッド（cross-method）の rhs は宣言通りの型 → フラグしない
          if isCpsTransformFunctionType(dd.symbol.info.finalResultType) || hasCpsTransformParam(dd.symbol) then
            traverseDirectStoragePolicyOnly(dd.rhs)
          else traverse(dd.rhs)
        case Apply(fun, args) =>
          traverse(fun)
          // 形式パラメータ型がCPS型の引数位置は合法 → arg自体はフラグしないが中身は再帰チェック
          val paramInfos = fun.tpe.widen match
            case mt: MethodType => mt.paramInfos
            case _ => List.fill(args.size)(ctx.definitions.AnyType)
          args.zip(paramInfos).foreach { (arg, paramTpe) =>
            if isCpsTransformFunctionType(paramTpe) then traverseChildren(arg)
            else traverse(arg)
          }
        case _: Closure =>
          // Block+Closure ペアの構造的ノード → 直接フラグしない、中身は再帰チェック
          traverseChildren(tree)
        case _ =>
          val tpe = tree.tpe
          if !isInsideSupportedLocalVal(tree) &&
            tpe != null &&
            !tpe.isError &&
            isCpsTransformFunctionType(tpe)
          then report.error("shift cannot be used in this position (not directly inside reset)", tree.srcPos)
          traverseChildren(tree)
    .traverse(tree)
    tree

object SelectiveANFTransform:
  val name = "selectiveanf"
  val CpsTmpName = new UniqueNameKind("$cpsTmp")
