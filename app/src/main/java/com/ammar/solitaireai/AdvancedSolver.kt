package com.ammar.solitaireai

import kotlin.math.min
import kotlin.math.sqrt

/** Bounded, multi-step beam search for the standalone Solitaire engine.
 * It plans only the first action and should be called again after the board changes.
 * Search runs synchronously; call it from a background thread on Android.
 */
data class SolverConfig(
    val maxDepth: Int = 10,
    val beamWidth: Int = 36,
    val maxExpandedNodes: Int = 3_500,
    val timeLimitMs: Long = 700
) {
    init {
        require(maxDepth in 1..30)
        require(beamWidth in 4..256)
        require(maxExpandedNodes in 50..100_000)
        require(timeLimitMs in 10..10_000)
    }
}

data class SolverResult(
    val move: Move?,
    val evaluation: Double,
    val depthReached: Int,
    val expandedNodes: Int,
    val elapsedMs: Long,
    val foundWin: Boolean,
    val timedOut: Boolean
)

object AdvancedSolver {
    private data class Node(
        val state: GameState,
        val firstMove: Move,
        val depth: Int,
        val value: Double,
        val rootBias: Double
    )

    fun suggest(state: GameState, config: SolverConfig = SolverConfig()): SolverResult {
        val started = System.nanoTime()
        val deadline = started + config.timeLimitMs * 1_000_000L
        val rootMoves = CardEngine.legalMoves(state)
        if (rootMoves.isEmpty()) return SolverResult(null, evaluate(state), 0, 0, elapsed(started), false, false)

        // Use the simple policy as a stable tie-break only; search determines the main choice.
        val orderedRoot = rootMoves.sortedByDescending { CardEngine.score(state, it) }
        var bestMove: Move = orderedRoot.first()
        var bestValue = Double.NEGATIVE_INFINITY
        var bestDepth = 0
        var expanded = 0
        var timedOut = false
        val visitedAtDepth = HashMap<String, Int>()

        var beam = orderedRoot.map { move ->
            val next = CardEngine.apply(state, move)
            Node(next, move, 1, evaluate(next) + rootBias(state, move), rootBias(state, move))
        }.sortedByDescending { it.value }.take(config.beamWidth)

        beam.firstOrNull()?.let {
            bestMove = it.firstMove
            bestValue = it.value
            bestDepth = 1
        }
        if (beam.any { isWon(it.state) }) {
            val win = beam.first { isWon(it.state) }
            return SolverResult(win.firstMove, 1_000_000.0, 1, beam.size, elapsed(started), true, false)
        }

        // Store a shallow state signature that hides unknown face-down card identities.
        beam.forEach { visitedAtDepth.putIfAbsent(signature(it.state), it.depth) }
        var depth = 1
        while (depth < config.maxDepth && beam.isNotEmpty() && expanded < config.maxExpandedNodes) {
            if (System.nanoTime() >= deadline) { timedOut = true; break }
            val candidates = ArrayList<Node>(config.beamWidth * 8)
            for (node in beam) {
                if (expanded >= config.maxExpandedNodes || System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                expanded++
                if (isWon(node.state)) {
                    return SolverResult(node.firstMove, 1_000_000.0, node.depth, expanded, elapsed(started), true, timedOut)
                }
                // Do not speculate about future unseen stock cards. DRAW is evaluated only as
                // a root candidate; after the screen changes, call suggest() again.
                for (move in CardEngine.legalMoves(node.state)) {
                    if (move.type == Move.Type.DRAW) continue
                    val child = try { CardEngine.apply(node.state, move) } catch (_: RuntimeException) { continue }
                    val childDepth = node.depth + 1
                    val key = signature(child)
                    val oldDepth = visitedAtDepth[key]
                    if (oldDepth != null && oldDepth <= childDepth) continue
                    visitedAtDepth[key] = childDepth
                    val value = evaluate(child) + node.rootBias - childDepth * 0.15
                    val candidate = Node(child, node.firstMove, childDepth, value, node.rootBias)
                    candidates += candidate
                    if (value > bestValue) {
                        bestValue = value
                        bestMove = node.firstMove
                        bestDepth = childDepth
                    }
                    if (isWon(child)) {
                        return SolverResult(node.firstMove, 1_000_000.0, childDepth, expanded, elapsed(started), true, timedOut)
                    }
                }
            }
            if (candidates.isEmpty()) break
            // Keep diverse first actions in the beam, not just many descendants of one move.
            val perFirstMove = HashMap<String, Int>()
            val nextBeam = ArrayList<Node>(config.beamWidth)
            for (candidate in candidates.sortedByDescending { it.value }) {
                val rootKey = candidate.firstMove.toString()
                val used = perFirstMove[rootKey] ?: 0
                if (used >= 4) continue
                nextBeam += candidate
                perFirstMove[rootKey] = used + 1
                if (nextBeam.size >= config.beamWidth) break
            }
            beam = nextBeam
            depth++
        }
        return SolverResult(bestMove, bestValue, bestDepth, expanded, elapsed(started), false, timedOut)
    }

    /** Evaluate state without rewarding the identities of face-down cards. */
    fun evaluate(s: GameState): Double {
        if (isWon(s)) return 1_000_000.0
        val foundationCount = s.foundations.values.sumOf { it.size }
        val hidden = s.tableau.flatten().count { !it.faceUp }
        val visible = s.tableau.flatten().count { it.faceUp }
        val emptyColumns = s.tableau.count { it.isEmpty() }
        val longestRuns = s.tableau.sumOf { col ->
            if (col.isEmpty()) 0 else {
                var run = 1
                for (i in col.lastIndex downTo 1) {
                    val upper = col[i]
                    val lower = col[i - 1]
                    if (upper.faceUp && lower.faceUp && lower.rank == upper.rank + 1 && lower.suit.red != upper.suit.red) run++ else break
                }
                run
            }
        }
        val usefulMoves = CardEngine.legalMoves(s).count { it.type != Move.Type.DRAW }
        val wasteTop = if (s.waste.isNotEmpty()) 1 else 0
        return foundationCount * 100.0 + visible * 4.0 - hidden * 23.0 +
            emptyColumns * 17.0 + longestRuns * 1.5 +
            min(usefulMoves, 30) * 1.8 + wasteTop * 1.0 - s.stock.size * 0.08
    }

    /** Useful in unit tests and reproducible benchmark builds; this checks safety, not win rate. */
    fun stressTest(seedCount: Int = 2_000, stepsPerSeed: Int = 100): SolverStressResult {
        require(seedCount > 0 && stepsPerSeed > 0)
        var validTransitions = 0
        var noMoveStates = 0
        var wins = 0
        for (seed in 0 until seedCount) {
            var state = CardEngine.newGame(seed, if (seed % 2 == 0) 1 else 3)
            var step = 0
            while (step < stepsPerSeed) {
                state.validate()
                val result = suggest(state, SolverConfig(maxDepth = 5, beamWidth = 12, maxExpandedNodes = 180, timeLimitMs = 20))
                val move = result.move
                if (move == null) { noMoveStates++; break }
                state = CardEngine.apply(state, move)
                state.validate()
                validTransitions++
                step++
                if (isWon(state)) { wins++; break }
            }
        }
        return SolverStressResult(seedCount, stepsPerSeed, validTransitions, noMoveStates, wins)
    }

    fun signature(s: GameState): String = buildString {
        s.tableau.forEach { col ->
            append('[')
            col.forEach { card -> append(if (card.faceUp) card.label else "??").append(',') }
            append(']')
        }
        append("|stock=").append(s.stock.size).append("|waste=")
        s.waste.forEach { append(it.label).append(',') }
        append("|foundation=")
        Suit.values().forEach { suit ->
            val pile = s.foundations.getValue(suit)
            append(suit.symbol).append(':').append(pile.lastOrNull()?.rank ?: 0).append(',')
        }
    }

    private fun rootBias(state: GameState, move: Move): Double = when {
        move.type == Move.Type.DRAW && state.stock.isEmpty() && state.waste.isNotEmpty() -> 12.0
        move.type == Move.Type.TABLEAU_TO_FOUNDATION || move.type == Move.Type.WASTE_TO_FOUNDATION -> 4.0
        else -> 0.0
    }
    private fun isWon(s: GameState) = Suit.values().all { s.foundations.getValue(it).size == 13 }
    private fun elapsed(start: Long) = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
}

data class SolverStressResult(
    val seeds: Int,
    val maxStepsPerSeed: Int,
    val validTransitions: Int,
    val noMoveStates: Int,
    val wins: Int
)
