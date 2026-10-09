package nz.co.timbertakeoff.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/** Check large-deck geometry independently of the cut-selection implementation. */
class LargeBoundarySpliceTest {
    private val epsilon = 0.00001
    private val forcedOrientations = listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS)
    private val endRuns = listOf("BE1", "BE2", "BE3", "BE4")

    private fun success(input: DeckInput): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid layout for $input, got $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    private fun u(g: DeckGeometry, point: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) point.x else point.y
    private fun v(g: DeckGeometry, point: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) point.y else point.x
    private fun close(a: Double, b: Double) = abs(a - b) < epsilon
    private fun isEnd(member: TimberMember) = member.kind == MemberKind.BOUNDARY && member.runId in endRuns
    private fun isEnd(join: MemberJoin) = join.kind == MemberKind.BOUNDARY && join.runId in endRuns

    private fun checkGeometry(result: DeckResult) {
        val g = result.geometry
        val t = result.input.joist.thicknessMm
        assertTrue(DeckCalculator.validate(result).joinToString(), DeckCalculator.validate(result).isEmpty())
        assertTrue("Every physical framing cut is at most 6000 mm", g.members.all { it.lengthMm > 0.0 && it.lengthMm <= 6000.0 + epsilon })
        val physicalCuts = mutableMapOf<String, List<Double>>()
        val crossPositions = listOf(t / 2.0, 1.5 * t, g.joistRunMm - 1.5 * t, g.joistRunMm - t / 2.0)
        endRuns.forEachIndexed { index, runId ->
            val pieces = g.members.filter { isEnd(it) && it.runId == runId }.sortedBy { u(g, it.start) }
            assertTrue("Both physical layers must exist at each end: $runId", pieces.isNotEmpty())
            assertTrue(pieces.all { it.layer == index % 2 && it.profile == result.input.joist })
            assertEquals(2.0 * t, u(g, pieces.first().start), epsilon)
            assertEquals(g.bearerRunMm - 2.0 * t, u(g, pieces.last().end), epsilon)
            assertTrue(pieces.all { close(v(g, it.start), crossPositions[index]) && close(v(g, it.end), crossPositions[index]) })
            pieces.zipWithNext().forEach { (left, right) ->
                assertEquals(left.end, right.start)
                val cutU = u(g, left.end)
                val meetingV = if (index < 2) 2.0 * t else g.joistRunMm - 2.0 * t
                assertTrue("$runId splice must meet an actual perpendicular joist endpoint", g.members.any { joist ->
                    joist.kind == MemberKind.JOIST && close(u(g, joist.start), cutU) &&
                        (close(v(g, joist.start), meetingV) || close(v(g, joist.end), meetingV))
                })
                assertTrue(g.joins.any { it.runId == runId && it.layer == left.layer && it.position == left.end })
            }
            physicalCuts[runId] = pieces.dropLast(1).map { u(g, it.end) }
            assertEquals(g.bearerRunMm - 4.0 * t, pieces.sumOf { it.lengthMm }, epsilon)
        }
        listOf("BE1" to "BE2", "BE3" to "BE4").forEach { (first, second) ->
            assertTrue("The two physical layers must have different splice positions", physicalCuts.getValue(first).none { cut ->
                physicalCuts.getValue(second).any { close(cut, it) }
            })
        }
        val physicalButtJoinCount = physicalCuts.values.sumOf { it.size }
        val spliceFixings = result.materials.filter { it.key.type == "Boundary splice fixing sets" }
        if (physicalButtJoinCount == 0) assertTrue(spliceFixings.isEmpty()) else {
            assertEquals(1, spliceFixings.size)
            val line = spliceFixings.single()
            assertEquals(MaterialCategory.FIXINGS, line.category)
            assertEquals("each", line.key.unit)
            assertEquals(physicalButtJoinCount.toDouble(), line.quantity, 0.0)
            assertTrue(line.key.specification.contains("specification and fasteners per set to be confirmed", ignoreCase = true))
        }
        assertEquals(4.0 * (g.bearerRunMm - 4.0 * t), g.members.filter(::isEnd).sumOf { it.lengthMm }, epsilon)
        g.joins.filterNot(::isEnd).forEach { join ->
            if (join.kind == MemberKind.BEARER) {
                assertTrue(g.pilePositionsMm.any { close(it, u(g, join.position)) })
                assertFalse(g.joins.any { other -> other.kind == MemberKind.BEARER && other.runId == join.runId && other.layer != join.layer && close(u(g, other.position), u(g, join.position)) })
            } else assertTrue("Only perpendicular end-boundary splices are exempt from bearing support", g.bearerPositionsMm.any { close(it, v(g, join.position)) })
        }
        // End-boundary splices add no support piles, bearer lines, footing holes, or timber runs.
        val expectedBearerLines = ceil((g.joistRunMm - 600.0) / 1800.0).toInt() + 1
        val expectedPilesPerLine = ceil((g.bearerRunMm - 400.0) / 1300.0).toInt() + 1
        assertEquals(expectedBearerLines, g.bearerPositionsMm.size)
        assertEquals(expectedPilesPerLine, g.pilePositionsMm.size)
        assertEquals(expectedBearerLines * expectedPilesPerLine, g.piles.size)
        assertEquals(2.0 * expectedBearerLines * g.bearerRunMm, g.members.filter { it.kind == MemberKind.BEARER }.sumOf { it.lengthMm }, epsilon)
        if (result.input.pileConnection == PileConnection.CONCRETE_FOOTINGS) {
            assertEquals(g.piles.size * 0.096, g.excavationM3, epsilon)
            assertEquals(g.piles.size * 0.0881875, g.concreteM3, epsilon)
        } else {
            assertEquals(0.0, g.excavationM3, 0.0)
            assertEquals(0.0, g.concreteM3, 0.0)
            assertTrue(result.materials.none { it.category == MaterialCategory.CONCRETE })
            assertEquals(g.piles.size.toDouble(), result.materials.single { it.key.type == "Post brackets" }.quantity, 0.0)
        }
        val expectedJoistCuts = g.members.filter { it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY }.map { it.lengthMm }.sorted()
        val materialJoistCuts = result.materials.filter { it.category == MaterialCategory.JOISTS }.flatMap { it.cutLengthsMm }.sorted()
        assertEquals(expectedJoistCuts, materialJoistCuts)
        result.materials.filter { it.key.unit == "lm" }.forEach { assertEquals(it.cutLengthsMm.sum() / 1000.0, it.quantity, epsilon) }
    }

    @Test fun decksLargerThanSixMetresInBothDirectionsUseActualStaggeredIntersections() {
        for ((width, length) in listOf(8000.0 to 9000.0, 15000.0 to 16000.0)) {
            forcedOrientations.forEach { orientation ->
                val result = success(DeckInput(widthMm = width, lengthMm = length, orientation = orientation))
                assertTrue(result.alternatives.all { it.errors.isEmpty() })
                assertTrue(result.geometry.joins.any(::isEnd))
                checkGeometry(result)
                assertEquals(result, success(result.input))
            }
        }
    }

    @Test fun applicationResourceLimitAllowsThirtyMetresWithNoOversizePhysicalCuts() {
        forcedOrientations.forEach { orientation ->
            checkGeometry(success(DeckInput(widthMm = 30000.0, lengthMm = 30000.0, orientation = orientation)))
        }
    }

    @Test fun actualBoundaryCutAtSixMetresNeedsNoJoinButOneMicronLongerDoes() {
        for (profile in listOf(Profiles.framing[0], Profiles.framing[3])) {
            forcedOrientations.forEach { orientation ->
                val bearerRun = 6000.0 + 4.0 * profile.thicknessMm
                val input = DeckInput(
                    widthMm = if (orientation == FramingOrientation.WIDTHWAYS) bearerRun else 4000.0,
                    lengthMm = if (orientation == FramingOrientation.WIDTHWAYS) 4000.0 else bearerRun,
                    orientation = orientation, joist = profile)
                val exact = success(input)
                assertTrue(exact.geometry.joins.none(::isEnd))
                assertEquals(4, exact.geometry.members.count(::isEnd))
                assertTrue(exact.geometry.members.filter(::isEnd).all { close(it.lengthMm, 6000.0) })
                val over = success(if (orientation == FramingOrientation.WIDTHWAYS) input.copy(widthMm = bearerRun + 0.001) else input.copy(lengthMm = bearerRun + 0.001))
                assertEquals(4, over.geometry.joins.count(::isEnd))
                checkGeometry(over)
            }
        }
    }

    @Test fun bothPostSystemsAndChangedProfilesKeepCutsAndTakeoffConsistent() {
        for (connection in PileConnection.entries) {
            for (profile in listOf(Profiles.framing[0], Profiles.framing[3])) {
                forcedOrientations.forEach { orientation ->
                    val result = success(DeckInput(widthMm = 8000.0, lengthMm = 9000.0,
                        orientation = orientation, joist = profile, bearer = Profiles.framing[3], pileConnection = connection))
                    checkGeometry(result)
                    val embedment = if (connection == PileConnection.CONCRETE_FOOTINGS) 500.0 else 0.0
                    assertTrue(result.geometry.piles.all { close(it.lengthMm, 1000.0 - 19.0 - profile.depthMm - 190.0 + embedment) })
                }
            }
        }
    }

    @Test fun largePictureFramesUseRealJoistIntersectionsAndPreserveInterfaceSupports() {
        forcedOrientations.forEach { orientation ->
            val result = success(DeckInput(widthMm = 8000.0, lengthMm = 9000.0, orientation = orientation,
                decking = Profiles.decking[1], actualDeckingWidthMm = 140.0, overhangMm = 20.0, pictureFrame = true))
            checkGeometry(result)
            assertEquals(listOf(120.0, result.geometry.bearerRunMm - 120.0), result.geometry.pictureFrameSupportPositionsMm)
            assertEquals(4, result.geometry.boards.count { it.role == DeckBoardRole.PICTURE_FRAME })
            assertTrue(result.geometry.members.any { it.kind == MemberKind.PICTURE_FRAME_SUPPORT })
        }
    }

    @Test fun ordinaryDeckQuantitiesAndSupportsAreUnchanged() {
        val concrete = success(DeckInput())
        val bracket = success(DeckInput(pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        assertEquals(15, concrete.geometry.piles.size)
        assertEquals(100050.0, concrete.geometry.members.sumOf { it.lengthMm }, epsilon)
        assertEquals(1.44, concrete.geometry.excavationM3, epsilon)
        assertEquals(1.3228125, concrete.geometry.concreteM3, epsilon)
        assertEquals(concrete.geometry.members, bracket.geometry.members)
        assertEquals(concrete.geometry.piles.map { it.position }, bracket.geometry.piles.map { it.position })
        assertTrue(concrete.geometry.joins.none(::isEnd))
        assertTrue(concrete.materials.none { it.key.type == "Boundary splice fixing sets" })
        assertTrue(bracket.materials.none { it.key.type == "Boundary splice fixing sets" })
        assertEquals(4.0 * (4800.0 - 180.0), concrete.geometry.members.filter(::isEnd).sumOf { it.lengthMm }, epsilon)
    }

    private fun largeFixture(): DeckResult = success(DeckInput(widthMm = 8000.0, lengthMm = 9000.0,
        orientation = FramingOrientation.LENGTHWAYS))

    /** Rebuild only one physical end-boundary run and matching join records, without using the engine's cutter. */
    private fun replaceEndRun(result: DeckResult, runId: String, internalCuts: List<Double>, recordJoins: Boolean = true): DeckResult {
        val g = result.geometry
        val template = g.members.first { it.runId == runId }
        val cross = v(g, template.start)
        val cuts = listOf(2.0 * result.input.joist.thicknessMm) + internalCuts.sorted() + listOf(g.bearerRunMm - 2.0 * result.input.joist.thicknessMm)
        val replacements = cuts.zipWithNext().mapIndexed { index, (start, end) ->
            template.copy(id = "$runId-rebuilt-$index", start = g.point(start, cross), end = g.point(end, cross))
        }
        val joins = if (recordJoins) internalCuts.map { MemberJoin(g.point(it, cross), MemberKind.BOUNDARY, runId, template.layer) } else emptyList()
        return result.copy(geometry = g.copy(members = g.members.filterNot { it.runId == runId } + replacements,
            joins = g.joins.filterNot { it.runId == runId } + joins))
    }

    @Test fun validatorRejectsAlignedPhysicalBoundarySplicesEvenWithoutJoinRecords() {
        val result = largeFixture()
        val shared = u(result.geometry, result.geometry.joins.first { it.runId == "BE1" }.position)
        for (records in listOf(true, false)) {
            val corrupted = replaceEndRun(result, "BE2", listOf(shared), recordJoins = records)
            val errors = DeckCalculator.validate(corrupted)
            assertTrue("Aligned double-boundary joins must be rejected from physical cuts: $errors", errors.any { it.contains("stagger", ignoreCase = true) })
        }
    }

    @Test fun validatorRejectsOffIntersectionPhysicalCutsIncludingMissingRecords() {
        val result = largeFixture()
        val offIntersection = u(result.geometry, result.geometry.joins.first { it.runId == "BE1" }.position) + 1.0
        for (records in listOf(true, false)) {
            val errors = DeckCalculator.validate(replaceEndRun(result, "BE1", listOf(offIntersection), recordJoins = records))
            assertTrue("Boundary physical cuts must meet perpendicular joists: $errors", errors.any {
                it.contains("perpendicular", ignoreCase = true) || it.contains("intersection", ignoreCase = true)
            })
        }
    }

    @Test fun validatorUsesPhysicalJoistEndsInsteadOfOnlyStoredCentrePositions() {
        val result = largeFixture()
        val g = result.geometry
        val cut = u(g, g.joins.first { it.runId == "BE1" }.position)
        val meetingRun = g.members.first { it.kind == MemberKind.JOIST && close(u(g, it.start), cut) }.runId
        val missing = result.copy(geometry = g.copy(members = g.members.filterNot { it.runId == meetingRun },
            joins = g.joins.filterNot { it.runId == meetingRun }))
        assertTrue(DeckCalculator.validate(missing).isNotEmpty())
        val moved = result.copy(geometry = g.copy(members = g.members.map {
            if (it.runId == meetingRun) it.copy(start = g.point(u(g, it.start) + 1.0, v(g, it.start)),
                end = g.point(u(g, it.end) + 1.0, v(g, it.end))) else it
        }, joins = g.joins.map { if (it.runId == meetingRun) it.copy(position = g.point(u(g, it.position) + 1.0, v(g, it.position))) else it }))
        assertTrue(DeckCalculator.validate(moved).isNotEmpty())
        val near = g.members.first { it.runId == meetingRun && close(v(g, it.start), 2.0 * result.input.joist.thicknessMm) }
        val shortened = result.copy(geometry = g.copy(members = g.members.map {
            if (it.id == near.id) it.copy(start = g.point(u(g, it.start), v(g, it.start) + 1.0)) else it
        }))
        assertTrue(DeckCalculator.validate(shortened).isNotEmpty())
    }

    @Test fun validatorRejectsMissingBoundaryLayerAndIncompleteOverallCoverage() {
        val result = largeFixture()
        val g = result.geometry
        val missingLayer = result.copy(geometry = g.copy(members = g.members.filterNot { it.runId == "BE2" },
            joins = g.joins.filterNot { it.runId == "BE2" }))
        assertTrue("Both physical layers are required", DeckCalculator.validate(missingLayer).isNotEmpty())
        val pieces = g.members.filter { it.runId == "BE1" }.sortedBy { u(g, it.start) }
        val incompleteStart = result.copy(geometry = g.copy(members = g.members.map {
            if (it.id == pieces.first().id) it.copy(start = g.point(u(g, it.start) + 1.0, v(g, it.start))) else it
        }))
        val incompleteEnd = result.copy(geometry = g.copy(members = g.members.map {
            if (it.id == pieces.last().id) it.copy(end = g.point(u(g, it.end) - 1.0, v(g, it.end))) else it
        }))
        assertTrue("Full boundary coverage must include the first corner", DeckCalculator.validate(incompleteStart).isNotEmpty())
        assertTrue("Full boundary coverage must include the last corner", DeckCalculator.validate(incompleteEnd).isNotEmpty())
        val gap = result.copy(geometry = g.copy(members = g.members.map {
            if (it.id == pieces.first().id) it.copy(end = g.point(u(g, it.end) - 1.0, v(g, it.end))) else it
        }))
        assertTrue(DeckCalculator.validate(gap).any { it.contains("gap", ignoreCase = true) || it.contains("coverage", ignoreCase = true) })
    }

    @Test fun validatorRejectsWrongLayerAndPhantomJoinRecords() {
        val result = largeFixture()
        val g = result.geometry
        val wrongLayer = result.copy(geometry = g.copy(members = g.members.map { if (it.runId == "BE2") it.copy(layer = 0) else it },
            joins = g.joins.map { if (it.runId == "BE2") it.copy(layer = 0) else it }))
        assertTrue(DeckCalculator.validate(wrongLayer).isNotEmpty())
        val actualCuts = g.joins.filter { it.runId == "BE1" }.map { u(g, it.position) }
        val unused = g.members.first { it.kind == MemberKind.JOIST && actualCuts.none { cut -> close(cut, u(g, it.start)) } }
        val phantom = MemberJoin(g.point(u(g, unused.start), result.input.joist.thicknessMm / 2.0), MemberKind.BOUNDARY, "BE1", 0)
        assertTrue("A legal joist centre alone does not create a physical boundary join", DeckCalculator.validate(result.copy(geometry = g.copy(joins = g.joins + phantom))).isNotEmpty())
    }

    @Test fun boundaryExceptionDoesNotPermitUnsupportedSideBoundaryOrOrdinaryJoistSplices() {
        val result = largeFixture()
        val g = result.geometry
        for (runId in listOf("BJ1", "J1")) {
            val pieces = g.members.filter { it.runId == runId }.sortedBy { v(g, it.start) }
            assertTrue(pieces.size > 1)
            val cutV = v(g, pieces.first().end)
            val changedV = cutV + 1.0
            val corrupted = result.copy(geometry = g.copy(members = g.members.map {
                when (it.id) {
                    pieces[0].id -> it.copy(end = g.point(u(g, it.end), changedV))
                    pieces[1].id -> it.copy(start = g.point(u(g, it.start), changedV))
                    else -> it
                }
            }, joins = g.joins.map { if (it.runId == runId && close(v(g, it.position), cutV)) it.copy(position = g.point(u(g, it.position), changedV)) else it }))
            assertTrue("$runId still requires its joins over bearers", DeckCalculator.validate(corrupted).any { it.contains("support", ignoreCase = true) || it.contains("bearer", ignoreCase = true) })
        }
    }

    @Test fun boundaryExceptionDoesNotPermitOneOversizePhysicalMember() {
        val result = largeFixture()
        val corrupted = replaceEndRun(result, "BE1", emptyList())
        assertTrue(DeckCalculator.validate(corrupted).any { it.contains("6000") })
    }
}
