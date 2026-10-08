package nz.co.timbertakeoff.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

class DeckCalculatorTest {
    private val epsilon = 0.00001
    private fun success(input: DeckInput = DeckInput()): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid input $input; got $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }
    private fun invalid(input: DeckInput): CalculationOutcome.Invalid {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected conflict for $input; got $outcome", outcome is CalculationOutcome.Invalid)
        return outcome as CalculationOutcome.Invalid
    }
    private fun u(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.x else p.y
    private fun v(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.y else p.x
    private fun line(result: DeckResult, type: String) = result.materials.single { it.key.type == type }

    @Test fun ordinaryDeckHasIndependentExpectedTakeoff() {
        val result = success()
        val g = result.geometry
        assertEquals(FramingOrientation.LENGTHWAYS, result.recommended)
        assertEquals(listOf(180.0, 1800.0, 3420.0), g.bearerPositionsMm)
        assertEquals(listOf(200.0, 1300.0, 2400.0, 3500.0, 4600.0), g.pilePositionsMm)
        assertEquals(15, g.piles.size)
        assertEquals(14, g.joistPositionsMm.size)
        assertEquals(1151.0, g.piles.first().lengthMm, epsilon)
        assertEquals(100050.0, g.members.sumOf { it.lengthMm }, epsilon)
        assertEquals(38, g.boards.size)
        assertEquals(220.0 / 37.0, g.deckingGapMm, epsilon)
        assertEquals(183.92, line(result, "Decking").quantity, epsilon)
        assertEquals(1.44, g.excavationM3, epsilon)
        assertEquals(1.3228125, g.concreteM3, epsilon)
        assertEquals(133.0, line(result, "Concrete bags").quantity, epsilon)
        assertEquals(296.0, line(result, "Nails").quantity, epsilon)
        assertEquals(1024.0, line(result, "Decking screws").quantity, epsilon)
        assertTrue(DeckCalculator.validate(result).isEmpty())
    }

    @Test fun smallDeckUsesOneBearerOnePileAndPositiveBoundaryCuts() {
        val g = success(DeckInput(widthMm = 400.0, lengthMm = 400.0)).geometry
        assertEquals(listOf(200.0), g.bearerPositionsMm)
        assertEquals(listOf(200.0), g.pilePositionsMm)
        assertEquals(1, g.piles.size)
        assertEquals(1, g.members.count { it.kind == MemberKind.NOG })
        assertEquals(60.0, g.startingBoardWidthMm, epsilon)
        assertEquals(5.0, g.deckingGapMm, epsilon)
        assertTrue(g.members.all { it.lengthMm > 0.0 })
    }

    @Test fun bearerAndPileSpacingNeverExceedTheirLimitsAtBoundaries() {
        for (width in listOf(2400.0, 2400.001, 4200.0, 4200.001)) {
            val g = success(DeckInput(widthMm = width, lengthMm = 4300.0, orientation = FramingOrientation.LENGTHWAYS)).geometry
            assertTrue(g.bearerPositionsMm.zipWithNext().all { (a, b) -> b - a <= 1800.0 + epsilon })
            assertEquals(ceil((width - 600.0) / 1800.0).toInt() + 1, g.bearerPositionsMm.size)
        }
        for (length in listOf(1700.0, 1700.001, 3000.0, 3000.001)) {
            val g = success(DeckInput(widthMm = 2400.0, lengthMm = length, orientation = FramingOrientation.LENGTHWAYS)).geometry
            assertTrue(g.pilePositionsMm.zipWithNext().all { (a, b) -> b - a <= 1300.0 + epsilon })
            assertEquals(ceil((length - 400.0) / 1300.0).toInt() + 1, g.pilePositionsMm.size)
        }
    }

    @Test fun joistsUseEvenSpacingAndRespectDoubleBoundaryFootprint() {
        val input = DeckInput(lengthMm = 4800.0, widthMm = 3600.0, maxJoistSpacingMm = 400.0, orientation = FramingOrientation.LENGTHWAYS)
        val g = success(input).geometry
        val t = input.joist.thicknessMm
        assertEquals(t / 2.0, g.joistPositionsMm.first(), epsilon)
        assertEquals(1.5 * t, g.joistPositionsMm[1], epsilon)
        assertEquals(g.bearerRunMm - t / 2.0, g.joistPositionsMm.last(), epsilon)
        val intervals = g.joistPositionsMm.drop(1).dropLast(1).zipWithNext().map { (a, b) -> b - a }
        assertTrue(intervals.all { it <= 400.0 + epsilon })
        assertTrue(intervals.all { abs(it - intervals.first()) < epsilon })
        g.members.filter { it.kind == MemberKind.JOIST }.forEach {
            assertEquals(2.0 * t, v(g, it.start), epsilon)
            assertEquals(g.joistRunMm - 2.0 * t, v(g, it.end), epsilon)
        }
        assertEquals(8, g.members.count { it.kind == MemberKind.BOUNDARY })
        val ends = g.members.filter { it.runId.startsWith("BE") }
        assertTrue(ends.all { abs(u(g, it.start) - 2.0 * t) < epsilon && abs(u(g, it.end) - (g.bearerRunMm - 2.0 * t)) < epsilon })
    }

    @Test fun minimumBearerCountAndSymmetricCantileversAreUsed() {
        for (width in listOf(400.0, 600.0, 800.0, 2400.0, 4800.0, 15000.0)) {
            val result = success(DeckInput(widthMm = width, lengthMm = 4000.0, orientation = FramingOrientation.LENGTHWAYS))
            val g = result.geometry
            assertEquals(width, g.bearerPositionsMm.first() + g.bearerPositionsMm.last(), epsilon)
            assertTrue(g.joistCantileverMm in 90.0..300.0)
            assertTrue(g.joistCantileverMm - 2.0 * result.input.joist.thicknessMm >= 90.0 - epsilon)
            assertEquals(if (width <= 600.0) 1 else ceil((width - 600.0) / 1800.0).toInt() + 1, g.bearerPositionsMm.size)
        }
    }

    @Test fun orientationOverrideRotatesGeometryAndDoesNotChangeRecommendation() {
        val auto = success()
        val widthways = success(DeckInput(orientation = FramingOrientation.WIDTHWAYS))
        assertEquals(auto.recommended, widthways.recommended)
        assertEquals(FramingOrientation.WIDTHWAYS, widthways.geometry.orientation)
        assertEquals(3600.0, widthways.geometry.bearerRunMm, epsilon)
        assertEquals(4800.0, widthways.geometry.joistRunMm, epsilon)
        assertTrue(widthways.geometry.boards.all { it.runsAlongX })
        assertEquals(Point(200.0, 180.0), widthways.geometry.piles.first().position)
        assertEquals(auto.alternatives, widthways.alternatives)
        val minimum = auto.alternatives.minBy { it.timberLengthMm!! }
        assertEquals(minimum.orientation, auto.recommended)
    }

    @Test fun squareDeckBreaksEfficiencyTieLengthways() {
        val result = success(DeckInput(widthMm = 4800.0, lengthMm = 4800.0))
        assertEquals(result.alternatives[0].timberLengthMm, result.alternatives[1].timberLengthMm)
        assertEquals(result.alternatives[0].pileCount, result.alternatives[1].pileCount)
        assertEquals(FramingOrientation.LENGTHWAYS, result.recommended)
    }

    @Test fun largeDeckHasSupportedJoistSplicesWithNoOversizeCuts() {
        val result = success(DeckInput(widthMm = 15000.0, lengthMm = 6000.0, orientation = FramingOrientation.LENGTHWAYS))
        val g = result.geometry
        assertTrue(g.joins.any { it.kind == MemberKind.JOIST })
        assertTrue(g.joins.filter { it.kind != MemberKind.BEARER }.all { join -> g.bearerPositionsMm.any { abs(it - v(g, join.position)) < epsilon } })
        assertTrue(g.members.filter { it.kind != MemberKind.NOG }.all { it.lengthMm <= 6000.0 + epsilon })
        assertTrue(DeckCalculator.validate(result).isEmpty())
    }

    @Test fun doubledBearerSplicesAreSupportedAndStaggered() {
        val g = success(DeckInput(widthMm = 15000.0, lengthMm = 6090.0, orientation = FramingOrientation.LENGTHWAYS)).geometry
        val joins = g.joins.filter { it.kind == MemberKind.BEARER }
        assertEquals(g.bearerPositionsMm.size * 2, joins.size)
        joins.forEach { join ->
            assertTrue(g.pilePositionsMm.any { abs(it - u(g, join.position)) < epsilon })
            assertFalse(joins.any { other -> other.runId == join.runId && other.layer != join.layer && abs(u(g, other.position) - u(g, join.position)) < epsilon })
        }
        assertTrue(g.members.filter { it.kind == MemberKind.BEARER }.all { it.lengthMm <= 6000.0 + epsilon })
    }

    @Test fun memberAtExactlySixMetresNeedsNoSpliceButLongerMemberDoes() {
        val atLimit = success(DeckInput(widthMm = 4800.0, lengthMm = 6000.0, orientation = FramingOrientation.LENGTHWAYS))
        assertTrue(atLimit.geometry.joins.none { it.kind == MemberKind.BEARER })
        val overLimit = success(DeckInput(widthMm = 4800.0, lengthMm = 6000.01, orientation = FramingOrientation.LENGTHWAYS))
        assertTrue(overLimit.geometry.joins.any { it.kind == MemberKind.BEARER })
        assertTrue(overLimit.geometry.members.all { it.lengthMm <= 6000.0 + epsilon })
    }

    @Test fun unsupportedPerpendicularBoundarySpliceIsExplainedAndAlternativeCanRemainValid() {
        val input = DeckInput(widthMm = 4800.0, lengthMm = 8000.0)
        val automatic = success(input)
        assertEquals(FramingOrientation.WIDTHWAYS, automatic.geometry.orientation)
        assertTrue(automatic.alternatives.first { it.orientation == FramingOrientation.LENGTHWAYS }.errors.single().contains("supported end-boundary splice"))
        assertTrue(invalid(input.copy(orientation = FramingOrientation.LENGTHWAYS)).errors.any { it.contains("6000") })
        assertTrue(invalid(DeckInput(widthMm = 8000.0, lengthMm = 8000.0)).errors.any { it.contains("boundary") })
    }

    @Test fun fullWidthBoardsArePreferredEvenWhenRippingCouldGiveCloserToFiveMillimetreGap() {
        val g = success().geometry
        assertEquals(90.0, g.startingBoardWidthMm, epsilon)
        assertTrue(g.boards.all { abs(it.widthMm - 90.0) < epsilon })
        assertTrue(g.deckingGapMm > 5.0)
    }

    @Test fun fourAndSixMillimetreGapLimitsAreInclusive() {
        val four = success(DeckInput(widthMm = 936.0, lengthMm = 4800.0, overhangMm = 0.0, orientation = FramingOrientation.LENGTHWAYS)).geometry
        val six = success(DeckInput(widthMm = 954.0, lengthMm = 4800.0, overhangMm = 0.0, orientation = FramingOrientation.LENGTHWAYS)).geometry
        assertEquals(10, four.boards.size)
        assertEquals(4.0, four.deckingGapMm, epsilon)
        assertEquals(10, six.boards.size)
        assertEquals(6.0, six.deckingGapMm, epsilon)
        assertEquals(90.0, four.startingBoardWidthMm, epsilon)
        assertEquals(90.0, six.startingBoardWidthMm, epsilon)
    }

    @Test fun startingRipCanReachSixtyAndCoverageRemainsExact() {
        val input = DeckInput(widthMm = 1000.0, lengthMm = 4800.0, overhangMm = 0.0, orientation = FramingOrientation.LENGTHWAYS)
        val g = success(input).geometry
        assertEquals(60.0, g.startingBoardWidthMm, epsilon)
        assertEquals(4.0, g.deckingGapMm, epsilon)
        assertEquals(11, g.boards.size)
        assertEquals(1000.0, g.boards.sumOf { it.widthMm } + 10 * g.deckingGapMm, epsilon)
    }

    @Test fun impossibleDeckingCoverageReturnsAConstraintMessage() {
        val error = invalid(DeckInput(widthMm = 400.0, lengthMm = 400.0, actualDeckingWidthMm = 140.0))
        assertTrue(error.errors.any { it.contains("4–6") && it.contains("60") })
    }

    @Test fun overhangChangesDeckingButDoesNotChangeFraming() {
        val baseline = success(DeckInput(orientation = FramingOrientation.LENGTHWAYS))
        val changed = success(baseline.input.copy(overhangMm = 50.0))
        assertEquals(baseline.geometry.members, changed.geometry.members)
        assertEquals(baseline.geometry.piles, changed.geometry.piles)
        assertEquals(4900.0, changed.geometry.boards.first().lengthMm, epsilon)
        assertEquals(-50.0, u(changed.geometry, changed.geometry.boards.first().origin), epsilon)
        assertEquals(-50.0, v(changed.geometry, changed.geometry.boards.first().origin), epsilon)
    }

    @Test fun materialProfilesDrivePileLengthAndKeepSpeciesSeparate() {
        val base = success(DeckInput(orientation = FramingOrientation.LENGTHWAYS))
        val deep = success(base.input.copy(bearer = Profiles.framing[2], joist = Profiles.framing[1], decking = Profiles.decking[2], deckingSpecies = "Kwila"))
        assertEquals(1049.0, deep.geometry.piles.first().lengthMm, epsilon)
        assertEquals(1151.0, base.geometry.piles.first().lengthMm, epsilon)
        val both = MaterialConsolidator.consolidate(base.materials + deep.materials)
        assertEquals(2, both.count { it.key.type == "Decking" })
        assertTrue(both.filter { it.key.type == "Decking" }.any { it.key.specification.contains("Kwila") })
    }

    @Test fun actualProfileDimensionsRemainPartOfMaterialIdentityEvenWhenNamesMatch() {
        val first = success(DeckInput(joist = TimberProfile("Custom joist", 140.0, 45.0)))
        val second = success(DeckInput(joist = TimberProfile("Custom joist", 190.0, 45.0)))
        val combined = MaterialConsolidator.consolidate(first.materials + second.materials)
        val customTimber = combined.filter { it.key.type == "Timber" && it.key.specification.contains("Custom joist") }
        assertEquals(2, customTimber.size)
    }

    @Test fun blockingIsStaggeredAndEveryActualRowGapMeetsMaximum() {
        val result = success(DeckInput(widthMm = 12000.0, lengthMm = 4800.0, orientation = FramingOrientation.LENGTHWAYS))
        val g = result.geometry
        val nogs = g.members.filter { it.kind == MemberKind.NOG }
        assertTrue(nogs.isNotEmpty())
        val firstRow = nogs.filter { it.id.startsWith("N1.") }
        assertEquals(2, firstRow.map { v(g, it.start) }.distinct().size)
        nogs.groupBy { u(g, it.start) }.values.forEach { bay ->
            val supports = (listOf(1.5 * result.input.joist.thicknessMm, g.joistRunMm - 1.5 * result.input.joist.thicknessMm) + bay.map { v(g, it.start) }).sorted()
            assertTrue(supports.zipWithNext().all { (a, b) -> b - a <= 1800.0 + epsilon })
        }
        assertEquals(nogs.sumOf { it.lengthMm } / 1000.0, result.materials.single { it.category == MaterialCategory.NOGS }.quantity, epsilon)
    }

    @Test fun bagYieldRoundsUpAndPileDisplacementUsesOnlyEmbedment() {
        val result = success(DeckInput(concreteYieldM3PerBag = 0.015))
        assertEquals(ceil(result.geometry.concreteM3 / 0.015), line(result, "Concrete bags").quantity, epsilon)
        val higher = success(result.input.copy(heightMm = 1500.0))
        assertEquals(result.geometry.concreteM3, higher.geometry.concreteM3, epsilon)
        assertEquals(result.geometry.excavationM3, higher.geometry.excavationM3, epsilon)
    }

    @Test fun exactConcreteBagBoundaryDoesNotAddAPhantomBag() {
        val onePile = success(DeckInput(widthMm = 400.0, lengthMm = 400.0, concreteYieldM3PerBag = 0.0881875))
        assertEquals(1.0, line(onePile, "Concrete bags").quantity, epsilon)
        val fifteenPiles = success(DeckInput(concreteYieldM3PerBag = 0.0881875))
        assertEquals(15.0, line(fifteenPiles, "Concrete bags").quantity, epsilon)
        val justInsufficient = success(DeckInput(widthMm = 400.0, lengthMm = 400.0, concreteYieldM3PerBag = 0.08818749))
        assertEquals(2.0, line(justInsufficient, "Concrete bags").quantity, epsilon)
    }

    @Test fun overhangCannotCreateAWhollyUnsupportedDeckingCourse() {
        val errors = invalid(DeckInput(overhangMm = 90.0, orientation = FramingOrientation.LENGTHWAYS)).errors
        assertTrue(errors.any { it.contains("no contact") })
        assertTrue(invalid(DeckInput(overhangMm = 300.0)).errors.any { it.contains("no contact") })
    }

    @Test fun smallDecksCannotHaveOverlappingPhysicalPilesOrUnspecifiedMergedFootings() {
        assertTrue(invalid(DeckInput(widthMm = 400.0, lengthMm = 450.0, orientation = FramingOrientation.LENGTHWAYS)).errors.any { it.contains("overlapping 125") })
        assertTrue(invalid(DeckInput(widthMm = 601.0, lengthMm = 4800.0, orientation = FramingOrientation.LENGTHWAYS)).errors.any { it.contains("footing holes overlap") })
        assertTrue(invalid(DeckInput(widthMm = 400.0, lengthMm = 600.0, orientation = FramingOrientation.LENGTHWAYS)).errors.any { it.contains("footing holes overlap") })
        val valid = success(DeckInput(widthMm = 760.0, lengthMm = 4800.0, overhangMm = 30.0, orientation = FramingOrientation.LENGTHWAYS)).geometry
        assertEquals(listOf(180.0, 580.0), valid.bearerPositionsMm)
        assertTrue(valid.bearerPositionsMm.zipWithNext().all { (a, b) -> b - a >= 400.0 - epsilon })
    }

    @Test fun consolidationPreservesUnitsExactCutsAndSpecifications() {
        val result = success()
        val consolidated = MaterialConsolidator.consolidate(result.materials + result.materials)
        result.materials.groupBy { it.key }.forEach { (key, group) ->
            val combined = consolidated.single { it.key == key }
            assertEquals(group.sumOf { it.quantity } * 2, combined.quantity, epsilon)
            assertEquals(group.sumOf { it.cutLengthsMm.size } * 2, combined.pieceCount)
        }
        val nailLine = line(result, "Nails")
        val unlikeUnit = nailLine.copy(key = nailLine.key.copy(unit = "boxes"))
        assertEquals(2, MaterialConsolidator.consolidate(listOf(nailLine, unlikeUnit)).size)
    }

    @Test fun noInternalJoistSupportAtOuterBoardsDoesNotGeneratePhantomScrews() {
        val result = success()
        val parallelRuns = result.geometry.joistPositionsMm.size
        val boards = result.geometry.boards.size
        // Two end boards reach only four full-length side boundary members, not ten internal joists.
        assertEquals(((boards - 2) * parallelRuns * 2 + 2 * 4 * 2).toDouble(), line(result, "Decking screws").quantity, epsilon)
    }

    @Test fun unsupportedTaskTypeIsAnExplicitError() {
        assertTrue(TaskLibrary.calculate("future.pergola", DeckInput()) is CalculationOutcome.Invalid)
        assertTrue(TaskLibrary.calculate("deck.freestanding.v1", DeckInput()) is CalculationOutcome.Success)
    }

    @Test fun invalidDimensionsAndNonFiniteInputsAreRejected() {
        val invalidInputs = listOf(
            DeckInput(widthMm = 0.0), DeckInput(lengthMm = -1.0), DeckInput(heightMm = Double.NaN),
            DeckInput(widthMm = Double.POSITIVE_INFINITY), DeckInput(widthMm = 30001.0),
            DeckInput(heightMm = 348.0), DeckInput(lengthMm = 399.0, orientation = FramingOrientation.LENGTHWAYS),
            DeckInput(widthMm = 350.0, orientation = FramingOrientation.LENGTHWAYS),
            DeckInput(maxJoistSpacingMm = 44.0), DeckInput(overhangMm = -1.0),
            DeckInput(actualDeckingWidthMm = 59.0), DeckInput(concreteYieldM3PerBag = 0.0),
            DeckInput(screwSpecification = ""), DeckInput(deckingSpecies = ""),
            DeckInput(joist = TimberProfile("wide", 140.0, 110.0))
        )
        invalidInputs.forEach { assertTrue(invalid(it).errors.isNotEmpty()) }
    }

    @Test fun heightAtExactFramingStackRequiresOnlyFiveHundredMillimetrePile() {
        val result = success(DeckInput(heightMm = 349.0))
        assertEquals(0.0, result.geometry.pileAboveGroundMm, epsilon)
        assertTrue(result.geometry.piles.all { abs(it.lengthMm - 500.0) < epsilon })
    }

    @Test fun seededConfigurationsAreDeterministicAndSatisfyIndependentGeometryInvariants() {
        val random = Random(20261008)
        var successes = 0
        repeat(180) {
            val input = DeckInput(
                widthMm = random.nextInt(400, 15001).toDouble(),
                lengthMm = random.nextInt(400, 6101).toDouble(),
                heightMm = random.nextInt(900, 2200).toDouble(),
                bearer = Profiles.framing[random.nextInt(Profiles.framing.size)],
                joist = Profiles.framing[random.nextInt(Profiles.framing.size)],
                maxJoistSpacingMm = listOf(300.0, 400.0, 450.0, 600.0)[random.nextInt(4)],
                actualDeckingWidthMm = listOf(90.0, 140.0)[random.nextInt(2)],
                overhangMm = random.nextInt(0, 61).toDouble())
            val outcome = DeckCalculator.calculate(input)
            assertEquals(outcome, DeckCalculator.calculate(input))
            if (outcome is CalculationOutcome.Success) {
                successes++
                val result = outcome.result
                val g = result.geometry
                assertTrue(DeckCalculator.validate(result).joinToString(), DeckCalculator.validate(result).isEmpty())
                assertTrue(g.members.all { it.lengthMm > 0.0 })
                assertTrue(g.members.filter { it.kind != MemberKind.NOG }.all { it.lengthMm <= 6000.0 + epsilon })
                assertTrue(g.pilePositionsMm.zipWithNext().all { (a, b) -> b - a <= 1300.0 + epsilon })
                assertTrue(g.bearerPositionsMm.zipWithNext().all { (a, b) -> b - a <= 1800.0 + epsilon })
                assertTrue(g.joistPositionsMm.zipWithNext().all { (a, b) -> b - a <= input.maxJoistSpacingMm + epsilon })
                assertEquals(g.piles.size * (0.096 - 0.0078125), g.concreteM3, epsilon)
                assertEquals(g.joistRunMm + 2 * input.overhangMm, g.boards.sumOf { it.widthMm } + (g.boards.size - 1) * g.deckingGapMm, epsilon)
                g.members.groupBy { Pair(it.runId, it.layer) }.values.forEach { pieces ->
                    if (pieces.first().kind != MemberKind.NOG) pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
                }
                result.materials.filter { it.key.unit == "lm" }.forEach { assertEquals(it.cutLengthsMm.sum() / 1000.0, it.quantity, epsilon) }
            }
        }
        assertTrue("At least 140 varied layouts must pass, got $successes", successes >= 140)
    }

    @Test fun invariantValidatorDetectsUnsupportedAndAlignedBearerJoins() {
        val result = success(DeckInput(widthMm = 10000.0, lengthMm = 6090.0, orientation = FramingOrientation.LENGTHWAYS))
        val original = result.geometry.joins.first { it.kind == MemberKind.BEARER }
        val unsupported = original.copy(position = result.geometry.point(123.0, v(result.geometry, original.position)))
        val corrupted = result.copy(geometry = result.geometry.copy(joins = result.geometry.joins + unsupported))
        assertTrue(DeckCalculator.validate(corrupted).any { it.contains("not supported") })
        val aligned = original.copy(layer = 1 - original.layer)
        assertTrue(DeckCalculator.validate(result.copy(geometry = result.geometry.copy(joins = result.geometry.joins + aligned))).any { it.contains("staggered") })
    }
}
