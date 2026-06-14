package continuations.plugin

import continuations.plugin.shared.types.{CpsSymbols, CpsTypeOps, CpsEligibility}
import continuations.plugin.shared.local.LocalCpsStorageOps

trait CPSUtils extends CpsSymbols with CpsTypeOps with CpsEligibility with LocalCpsStorageOps
