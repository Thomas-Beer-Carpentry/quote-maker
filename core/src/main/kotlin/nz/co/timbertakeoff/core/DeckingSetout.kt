package nz.co.timbertakeoff.core

/** A one-based infill board number and its exact leading-edge distance from the first board. */
data class DeckingMark(val boardNumber: Int, val runningMm: Double, val widthMm: Double)

/**
 * All distances are millimetres. The first infill board's leading edge is the zero datum.
 * Marks run in the positive X direction when [markAlongX] is true, otherwise positive Y.
 * Each board occupies [DeckingMark.widthMm] on that positive side of its mark, followed by the gap.
 * [datumOffsetMm] locates zero relative to the near outside framing edge, including overhang
 * or the picture-frame board and its inner gap. Picture-frame perimeter boards are not numbered.
 */
data class DeckingSetout(
    val datum: Point,
    val markAlongX: Boolean,
    val datumOffsetMm: Double,
    val gapMm: Double,
    val marks: List<DeckingMark>
)

object DeckingSetoutCalculator {
    /** Uses calculated board geometry without re-optimising gaps or rounding successive pitches. */
    fun calculate(result: DeckResult): DeckingSetout {
        val infill = result.geometry.boards.filter { it.role == DeckBoardRole.INFILL }
        require(infill.isNotEmpty()) { "Decking set-out requires at least one calculated infill board." }
        val markAlongX = !infill.first().runsAlongX
        require(infill.all { !it.runsAlongX == markAlongX }) { "Decking infill boards must share a direction." }
        fun coordinate(board: DeckBoard) = if (markAlongX) board.origin.x else board.origin.y
        val ordered = infill.sortedBy(::coordinate)
        val datum = ordered.first().origin
        val offset = coordinate(ordered.first())
        return DeckingSetout(
            datum = datum,
            markAlongX = markAlongX,
            datumOffsetMm = offset,
            gapMm = result.geometry.deckingGapMm,
            marks = ordered.mapIndexed { index, board ->
                DeckingMark(index + 1, coordinate(board) - offset, board.widthMm)
            }
        )
    }
}
