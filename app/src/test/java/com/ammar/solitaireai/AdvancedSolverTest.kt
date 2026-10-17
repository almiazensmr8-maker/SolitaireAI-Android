package com.ammar.solitaireai

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdvancedSolverTest {
    @Test fun solverSuggestsLegalMoveWhenOneExists() {
        for (seed in 0 until 40) {
            val state = CardEngine.newGame(seed)
            val result = AdvancedSolver.suggest(
                state,
                SolverConfig(maxDepth = 4, beamWidth = 10, maxExpandedNodes = 100, timeLimitMs = 50)
            )
            val legal = CardEngine.legalMoves(state)
            if (legal.isNotEmpty()) {
                assertNotNull(result.move)
                assertTrue(legal.contains(result.move))
            }
        }
    }

    @Test fun solverMovesPreserveAllCards() {
        for (seed in 0 until 12) {
            var state = CardEngine.newGame(seed, if (seed % 2 == 0) 1 else 3)
            repeat(6) {
                val result = AdvancedSolver.suggest(
                    state,
                    SolverConfig(maxDepth = 3, beamWidth = 8, maxExpandedNodes = 60, timeLimitMs = 30)
                )
                val move = result.move ?: return@repeat
                state = CardEngine.apply(state, move)
                state.validate()
            }
        }
    }
}
