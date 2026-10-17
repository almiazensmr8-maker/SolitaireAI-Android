package com.ammar.solitaireai

import org.junit.Assert.*
import org.junit.Test

class CardEngineTest {
    @Test fun newGameContains52UniqueCards() {
        val s = CardEngine.newGame(123)
        s.validate()
        assertEquals(28, s.tableau.sumOf { it.size })
        assertEquals(24, s.stock.size)
    }

    @Test fun repeatedLegalMovesPreserveDeck() {
        for (seed in 0 until 250) {
            var s = CardEngine.newGame(seed, if (seed % 2 == 0) 1 else 3)
            repeat(80) {
                val m = CardEngine.chooseMove(s) ?: return@repeat
                s = CardEngine.apply(s, m)
                s.validate()
            }
        }
    }

    @Test fun stressTestCompletes() {
        val r = CardEngine.stressTest(50, 30)
        assertEquals(50, r.seeds)
        assertTrue(r.validSteps > 0)
    }
}
