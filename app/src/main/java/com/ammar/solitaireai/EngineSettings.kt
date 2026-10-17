package com.ammar.solitaireai

/** Shared search presets used by the standalone app and its floating control service. */
object EngineSettings {
    enum class Mode { QUICK, BALANCED, DEEP }
    @Volatile var mode: Mode = Mode.BALANCED
        private set

    fun setMode(value: Mode) { mode = value }

    fun config(): SolverConfig = when (mode) {
        Mode.QUICK -> SolverConfig(maxDepth = 4, beamWidth = 10, maxExpandedNodes = 250, timeLimitMs = 80)
        Mode.BALANCED -> SolverConfig(maxDepth = 10, beamWidth = 36, maxExpandedNodes = 3_500, timeLimitMs = 700)
        Mode.DEEP -> SolverConfig(maxDepth = 18, beamWidth = 72, maxExpandedNodes = 12_000, timeLimitMs = 1_800)
    }
}
