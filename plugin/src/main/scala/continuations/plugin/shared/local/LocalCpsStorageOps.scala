package continuations.plugin.shared.local

import continuations.plugin.shared.types.{CpsSymbols, CpsTypeOps}
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*

trait LocalCpsStorageOps:
  self: CpsSymbols & CpsTypeOps =>

  final protected val LocalCpsValueRuntimeMessage =
    "local CPS function value cannot be used as an ordinary runtime value; invoke it under reset or pass it to a CPS-transformed consumer"

  /** method / block 直下の local storage で、末尾が CPS 値へ到達するもの。 */
  def isLocalStoredCpsVal(sym: Symbol)(using Context): Boolean =
    sym.exists &&
      !sym.is(Flags.Method) &&
      !sym.is(Flags.Param) &&
      !sym.name.toString.endsWith("$transformed") &&
      !sym.owner.isClass &&
      !sym.owner.is(Flags.Package) &&
      !hasDirectCpsContextFunctionStorageLeaf(sym.info) &&
      isCpsValType(sym.info)

  /** method / block 直下の local val/lazy val/var で、末尾が CPS 値へ到達するもの。 */
  def isLocalCpsVal(sym: Symbol)(using Context): Boolean =
    isLocalStoredCpsVal(sym)

  /** local CPS var への書き込みで、transformed sibling の同期が必要なもの。 */
  def isLocalMutableCpsVal(sym: Symbol)(using Context): Boolean =
    isLocalStoredCpsVal(sym) && sym.is(Flags.Mutable)

  /** local CPS lazy val かどうか。 */
  def isLocalLazyCpsVal(sym: Symbol)(using Context): Boolean =
    isLocalStoredCpsVal(sym) && sym.is(Flags.Lazy)

  /** strict local CPS val かどうか。 */
  def isStrictLocalCpsVal(sym: Symbol)(using Context): Boolean =
    isLocalStoredCpsVal(sym) && !sym.is(Flags.Lazy) && !sym.is(Flags.Mutable)

  /** ANF 後の local CPS storage RHS から、保存される CPS function value を作る前に即時評価される prelude と、保存される value shell を分離する。
    */
  def splitStorageRhsPrelude(rhs: Tree)(using Context): (List[Tree], Tree) = rhs match
    case typed: Typed =>
      val (prefix, expr) = splitStorageRhsPrelude(typed.expr)
      (prefix, cpy.Typed(typed)(expr, typed.tpt))
    case block @ Block(stats, closure: Closure) =>
      val methSym = closure.meth.symbol
      val closureDefIndex = stats.indexWhere {
        case dd: DefDef => dd.symbol == methSym
        case _ => false
      }
      if closureDefIndex >= 0 then
        val (prefix, shellStats) = stats.splitAt(closureDefIndex)
        (prefix, cpy.Block(block)(shellStats, closure))
      else (stats, closure)
    case block @ Block(stats, expr) =>
      val (nestedPrefix, nestedExpr) = splitStorageRhsPrelude(expr)
      (stats ++ nestedPrefix, cpy.Block(block)(Nil, nestedExpr))
    case _ =>
      (Nil, rhs)

  def hasImmediateCpsConsumption(tree: Tree)(using Context): Boolean =
    var found = false
    new tpd.TreeTraverser:
      override def traverse(t: Tree)(using Context): Unit =
        if !found then
          t match
            case _: TypeTree =>
              ()
            case _: DefDef =>
              ()
            case _: Closure =>
              ()
            case _ =>
              if isImmediateCpsExpr(t) then found = true
              else traverseChildren(t)
    .traverse(tree)
    found

  def storageRhsPreludeHasImmediateCps(rhs: Tree)(using Context): Boolean =
    splitStorageRhsPrelude(rhs)._1.exists(hasImmediateCpsConsumption)

  private def isImmediateCpsExpr(tree: Tree)(using Context): Boolean = tree match
    case Apply(_, args) => args.exists(a => isCpsTransformType(a.tpe))
    case Typed(expr, _) => isImmediateCpsExpr(expr)
    case _ => false
