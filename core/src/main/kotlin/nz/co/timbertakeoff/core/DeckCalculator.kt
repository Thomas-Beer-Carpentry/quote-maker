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
            buildList {
                addAll(listOf(
                "Preliminary estimating / set-out information. Structural design and compliance have not been verified.",
                "Exact quantities; no waste allowance or stock-length optimisation.",
                "Cantilevers and pile supports are measured to bearer-pair centrelines. Interior joists fit between doubled end boundaries.",
                "Bearer lamination nails include stations at both ends, spaced at no more than 600 mm. Joist ends at bearer-supported splices each receive two nails."
                ))
                if (endBoundarySplices(selected.geometry).isNotEmpty())
                    add("Doubled end-boundary joins use the supplied exception: butt joins fall at perpendicular joist centrelines and are staggered between the two members, without extra bearing supports. Splice fixing specifications remain to be confirmed.")
                if (input.pictureFrame) {
                    add("Picture-frame boards are full-width with closed 45° mitres; equal gaps separate the frame and infill. Any rip is the first infill board.")
                    add("Continuous picture-frame interface supports use the joist profile, with supported splices. Two decking screws per perimeter station, evenly spaced at no more than the selected joist spacing between doubled corner boundaries.")
                    add("Picture-frame support cuts receive four end nails per piece plus two nails at each bearer intersection; fixing quantities are provisional estimating assumptions.")
                    selected.geometry.members.filter { it.kind == MemberKind.PICTURE_FRAME_PACKER }.groupBy { it.thicknessMm }.forEach { (width, packers) ->
                        add("${packers.size} picture-frame blocking packers: rip ${input.joist.name} source timber to ${format(width)} mm wide, then cut ${format(input.joist.thicknessMm)} mm long. Source takeoff includes one exact cut per packer; ripping/stock optimisation and waste are excluded. One fixing set per packer, specification and fasteners per set to be confirmed.")
                    }
                } else add("Both physical members of each doubled side boundary receive decking fixings. Parallel end boundaries have no discrete decking crossing.")
                if (input.pileConnection == PileConnection.EXISTING_CONCRETE_BRACKETS)
                    add("Bracket-mounted post lengths are ground-to-bearer underside with no embedment. One bracket per post; bracket and anchor specifications remain to be confirmed. Existing concrete suitability is not verified.")
            }))
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
        if (result.input.pileConnection == PileConnection.EXISTING_CONCRETE_BRACKETS) {
            if (result.materials.any { it.category == MaterialCategory.CONCRETE }) errors += "Bracket-mounted posts must not include excavation, concrete or concrete bags."
            val brackets = result.materials.filter { it.key.type == "Post brackets" }
            if (brackets.size != 1 || abs(brackets.sumOf { it.quantity } - result.geometry.piles.size) > EPS)
                errors += "Bracket-mounted posts need exactly one bracket per post."
        }
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
        if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS && (!input.concreteYieldM3PerBag.isFinite() || input.concreteYieldM3PerBag !in 0.000001..1.0))
            add("Concrete yield per 20 kg bag must be between 0.000001 and 1 cubic metre.")
    }

    private fun geometry(input: DeckInput, orientation: FramingOrientation): DeckGeometry {
        val b = if (orientation == FramingOrientation.LENGTHWAYS) input.lengthMm else input.widthMm
        val j = if (orientation == FramingOrientation.LENGTHWAYS) input.widthMm else input.lengthMm
        val t = input.joist.thicknessMm
        fun point(u: Double, v: Double) = if (orientation == FramingOrientation.WIDTHWAYS) Point(u, v) else Point(v, u)
        if (b < 2.0 * PILE_END - EPS) conflict("Bearer run must be at least 400 mm to accommodate the two 200 mm end cantilevers.")
        if (b <= 4.0 * t || j <= 4.0 * t) conflict("Deck dimensions must exceed the space occupied by the doubled boundaries (${format(4.0 * t)} mm).")
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
        if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS &&
            (bearerPositions.zipWithNext().any { (left, right) -> right - left < 400.0 - EPS } ||
            pilePositions.zipWithNext().any { (left, right) -> right - left < 400.0 - EPS }))
            conflict("The 400 × 400 mm footing holes overlap. A combined-footing excavation/concrete detail has not been specified; increase dimensions or change orientation.")
        val pileAboveGround = input.heightMm - input.decking.thicknessMm - input.joist.depthMm - input.bearer.depthMm
        if (pileAboveGround < -EPS) conflict("Finished deck height must be at least the decking, joist and bearer depth combined (${format(input.decking.thicknessMm + input.joist.depthMm + input.bearer.depthMm)} mm).")
        if (input.pileConnection == PileConnection.EXISTING_CONCRETE_BRACKETS && pileAboveGround <= EPS)
            conflict("Bracket-mounted posts need a positive ground-to-bearer underside length; increase the finished deck height above the framing stack.")

        val sideCentres = listOf(t / 2.0, 1.5 * t, b - 1.5 * t, b - t / 2.0)
        val innerSpan = b - 3.0 * t
        val joistIntervals = ceilSafe(innerSpan / input.maxJoistSpacingMm)
        val frameInset = input.actualDeckingWidthMm - input.overhangMm
        val frameLayout = if (input.pictureFrame) {
            if (frameInset <= EPS) conflict("Picture-frame inner edges must lie inside the framing; reduce the overhang below the full board width.")
            pictureFrameBoardLayout(j - 2.0 * frameInset, input.actualDeckingWidthMm)
        } else null
        val frameSupportPositions = if (input.pictureFrame) listOf(frameInset, b - frameInset) else emptyList()
        if (input.pictureFrame && b - 2.0 * frameInset - 2.0 * frameLayout!!.gap <= EPS)
            conflict("Full-width picture-frame boards and their gaps leave no positive infill run length.")
        // Reuse a doubled boundary only when it supports both sides of the frame/infill interface.
        val additionalSupports = frameSupportPositions.filterIndexed { index, cross ->
            val distance = if (index == 0) cross else b - cross
            if (distance + frameLayout!!.gap < 2.0 * t - EPS) false
            else {
                if (distance - t / 2.0 < 2.0 * t - EPS)
                    conflict("Picture-frame support at ${format(distance)} mm partially overlaps the doubled side boundary without supporting the full frame/infill interface; change board width, overhang or joist thickness.")
                true
            }
        }
        val internalJoists = if (!input.pictureFrame) (1 until joistIntervals).map { 1.5 * t + innerSpan * it / joistIntervals }
            else (listOf(1.5 * t, b - 1.5 * t) + additionalSupports).sorted().zipWithNext().flatMap { (a, z) ->
                val intervals = ceilSafe((z - a) / input.maxJoistSpacingMm)
                (1 until intervals).map { a + (z - a) * it / intervals }
            }
        val joistPositions = (sideCentres + internalJoists + additionalSupports).sorted()
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
            val embedment = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) PILE_EMBEDMENT else 0.0
            pilePositions.forEachIndexed { index, u -> piles += Pile("P${line + 1}.${index + 1}", point(u, v), max(0.0, pileAboveGround) + embedment) }
        }
        sideCentres.forEachIndexed { index, u ->
            addRun(MemberKind.BOUNDARY, "BJ${index + 1}", index % 2, 0.0, j, u, false, input.joist, bearerPositions)
        }
        internalJoists.forEachIndexed { index, u ->
            addRun(MemberKind.JOIST, "J${index + 1}", 0, 2.0 * t, j - 2.0 * t, u, false, input.joist, bearerPositions)
        }
        additionalSupports.forEachIndexed { index, u ->
            addRun(MemberKind.PICTURE_FRAME_SUPPORT, "PF${index + 1}", 0, 2.0 * t, j - 2.0 * t, u, false, input.joist, bearerPositions)
        }
        // User-supplied end-boundary exception: joins at actual perpendicular joists,
        // with the second member's joins excluded from those used by the first.
        listOf(listOf(t / 2.0, 1.5 * t), listOf(j - 1.5 * t, j - t / 2.0)).forEachIndexed { pair, centres ->
            val firstJoins = addRun(MemberKind.BOUNDARY, "BE${pair * 2 + 1}", 0,
                2.0 * t, b - 2.0 * t, centres[0], true, input.joist, internalJoists)
            addRun(MemberKind.BOUNDARY, "BE${pair * 2 + 2}", 1,
                2.0 * t, b - 2.0 * t, centres[1], true, input.joist, internalJoists, firstJoins)
        }
        // End boundaries count as restraint rows. Staggered nogs remain within the clear interior.
        val restraintSpan = j - 3.0 * t
        val blockingIntervals = max(2, ceilSafe(restraintSpan / (MAX_BLOCKING_SPACING - t / 2.0)))
        val blockingRows = (1 until blockingIntervals).map { 1.5 * t + restraintSpan * it / blockingIntervals }
        joistPositions.zipWithNext().forEachIndexed { bay, (left, right) ->
            val cut = right - left - t
            if (cut > EPS) blockingRows.forEachIndexed { row, baseV ->
                val v = baseV + if (bay % 2 == 0) -t / 2.0 else t / 2.0
                val packer = input.pictureFrame && cut < t - EPS &&
                    additionalSupports.any { abs(it - left) < EPS || abs(it - right) < EPS }
                if (packer) {
                    val run = "PK${row + 1}.${bay + 1}"
                    val profile = TimberProfile("Ripped packer · ${format(input.joist.depthMm)} × ${format(cut)} mm", input.joist.depthMm, cut, input.joist.species)
                    members += TimberMember(run, MemberKind.PICTURE_FRAME_PACKER, point((left + right) / 2.0, v - t / 2.0),
                        point((left + right) / 2.0, v + t / 2.0), cut, profile, run)
                } else members += TimberMember("N${row + 1}.${bay + 1}", MemberKind.NOG, point(left + t / 2.0, v),
                        point(right - t / 2.0, v), t, input.joist, "N${row + 1}.${bay + 1}")
            }
        }
        val boardLayout = frameLayout ?: boardLayout(j + 2.0 * input.overhangMm, input.actualDeckingWidthMm)
        val boards = mutableListOf<DeckBoard>()
        if (input.pictureFrame) {
            val o = input.overhangMm
            val d = frameInset
            val width = input.actualDeckingWidthMm
            fun frame(originU: Double, originV: Double, length: Double, alongU: Boolean, outline: List<Point>) {
                boards += DeckBoard(boards.size, point(originU, originV), width, length,
                    if (alongU) orientation == FramingOrientation.WIDTHWAYS else orientation == FramingOrientation.LENGTHWAYS,
                    DeckBoardRole.PICTURE_FRAME, outline)
            }
            frame(-o, -o, b + 2.0 * o, true, listOf(point(-o, -o), point(b + o, -o), point(b - d, d), point(d, d)))
            frame(-o, j - d, b + 2.0 * o, true, listOf(point(-o, j + o), point(d, j - d), point(b - d, j - d), point(b + o, j + o)))
            frame(-o, -o, j + 2.0 * o, false, listOf(point(-o, -o), point(d, d), point(d, j - d), point(-o, j + o)))
            frame(b - d, -o, j + 2.0 * o, false, listOf(point(b + o, -o), point(b + o, j + o), point(b - d, j - d), point(b - d, d)))
        }
        var v = if (input.pictureFrame) frameInset + boardLayout.gap else -input.overhangMm
        repeat(boardLayout.count) { index ->
            val width = if (index == 0) boardLayout.startWidth else input.actualDeckingWidthMm
            val start = if (input.pictureFrame) frameInset + boardLayout.gap else -input.overhangMm
            val length = if (input.pictureFrame) b - 2.0 * frameInset - 2.0 * boardLayout.gap else b + 2.0 * input.overhangMm
            boards += DeckBoard(boards.size, point(start, v), width, length,
                orientation == FramingOrientation.WIDTHWAYS)
            v += width + boardLayout.gap
        }
        // Exact decimal volumes avoid a phantom extra bag at an exact whole-bag boundary.
        val excavation = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) BigDecimal("0.096").multiply(BigDecimal.valueOf(piles.size.toLong())).toDouble() else 0.0
        val concrete = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) BigDecimal("0.0881875").multiply(BigDecimal.valueOf(piles.size.toLong())).toDouble() else 0.0
        return DeckGeometry(orientation, b, j, bearerPositions, pilePositions, joistPositions, blockingRows,
            members, piles, joins, boards, actualJoistSpacing, actualBearerSpacing, cantilever,
            boardLayout.gap, boardLayout.startWidth, max(0.0, pileAboveGround), excavation, concrete, frameSupportPositions)
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
        if (previous.last() < 0) conflict("No permitted splice layout can keep every member at or below 6000 mm while respecting join locations and stagger requirements.")
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

    /** Infill has one equal gap on each frame edge, so n boards require n + 1 gaps. */
    private fun pictureFrameBoardLayout(clear: Double, width: Double): BoardLayout {
        if (clear < 68.0 - EPS) conflict("Full-width picture-frame boards leave too little space for a 60 mm infill board and two 4–6 mm gaps.")
        val maxCount = floor(clear / (width + 4.0)).toInt() + 2
        val full = (1..maxCount).mapNotNull { count ->
            val gap = (clear - count * width) / (count + 1)
            if (gap in (4.0 - EPS)..(6.0 + EPS)) BoardLayout(count, gap.coerceIn(4.0, 6.0), width) else null
        }
        if (full.isNotEmpty()) return full.minWith(compareBy({ abs(it.gap - 5.0) }, { it.count }))
        val ripped = (1..maxCount).mapNotNull { count ->
            val lower = max(4.0, (clear - count * width) / (count + 1))
            val upper = min(6.0, (clear - 60.0 - (count - 1) * width) / (count + 1))
            if (lower > upper + EPS) null else {
                val gap = 5.0.coerceIn(lower, max(lower, upper))
                val rip = clear - (count - 1) * width - (count + 1) * gap
                if (rip in (60.0 - EPS)..(width + EPS)) BoardLayout(count, gap, rip.coerceIn(60.0, width)) else null
            }
        }
        return ripped.minWithOrNull(compareBy({ abs(it.gap - 5.0) }, { -it.startWidth }, { it.count }))
            ?: conflict("Picture-frame infill width ${format(clear)} mm cannot be covered with ${format(width)} mm boards, equal 4–6 mm gaps including both frame edges, and a first infill board at least 60 mm wide.")
    }

    private fun takeoff(input: DeckInput, g: DeckGeometry): List<MaterialLine> {
        val lines = mutableListOf<MaterialLine>()
        fun timber(category: MaterialCategory, type: String, spec: String, cuts: List<Double>, notes: List<String> = emptyList()) {
            if (cuts.isNotEmpty()) lines += MaterialLine(category, MaterialKey(type, spec, "lm"), cuts.sum() / 1000.0, cuts, notes)
        }
        timber(MaterialCategory.PILES, "Timber", "125 × 125 mm timber piles · treatment unspecified", g.piles.map { it.lengthMm })
        timber(MaterialCategory.BEARERS, "Timber", materialSpecification(input.bearer), g.members.filter { it.kind == MemberKind.BEARER }.map { it.lengthMm })
        val boundarySplices = endBoundarySplices(g)
        val boundaryNotes = if (boundarySplices.isEmpty()) emptyList() else listOf(
            "Doubled end boundaries: ${boundarySplices.size} staggered butt joins at perpendicular joists; no additional bearing supports. Splice fixings to be confirmed.")
        timber(MaterialCategory.JOISTS, "Timber", materialSpecification(input.joist), g.members.filter { it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY }.map { it.lengthMm }, boundaryNotes)
        val nogs = g.members.filter { it.kind == MemberKind.NOG }
        val frameSupports = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }
        val packers = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_PACKER }
        val blockingNotes = if (input.pictureFrame) buildList {
            if (nogs.isNotEmpty()) add("Ordinary nogs: ${cutSchedule(nogs.map { it.lengthMm })}.")
            if (frameSupports.isNotEmpty()) add("Continuous picture-frame supports: ${cutSchedule(frameSupports.map { it.lengthMm })}; ${input.joist.name}.")
            packers.groupBy { it.thicknessMm }.forEach { (rip, members) ->
                add("Ripped packers: ${members.size} × ${format(input.joist.thicknessMm)} mm source cuts from ${input.joist.name}; finished ${format(input.joist.depthMm)} × ${format(rip)} mm. Fixings to be confirmed.")
            }
        } else emptyList()
        timber(MaterialCategory.NOGS, "Timber", materialSpecification(input.joist), (nogs + frameSupports + packers).map { it.lengthMm }, blockingNotes)
        val deckingNotes = if (input.pictureFrame) listOf(
            "Picture frame: ${cutSchedule(g.boards.filter { it.role == DeckBoardRole.PICTURE_FRAME }.map { it.lengthMm })} long-point cuts; four full-width ${format(input.actualDeckingWidthMm)} mm boards with 45° mitres.",
            "Infill: ${cutSchedule(g.boards.filter { it.role == DeckBoardRole.INFILL }.map { it.lengthMm })}; first board ${format(g.startingBoardWidthMm)} mm wide; equal ${format(g.deckingGapMm)} mm gaps including frame edges."
        ) else emptyList()
        timber(MaterialCategory.DECKING, "Decking", "${input.decking.nominal} · finished ${format(input.actualDeckingWidthMm)} mm × ${format(input.decking.thicknessMm)} mm · ${input.deckingSpecies}", g.boards.map { it.lengthMm }, deckingNotes)
        if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) {
            lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Excavation", "400 × 400 × 600 mm footing holes", "m³"), g.excavationM3)
            lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Concrete", "Net fill after pile displacement", "m³"), g.concreteM3)
            val bags = BigDecimal.valueOf(g.concreteM3).divide(BigDecimal.valueOf(input.concreteYieldM3PerBag), 0, RoundingMode.CEILING).toDouble()
            lines += MaterialLine(MaterialCategory.CONCRETE, MaterialKey("Concrete bags", "20 kg · yield ${format(input.concreteYieldM3PerBag)} m³/bag", "bags"), bags)
        } else lines += MaterialLine(MaterialCategory.FIXINGS,
            MaterialKey("Post brackets", "125 × 125 mm posts · bracket and anchors specification to be confirmed", "each"), g.piles.size.toDouble())
        val bearerPileNails = 4 * g.piles.size
        val laminationNails = 4 * (ceilSafe(g.bearerRunMm / 600.0) + 1) * g.bearerPositionsMm.size
        val perpendicularJoists = g.members.filter { (it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY || it.kind == MemberKind.PICTURE_FRAME_SUPPORT) && abs(u(g, it.start) - u(g, it.end)) < EPS }
        val joistNails = 2 * perpendicularJoists.sumOf { member ->
            g.bearerPositionsMm.count { it >= min(v(g, member.start), v(g, member.end)) - EPS && it <= max(v(g, member.start), v(g, member.end)) + EPS }
        }
        val nogNails = 4 * g.members.count { it.kind == MemberKind.NOG || it.kind == MemberKind.PICTURE_FRAME_SUPPORT }
        lines += MaterialLine(MaterialCategory.FIXINGS, MaterialKey("Nails", "90 mm Paslode", "each"), (bearerPileNails + laminationNails + joistNails + nogNails).toDouble())
        val packerCount = g.members.count { it.kind == MemberKind.PICTURE_FRAME_PACKER }
        if (packerCount > 0) lines += MaterialLine(MaterialCategory.FIXINGS,
            MaterialKey("Packer fixing sets", "Specification and fasteners per set to be confirmed", "each"), packerCount.toDouble())
        if (boundarySplices.isNotEmpty()) lines += MaterialLine(MaterialCategory.FIXINGS,
            MaterialKey("Boundary splice fixing sets", "One per end-boundary butt join · specification and fasteners per set to be confirmed", "each"), boundarySplices.size.toDouble())
        // Count each continuous perpendicular joist once, even if that run has a supported splice.
        val joistRuns = perpendicularJoists.groupBy { it.runId }.values
        val infillScrews = 2 * g.boards.filter { it.role == DeckBoardRole.INFILL }.sumOf { board ->
            val boardStart = v(g, board.origin)
            val boardEnd = boardStart + board.widthMm
            joistRuns.count { pieces ->
                val low = pieces.minOf { min(v(g, it.start), v(g, it.end)) }
                val high = pieces.maxOf { max(v(g, it.start), v(g, it.end)) }
                val cross = u(g, pieces.first().start)
                val footprintOverlap = !input.pictureFrame || min(u(g, board.origin) + board.lengthMm, cross + input.joist.thicknessMm / 2.0) -
                    max(u(g, board.origin), cross - input.joist.thicknessMm / 2.0) > EPS
                footprintOverlap && min(boardEnd, high) - max(boardStart, low) > EPS
            }
        }
        val frameScrews = if (input.pictureFrame) {
            // Ends lie on continuous supported timber, inside the doubled corner boundaries.
            4 * (ceilSafe((g.bearerRunMm - 4.0 * input.joist.thicknessMm) / input.maxJoistSpacingMm) + 1) +
                4 * (ceilSafe((g.joistRunMm - 4.0 * input.joist.thicknessMm) / input.maxJoistSpacingMm) + 1)
        } else 0
        val screws = infillScrews + frameScrews
        lines += MaterialLine(MaterialCategory.FIXINGS, MaterialKey("Decking screws", input.screwSpecification, "each"), screws.toDouble())
        return lines
    }

    private fun validateGeometry(input: DeckInput, g: DeckGeometry): List<String> = buildList {
        val t = input.joist.thicknessMm
        if (g.orientation == FramingOrientation.AUTOMATIC) add("Calculated orientation must be explicit.")
        if (g.members.any { !it.lengthMm.isFinite() || it.lengthMm <= EPS }) add("Every timber member must have a positive finite cut length.")
        if (g.members.any { abs(it.start.x - it.end.x) > EPS && abs(it.start.y - it.end.y) > EPS }) add("Framing members must follow the calculated orthogonal axes.")
        if (g.members.filter { it.kind != MemberKind.NOG && it.kind != MemberKind.PICTURE_FRAME_PACKER }.any { it.lengthMm > MAX_MEMBER + EPS }) add("A bearer or joist cut exceeds 6000 mm.")
        if (g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a > MAX_BEARER_SPACING + EPS }) add("Bearer spacing exceeds 1800 mm.")
        if (g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a < max(PILE_SIZE, 2.0 * input.bearer.thicknessMm) - EPS }) add("Bearer pairs or their piles physically overlap.")
        if (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a > MAX_PILE_SPACING + EPS }) add("Pile spacing exceeds 1300 mm.")
        if (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a < PILE_SIZE - EPS }) add("Pile bodies physically overlap.")
        if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS &&
            (g.pilePositionsMm.zipWithNext().any { (a, z) -> z - a < 400.0 - EPS } || g.bearerPositionsMm.zipWithNext().any { (a, z) -> z - a < 400.0 - EPS }))
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
            val pieces = g.members.filter { it.kind == join.kind && it.runId == join.runId && it.layer == join.layer }
            if (pieces.none { samePoint(it.end, join.position) } || pieces.none { samePoint(it.start, join.position) })
                add("Splice ${join.runId} does not match two meeting physical cuts.")
            if (join.kind == MemberKind.NOG || join.kind == MemberKind.PICTURE_FRAME_PACKER) add("Blocking and ripped packers cannot contain framing splice records.")
            if (join.kind == MemberKind.BEARER) {
                val support = g.piles.any { abs(u(g, join.position) - u(g, it.position)) < EPS && abs(v(g, join.position) - v(g, it.position)) <= input.bearer.thicknessMm / 2.0 + EPS }
                if (!support) add("Bearer splice ${join.runId} is not supported by a pile.")
                if (g.joins.any { other -> other.kind == MemberKind.BEARER && other.runId == join.runId && other.layer != join.layer && abs(u(g, other.position) - u(g, join.position)) < EPS })
                    add("Double bearer splice positions must be staggered.")
            } else if (pieces.any { isEndBoundaryMember(g, it) }) {
                val member = pieces.first { isEndBoundaryMember(g, it) }
                if (abs(v(g, member.start) - v(g, join.position)) > EPS || !hasPerpendicularJoist(input, g, join.position))
                    add("End-boundary splice ${join.runId} must fall at a perpendicular joist intersection.")
            } else if (g.bearerPositionsMm.none { abs(v(g, join.position) - it) < EPS }) add("Joist splice ${join.runId} is not supported by a bearer.")
        }
        // Check physical cuts as well as the join records so a missing record cannot hide an unsupported splice.
        val timberRuns = g.members.filter { it.kind != MemberKind.NOG && it.kind != MemberKind.PICTURE_FRAME_PACKER }.groupBy { Pair(it.runId, it.layer) }
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
                } else if (isEndBoundaryMember(g, left)) {
                    if (!hasPerpendicularJoist(input, g, left.end))
                        add("End-boundary cut ${left.runId} must splice at a perpendicular joist intersection.")
                } else if (alongU || g.bearerPositionsMm.none { abs(it - v(g, left.end)) < EPS })
                    add("Joist cut ${left.runId} has an unsupported splice.")
            }
        }
        addAll(endBoundaryErrors(input, g))
        if (g.piles.size != g.bearerPositionsMm.size * g.pilePositionsMm.size) add("Every calculated bearer/pile intersection must contain one pile.")
        val embedment = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) PILE_EMBEDMENT else 0.0
        if (g.piles.any { it.lengthMm <= EPS || it.lengthMm < embedment - EPS || !it.lengthMm.isFinite() })
            add("Post lengths must be positive and include the selected connection's embedment.")
        val expectedPileLength = input.heightMm - input.decking.thicknessMm - input.joist.depthMm - input.bearer.depthMm + embedment
        if (g.piles.any { abs(it.lengthMm - expectedPileLength) > EPS }) add("Pile lengths do not reconcile to finished deck height.")
        val nogBays = g.members.filter { it.kind == MemberKind.NOG || it.kind == MemberKind.PICTURE_FRAME_PACKER }.groupBy {
            if (it.kind == MemberKind.PICTURE_FRAME_PACKER) u(g, it.start) - it.thicknessMm / 2.0 else u(g, it.start)
        }
        fun rowCentre(member: TimberMember) = if (member.kind == MemberKind.PICTURE_FRAME_PACKER) (v(g, member.start) + v(g, member.end)) / 2.0 else v(g, member.start)
        nogBays.values.forEach { nogs ->
            val restraints = (listOf(1.5 * t, g.joistRunMm - 1.5 * t) + nogs.map { rowCentre(it) }).sorted()
            if (restraints.zipWithNext().any { (a, z) -> z - a > MAX_BLOCKING_SPACING + EPS }) add("Blocking row spacing exceeds 1800 mm.")
            if (nogs.any { rowCentre(it) - t / 2.0 < 2.0 * t - EPS || rowCentre(it) + t / 2.0 > g.joistRunMm - 2.0 * t + EPS }) add("Blocking overlaps the doubled end boundaries.")
        }
        if (input.pictureFrame) addAll(pictureFrameErrors(input, g))
        else if (g.boards.isEmpty()) add("A valid decking board layout is required.") else {
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
            if (g.boards.any { it.role != DeckBoardRole.INFILL || it.outline.isNotEmpty() } || g.pictureFrameSupportPositionsMm.isNotEmpty() || g.members.any { it.kind == MemberKind.PICTURE_FRAME_SUPPORT || it.kind == MemberKind.PICTURE_FRAME_PACKER })
                add("Unframed decking cannot contain picture-frame boards or supports.")
        }
        val expectedExcavation = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) g.piles.size * 0.096 else 0.0
        val expectedConcrete = if (input.pileConnection == PileConnection.CONCRETE_FOOTINGS) g.piles.size * (0.096 - 0.0078125) else 0.0
        if (abs(g.excavationM3 - expectedExcavation) > EPS || abs(g.concreteM3 - expectedConcrete) > EPS)
            add("Footing volumes must subtract only the embedded pile displacement.")
    }.distinct()

    private fun isEndBoundaryMember(g: DeckGeometry, member: TimberMember) =
        member.kind == MemberKind.BOUNDARY && abs(v(g, member.start) - v(g, member.end)) < EPS

    private fun endBoundarySplices(g: DeckGeometry) = g.joins.filter { join ->
        join.kind == MemberKind.BOUNDARY && g.members.any {
            it.runId == join.runId && it.layer == join.layer && isEndBoundaryMember(g, it)
        }
    }

    /** The transverse joist meets the inner face of the doubled boundary, not its outer layer. */
    private fun hasPerpendicularJoist(input: DeckInput, g: DeckGeometry, splice: Point): Boolean {
        val face = if (v(g, splice) < g.joistRunMm / 2.0) 2.0 * input.joist.thicknessMm
            else g.joistRunMm - 2.0 * input.joist.thicknessMm
        return g.members.any { member ->
            member.kind == MemberKind.JOIST && member.profile == input.joist &&
                abs(u(g, member.start) - u(g, member.end)) < EPS &&
                abs(u(g, member.start) - u(g, splice)) < EPS &&
                (abs(v(g, member.start) - face) < EPS || abs(v(g, member.end) - face) < EPS)
        }
    }

    /** Reconstruct actual paired rails so relabelled cuts or omitted join records cannot hide a bad splice. */
    private fun endBoundaryErrors(input: DeckInput, g: DeckGeometry): List<String> = buildList {
        val t = input.joist.thicknessMm
        val centres = listOf(t / 2.0, 1.5 * t, g.joistRunMm - 1.5 * t, g.joistRunMm - t / 2.0)
        val rails = g.members.filter { isEndBoundaryMember(g, it) }
        if (rails.any { member -> centres.none { abs(v(g, member.start) - it) < EPS } })
            add("End-boundary members must occupy the four doubled-boundary centrelines.")
        val physicalSplices = centres.mapIndexed { index, cross ->
            val pieces = rails.filter { abs(v(g, it.start) - cross) < EPS }.sortedBy { u(g, it.start) }
            if (pieces.isEmpty()) add("Each doubled end boundary requires both complete timber members.")
            else {
                if (abs(u(g, pieces.first().start) - 2.0 * t) > EPS ||
                    abs(u(g, pieces.last().end) - (g.bearerRunMm - 2.0 * t)) > EPS)
                    add("End-boundary cuts must cover the full span between the doubled side boundaries.")
                if (pieces.any { it.profile != input.joist || abs(it.thicknessMm - t) > EPS ||
                    it.layer != index % 2 || u(g, it.end) <= u(g, it.start) + EPS })
                    add("End-boundary cuts must retain the selected joist profile and their doubled-member layer.")
                if (pieces.zipWithNext().any { (a, z) -> !samePoint(a.end, z.start) })
                    add("End-boundary timber has a gap or overlapping cuts.")
            }
            pieces.dropLast(1).map { piece ->
                if (!hasPerpendicularJoist(input, g, piece.end))
                    add("Every physical end-boundary splice must fall at a perpendicular joist intersection.")
                u(g, piece.end)
            }
        }
        physicalSplices.chunked(2).forEach { pair ->
            if (pair[0].any { first -> pair[1].any { second -> abs(first - second) < EPS } })
                add("Double end-boundary splice positions must be staggered between the two members.")
        }
    }.distinct()

    private fun pictureFrameErrors(input: DeckInput, g: DeckGeometry): List<String> = buildList {
        val b = g.bearerRunMm
        val j = g.joistRunMm
        val o = input.overhangMm
        val w = input.actualDeckingWidthMm
        val d = w - o
        val gap = g.deckingGapMm
        val t = input.joist.thicknessMm
        val frame = g.boards.filter { it.role == DeckBoardRole.PICTURE_FRAME }
        val infill = g.boards.filter { it.role == DeckBoardRole.INFILL }
        if (g.boards.any { !it.widthMm.isFinite() || !it.lengthMm.isFinite() || it.widthMm <= EPS || it.lengthMm <= EPS })
            add("Every decking board needs positive finite dimensions.")
        if (frame.size != 4 || frame.any { abs(it.widthMm - w) > EPS })
            add("Picture frames require four full-width perimeter boards.")
        val expectedOutlines = listOf(
            listOf(g.point(-o, -o), g.point(b + o, -o), g.point(b - d, d), g.point(d, d)),
            listOf(g.point(-o, j + o), g.point(d, j - d), g.point(b - d, j - d), g.point(b + o, j + o)),
            listOf(g.point(-o, -o), g.point(d, d), g.point(d, j - d), g.point(-o, j + o)),
            listOf(g.point(b + o, -o), g.point(b + o, j + o), g.point(b - d, j - d), g.point(b - d, d))
        )
        val expectedOrigins = listOf(g.point(-o, -o), g.point(-o, j - d), g.point(-o, -o), g.point(b - d, -o))
        frame.forEachIndexed { index, board ->
            if (index < 4) {
                val expectedLength = if (index < 2) b + 2.0 * o else j + 2.0 * o
                val expectedDirection = if (index < 2) g.orientation == FramingOrientation.WIDTHWAYS else g.orientation == FramingOrientation.LENGTHWAYS
                if (abs(board.lengthMm - expectedLength) > EPS || board.runsAlongX != expectedDirection || !samePoint(board.origin, expectedOrigins[index]) ||
                    board.outline.size != 4 || board.outline.zip(expectedOutlines[index]).any { (a, z) -> !samePoint(a, z) })
                    add("Picture-frame boards must have closed 45° mitres and match the finished perimeter exactly.")
            }
        }
        if (infill.isEmpty()) add("Picture frames need a valid infill board layout.") else {
            if (gap < 4.0 - EPS || gap > 6.0 + EPS) add("Picture-frame and infill gaps must be equal and between 4–6 mm.")
            if (abs(infill.first().widthMm - g.startingBoardWidthMm) > EPS || infill.first().widthMm < 60.0 - EPS || infill.first().widthMm > w + EPS)
                add("The first infill board must match the calculated rip and be 60 mm to full width.")
            if (infill.drop(1).any { abs(it.widthMm - w) > EPS }) add("Only the first infill board after the picture frame may be ripped.")
            if (abs(v(g, infill.first().origin) - d - gap) > EPS || abs(v(g, infill.last().origin) + infill.last().widthMm + gap - (j - d)) > EPS)
                add("Equal gaps must separate the first and last infill boards from the picture frame.")
            if (infill.any { abs(u(g, it.origin) - d - gap) > EPS || abs(it.lengthMm - (b - 2.0 * d - 2.0 * gap)) > EPS ||
                    it.runsAlongX != (g.orientation == FramingOrientation.WIDTHWAYS) || it.outline.isNotEmpty() })
                add("Infill runs must stop one equal gap inside both perpendicular picture-frame boards.")
            if (infill.zipWithNext().any { (a, z) -> abs(v(g, z.origin) - v(g, a.origin) - a.widthMm - gap) > EPS })
                add("Picture-frame infill gaps are not equal.")
            val covered = 2.0 * w + infill.sumOf { it.widthMm } + (infill.size + 1) * gap
            if (abs(covered - j - 2.0 * o) > EPS) add("Picture-frame and infill boards do not cover the finished width exactly.")
        }
        val expectedSupports = listOf(d, b - d)
        if (g.pictureFrameSupportPositionsMm.size != 2 || g.pictureFrameSupportPositionsMm.zip(expectedSupports).any { (a, z) -> abs(a - z) > EPS })
            add("Picture-frame interface support positions must equal board width less overhang on both ends.")
        expectedSupports.forEachIndexed { index, cross ->
            val distance = if (index == 0) cross else b - cross
            if (distance + gap < 2.0 * t - EPS) {
                if (distance <= EPS) add("The picture-frame interface is outside the existing doubled boundary support.")
            } else {
                val support = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT && abs(u(g, it.start) - cross) < EPS }
                if (distance - t / 2.0 < 2.0 * t - EPS || support.isEmpty() ||
                    abs(support.minOfOrNull { min(v(g, it.start), v(g, it.end)) }?.minus(2.0 * t) ?: 1.0) > EPS ||
                    abs(support.maxOfOrNull { max(v(g, it.start), v(g, it.end)) }?.minus(j - 2.0 * t) ?: 1.0) > EPS)
                    add("Each perpendicular picture-frame interface needs continuous support between doubled end boundaries without overlapping timber.")
            }
        }
        if (g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }.any { member -> expectedSupports.none { abs(u(g, member.start) - it) < EPS } })
            add("Unexpected picture-frame support position.")
        val actualCentres = g.members.filter { (it.kind == MemberKind.JOIST || it.kind == MemberKind.BOUNDARY || it.kind == MemberKind.PICTURE_FRAME_SUPPORT) && abs(u(g, it.start) - u(g, it.end)) < EPS }
            .map { u(g, it.start) }.distinct().sorted()
        if (actualCentres.size != g.joistPositionsMm.size || actualCentres.zip(g.joistPositionsMm).any { (a, z) -> abs(a - z) > EPS })
            add("Joist positions must include the actual continuous picture-frame supports.")
        // Verify every clear bay and every row, including bays occupied by ripped packers.
        val restraintSpan = j - 3.0 * t
        val intervals = max(2, ceilSafe(restraintSpan / (MAX_BLOCKING_SPACING - t / 2.0)))
        val expectedRows = (1 until intervals).map { 1.5 * t + restraintSpan * it / intervals }
        if (g.blockingRowsMm.size != expectedRows.size || g.blockingRowsMm.zip(expectedRows).any { (a, z) -> abs(a - z) > EPS })
            add("Picture-frame blocking rows must be evenly distributed within the 1800 mm maximum.")
        val supportCentres = g.members.filter { it.kind == MemberKind.PICTURE_FRAME_SUPPORT }.map { u(g, it.start) }.distinct()
        val blocking = g.members.filter { it.kind == MemberKind.NOG || it.kind == MemberKind.PICTURE_FRAME_PACKER }
        val expectedBlockingIds = mutableSetOf<String>()
        actualCentres.zipWithNext().forEachIndexed { bay, (left, right) ->
            val clear = right - left - t
            if (clear > EPS) expectedRows.forEach { base ->
                val centreV = base + if (bay % 2 == 0) -t / 2.0 else t / 2.0
                val centreU = (left + right) / 2.0
                val mustRip = clear < t - EPS && supportCentres.any { abs(it - left) < EPS || abs(it - right) < EPS }
                val matching = blocking.filter { member ->
                    abs((u(g, member.start) + u(g, member.end)) / 2.0 - centreU) < EPS &&
                        abs((v(g, member.start) + v(g, member.end)) / 2.0 - centreV) < EPS
                }
                matching.forEach { expectedBlockingIds += it.id }
                if (matching.size != 1) add("Every clear joist bay needs one nog or ripped packer at each staggered blocking row.")
                matching.singleOrNull()?.let { member ->
                    if (mustRip) {
                        if (member.kind != MemberKind.PICTURE_FRAME_PACKER || abs(member.lengthMm - t) > EPS || abs(member.thicknessMm - clear) > EPS ||
                            abs(member.profile.thicknessMm - clear) > EPS || abs(member.profile.depthMm - input.joist.depthMm) > EPS ||
                            member.profile.species != input.joist.species || abs(u(g, member.start) - u(g, member.end)) > EPS)
                            add("Narrow picture-frame bays need ripped packers matching the clear strip width and the joist thickness as cut length.")
                    } else if (member.kind != MemberKind.NOG || abs(member.lengthMm - clear) > EPS || abs(member.thicknessMm - t) > EPS ||
                        member.profile != input.joist || abs(v(g, member.start) - v(g, member.end)) > EPS)
                        add("Blocking cuts must match their clear joist bays and selected joist profile.")
                }
            }
        }
        if (blocking.any { it.id !in expectedBlockingIds }) add("Blocking or packers occur outside the calculated staggered rows.")
    }

    private fun samePoint(a: Point, b: Point) = abs(a.x - b.x) < EPS && abs(a.y - b.y) < EPS

    private fun u(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.x else p.y
    private fun v(g: DeckGeometry, p: Point) = if (g.orientation == FramingOrientation.WIDTHWAYS) p.y else p.x
    private fun evenly(start: Double, end: Double, intervals: Int) = (0..intervals).map { start + (end - start) * it / intervals }
    private fun ceilSafe(value: Double) = ceil(value - 1e-12).toInt().coerceAtLeast(1)
    private fun materialSpecification(profile: TimberProfile): String {
        val actual = "${format(profile.depthMm)} × ${format(profile.thicknessMm)} mm"
        return if (profile.name == actual) profile.label else "${profile.name} · actual $actual · ${profile.species}"
    }
    private fun cutSchedule(cuts: List<Double>): String = cuts.groupBy {
        BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }.entries.sortedBy { it.key.toDouble() }.joinToString(", ") { (cut, pieces) -> "${pieces.size} × $cut mm" }
    private fun format(value: Double): String = if (value == floor(value)) value.toLong().toString() else java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
