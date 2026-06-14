package continuations.plugin

import continuations.plugin.phase.cps.SelectiveCPSTransform
import continuations.plugin.phase.anf.SelectiveANFTransform
import continuations.plugin.phase.stub.SelectiveCPSStubPhase
import dotty.tools.dotc.plugins.{StandardPlugin, PluginPhase}
import dotty.tools.dotc.core.Contexts.Context

class SelectiveCPSPlugin extends StandardPlugin:
  val name = "continuations"
  val description = "applies selective CPS conversion (shift/reset)"

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    List(new SelectiveCPSStubPhase(), new SelectiveANFTransform(), new SelectiveCPSTransform())
