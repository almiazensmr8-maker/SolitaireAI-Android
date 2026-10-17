package com.ammar.solitaireai

import kotlin.random.Random

enum class Suit(val symbol: String, val red: Boolean) {
    SPADES("♠", false), HEARTS("♥", true), DIAMONDS("♦", true), CLUBS("♣", false)
}
data class Card(val rank: Int, val suit: Suit, val faceUp: Boolean = true) {
    init { require(rank in 1..13) }
    val label: String get() = (when(rank) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> rank.toString() }) + suit.symbol
}
data class Move(val type: Type, val src: Int = -1, val dst: Int = -1, val index: Int = -1, val card: Card? = null) {
    enum class Type { DRAW, WASTE_TO_TABLEAU, WASTE_TO_FOUNDATION, TABLEAU_TO_TABLEAU, TABLEAU_TO_FOUNDATION }
    override fun toString(): String = when(type) {
        Type.DRAW -> "اسحب من الكومة"
        Type.WASTE_TO_TABLEAU -> "انقل ${card?.label} إلى العمود ${dst + 1}"
        Type.WASTE_TO_FOUNDATION -> "ضع ${card?.label} في الأساس"
        Type.TABLEAU_TO_TABLEAU -> "انقل ${card?.label} من العمود ${src + 1} إلى ${dst + 1}"
        Type.TABLEAU_TO_FOUNDATION -> "ضع ${card?.label} من العمود ${src + 1} في الأساس"
    }
}
data class GameState(
    val tableau: MutableList<MutableList<Card>>,
    val stock: MutableList<Card>,
    val waste: MutableList<Card> = mutableListOf(),
    val foundations: MutableMap<Suit, MutableList<Card>> = Suit.values().associateWith { mutableListOf<Card>() }.toMutableMap(),
    var movesMade: Int = 0,
    var score: Int = 0,
    var drawCount: Int = 1
) {
    fun copyDeep() = GameState(
        tableau.map { it.toMutableList() }.toMutableList(),
        stock.toMutableList(), waste.toMutableList(),
        foundations.mapValues { it.value.toMutableList() }.toMutableMap(),
        movesMade, score, drawCount
    )
    fun validate() {
        require(drawCount == 1 || drawCount == 3)
        val all = tableau.flatten() + stock + waste + foundations.values.flatten()
        require(all.size == 52) { "Expected 52 cards, got ${all.size}" }
        require(all.map { it.label }.toSet().size == 52) { "Duplicate cards" }
    }
}
object CardEngine {
    fun newGame(seed: Int = Random.nextInt(), drawCount: Int = 1): GameState {
        val deck = Suit.values().flatMap { s -> (1..13).map { Card(it, s, true) } }.shuffled(Random(seed))
        var p = 0
        val tableau = mutableListOf<MutableList<Card>>()
        for (col in 0 until 7) {
            val pile = mutableListOf<Card>()
            for (row in 0..col) {
                val c = deck[p++]
                pile += c.copy(faceUp = row == col)
            }
            tableau += pile
        }
        val stock = deck.drop(p).map { it.copy(faceUp = false) }.toMutableList()
        return GameState(tableau, stock, drawCount = drawCount).also { it.validate() }
    }

    private fun canStack(card: Card, target: Card?) =
        if (target == null) card.rank == 13 else target.faceUp && target.rank == card.rank + 1 && target.suit.red != card.suit.red
    private fun isOrderedRun(run: List<Card>): Boolean {
        if (run.isEmpty() || run.any { !it.faceUp }) return false
        return run.zipWithNext().all { (lower, upper) ->
            lower.rank == upper.rank + 1 && lower.suit.red != upper.suit.red
        }
    }
    private fun canFoundation(card: Card, pile: List<Card>) =
        if (pile.isEmpty()) card.rank == 1 else pile.last().suit == card.suit && pile.last().rank + 1 == card.rank

    fun legalMoves(s: GameState): List<Move> {
        val out = mutableListOf<Move>()
        for (i in s.tableau.indices) {
            val col = s.tableau[i]
            if (col.isEmpty()) continue
            val top = col.last()
            if (top.faceUp && canFoundation(top, s.foundations.getValue(top.suit)))
                out += Move(Move.Type.TABLEAU_TO_FOUNDATION, src = i, card = top)
            val firstFaceUp = col.indexOfFirst { it.faceUp }.let { if (it < 0) col.size else it }
            for (idx in firstFaceUp until col.size) {
                val card = col[idx]
                val run = col.subList(idx, col.size)
                if (!isOrderedRun(run)) continue
                for (dst in s.tableau.indices) {
                    if (dst == i) continue
                    val target = s.tableau[dst].lastOrNull()
                    if (canStack(card, target)) out += Move(Move.Type.TABLEAU_TO_TABLEAU, i, dst, idx, card)
                }
            }
        }
        s.waste.lastOrNull()?.let { card ->
            if (canFoundation(card, s.foundations.getValue(card.suit)))
                out += Move(Move.Type.WASTE_TO_FOUNDATION, card = card)
            for (dst in s.tableau.indices) if (canStack(card, s.tableau[dst].lastOrNull()))
                out += Move(Move.Type.WASTE_TO_TABLEAU, dst = dst, card = card)
        }
        if (s.stock.isNotEmpty() || s.waste.isNotEmpty()) out += Move(Move.Type.DRAW)
        return out
    }

    fun score(s: GameState, m: Move): Int {
        return when(m.type) {
            Move.Type.TABLEAU_TO_FOUNDATION -> 35
            Move.Type.WASTE_TO_FOUNDATION -> 30
            Move.Type.TABLEAU_TO_TABLEAU -> {
                val col = s.tableau[m.src]
                (if (m.index > 0 && !col[m.index - 1].faceUp) 55 else 8) +
                    (if (m.card?.rank == 13 && s.tableau[m.dst].isEmpty()) 18 else 0)
            }
            Move.Type.WASTE_TO_TABLEAU -> 14
            Move.Type.DRAW -> -3
        }
    }

    fun chooseMove(s: GameState): Move? = legalMoves(s).maxWithOrNull(
        compareBy<Move> { score(s, it) }.thenBy { it.type.ordinal }.thenBy { it.src }.thenBy { it.dst }
    )

    fun apply(s: GameState, m: Move): GameState {
        val n = s.copyDeep()
        when(m.type) {
            Move.Type.DRAW -> {
                if (n.stock.isNotEmpty()) repeat(minOf(n.drawCount, n.stock.size)) {
                    val c = n.stock.removeAt(n.stock.lastIndex)
                    n.waste += c.copy(faceUp = true)
                } else if (n.waste.isNotEmpty()) {
                    n.stock.addAll(n.waste.asReversed().map { it.copy(faceUp = false) })
                    n.waste.clear()
                } else error("No cards to draw")
            }
            Move.Type.WASTE_TO_TABLEAU -> {
                val c = n.waste.removeAt(n.waste.lastIndex)
                require(c == m.card)
                require(canStack(c, n.tableau[m.dst].lastOrNull()))
                n.tableau[m.dst] += c
            }
            Move.Type.WASTE_TO_FOUNDATION -> {
                val c = n.waste.last()
                require(c == m.card && canFoundation(c, n.foundations.getValue(c.suit)))
                n.foundations.getValue(c.suit) += n.waste.removeAt(n.waste.lastIndex)
                n.score += 10
            }
            Move.Type.TABLEAU_TO_FOUNDATION -> {
                val c = n.tableau[m.src].last()
                require(c == m.card && canFoundation(c, n.foundations.getValue(c.suit)))
                n.foundations.getValue(c.suit) += n.tableau[m.src].removeAt(n.tableau[m.src].lastIndex)
                reveal(n.tableau[m.src]); n.score += 10
            }
            Move.Type.TABLEAU_TO_TABLEAU -> {
                val from = n.tableau[m.src]
                val moving = from.subList(m.index, from.size).toList()
                require(moving.isNotEmpty() && isOrderedRun(moving))
                require(canStack(moving.first(), n.tableau[m.dst].lastOrNull()))
                n.tableau[m.dst].addAll(moving)
                repeat(moving.size) { from.removeAt(from.lastIndex) }
                reveal(from)
            }
        }
        n.movesMade++
        if (Suit.values().all { n.foundations.getValue(it).size == 13 }) n.score += 1000
        n.validate()
        return n
    }
    private fun reveal(col: MutableList<Card>) {
        if (col.isNotEmpty() && !col.last().faceUp) col[col.lastIndex] = col.last().copy(faceUp = true)
    }

    /** Reproducible smoke test: every seed must preserve all 52 unique cards after legal moves. */
    fun stressTest(seedCount: Int = 2_000, stepsPerSeed: Int = 150): StressResult {
        var steps = 0
        var noMoveStates = 0
        for (seed in 0 until seedCount) {
            var state = newGame(seed, if (seed % 2 == 0) 1 else 3)
            repeat(stepsPerSeed) {
                val move = chooseMove(state)
                if (move == null) { noMoveStates++; return@repeat }
                state = apply(state, move)
                state.validate()
                steps++
            }
        }
        return StressResult(seedCount, stepsPerSeed, steps, noMoveStates)
    }
}
data class StressResult(val seeds: Int, val stepsPerSeed: Int, val validSteps: Int, val noMoveStates: Int)
