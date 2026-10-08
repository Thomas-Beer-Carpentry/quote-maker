package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*
import java.util.Locale
import kotlin.math.*

/** All four sheets consume the same immutable calculated layout and takeoff. No quantities are inferred by the renderer. */
object DrawingGenerator {
    fun generate(result: DeckResult, title: DrawingTitle = DrawingTitle(), sheetSize: SheetSize = SheetSize.A3): List<DrawingSheet> =
        listOf(framing(result, title, sheetSize), decking(result, title, sheetSize), section(result, title, sheetSize)) +
            MaterialDrawingGenerator.generate(result, title, sheetSize)

    internal fun frame(builder: SheetBuilder, code: String, drawing: String, title: DrawingTitle, scale: Double?) {
        val w = builder.size.widthMm
        val h = builder.size.heightMm
        builder.rect(10.0, 10.0, w - 20.0, h - 20.0, 0.45)
        builder.text(15.0, 20.0, "TIMBER TAKEOFF", 4.0, bold = true)
        builder.text(w - 15.0, 19.5, drawing.uppercase(), 3.5, TextAlign.RIGHT, bold = true)
        builder.text(15.0, 26.0, "PRELIMINARY ESTIMATING / SET-OUT INFORMATION", 2.5, bold = true)
        builder.text(15.0, 31.0, "Provisional rules. Not structurally verified. Confirm design and site conditions before construction.", 2.2)
        val bottom = h - 34.0
        builder.line(10.0, bottom, w - 10.0, bottom, 0.35)
        val right = w - 96.0
        builder.line(right, bottom, right, h - 10.0, 0.25)
        builder.line(w - 43.0, bottom, w - 43.0, h - 10.0, 0.25)
        builder.text(15.0, bottom + 5.0, fit(title.project, (right - 20.0) / 1.8), 3.5, bold = true)
        builder.text(15.0, bottom + 10.0, fit(listOf(title.client, title.task).filter { it.isNotBlank() }.joinToString("  |  "), (right - 20.0) / 1.3), 2.5)
        builder.text(15.0, bottom + 15.0, fit(title.address.ifBlank { "Freestanding timber deck" }, (right - 20.0) / 1.2), 2.2)
        builder.text(15.0, bottom + 20.0, "Exact quantities only · No purchasing waste allowance · All dimensions in mm", 2.2)
        builder.text(right + 4.0, bottom + 5.0, "SCALE AT ${builder.size.name}", 2.2)
        builder.text(right + 4.0, bottom + 10.0, scale?.let { "1:${mm(it)}" } ?: "SCHEDULE / NTS", 3.0, bold = true)
        builder.text(right + 4.0, bottom + 15.0, "Print at 100%", 2.2)
        builder.text(right + 4.0, bottom + 20.0, fit(title.preparedDate, 24.0), 2.2)
        builder.text(w - 38.0, bottom + 9.0, code, 5.0, bold = true)
        builder.text(w - 38.0, bottom + 16.0, "VERSION 1", 2.2)
        builder.text(w - 38.0, bottom + 21.0, "ESTIMATING", 2.2)
    }

    private fun framing(result: DeckResult, title: DrawingTitle, size: SheetSize): DrawingSheet {
        val b = SheetBuilder(size)
        val input = result.input
        val g = result.geometry
        val plan = PlanTransform.fit(input.widthMm, input.lengthMm, size)
        frame(b, "D01", "Framing plan", title, plan.scale)
        val bearers = g.members.filter { it.kind == MemberKind.BEARER }
        bearers.forEach { drawMember(b, plan, it, 0.18, dashed = true) }
        g.piles.forEach { pile ->
            val p = plan.point(pile.position)
            b.rect(p.x - 62.5 / plan.scale, p.y - 62.5 / plan.scale, 125.0 / plan.scale, 125.0 / plan.scale, 0.3)
            b.line(p.x - 1.4, p.y, p.x + 1.4, p.y, 0.13)
            b.line(p.x, p.y - 1.4, p.x, p.y + 1.4, 0.13)
        }
        g.members.filter { it.kind != MemberKind.BEARER }.forEach { drawMember(b, plan, it, if (it.kind == MemberKind.BOUNDARY) 0.35 else 0.2) }
        b.rect(plan.x, plan.y, input.widthMm / plan.scale, input.lengthMm / plan.scale, 0.45)
        g.joins.forEach { join ->
            val p = plan.point(join.position)
            // Cross bars are a join notation, never a fixing representation.
            val horizontal = (join.kind == MemberKind.BEARER) == (g.orientation == FramingOrientation.WIDTHWAYS)
            if (horizontal) {
                b.line(p.x - 0.8, p.y - 1.5, p.x + 0.2, p.y + 1.5, 0.3)
                b.line(p.x + 0.2, p.y - 1.5, p.x + 1.2, p.y + 1.5, 0.3)
            } else {
                b.line(p.x - 1.5, p.y - 0.8, p.x + 1.5, p.y + 0.2, 0.3)
                b.line(p.x - 1.5, p.y + 0.2, p.x + 1.5, p.y + 1.2, 0.3)
            }
        }
        overallDimensions(b, plan, input.widthMm, input.lengthMm)
        // Bearer and joist chains use pair centres and actual installed member centres.
        val bearerAlongX = g.orientation == FramingOrientation.LENGTHWAYS
        val bearerPoints = listOf(0.0) + g.bearerPositionsMm + g.joistRunMm
        chain(b, plan, bearerPoints, bearerAlongX, outsideAtEnd = true, label = "BEARER CL", offset = 9.0)
        chain(b, plan, g.joistPositionsMm, !bearerAlongX, outsideAtEnd = true, label = "JOIST CL", offset = 9.0)
        chain(b, plan, listOf(0.0) + g.pilePositionsMm + g.bearerRunMm, !bearerAlongX,
            outsideAtEnd = true, label = "PILE CL", offset = 18.0)
        val bearerFirst = g.bearerPositionsMm.first()
        if (bearerAlongX) {
            val axisY = plan.y - 8.0
            dimHorizontal(b, plan.x, plan.x + bearerFirst / plan.scale, axisY, plan.y, "${mm(g.joistCantileverMm)} CT")
        } else {
            dimVertical(b, plan.y, plan.y + bearerFirst / plan.scale, plan.x - 8.0, plan.x, "${mm(g.joistCantileverMm)} CT")
        }
        // End pile dimensions refer to the full bearer run: 200 mm at each end.
        val p0 = plan.point(g.point(0.0, g.bearerPositionsMm.first()))
        val p1 = plan.point(g.point(g.pilePositionsMm.first(), g.bearerPositionsMm.first()))
        if (g.orientation == FramingOrientation.WIDTHWAYS) dimHorizontal(b, p0.x, p1.x, plan.y - 15.0, p0.y, "200 CT")
        else dimVertical(b, p0.y, p1.y, plan.x - 15.0, p0.x, "200 CT")
        sampleLabel(b, plan, bearers.firstOrNull(), "B1")
        sampleLabel(b, plan, g.members.firstOrNull { it.kind == MemberKind.JOIST }, "J1")
        sampleLabel(b, plan, g.members.firstOrNull { it.kind == MemberKind.NOG }, "N1")
        val x = size.widthMm - 77.0
        var y = 44.0
        y = b.note(x, y, 61.0, "FRAMING KEY", 3.0, true) + 2.0
        y = b.note(x, y, 61.0, "B1 DOUBLE BEARER ${input.bearer.name}", bold = true) + 2.0
        y = b.note(x, y, 61.0, input.bearer.species) + 3.0
        y = b.note(x, y, 61.0, "J1 JOISTS ${input.joist.name}", bold = true) + 2.0
        y = b.note(x, y, 61.0, "All four boundaries have two full members. N1 nogs use joist profile.") + 3.0
        val bearerSpacing = if (g.bearerPositionsMm.size == 1) "Single bearer line." else "Bearer spacing ${mm(g.actualBearerSpacingMm)} mm actual."
        y = b.note(x, y, 61.0, "Joist spacing ${mm(g.actualJoistSpacingMm)} mm maximum actual. $bearerSpacing") + 3.0
        y = b.note(x, y, 61.0, "Joist end CT: ${mm(g.joistCantileverMm)} mm side boundaries; ${mm(g.joistCantileverMm - 2.0 * input.joist.thicknessMm)} mm internal cut ends, both ends.") + 3.0
        y = b.note(x, y, 61.0, "125 × 125 piles: ${g.piles.size}. Bearer end cantilevers 200 mm both ends.") + 3.0
        y = b.note(x, y, 61.0, "CT = cantilever to bearer pair centreline. CL = member centreline. Cross bars indicate supported joins.") + 3.0
        if (y + 18.0 < size.heightMm - 45.0) b.note(x, y, 61.0, "Bearer joins are staggered between the doubled members. All framing cuts ≤ 6,000 mm.")
        scaleBar(b, plan, size)
        return b.sheet("D01", "Framing plan", plan.scale)
    }

    private fun decking(result: DeckResult, title: DrawingTitle, size: SheetSize): DrawingSheet {
        val b = SheetBuilder(size)
        val i = result.input
        val g = result.geometry
        val width = i.widthMm + i.overhangMm * 2.0
        val length = i.lengthMm + i.overhangMm * 2.0
        val plan = PlanTransform.fit(width, length, size)
        frame(b, "D02", "Decking plan", title, plan.scale)
        g.boards.forEach { board ->
            val p = plan.point(Point(board.origin.x + i.overhangMm, board.origin.y + i.overhangMm))
            b.rect(p.x, p.y, (if (board.runsAlongX) board.lengthMm else board.widthMm) / plan.scale,
                (if (board.runsAlongX) board.widthMm else board.lengthMm) / plan.scale, 0.16)
        }
        b.rect(plan.x, plan.y, width / plan.scale, length / plan.scale, 0.4)
        b.rect(plan.x + i.overhangMm / plan.scale, plan.y + i.overhangMm / plan.scale,
            i.widthMm / plan.scale, i.lengthMm / plan.scale, 0.25, dashed = true)
        overallDimensions(b, plan, width, length)
        val cx = plan.x + width / plan.scale / 2.0
        val cy = plan.y + length / plan.scale / 2.0
        val alongX = g.boards.first().runsAlongX
        if (alongX) { arrow(b, cx - 10.0, cy, cx + 10.0, cy); b.text(cx, cy - 3.0, "BOARD DIRECTION", 2.5, TextAlign.CENTER) }
        else { arrow(b, cx, cy + 10.0, cx, cy - 10.0); b.text(cx + 3.0, cy, "BOARD DIRECTION", 2.5, rotation = -90.0) }
        val x = size.widthMm - 77.0
        var y = 44.0
        y = b.note(x, y, 61.0, "DECKING SET-OUT", 3.0, true) + 3.0
        y = b.note(x, y, 61.0, "${i.decking.nominal} · ${i.deckingSpecies}", bold = true) + 3.0
        y = b.note(x, y, 61.0, "Actual finished width ${mm(i.actualDeckingWidthMm)} mm. Thickness ${mm(i.decking.thicknessMm)} mm.") + 3.0
        y = b.note(x, y, 61.0, "${g.boards.size} continuous board runs. Equal gap ${mm(g.deckingGapMm)} mm.") + 3.0
        val ripped = abs(g.startingBoardWidthMm - i.actualDeckingWidthMm) > 0.001
        y = b.note(x, y, 61.0, if (ripped) "START BOARD: RIP TO ${mm(g.startingBoardWidthMm)} mm. Remaining boards full width." else "All boards are full width; no starting rip required.", bold = true) + 3.0
        y = b.note(x, y, 61.0, "Decking overhang ${mm(i.overhangMm)} mm on all four sides. Dashed rectangle is outside framing.") + 3.0
        b.note(x, y, 61.0, "Decking joins and purchasing stock lengths are excluded. No waste allowance.")
        // Overhang details remain legible even where their physical scale is very small.
        val topX = plan.x + width / plan.scale * 0.7
        leader(b, topX, plan.y + i.overhangMm / plan.scale, topX + 8.0, plan.y - 7.0, "${mm(i.overhangMm)} OVERHANG", false)
        val leftY = plan.y + length / plan.scale * 0.65
        leader(b, plan.x + i.overhangMm / plan.scale, leftY, plan.x - 9.0, leftY + 9.0, "${mm(i.overhangMm)}", true)
        val first = g.boards.first()
        val firstP = plan.point(Point(first.origin.x + i.overhangMm, first.origin.y + i.overhangMm))
        if (ripped) leader(b, firstP.x + (if (alongX) 5.0 else first.widthMm / plan.scale / 2.0), firstP.y + (if (alongX) first.widthMm / plan.scale / 2.0 else 5.0), plan.x + 8.0, plan.y - 15.0, "START ${mm(first.widthMm)}", false)
        scaleBar(b, plan, size)
        return b.sheet("D02", "Decking plan", plan.scale)
    }

    private fun section(result: DeckResult, title: DrawingTitle, size: SheetSize): DrawingSheet {
        val b = SheetBuilder(size)
        val i = result.input
        val g = result.geometry
        val availableW = size.widthMm - 133.0
        val availableH = size.heightMm - 106.0
        val scale = chooseScale(max(g.joistRunMm / availableW, (i.heightMm + 600.0) / availableH))
        val sx = 36.0 + (availableW - g.joistRunMm / scale) / 2.0
        val top = 46.0 + (availableH - (i.heightMm + 600.0) / scale) / 2.0
        val ground = top + i.heightMm / scale
        val run = g.joistRunMm / scale
        val deckBottom = top + i.decking.thicknessMm / scale
        val joistBottom = deckBottom + i.joist.depthMm / scale
        val bearerBottom = joistBottom + i.bearer.depthMm / scale
        frame(b, "D03", "Typical section along joists", title, scale)
        b.rect(sx - i.overhangMm / scale, top, run + 2.0 * i.overhangMm / scale, i.decking.thicknessMm / scale, 0.3)
        b.rect(sx, deckBottom, run, i.joist.depthMm / scale, 0.35)
        g.bearerPositionsMm.forEach { pos ->
            val x = sx + pos / scale
            val bearerW = 2.0 * i.bearer.thicknessMm / scale
            b.rect(x - bearerW / 2.0, joistBottom, bearerW, i.bearer.depthMm / scale, 0.35)
            b.line(x, joistBottom, x, bearerBottom, 0.18)
            b.rect(x - 62.5 / scale, bearerBottom, 125.0 / scale, (g.pileAboveGroundMm + 500.0) / scale, 0.35)
            b.rect(x - 200.0 / scale, ground, 400.0 / scale, 600.0 / scale, 0.25)
            // Sparse aggregate notation identifies concrete without obscuring the pile.
            val dots = max(2, floor(400.0 / scale / 2.5).toInt())
            repeat(dots) { n ->
                val dx = x - 180.0 / scale + n * 360.0 / scale / (dots - 1)
                if (abs(dx - x) > 72.0 / scale) repeat(3) { row -> b.circle(dx, ground + (100.0 + row * 200.0) / scale, 0.16, fill = true) }
            }
        }
        b.line(sx - 9.0, ground, sx + run + 9.0, ground, 0.45)
        b.text(sx, ground - 2.5, "FLAT LEVEL GROUND", 2.5)
        dimVertical(b, top, ground, sx - 17.0, sx, "${mm(i.heightMm)} FINISHED HEIGHT")
        val first = sx + g.bearerPositionsMm.first() / scale
        dimVertical(b, ground, ground + 500.0 / scale, sx - 9.0, first - 62.5 / scale, "500 EMBEDMENT")
        dimVertical(b, ground, ground + 600.0 / scale, sx - 17.0, first - 200.0 / scale, "600 HOLE")
        dimHorizontal(b, first - 200.0 / scale, first + 200.0 / scale, ground + 600.0 / scale + 7.0, ground + 600.0 / scale, "400 HOLE")
        dimVertical(b, bearerBottom, ground + 500.0 / scale, sx + run + 12.0, sx + run, "${mm(g.piles.first().lengthMm)} PILE CUT")
        val x = size.widthMm - 77.0
        var y = 44.0
        y = b.note(x, y, 61.0, "SECTION NOTES", 3.0, true) + 3.0
        y = b.note(x, y, 61.0, "Decking ${i.decking.nominal}; ${mm(i.decking.thicknessMm)} mm thickness.") + 3.0
        y = b.note(x, y, 61.0, "Joist ${i.joist.name}, sitting on doubled ${i.bearer.name} bearers.") + 3.0
        y = b.note(x, y, 61.0, "Pile 125 × 125 mm. Above ground ${mm(g.pileAboveGroundMm)} mm; cut length ${mm(g.piles.first().lengthMm)} mm.") + 3.0
        y = b.note(x, y, 61.0, "Footing 400 × 400 mm square × 600 mm deep. Pile embedment 500 mm.") + 3.0
        y = b.note(x, y, 61.0, "Finished height includes decking, joist and bearer depths. Concrete volume excludes embedded pile displacement.") + 3.0
        b.note(x, y, 61.0, "Section shows one representative pile on each bearer line. Refer D01 for all pile positions.")
        return b.sheet("D03", "Typical section along joists", scale)
    }

    private fun drawMember(b: SheetBuilder, t: PlanTransform, member: TimberMember, weight: Double, dashed: Boolean = false) {
        val a = t.point(member.start)
        val z = t.point(member.end)
        val thickness = member.thicknessMm / t.scale
        if (abs(a.y - z.y) < 0.00001) b.rect(min(a.x, z.x), a.y - thickness / 2.0, abs(z.x - a.x), thickness, weight, dashed = dashed)
        else b.rect(a.x - thickness / 2.0, min(a.y, z.y), thickness, abs(z.y - a.y), weight, dashed = dashed)
    }

    private fun sampleLabel(b: SheetBuilder, t: PlanTransform, member: TimberMember?, name: String) {
        if (member == null) return
        val a = t.point(member.start)
        val z = t.point(member.end)
        val x = (a.x + z.x) / 2.0
        val y = (a.y + z.y) / 2.0
        b.text(x + 2.0, y - 2.0, name, 2.5, bold = true)
    }

    private fun overallDimensions(b: SheetBuilder, p: PlanTransform, width: Double, length: Double) {
        dimHorizontal(b, p.x, p.x + width / p.scale, p.y - 22.0, p.y, mm(width))
        dimVertical(b, p.y, p.y + length / p.scale, p.x - 22.0, p.x, mm(length))
    }

    private fun chain(b: SheetBuilder, t: PlanTransform, values: List<Double>, alongX: Boolean, outsideAtEnd: Boolean, label: String, offset: Double) {
        if (values.size < 2) return
        val base = if (alongX) t.y + t.height / t.scale else t.x + t.width / t.scale
        val axis = base + if (outsideAtEnd) offset else -offset
        val spacing = values.zipWithNext().map { it.second - it.first }
        val equal = spacing.maxOrNull()!! - spacing.minOrNull()!! < 0.01
        val start = if (alongX) t.x else t.y
        val pts = values.map { start + it / t.scale }
        if (alongX) {
            b.line(pts.first(), axis, pts.last(), axis, 0.13)
            pts.forEach { b.line(it, base + 1.0, it, axis + 1.5, 0.13); tick(b, it, axis) }
            if (equal) b.text((pts.first() + pts.last()) / 2.0, axis - 1.2, "${spacing.size} @ ${mm(spacing.first())} $label", 2.2, TextAlign.CENTER)
            else {
                spacing.forEachIndexed { n, gap -> if (pts[n + 1] - pts[n] > 9.0) b.text((pts[n] + pts[n + 1]) / 2.0, axis - 1.2, mm(gap), 2.2, TextAlign.CENTER) }
                b.text((pts.first() + pts.last()) / 2.0, axis + 4.5, label, 2.2, TextAlign.CENTER)
            }
        } else {
            b.line(axis, pts.first(), axis, pts.last(), 0.13)
            pts.forEach { b.line(base + 1.0, it, axis + 1.5, it, 0.13); tick(b, axis, it) }
            if (equal) b.text(axis - 1.2, (pts.first() + pts.last()) / 2.0, "${spacing.size} @ ${mm(spacing.first())} $label", 2.2, TextAlign.CENTER, -90.0)
            else {
                spacing.forEachIndexed { n, gap -> if (pts[n + 1] - pts[n] > 9.0) b.text(axis - 1.2, (pts[n] + pts[n + 1]) / 2.0, mm(gap), 2.2, TextAlign.CENTER, -90.0) }
                b.text(axis + 4.5, (pts.first() + pts.last()) / 2.0, label, 2.2, TextAlign.CENTER, -90.0)
            }
        }
    }

    private fun dimHorizontal(b: SheetBuilder, x1: Double, x2: Double, y: Double, originY: Double, value: String) {
        b.line(x1, originY - 1.0, x1, y - 1.5, 0.13)
        b.line(x2, originY - 1.0, x2, y - 1.5, 0.13)
        b.line(x1, y, x2, y, 0.13)
        tick(b, x1, y); tick(b, x2, y)
        if (abs(x2 - x1) >= value.length * 1.15) b.text((x1 + x2) / 2.0, y - 1.2, value, 2.5, TextAlign.CENTER)
        else { b.line(x2, y, x2 + 6.0, y, 0.13); b.text(x2 + 2.0, y - 1.2, value, 2.2) }
    }

    private fun dimVertical(b: SheetBuilder, y1: Double, y2: Double, x: Double, originX: Double, value: String) {
        b.line(originX - 1.0, y1, x - 1.5, y1, 0.13)
        b.line(originX - 1.0, y2, x - 1.5, y2, 0.13)
        b.line(x, y1, x, y2, 0.13)
        tick(b, x, y1); tick(b, x, y2)
        if (abs(y2 - y1) >= value.length * 1.15) b.text(x - 1.2, (y1 + y2) / 2.0, value, 2.5, TextAlign.CENTER, -90.0)
        else b.text(x - 1.2, y1 - 2.0, value, 2.2, rotation = -90.0)
    }

    private fun tick(b: SheetBuilder, x: Double, y: Double) = b.line(x - 0.8, y + 0.8, x + 0.8, y - 0.8, 0.22)
    private fun leader(b: SheetBuilder, x: Double, y: Double, tx: Double, ty: Double, value: String, left: Boolean) {
        b.line(x, y, tx, ty, 0.13)
        b.line(tx, ty, tx + if (left) -4.0 else 4.0, ty, 0.13)
        b.text(tx + if (left) -1.0 else 1.0, ty - 1.2, value, 2.2, if (left) TextAlign.RIGHT else TextAlign.LEFT)
    }
    private fun arrow(b: SheetBuilder, x1: Double, y1: Double, x2: Double, y2: Double) {
        b.line(x1, y1, x2, y2, 0.3)
        val a = atan2(y2 - y1, x2 - x1)
        listOf(a + PI * 0.8, a - PI * 0.8).forEach { b.line(x2, y2, x2 + cos(it) * 2.0, y2 + sin(it) * 2.0, 0.3) }
    }
    private fun scaleBar(b: SheetBuilder, p: PlanTransform, size: SheetSize) {
        val real = p.scale * 20.0
        val y = size.heightMm - 43.0
        b.line(38.0, y, 58.0, y, 0.45)
        b.line(38.0, y - 1.5, 38.0, y + 1.5, 0.25)
        b.line(48.0, y - 1.0, 48.0, y + 1.0, 0.25)
        b.line(58.0, y - 1.5, 58.0, y + 1.5, 0.25)
        b.text(38.0, y - 2.5, "0", 2.2, TextAlign.CENTER)
        b.text(58.0, y - 2.5, "${mm(real)} mm", 2.2, TextAlign.CENTER)
    }
    internal fun mm(value: Double): String = if (abs(value - round(value)) < 0.0000001) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    internal fun amount(value: Double): String = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    private fun fit(value: String, chars: Double): String = if (value.length <= chars.toInt()) value else value.take((chars.toInt() - 1).coerceAtLeast(0)) + "…"
    private fun chooseScale(minimum: Double): Double = listOf(10.0, 20.0, 25.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0, 750.0, 1000.0).firstOrNull { it >= minimum } ?: ceil(minimum / 500.0) * 500.0
    private data class PlanTransform(val x: Double, val y: Double, val width: Double, val height: Double, val scale: Double) {
        fun point(p: Point) = Point(x + p.x / scale, y + p.y / scale)
        companion object {
            fun fit(width: Double, height: Double, sheet: SheetSize): PlanTransform {
                val left = 38.0
                val top = 58.0
                val w = sheet.widthMm - 140.0
                val h = sheet.heightMm - 123.0
                val s = chooseScale(max(width / w, height / h))
                return PlanTransform(left + (w - width / s) / 2.0, top + (h - height / s) / 2.0, width, height, s)
            }
        }
    }
}
