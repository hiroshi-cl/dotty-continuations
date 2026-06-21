package continuations.plugin.shared.types

import continuations.plugin.shared.local.LocalCpsStorageOps
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*

trait CpsEligibility:
  self: CpsSymbols & CpsTypeOps & LocalCpsStorageOps =>

  /** CPS型戻り値またはCPS型引数を持つメソッドかどうか（スタブ生成対象） */
  def needsTransformedStub(sym: Symbol)(using Context): Boolean =
    sym.is(Flags.Method) &&
      !sym.is(Flags.Accessor) &&
      !sym.is(Flags.Inline) &&
      !sym.isConstructor &&
      !(sym.name.toString.startsWith("$anonfun") && sym.is(Flags.Synthetic)) &&
      !sym.name.toString.endsWith("$transformed") && (
        hasCpsTransformParam(sym) ||
          hasCpsTransformFunctionParam(sym) ||
          // Keep direct CPS results owner-agnostic so local and member methods share the same eligibility rule.
          (isCpsValType(sym.info.finalResultType) && !hasCpsValueContainerResult(sym)) ||
          hasCpsValueContainerResult(sym) ||
          hasCpsValueContainerParam(sym)
      )

  def isUnsupportedInlineCpsProvider(sym: Symbol)(using Context): Boolean =
    sym.is(Flags.Method) &&
      sym.is(Flags.Inline) &&
      !sym.isConstructor &&
      !sym.name.toString.endsWith("$transformed") &&
      (hasCpsTransformParam(sym) ||
        hasCpsTransformFunctionParam(sym) ||
        (isCpsValType(sym.info.finalResultType) && !hasCpsValueContainerResult(sym)) ||
        hasCpsValueContainerResult(sym) ||
        hasCpsValueContainerParam(sym))

  /** $transformed スタブシンボルかどうか */
  def isTransformedStub(sym: Symbol)(using Context): Boolean =
    sym.name.toString.endsWith("$transformed")

  def isMemberStoredCpsVal(sym: Symbol)(using Context): Boolean =
    sym.exists &&
      !sym.is(Flags.Method) &&
      !sym.is(Flags.Param) &&
      !sym.is(Flags.ParamAccessor) &&
      (sym.owner.isClass || (sym.owner.isConstructor && sym.owner.owner.isClass)) &&
      !sym.owner.is(Flags.Trait) &&
      !sym.owner.is(Flags.Package) &&
      !sym.name.toString.endsWith("$transformed") &&
      !sym.is(Flags.Deferred) &&
      !hasDirectCpsContextFunctionStorageLeaf(sym.info) &&
      isCpsValType(sym.info)

  def isStrictMemberCpsVal(sym: Symbol)(using Context): Boolean =
    isMemberStoredCpsVal(sym) && !sym.is(Flags.Lazy) && !sym.is(Flags.Mutable)

  def isLazyMemberCpsVal(sym: Symbol)(using Context): Boolean =
    isMemberStoredCpsVal(sym) && sym.is(Flags.Lazy)

  def isMutableMemberCpsVal(sym: Symbol)(using Context): Boolean =
    isMemberStoredCpsVal(sym) && sym.is(Flags.Mutable)

  def needsTransformedMemberValStub(sym: Symbol)(using Context): Boolean =
    isStrictMemberCpsVal(sym) || isLazyMemberCpsVal(sym) || isMutableMemberCpsVal(sym)

  def isUnsupportedDirectCpsContextFunctionStorage(sym: Symbol)(using Context): Boolean =
    sym.exists &&
      !sym.is(Flags.Method) &&
      !sym.is(Flags.Param) &&
      !sym.name.toString.endsWith("$transformed") &&
      !sym.is(Flags.Deferred) &&
      !sym.owner.is(Flags.Package) &&
      hasDirectCpsContextFunctionStorageLeaf(sym.info)

  final protected val UnsupportedResidualCpsValStorageMessage =
    "CPS-valued storage in this owner or shape is not supported; use a def or move it to a supported local or class member"

  /** Supported local/member storage と direct-storage 専用診断のいずれにも分類されない CPS val。 */
  def isUnsupportedResidualCpsValStorage(sym: Symbol)(using Context): Boolean =
    sym.exists &&
      !sym.is(Flags.Method) &&
      !isUnsupportedDirectCpsContextFunctionStorage(sym) &&
      !isLocalStoredCpsVal(sym) &&
      !isStrictMemberCpsVal(sym) &&
      !isLazyMemberCpsVal(sym) &&
      !isMutableMemberCpsVal(sym) &&
      !sym.name.toString.endsWith("$transformed") &&
      isCpsValType(sym.info)
