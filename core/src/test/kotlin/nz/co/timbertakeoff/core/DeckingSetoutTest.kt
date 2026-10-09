package nz.co.timbertakeoff.core

import org.junit.Assert.*
import org.junit.Test

class DeckingSetoutTest {
    private val epsilon = 0.00000001

    private fun success(input: DeckInput): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid layout; got $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    private fun fullBoardInput(orientation: FramingOrientation = FramingOrientation.LENGTHWAYS) = DeckInput(
        widthMm = if (orientation == FramingOrientation.LENGTHWAYS) 535.0 else 4800.0,
        lengthMm = if (orientation == FramingOrientation.LENGTHWAYS) 4800.0 else 535.0,
        decking = Profiles.decking[1],
        actualDeckingWidthMm = 140.0,
        orientation = orientation
    )

    @Test fun fullBoardsHaveIndependentRunningMeasurementsFromTheirLeadingEdge() {
        // 4 × 140 + 3 × 5 = 575 mm finished cover, minus 40 mm combined overhang.
        val result = success(fullBoardInput())
        val setout = DeckingSetoutCalculator.calculate(result)
        assertEquals(5.0, setout.gapMm, epsilon)
        assertEquals(listOf(1, 2, 3, 4), setout.marks.map { it.boardNumber })
        assertEquals(listOf(0.0, 145.0, 290.0, 435.0), setout.marks.map { it.runningMm })
        assertEquals(listOf(140.0, 140.0, 140.0, 140.0), setout.marks.map { it.widthMm })
        assertEquals(Point(-20.0, -20.0), setout.datum)
        assertEquals(-20.0, setout.datumOffsetMm, epsilon)
        assertTrue(setout.markAlongX)
        // Each board occupies the positive side of its leading-edge mark; the gap follows it.
        assertEquals(575.0, setout.marks.last().runningMm + setout.marks.last().widthMm, epsilon)
    }

    @Test fun orientationRotatesTheMarkDirectionWithoutChangingTheRunningDistances() {
        val lengthways = DeckingSetoutCalculator.calculate(success(fullBoardInput()))
        val widthways = DeckingSetoutCalculator.calculate(success(fullBoardInput(FramingOrientation.WIDTHWAYS)))
        assertTrue(lengthways.markAlongX)
        assertFalse(widthways.markAlongX)
        assertEquals(lengthways.marks, widthways.marks)
        assertEquals(lengthways.gapMm, widthways.gapMm, epsilon)
        assertEquals(Point(-20.0, -20.0), widthways.datum)
        assertEquals(-20.0, widthways.datumOffsetMm, epsilon)
    }

    @Test fun firstRippedBoardChangesTheFirstStepAndFullBoardsKeepTheirActualPitch() {
        val result = success(DeckInput(widthMm = 1000.0, lengthMm = 4800.0, overhangMm = 0.0,
            orientation = FramingOrientation.LENGTHWAYS))
        val setout = DeckingSetoutCalculator.calculate(result)
        assertEquals(4.0, setout.gapMm, epsilon)
        assertEquals(60.0, setout.marks.first().widthMm, epsilon)
        assertEquals(listOf(0.0, 64.0, 158.0, 252.0), setout.marks.take(4).map { it.runningMm })
        assertTrue(setout.marks.drop(1).all { it.widthMm == 90.0 })
        assertEquals(0.0, setout.datumOffsetMm, epsilon)
        assertEquals(1000.0, setout.marks.last().runningMm + setout.marks.last().widthMm, epsilon)
    }

    @Test fun pictureFrameSetsTheDatumAfterItsInnerGapAndNumbersOnlyInfillBoards() {
        val result = success(DeckInput(widthMm = 3670.0, lengthMm = 4800.0,
            decking = Profiles.decking[1], actualDeckingWidthMm = 140.0,
            pictureFrame = true, orientation = FramingOrientation.LENGTHWAYS,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        val setout = DeckingSetoutCalculator.calculate(result)
        assertEquals(Point(125.0, 125.0), setout.datum)
        assertEquals(125.0, setout.datumOffsetMm, epsilon)
        assertEquals(5.0, setout.gapMm, epsilon)
        assertEquals(24, setout.marks.size)
        assertEquals(85.0, setout.marks.first().widthMm, epsilon)
        assertEquals(listOf(0.0, 90.0, 235.0, 380.0), setout.marks.take(4).map { it.runningMm })
        assertEquals(1, setout.marks.first().boardNumber)
        assertEquals(24, setout.marks.last().boardNumber)
        assertTrue(setout.marks.drop(1).all { it.widthMm == 140.0 })
        // The last infill board ends 5 mm before the far picture-frame inner edge.
        assertEquals(3545.0, setout.datumOffsetMm + setout.marks.last().runningMm + setout.marks.last().widthMm, epsilon)
    }

    @Test fun fractionalGapsRetainExactRunningDistancesWithoutRoundingEachStep() {
        val result = success(DeckInput(orientation = FramingOrientation.LENGTHWAYS))
        val setout = DeckingSetoutCalculator.calculate(result)
        assertEquals(220.0 / 37.0, setout.gapMm, epsilon)
        assertEquals(38, setout.marks.size)
        assertEquals(90.0 + 220.0 / 37.0, setout.marks[1].runningMm, epsilon)
        assertEquals(2.0 * 90.0 + 2.0 * 220.0 / 37.0, setout.marks[2].runningMm, epsilon)
        assertEquals(3550.0, setout.marks.last().runningMm, epsilon)
        // This would drift if every pitch were rounded to the printable 0.1 mm precision.
        assertNotEquals(37.0 * 95.9, setout.marks.last().runningMm, 0.1)
    }

    @Test fun geometryOrderDoesNotChangeSetoutOrMutateTheCalculatedBoards() {
        val original = success(fullBoardInput())
        val reversed = original.copy(geometry = original.geometry.copy(boards = original.geometry.boards.reversed()))
        val originalBoards = reversed.geometry.boards.toList()
        assertEquals(DeckingSetoutCalculator.calculate(original), DeckingSetoutCalculator.calculate(reversed))
        assertEquals(originalBoards, reversed.geometry.boards)
    }

    @Test fun adjustedOverhangMovesTheFramingDatumOffsetWithoutChangingBoardPitch() {
        val result = success(fullBoardInput().copy(widthMm = 475.0, overhangMm = 50.0))
        val setout = DeckingSetoutCalculator.calculate(result)
        assertEquals(Point(-50.0, -50.0), setout.datum)
        assertEquals(-50.0, setout.datumOffsetMm, epsilon)
        assertEquals(listOf(0.0, 145.0, 290.0, 435.0), setout.marks.map { it.runningMm })
    }
}
