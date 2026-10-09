package nz.co.timbertakeoff.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PictureFrameCalculatorTest {
    private val epsilon = 0.00001
    private fun success(input: DeckInput): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid layout; got $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }
    private fun invalid(input: DeckInput): CalculationOutcome.Invalid {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected a clear constraint conflict; got $outcome", outcome is CalculationOutcome.Invalid)
        return outcome as CalculationOutcome.Invalid
    }
    private fun frameInput() = DeckInput(widthMm = 3670.0, lengthMm = 4800.0,
        decking = Profiles.decking[1], actualDeckingWidthMm = 140.0, pictureFrame = true,
        pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS, orientation = FramingOrientation.LENGTHWAYS)
    private fun u(g: DeckGeometry, point: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) point.x else point.y
    private fun v(g: DeckGeometry, point: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) point.y else point.x
    private fun material(result: DeckResult, type: String) = result.materials.single { it.key.type == type }

    @Test fun bracketPostsStopAtGroundAndRemoveAllFootingQuantities() {
        val concrete = success(DeckInput())
        val bracket = success(concrete.input.copy(pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        assertEquals(concrete.geometry.members, bracket.geometry.members)
        assertEquals(concrete.geometry.piles.size, bracket.geometry.piles.size)
        assertEquals(651.0, bracket.geometry.pileAboveGroundMm, epsilon)
        assertTrue(bracket.geometry.piles.all { abs(it.lengthMm - 651.0) < epsilon })
        assertEquals(9.765, bracket.materials.single { it.category == MaterialCategory.PILES }.quantity, epsilon)
        assertEquals(0.0, bracket.geometry.excavationM3, epsilon)
        assertEquals(0.0, bracket.geometry.concreteM3, epsilon)
        assertTrue(bracket.materials.none { it.category == MaterialCategory.CONCRETE })
        assertEquals(15.0, material(bracket, "Post brackets").quantity, epsilon)
        assertTrue(material(bracket, "Post brackets").key.specification.contains("to be confirmed"))
        assertTrue(DeckCalculator.validate(bracket).isEmpty())
    }

    @Test fun bracketPostLengthsFollowChangedFramingDepthsWithoutEmbedment() {
        val result = success(DeckInput(heightMm = 1500.0, joist = Profiles.framing[1], bearer = Profiles.framing[2],
            decking = Profiles.decking[2], pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        assertTrue(result.geometry.piles.all { abs(it.lengthMm - 1049.0) < epsilon })
        assertEquals(1049.0, result.geometry.pileAboveGroundMm, epsilon)
    }

    @Test fun bracketConnectionKeepsPhysicalPileLimitsButDoesNotRequireSeparateFootingHoles() {
        val valid = success(DeckInput(widthMm = 400.0, lengthMm = 600.0,
            orientation = FramingOrientation.LENGTHWAYS, pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        assertEquals(listOf(200.0, 400.0), valid.geometry.pilePositionsMm)
        assertTrue(invalid(valid.input.copy(lengthMm = 450.0)).errors.any { it.contains("125") })
        assertTrue(invalid(valid.input.copy(pileConnection = PileConnection.CONCRETE_FOOTINGS)).errors.any { it.contains("footing holes overlap") })
    }

    @Test fun zeroLengthBracketPostsAreRejectedButEmbeddedPilesRemainValidAtSameHeight() {
        val errors = invalid(DeckInput(heightMm = 349.0, pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS)).errors
        assertTrue(errors.any { it.contains("positive ground-to-bearer") })
        assertTrue(success(DeckInput(heightMm = 349.0)).geometry.piles.all { it.lengthMm == 500.0 })
        // A saved concrete yield has no bearing on an existing-concrete bracket layout.
        assertTrue(DeckCalculator.calculate(DeckInput(concreteYieldM3PerBag = 0.0,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS)) is CalculationOutcome.Success)
    }

    @Test fun pictureFrameHasIndependentExpectedExactTakeoffAndFirstInfillRip() {
        val result = success(frameInput())
        val g = result.geometry
        val frame = g.boards.filter { it.role == DeckBoardRole.PICTURE_FRAME }
        val infill = g.boards.filter { it.role == DeckBoardRole.INFILL }
        assertEquals(4, frame.size)
        assertEquals(24, infill.size)
        assertEquals(28, g.boards.size)
        assertTrue(frame.all { it.widthMm == 140.0 && it.outline.size == 4 })
        assertEquals(listOf(4840.0, 4840.0, 3710.0, 3710.0), frame.map { it.lengthMm })
        assertEquals(85.0, g.startingBoardWidthMm, epsilon)
        assertEquals(85.0, infill.first().widthMm, epsilon)
        assertTrue(infill.drop(1).all { it.widthMm == 140.0 })
        assertEquals(5.0, g.deckingGapMm, epsilon)
        assertEquals(125.0, u(g, infill.first().origin), epsilon)
        assertEquals(125.0, v(g, infill.first().origin), epsilon)
        assertTrue(infill.all { it.lengthMm == 4550.0 })
        assertEquals(126.3, material(result, "Decking").quantity, epsilon)
        assertEquals(660.0, material(result, "Decking screws").quantity, epsilon)
        assertEquals(316.0, material(result, "Nails").quantity, epsilon)
        assertEquals(107995.0, g.members.sumOf { it.lengthMm }, epsilon)
        assertTrue(DeckCalculator.validate(result).joinToString(), DeckCalculator.validate(result).isEmpty())
    }

    @Test fun fullInfillBoardsArePreferredBeforeAnyRipAndBothFrameEdgeGapsAreIncluded() {
        val result = success(frameInput().copy(widthMm = 3600.0))
        val g = result.geometry
        val infill = g.boards.filter { it.role == DeckBoardRole.INFILL }
        assertEquals(23, infill.size)
        assertEquals(27, g.boards.size)
        assertEquals(140.0, g.startingBoardWidthMm, epsilon)
        assertEquals(140.0 / 24.0, g.deckingGapMm, epsilon)
        assertEquals(121.57166666666667, material(result, "Decking").quantity, epsilon)
        assertEquals(10.995, result.materials.single { it.category == MaterialCategory.NOGS }.quantity, epsilon)
        assertEquals(106875.0, g.members.sumOf { it.lengthMm }, epsilon)
        assertEquals(636.0, material(result, "Decking screws").quantity, epsilon)
        assertEquals(3640.0, 280.0 + infill.sumOf { it.widthMm } + (infill.size + 1) * g.deckingGapMm, epsilon)
        assertEquals(g.joistRunMm - 120.0, v(g, infill.last().origin) + infill.last().widthMm + g.deckingGapMm, epsilon)
    }

    @Test fun pictureFrameGapLimitsAndSixtyMillimetreFirstInfillAreInclusive() {
        val four = success(frameInput().copy(widthMm = 1724.0, overhangMm = 0.0)).geometry
        val six = success(frameInput().copy(widthMm = 1746.0, overhangMm = 0.0)).geometry
        assertEquals(4.0, four.deckingGapMm, epsilon)
        assertEquals(6.0, six.deckingGapMm, epsilon)
        assertEquals(140.0, four.startingBoardWidthMm, epsilon)
        assertEquals(140.0, six.startingBoardWidthMm, epsilon)
        val rip = success(frameInput().copy(widthMm = 1760.0)).geometry
        assertEquals(60.0, rip.startingBoardWidthMm, epsilon)
        assertEquals(5.0, rip.deckingGapMm, epsilon)
    }

    @Test fun bothOrientationsRotateTheMitresAndSupportedInfillWithoutChangingDimensions() {
        for (orientation in listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS)) {
            val result = success(frameInput().copy(orientation = orientation))
            val g = result.geometry
            val frame = g.boards.filter { it.role == DeckBoardRole.PICTURE_FRAME }
            val infill = g.boards.filter { it.role == DeckBoardRole.INFILL }
            assertEquals(2, frame.count { it.runsAlongX })
            assertTrue(infill.all { it.runsAlongX == (orientation == FramingOrientation.WIDTHWAYS) })
            assertTrue(frame.flatMap { it.outline }.all { it.x >= -20.0 && it.x <= 3690.0 && it.y >= -20.0 && it.y <= 4820.0 })
            assertEquals(listOf(120.0, g.bearerRunMm - 120.0), g.pictureFrameSupportPositionsMm)
            val corner = g.point(-20.0, -20.0)
            assertEquals(2, frame.count { it.outline.contains(corner) })
            assertTrue(DeckCalculator.validate(result).isEmpty())
        }
    }

    @Test fun interfaceSupportsAreAtOneHundredTwentyAndNarrowBaysUseRippedPackers() {
        val result = success(frameInput())
        val g = result.geometry
        val supports = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }
        assertEquals(listOf(120.0, 4680.0), supports.map { u(g, it.start) })
        assertTrue(supports.all { v(g, it.start) == 90.0 && v(g, it.end) == 3580.0 })
        val packers = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_PACKER }
        assertEquals(2, packers.size)
        assertTrue(packers.all { it.lengthMm == 45.0 && it.thicknessMm == 7.5 && it.profile.thicknessMm == 7.5 })
        assertTrue(packers.all { u(g, it.start) == u(g, it.end) })
        assertTrue(packers.all { it.profile.depthMm == 140.0 && it.profile.species == result.input.joist.species })
        val nogs = result.materials.single { it.category == MaterialCategory.NOGS }
        assertEquals(11.135, nogs.quantity, epsilon)
        assertEquals(2, nogs.cutLengthsMm.count { abs(it - 45.0) < epsilon })
        assertFalse(nogs.cutLengthsMm.any { abs(it - 7.5) < epsilon })
        assertTrue(nogs.cutNotes.any { it.contains("2 × 45 mm source cuts") && it.contains("140 × 7.5 mm") })
        assertEquals(2.0, material(result, "Packer fixing sets").quantity, epsilon)
        assertTrue(material(result, "Packer fixing sets").key.specification.contains("to be confirmed"))
        assertTrue(g.joistPositionsMm.zipWithNext().all { (a, z) -> z - a <= 450.0 + epsilon && z - a >= 45.0 - epsilon })
        val consolidated = MaterialConsolidator.consolidate(result.materials)
        assertEquals(1, consolidated.count { it.key.type == "Timber" && it.key.specification.startsWith("140 × 45 mm") })
        assertEquals(79.195, consolidated.single { it.key.type == "Timber" && it.key.specification.startsWith("140 × 45 mm") }.quantity, epsilon)
    }

    @Test fun existingDoubledBoundaryCanSupplyTheInterfaceWithoutRedundantSupportTimber() {
        val result = success(DeckInput(pictureFrame = true, orientation = FramingOrientation.LENGTHWAYS))
        assertEquals(listOf(70.0, 4730.0), result.geometry.pictureFrameSupportPositionsMm)
        assertTrue(result.geometry.members.none { it.kind == MemberKind.PICTURE_FRAME_SUPPORT || it.kind == MemberKind.PICTURE_FRAME_PACKER })
        assertEquals(success(DeckInput(orientation = FramingOrientation.LENGTHWAYS)).geometry.members, result.geometry.members)
        assertTrue(DeckCalculator.validate(result).isEmpty())
    }

    @Test fun largeContinuousFrameSupportsHaveOnlyBearerSupportedSplicesAndSixMetreCuts() {
        val result = success(frameInput().copy(widthMm = 15000.0, lengthMm = 6000.0))
        val g = result.geometry
        val supportJoins = g.joins.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }
        assertTrue(supportJoins.isNotEmpty())
        assertTrue(supportJoins.all { join -> g.bearerPositionsMm.any { abs(it - v(g, join.position)) < epsilon } })
        assertTrue(g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }.all { it.lengthMm > 0.0 && it.lengthMm <= 6000.0 })
        val packers = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_PACKER }
        assertEquals(g.blockingRowsMm.size * 2, packers.size)
        assertTrue(DeckCalculator.validate(result).isEmpty())
    }

    @Test fun smallInfillBetweenTwoContinuousSupportsUsesPackersAtEachBlockingRow() {
        val result = success(frameInput().copy(lengthMm = 540.0, widthMm = 2000.0, actualDeckingWidthMm = 255.0))
        val g = result.geometry
        val packers = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_PACKER }
        assertEquals(g.blockingRowsMm.size, packers.size)
        assertTrue(packers.all { abs(u(g, it.start) - 270.0) < epsilon && abs(it.thicknessMm - 25.0) < epsilon })
        assertTrue(g.boards.filter { it.role == DeckBoardRole.INFILL }.all { abs(it.lengthMm - 60.0) < epsilon })
        assertTrue(DeckCalculator.validate(result).isEmpty())
    }

    @Test fun supportOverlapAndImpossibleFrameCoverageExplainTheirConflicts() {
        assertTrue(invalid(frameInput().copy(actualDeckingWidthMm = 120.0)).errors.any { it.contains("partially overlaps") })
        assertTrue(invalid(frameInput().copy(overhangMm = 140.0)).errors.any { it.contains("inner edges") })
        assertTrue(invalid(frameInput().copy(widthMm = 360.0, actualDeckingWidthMm = 200.0, overhangMm = 0.0)).errors.any { it.contains("too little space") })
        assertTrue(invalid(frameInput().copy(lengthMm = 400.0, actualDeckingWidthMm = 200.0, overhangMm = 0.0)).errors.any { it.contains("infill run length") })
    }

    @Test fun independentValidatorDetectsMissingPackersUnsupportedFrameJoinsAndBadMitres() {
        val result = success(frameInput())
        val missingPacker = result.copy(geometry = result.geometry.copy(members = result.geometry.members.filter { it.kind != MemberKind.PICTURE_FRAME_PACKER }))
        assertTrue(DeckCalculator.validate(missingPacker).any { it.contains("one nog or ripped packer") })
        val first = result.geometry.boards.first()
        val badMitre = first.copy(outline = first.outline.dropLast(1) + Point(0.0, 0.0))
        assertTrue(DeckCalculator.validate(result.copy(geometry = result.geometry.copy(boards = listOf(badMitre) + result.geometry.boards.drop(1)))).any { it.contains("45° mitres") })
        val large = success(frameInput().copy(widthMm = 15000.0, lengthMm = 6000.0))
        val join = large.geometry.joins.first { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }
        val invalidJoin = join.copy(position = large.geometry.point(u(large.geometry, join.position), 123.0))
        assertTrue(DeckCalculator.validate(large.copy(geometry = large.geometry.copy(joins = large.geometry.joins + invalidJoin))).any { it.contains("not supported") })
        val noBrackets = result.copy(materials = result.materials.filter { it.key.type != "Post brackets" })
        assertTrue(DeckCalculator.validate(noBrackets).any { it.contains("one bracket per post") })
    }
}
