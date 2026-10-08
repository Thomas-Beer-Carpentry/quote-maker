package nz.co.timbertakeoff.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import java.math.BigDecimal
import java.math.RoundingMode

/** Pure, deterministic V1 estimating rules. All distances are in millimetres. */
object DeckCalculator {
    private const val EPS = 0.00001
    private const val MAX_MEMBER = 6000.0
    private const val MAX_BEARER_SPACING = 1800.0
    private const val MAX_PILE_SPACING = 1300.0
    private const val MAX_BLOCKING_SPACING = 1800.0
    private const val PILE_END = 200.0
    private const val PILE_EMBEDMENT = 500.0
    private const val PILE_SIZE = 125.0

    fun calculate(input: DeckInput): CalculationOutcome {
        val inputErrors = inputErrors(input)
        if (inputErrors.isNotEmpty()) return CalculationOutcome.Invalid(inputErrors)
        val layouts = listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).map { orientation ->
            try {
                val geometry = geometry(input, orientation)
                val errors = validateGeometry(input, geometry)
                if (errors.isNotEmpty()) Candidate(orientation, errors = errors)
                else Candidate(orientation, geometry, takeoff(input, geometry))
            } catch (conflict: LayoutConflict) {
                Candidate(orientation, errors = listOf(conflict.message ?: "Layout cannot satisfy the construction rules."))
            }
        }
        val alternatives = layouts.map { candidate ->
            OrientationAlternative(candidate.orientation, candidate.geometry?.members?.sumOf { it.lengthMm },
                candidate.geometry?.piles?.size, candidate.errors)
        }
        val best = layouts.filter { it.geometry != null }.minWithOrNull(
            compareBy<Candidate>({ it.geometry!!.members.sumOf { member -> member.lengthMm } },
                { it.geometry!!.piles.size }, { if (it.orientation == FramingOrientation.LENGTHWAYS) 0 else 1 })
        ) ?: return CalculationOutcome.Invalid(
            layouts.flatMap { candidate -> candidate.errors.map { "${candidate.orientation}: $it" } }, alternatives)
        val selected = if (input.orientation == FramingOrientation.AUTOMATIC) best
            else layouts.first { it.orientation == input.orientation }
        if (selected.geometry == null) return CalculationOutcome.Invalid(selected.errors, alternatives)
        return CalculationOutcome.Success(DeckResult(input, selected.geometry, selected.materials, alternatives, best.orientation,
            listOf(
                "Preliminary estimating / set-out information. Structural design and compliance have not been verified.",
                "Exact quantities; no waste allowance or stock-length optimisation.",
                "Cantilevers and pile supports are measured to bearer-pair centrelines. Interior joists fit between doubled end boundaries.",
                "Both physical members of each doubled side boundary receive decking fixings. Parallel end boundaries have no discrete decking crossing.",
                "Bearer lamination nails include stations at both ends, spaced at no more than 600 mm. Spliced joist ends each receive two nails."
            )))
    }

    /** Independent safety checks are also exposed to tests and future task integrations. */
    fun validate(result: DeckResult): List<String> {
        val errors = validateGeometry(result.input, result.geometry).toMutableList()
        if (result.materials.any { !it.quantity.isFinite() || it.quantity < 0.0 || it.cutLengthsMm.any { cut -> !cut.isFinite() || cut <= 0.0 } })
            errors += "Material quantities and cut lengths must be positive and finite."
        result.materials.filter { it.key.unit == "lm" }.forEach { line ->
            if (abs(line.quantity - line.cutLengthsMm.sum() / 1000.0) > EPS)
                errors += "Lineal metres must equal the exact cut lengths for ${line.key.specification}."
        }
        if (result.materials.filter { it.key.unit == "bags" || it.key.unit == "each" }.any { abs(it.quantity - floor(it.quantity)) > EPS })
            errors += "Physical bag and fixing quantities must be whole counts."
        return errors.distinct()
    }

    private data class Candidate(val orientation: FramingOrientation, val geometry: DeckGeometry? = null,
        val materials: List<MaterialLine> = emptyList(), val errors: List<String> = emptyList())
    private class LayoutConflict(message: String) : RuntimeException(message)
    private fun conflict(message: String): Nothing = throw LayoutConflict(message)

    private fun inputErrors(input: DeckInput): List<String> = buildList {
        fun range(value: Double, low: Double, high: Double, name: String) {
            if (!value.isFinite() || value < low || value > high) add("$name must be between ${format(low)} and ${format(high)} mm.")
        }
        range(input.widthMm, 1.0, 30000.0, "Deck width")
        range(input.lengthMm, 1.0, 30000.0, "Deck length")
        range(input.heightMm, 1.0, 10000.0, "Finished deck height")
        range(input.maxJoistSpacingMm, 40.0, 1800.0, "Maximum joist spacing")
        range(input.actualDeckingWidthMm, 60.0, 400.0, "Actual finished decking width")
        range(input.overhangMm, 0.0, 300.0, "Decking overhang")
        range(input.decking.thicknessMm, 1.0, 100.0, "Decking thickness")
        listOf("Bearer" to input.bearer, "Joist" to input.joist).forEach { (label, profile) ->
            range(profile.depthMm, 45.0, 600.0, "$label depth")
            range(profile.thicknessMm, 20.0, 125.0, "$label thickness")
            if (profile.name.isBlank() || profile.species.isBlank()) add("$label profile and species must be specified.")
        }
        if (input.maxJoistSpacingMm < input.joist.thicknessMm) add("Maximum joist spacing must be at least the joist thickness.")
        if (input.deckingSpecies.isBlank()) add("Decking species must be specified.")
        if (input.screwSpecification.isBlank()) add("Decking screw specification must be specified.")
        if (!input.concreteYieldM3PerBag.isFinite() || input.concreteYieldM3PerBag !in 0.000001..1.0)
            add("Concrete yield per 20 kg bag must be between 0.000001 and 1 cubic metre.")
    }

    private fun geometry(input: DeckInput, orientation: FramingOrientation): DeckGeometry {
        val b = if (orientation == FramingOrientation.LENGTHWAYS) input.lengthMm else input.widthMm
        val j = if (orientation == FramingOrientation.LENGTHWAYS) input.widthMm else input.lengthMm
        val t = input.joist.thicknessMm
        fun point(u: Double, v: Double) = if (orientation == FramingOrientation.WIDTHWAYS) Point(u, v) else Point(v, u)
        if (b < 2.0 * PILE_END - EPS) conflict("Bearer run must be at least 400 mm to accommodate the two 200 mm end cantilevers.")
        if (b <= 4.0 * t || j <= 4.0 * t) conflict("Deck dimensions must exceed the space occupied by the doubled boundaries (${format(4.0 * t)} mm).")
        if (b - 4.0 * t > MAX_MEMBER + EPS)
            conflict("Perpendicular end boundary cut length ${format(b - 4.0 * t)} mm exceeds 6000 mm. A supported end-boundary splice detail has not been specified; change orientation or dimensions.")
        val minimumCantilever = 2.0 * t + 90.0
        if (minimumCantilever > 300.0 + EPS)
            conflict("The selected joist thickness leaves no cantilever satisfying both the outside boundary and the interior joist ends (90–300 mm).")
        if (j < 2.0 * minimumCantilever - EPS)
            conflict("Joist run is too short for doubled end boundaries and 90 mm minimum cantilevers at both interior joist ends.")
        val minimumBearerSpan = max(0.0, j - 600.0)
        val bearerIntervals = if (minimumBearerSpan <= EPS) 0 else ceilSafe(minimumBearerSpan / MAX_BEARER_SPACING)
        val actualBearerSpacing = if (bearerIntervals == 0) 0.0 else min(MAX_BEARER_SPACING, (j - 2.0 * minimumCantilever) / bearerIntervals)
        val cantilever = if (bearerIntervals == 0) j / 2.0 else (j - actualBearerSpacing * bearerIntervals) / 2.0
        val bearerPositions = if (bearerIntervals == 0) listOf(j / 2.0)
            else evenly(cantilever, j - cantilever, bearerIntervals)
        if (bearerIntervals > 0 && actualBearerSpacing < max(PILE_SIZE, 2.0 * input.bearer.thicknessMm) - EPS)
            conflict("The required bearer lines physically overlap timber or piles within the permitted joist cantilevers. Increase the joist run or change orientation.")
        val pileSpan = b - 2.0 * PILE_END
        val pilePositions = if (pileSpan <= EPS) listOf(PILE_END)
            else evenly(PILE_END, b - PILE_END, ceilSafe(pileSpan / MAX_PILE_SPACING))
        if (pilePositions.zipWithNext().any { (left, right) -> right - left < PILE_SIZE - EPS })
            conflict("The fixed 200 mm bearer end cantilevers leave overlapping 125 mm piles. Increase the bearer run or change orientation.")
        if (bearerPositions.zipWithNext().any { (left, right) -> right - left < 400.0 - EPS } ||
            pilePositions.zipWithNext().any { (left, right) -> right - left < 400.0 - EPS })
            conflict("The 400 × 400 mm footing holes overlap. A combined-footing excavation/concrete detail has not been specified; increase dimensions or change orientation.")
        val pileAboveGround = input.heightMm - input.decking.thicknessMm - input.joist.depthMm - input.bearer.depthMm
        if (pileAboveGround < -EPS) conflict("Finished deck height must be at least the decking, joist and bearer depth combined (${format(input.decking.thicknessMm + input.joist.depthMm + input.bearer.depthMm)} mm).")

        val sideCentres = listOf(t / 2.0, 1.5 * t, b - 1.5 * t, b - t / 2.0)
        val innerSpan = b - 3.0 * t
        val joistIntervals = ceilSafe(innerSpan / input.maxJoistSpacingMm)
        val internalJoists = (1 until joistIntervals).map { 1.5 * t + innerSpan * it / joistIntervals }
        val joistPositions = (sideCentres + internalJoists).sorted()
        if (joistPositions.zipWithNext().any { (left, right) -> right - left < t - EPS })
            conflict("Evenly spaced joists overlap with the selected thickness and maximum spacing. Increase the maximum joist spacing.")
        val actualJoistSpacing = joistPositions.zipWithNext().maxOf { (a, z) -> z - a }
        val members = mutableListOf<TimberMember>()
        val joins = mutableListOf<MemberJoin>()
        val piles = mutableListOf<Pile>()
        fun addRun(kind: MemberKind, runId: String, layer: Int, start: Double, end: Double,
            cross: Double, alongU: Boolean, profile: TimberProfile, supports: List<Double>, forbidden: List<Double> = emptyList()): List<Double> {
            val cuts = supportedCuts(start, end, supports, forbidden)
            cuts.zipWithNext().forEachIndexed { index, (a, z) ->
                members += TimberMember("$runId-$layer-${index + 1}", kind,
                    if (alongU) point(a, cross) else point(cross, a),
                    if (alongU) point(z, cross) else point(cross, z), profile.thicknessMm, profile, runId, layer)
            }
            val splices = cuts.drop(1).dropLast(1)
            splices.forEach { cut -> joins += MemberJoin(if (alongU) point(cut, cross) else point(cross, cut), kind, runId, layer) }
            return splices
        }
        bearerPositions.forEachIndexed { line, v ->
            val runId = "B${line + 1}"
            val firstJoins = addRun(MemberKind.BEARER, runId, 0, 0.0, b, v - input.bearer.thicknessMm / 2.0, true, input.bearer, pilePositions)
            addRun(MemberKind.BEARER, runId, 1, 0.0, b, v + input.bearer.thicknessMm / 2.0, true, input.bearer, pilePositions, firstJoins)
            pilePositions.forEachIndexed { index, u -> piles += Pile("P${line + 1}.${index + 1}", point(u, v), max(0.0, pileAboveGround) + PILE_EMBEDMENT) }
        }
        sideCentres.forEachIndexed { index, u ->
            addRun(MemberKind.BOUNDARY, "BJ${index + 1}", index % 2, 0.0, j, u, false, input.joist, bearerPositions)
        }
        internalJoists.forEachIndexed { index, u ->
            addRun(MemberKind.JOIST, "J${index + 1}", 0, 2.0 * t, j - 2.0 * t, u, false, input.joist, bearerPositions)
        }
        listOf(t / 2.0, 1.5 * t, j - 1.5 * t, j - t / 2.0).forEachIndexed { index, v ->
            addRun(MemberKind.BOUNDARY, "BE${index + 1}", index % 2, 2.0 * t, b - 2.0 * t, v, true, input.joist, emptyList())
        }
        // End boundaries count as restraint rows. Staggered nogs remain within the clear interior.
        val restraintSpan = j - 3.0 * t
        val blockingIntervals = max(2, ceilSafe(restraintSpan / (MAX_BLOCKING_SPACING - t / 2.0)))
        val blockingRows = (1 until blockingIntervals).map { 1.5 * t + restraintSpan * it / blockingIntervals }
        joistPositions.zipWithNext().forEachIndexed { bay, (left, right) ->
            val cut = right - left - t
            if (cut > EPS) blockingRows.forEachIndexed { row, baseV ->
                val v = baseV + if (bay % 2 == 0) -t / 2.0 else t / 2.0
                members += TimberMember("N${row + 1}.${bay + 1}", MemberKind.NOG, point(left + t / 2.0, v),
                    point(right - t / 2.0, v), t, input.joist, "N${row + 1}.${bay + 1}")
            }
        }
        val boardLayout = boardLayout(j + 2.0 * input.overhangMm, input.actualDeckingWidthMm)
        val boards = mutableListOf<DeckBoard>()
        var v = -input.overhangMm
        repeat(boardLayout.count) { index ->
            val width = if (index == 0) boardLayout.startWidth else input.actualDeckingWidthMm
            boards += DeckBoard(index, point(-input.overhangMm, v), width, b + 2.0 * input.overhangMm,
                orientation == FramingOrientation.WIDTHWAYS)
            v += width + boardLayout.gap
        }
        // Exact decimal volumes avoid a phantom extra bag at an exact whole-bag boundary.
        val excavation = BigDecimal("0.096").multiply(BigDecimal.valueOf(piles.size.toLong())).toDouble()
        val concrete = BigDecimal("0.0881875").multiply(BigDecimal.valueOf(piles.size.toLong())).toDouble()
        return DeckGeometry(orientation, b, j, bearerPositions, pilePositions, joistPositions, blockingRows,
            members, piles, joins, boards, actualJoistSpacing, actualBearerSpacing, cantilever,
            boardLayout.gap, boardLayout.startWidth, max(0.0, pileAboveGround), excavation, concrete)
    }

    /** Minimum pieces, then minimum squared cut lengths for a balanced supported cutting schedule. */
    private fun supportedCuts(start: Double, end: Double, supports: List<Double>, forbidden: List<Double>): List<Double> {
        val nodes = listOf(start) + supports.filter { it > start + EPS && it < end - EPS && forbidden.none { excluded -> abs(it - excluded) < EPS } }.sorted() + end
        val counts = IntArray(nodes.size) { Int.MAX_VALUE }
        val squaredLengths = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(nodes.size) { -1 }
        counts[0] = 0
        squaredLengths[0] = 0.0
        for (next in 1 until nodes.size) for (from in 0 until next) {
            val length = nodes[next] - nodes[from]
            if (length > MAX_MEMBER + EPS || counts[from] == Int.MAX_VALUE) continue
            val count = counts[from] + 1
            val square = squaredLengths[from] + length * length
            if (count < counts[next] || (count == counts[next] && square < squaredLengths[next] - EPS)) {
                counts[next] = count
                squaredLengths[next] = square
                previous[next] = from
            }
        }
        if (previous.last() < 0) conflict("No supported, staggered splice layout can keep every member at or below 6000 mm.")
        val reversed = mutableListOf<Double>()
        var node = nodes.lastIndex
        while (node >= 0) {
            reversed += nodes[node]
            node = previous[node]
        }
        return reversed.reversed()
    }

    private data class BoardLayout(val count: Int, val gap: Double, val startWidth: Double)
    private fun boardLayout(finished: Double, width: Double): BoardLayout {
        if (abs(finished - width) <= EPS) return BoardLayout(1, 0.0, width)
        val full = mutableListOf<BoardLayout>()
        val maxCount = floor((finished + 6.0) / (width + 4.0)).toInt() + 2
        for (count in 2..maxCount) {
            val gap = (finished - count * width) / (count - 1)
            if (gap >= 4.0 - EPS && gap <= 6.0 + EPS) full += BoardLayout(count, gap.coerceIn(4.0, 6.0), width)
        }
        if (full.isNotEmpty()) return full.minWith(compareBy({ abs(it.gap - 5.0) }, { it.count }))
        if (finished in 60.0..width) return BoardLayout(1, 0.0, finished)
        val ripped = mutableListOf<BoardLayout>()
        for (count in 2..maxCount) {
            val lower = max(4.0, (finished - count * width) / (count - 1))
            val upper = min(6.0, (finished - 60.0 - (count - 1) * width) / (count - 1))
            if (lower <= upper + EPS) {
                val gap = 5.0.coerceIn(lower, max(lower, upper))
                val rip = finished - (count - 1) * (width + gap)
                if (rip >= 60.0 - EPS && rip <= width + EPS) ripped += BoardLayout(count, gap, rip.coerceIn(60.0, width))
            }
        }
        return ripped.minWithOrNull(compareBy({ abs(it.gap - 5.0) }, { -it.startWidth }, { it.count }))
            ?: conflict("Finished decking width ${format(finished)} mm cannot be covered with ${format(width)} mm boards, equal 4–6 mm gaps and a starting board at least 60 mm wide.")
    }

    private fun takeoff(input: DeckInput, g: DeckGeometry): List<MaterialLine> {
        val lines = mutableListOf<MaterialLine>()
        fun timber(category: MaterialCategory, type: String, spec: String, cuts: List<Double>) {
            if (cuts.isNotEmpty()) lines += MaterialLine(category, MaterialKey(type, spec, "lm"), cuts.sum() / 1000.0, cuts)
        }
        timber(MaterialCategory.PILES, "Timber", "125 × 125 mm timber piles · treatment unspecified", g.piles.map { it.lengthMm })
        timber(MaterialCategory.BEARERS, "Timber", materialSpecification(input.bearer), g.members.filter { it.kind == MemberKind.BEARER }.map { it.lengthMm })
        timber(MaterialCategory.JOISTS, "Timber", materialSpecification(input.joist), g.members.filter { it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY }.map { it.lengthMm })
        timber(MaterialCategory.NOGS, "Timber", materialSpecification(input.joist), g.members.filter { it.kind == MemberKind.NOG }.map { it.lengthMm })
        timber(MaterialCategory.DECKING, "Decking", "${input.decking.nominal} · finished ${format(input.actualDeckingWidthMm)} mm × ${format(input.decking.thicknessMm)} mm · ${input.deckingSpecies}", g.boards.map { it.lengthMm })
        lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Excavation", "400 × 400 × 600 mm footing holes", "m³"), g.excavationM3)
        lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Concrete", "Net fill after pile displacement", "m³"), g.concreteM3)
        val bags = BigDecimal.valueOf(g.concreteM3).divide(BigDecimal.valueOf(input.concreteYieldM3PerBag), 0, RoundingMode.CEILING).toDouble()
        lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Concrete bags", "20 kg · yield ${format(input.concreteYieldM3PerBag)} m³/bag", "bags"), bags)
        val bearerPileNails = 4 * g.piles.size
        val laminationNails = 4 * (ceilSafe(g.bearerRunMm / 600.0) + 1) * g.bearerPositionsMm.size
        val perpendicularJoists = g.members.filter { (it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY) && abs(u(g, it.start) - u(g, it.end)) < EPS }
        val joistNails = 2 * perpendicularJoists.sumOf { member ->
            g.bearerPositionsMm.count { it >= min(v(g, member.start), v(g, member.end)) - EPS && it <= max(v(g, member.start), v(g, member.end)) + EPS }
        }
        val nogNails = 4 * g.members.count { it.kind == MemberKind.NOG }
        lines += MaterialLine(MaterialCategory.FIXINGS, MaterialKey("Nails", "90 mm Paslode", "each"), (bearerPileNails + laminationNails + joistNails + nogNails).toDouble())
        // Count each continuous perpendicular joist once, even if that run has a supported splice.
        val joistRuns = perpendicularJoists.groupBy { it.runId }.values
        val screws = 2 * g.boards.sumOf { board ->
            val boardStart = v(g, board.origin)
            val boardEnd = boardStart + board.widthMm
            joistRuns.count { pieces ->
                val low = pieces.minOf { min(v(g, it.start), v(g, it.end)) }
                val high = pieces.maxOf { max(v(g, it.start), v(g, it.end)) }
                min(boardEnd, high) - max(boardStart, low) > EPS
            }
        }
        lines += MaterialLine(MaterialCategory.FIXINGS, MaterialKey("Decking screws", input.screwSpecification, "each"), screws.toDouble())
        return lines
    }

    private fun validateGeometry(input: DeckInput, g: DeckGeometry): List<String> = buildList {
        val t = input.joist.thicknessMm
        if (g.orientation == FramingOrientation.AUTOMATIC) add("Calculated orientation must be explicit.")
        if (g.members.any { !it.lengthMm.isFinite() || it.lengthMm <= EPS }) add("Every timber member must have a positive finite cut length.")
        if (g.members.any { abs(it.start.x - it.end.x) > EPS && abs(it.start.y - it.end.y) > EPS }) add("Framing members must follow the calculated orthogonal axes.")
        if (g.members.filter { it.kind != MemberKind.NOG }.any { it.lengthMm > MAX_MEMBER + EPS }) add("A bearer or joist cut exceeds 6000 mm.")
        if (g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a > MAX_BEARER_SPACING + EPS }) add("Bearer spacing exceeds 1800 mm.")
        if (g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a < max(PILE_SIZE, 2.0 * input.bearer.thicknessMm) - EPS }) add("Bearer pairs or their piles physically overlap.")
        if (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a > MAX_PILE_SPACING + EPS }) add("Pile spacing exceeds 1300 mm.")
        if (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a < PILE_SIZE - EPS }) add("Pile bodies physically overlap.")
        if (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a < 400.0 - EPS } || g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a < 400.0 - EPS })
            add("Footing holes overlap without a specified combined-footing detail.")
        if (g.pilePositionsMm.isEmpty() || abs(g.pilePositionsMm.first() - 200.0) > EPS || abs(g.bearerRunMm - g.pilePositionsMm.last() - 200.0) > EPS)
            add("Bearer end cantilevers must be 200 mm.")
        if (g.joistPositionsMm.zipWithNext().any { (a, z) -> z - a > input.maxJoistSpacingMm + EPS || z - a < t - EPS }) add("Joist spacing exceeds its maximum or members overlap.")
        if (g.bearerPositionsMm.isNotEmpty()) {
            val cantilevers = listOf(g.bearerPositionsMm.first(), g.joistRunMm - g.bearerPositionsMm.last())
            if (cantilevers.any { it < 90.0 - EPS || it > 300.0 + EPS || it - 2.0 * t < 90.0 - EPS || it - 2.0 * t > 300.0 + EPS })
                add("Outside and interior joist cantilevers must each be 90–300 mm.")
        } else add("At least one bearer line is required.")
        g.joins.forEach { join ->
            if (join.kind == MemberKind.BEARER) {
                val support = g.piles.any { abs(u(g, join.position) - u(g, it.position)) < EPS && abs(v(g, join.position) - v(g, it.position)) <= input.bearer.thicknessMm / 2.0 + EPS }
                if (!support) add("Bearer splice ${join.runId} is not supported by a pile.")
                if (g.joins.any { other -> other.kind == MemberKind.BEARER && other.runId == join.runId && other.layer != join.layer && abs(u(g, other.position) - u(g, join.position)) < EPS })
                    add("Double bearer splice positions must be staggered.")
            } else if (g.bearerPositionsMm.none { abs(v(g, join.position) - it) < EPS }) add("Joist splice ${join.runId} is not supported by a bearer.")
        }
        // Check physical cuts as well as the join records so a missing record cannot hide an unsupported splice.
        val timberRuns = g.members.filter { it.kind != MemberKind.NOG }.groupBy { Pair(it.runId, it.layer) }
        timberRuns.values.forEach { pieces ->
            val alongU = abs(v(g, pieces.first().start) - v(g, pieces.first().end)) < EPS
            val sorted = pieces.sortedBy { if (alongU) u(g, it.start) else v(g, it.start) }
            sorted.zipWithNext().forEach { (left, right) ->
                if (abs(left.end.x - right.start.x) > EPS || abs(left.end.y - right.start.y) > EPS)
                    add("Timber run ${left.runId} has a gap or overlapping cuts.")
                if (g.joins.none { it.runId == left.runId && it.layer == left.layer && abs(it.position.x - left.end.x) < EPS && abs(it.position.y - left.end.y) < EPS })
                    add("Timber run ${left.runId} has a splice without a matching join record.")
                if (left.kind == MemberKind.BEARER) {
                    if (g.pilePositionsMm.none { abs(it - u(g, left.end)) < EPS }) add("Bearer cut ${left.runId} has an unsupported splice.")
                } else if (alongU || g.bearerPositionsMm.none { abs(it - v(g, left.end)) < EPS })
                    add("Joist cut ${left.runId} has an unsupported splice.")
            }
        }
        if (g.piles.size != g.bearerPositionsMm.size * g.pilePositionsMm.size) add("Every calculated bearer/pile intersection must contain one pile.")
        if (g.piles.any { it.lengthMm < PILE_EMBEDMENT - EPS || !it.lengthMm.isFinite() }) add("Pile length cannot be less than its 500 mm embedment.")
        val expectedPileLength = input.heightMm - input.decking.thicknessMm - input.joist.depthMm - input.bearer.depthMm + PILE_EMBEDMENT
        if (g.piles.any { abs(it.lengthMm - expectedPileLength) > EPS }) add("Pile lengths do not reconcile to finished deck height.")
        val nogBays = g.members.filter { it.kind == MemberKind.NOG }.groupBy { u(g, it.start) }
        nogBays.values.forEach { nogs ->
            val restraints = (listOf(1.5 * t, g.joistRunMm - 1.5 * t) + nogs.map { v(g, it.start) }).sorted()
            if (restraints.zipWithNext().any { (a, z) -> z - a > MAX_BLOCKING_SPACING + EPS }) add("Blocking row spacing exceeds 1800 mm.")
            if (nogs.any { v(g, it.start) - t / 2.0 < 2.0 * t - EPS || v(g, it.start) + t / 2.0 > g.joistRunMm - 2.0 * t + EPS }) add("Blocking overlaps the doubled end boundaries.")
        }
        if (g.boards.isEmpty()) add("A valid decking board layout is required.") else {
            if (abs(u(g, g.boards.first().origin) + input.overhangMm) > EPS || abs(v(g, g.boards.first().origin) + input.overhangMm) > EPS)
                add("Decking must begin at the selected overhang outside the framing.")
            if (g.boards.any { abs(it.lengthMm - g.bearerRunMm - 2.0 * input.overhangMm) > EPS || abs(u(g, it.origin) + input.overhangMm) > EPS || it.runsAlongX != (g.orientation == FramingOrientation.WIDTHWAYS) })
                add("Decking runs must match the finished dimensions and framing orientation.")
            if (g.boards.any { min(v(g, it.origin) + it.widthMm, g.joistRunMm) - max(v(g, it.origin), 0.0) <= EPS })
                add("Decking overhang leaves a board with no contact with the framing. Reduce the overhang or change board width/layout.")
            if (g.boards.drop(1).any { abs(it.widthMm - input.actualDeckingWidthMm) > EPS }) add("Only the starting board may be ripped.")
            if (g.boards.first().widthMm < 60.0 - EPS || g.boards.first().widthMm > input.actualDeckingWidthMm + EPS) add("Starting board width must be 60 mm to the full board width.")
            if (g.boards.size > 1 && (g.deckingGapMm < 4.0 - EPS || g.deckingGapMm > 6.0 + EPS)) add("Decking gaps must be 4–6 mm.")
            val covered = g.boards.sumOf { it.widthMm } + (g.boards.size - 1) * g.deckingGapMm
            if (abs(covered - g.joistRunMm - 2.0 * input.overhangMm) > EPS) add("Decking does not cover the finished dimensions exactly.")
            if (g.boards.zipWithNext().any { (a, z) -> abs(v(g, z.origin) - v(g, a.origin) - a.widthMm - g.deckingGapMm) > EPS }) add("Decking gaps are not equal.")
        }
        if (abs(g.excavationM3 - g.piles.size * 0.096) > EPS || abs(g.concreteM3 - g.piles.size * (0.096 - 0.0078125)) > EPS)
            add("Footing volumes must subtract only the embedded pile displacement.")
    }.distinct()

    private fun u(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.x else p.y
    private fun v(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.y else p.x
    private fun evenly(start: Double, end: Double, intervals: Int) = (0..intervals).map { start + (end - start) * it / intervals }
    private fun ceilSafe(value: Double) = ceil(value - 1e-12).toInt().coerceAtLeast(1)
    private fun materialSpecification(profile: TimberProfile): String {
        val actual = "${format(profile.depthMm)} × ${format(profile.thicknessMm)} mm"
        return if (profile.name == actual) profile.label else "${profile.name} · actual $actual · ${profile.species}"
    }
    private fun format(value: Double): String = if (value == floor(value)) value.toLong().toString() else java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
