package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LargeBoundaryDrawingTest {
    private fun result(input: DeckInput): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected a valid large layout: $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    private fun inputs() = listOf(
        DeckInput(widthMm = 8000.0, lengthMm = 9000.0, orientation = FramingOrientation.LENGTHWAYS),
        DeckInput(widthMm = 8000.0, lengthMm = 9000.0, orientation = FramingOrientation.WIDTHWAYS),
        DeckInput(widthMm = 8000.0, lengthMm = 9000.0, orientation = FramingOrientation.WIDTHWAYS,
            decking = Profiles.decking[1], pictureFrame = true,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS),
        DeckInput(widthMm = 8000.0, lengthMm = 9000.0, orientation = FramingOrientation.LENGTHWAYS,
            maxJoistSpacingMm = 1800.0)
    )

    @Test fun `every supported or end boundary splice gets cross bars across its actual member direction`() {
        inputs().forEach { input ->
            val result = result(input)
            SheetSize.entries.forEach { size ->
                val sheet = DrawingGenerator.generate(result, sheetSize = size).first()
                val scale = sheet.scaleDenominator!!
                val border = sheet.elements.filterIsInstance<DrawingElement.Rect>().single { rect ->
                    rect.weightMm == 0.45 && abs(rect.width - input.widthMm / scale) < 0.00001 &&
                        abs(rect.height - input.lengthMm / scale) < 0.00001
                }
                val lines = sheet.elements.filterIsInstance<DrawingElement.Line>()
                result.geometry.joins.forEach { join ->
                    val run = result.geometry.members.first { it.runId == join.runId && it.layer == join.layer }
                    val x = border.x + join.position.x / scale
                    val y = border.y + join.position.y / scale
                    val offsets = if (abs(run.start.y - run.end.y) < 0.001)
                        listOf(listOf(-0.8, -1.5, 0.2, 1.5), listOf(0.2, -1.5, 1.2, 1.5))
                    else listOf(listOf(-1.5, -0.8, 1.5, 0.2), listOf(-1.5, 0.2, 1.5, 1.2))
                    offsets.forEach { mark ->
                        assertTrue("Missing correctly oriented splice bar for ${join.runId}/${join.layer}", lines.any { line ->
                            line.weightMm == 0.3 && abs(line.x1 - x - mark[0]) < 0.00001 &&
                                abs(line.y1 - y - mark[1]) < 0.00001 && abs(line.x2 - x - mark[2]) < 0.00001 &&
                                abs(line.y2 - y - mark[3]) < 0.00001
                        })
                    }
                }
                val crossBars = lines.count { line -> line.weightMm == 0.3 &&
                    ((abs(abs(line.x2 - line.x1) - 1.0) < 0.00001 && abs(abs(line.y2 - line.y1) - 3.0) < 0.00001) ||
                        (abs(abs(line.x2 - line.x1) - 3.0) < 0.00001 && abs(abs(line.y2 - line.y1) - 1.0) < 0.00001)) }
                assertEquals("All join records are drawn exactly once", result.geometry.joins.size * 2, crossBars)
                val text = texts(sheet)
                assertTrue(text.contains("staggered end-boundary splices at perpendicular joists"))
                assertTrue(text.contains("Other joist joins over bearers"))
                assertFalse("End-boundary joins must not be labelled bearer-supported", text.contains("Cross bars indicate supported joins"))
            }
        }
    }

    @Test fun `enlarged end boundary detail shows actual staggered layers and intersecting joist centrelines`() {
        inputs().take(3).forEach { input ->
            val result = result(input)
            val g = result.geometry
            fun uv(point: Point) = if (g.orientation == FramingOrientation.LENGTHWAYS) Point(point.y, point.x) else point
            val nearJoins = g.joins.filter { it.runId in listOf("BE1", "BE2") }
            assertEquals(2, nearJoins.size)
            assertNotEquals(uv(nearJoins[0].position).x, uv(nearJoins[1].position).x, 0.00001)
            nearJoins.forEach { join ->
                assertTrue(g.members.any { member -> member.kind == MemberKind.JOIST &&
                    abs(uv(member.start).x - uv(join.position).x) < 0.00001 })
            }
            SheetSize.entries.forEach { size ->
                val sheets = DrawingGenerator.generate(result, sheetSize = size)
                assertEquals(listOf("D01", "D02", "D03", "M01"), sheets.take(4).map { it.code })
                val detail = sheets.single { it.code == "D06" }
                assertTrue("The detail must enlarge the actual framing", detail.scaleDenominator!! < sheets[0].scaleDenominator!!)
                val text = texts(detail)
                assertTrue(text.contains("perpendicular joist CL"))
                assertTrue(text.contains("paired boundary member continues past that splice"))
                assertTrue(text.contains("do not require a bearer below"))
                assertTrue(text.contains("PRELIMINARY ESTIMATING / SET-OUT INFORMATION"))
                nearJoins.forEach { join ->
                    assertTrue("The splice running measurement derives from calculated geometry",
                        text.contains("${join.runId}, ${DrawingGenerator.mm(uv(join.position).x)} mm from framing end"))
                    val intersecting = g.members.first { member -> member.kind == MemberKind.JOIST &&
                        abs(uv(member.start).x - uv(join.position).x) < 0.00001 }
                    assertTrue(detail.elements.filterIsInstance<DrawingElement.Text>().any { it.text == intersecting.runId })
                }
                val labels = detail.elements.filterIsInstance<DrawingElement.Text>()
                    .filter { it.text in listOf("S1", "S2") }.sortedBy { it.x }
                assertEquals(2, labels.size)
                assertEquals("Printed stagger matches the actual two splice stations",
                    abs(uv(nearJoins[0].position).x - uv(nearJoins[1].position).x) / detail.scaleDenominator!!,
                    labels[1].x - labels[0].x, 0.00001)
                val boundaryRails = detail.elements.filterIsInstance<DrawingElement.Rect>()
                    .filter { it.weightMm == 0.35 }
                assertEquals("Both full-width boundary rails remain distinct", 2, boundaryRails.map { it.y }.distinct().size)
                assertTrue(boundaryRails.all { abs(it.height - input.joist.thicknessMm / detail.scaleDenominator!!) < 0.00001 })
            }
        }
    }

    @Test fun `large framing notes and splice detail remain on sheet at readable printed scale`() {
        inputs().forEach { input -> SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(result(input), sheetSize = size)
            listOf(sheets.first(), sheets.single { it.code == "D06" }).forEach { sheet ->
                sheet.elements.forEach { element ->
                    when (element) {
                        is DrawingElement.Line -> { bound(sheet, element.x1, element.y1); bound(sheet, element.x2, element.y2) }
                        is DrawingElement.Rect -> { bound(sheet, element.x, element.y); bound(sheet, element.x + element.width, element.y + element.height) }
                        is DrawingElement.Circle -> { bound(sheet, element.x - element.radiusMm, element.y - element.radiusMm); bound(sheet, element.x + element.radiusMm, element.y + element.radiusMm) }
                        is DrawingElement.Polygon -> element.points.forEach { bound(sheet, it.x, it.y) }
                        is DrawingElement.Text -> {
                            bound(sheet, element.x, element.y)
                            assertTrue(element.sizeMm >= 2.2)
                            if (element.x == size.widthMm - 77.0)
                                assertTrue("Notes must finish above the title block: ${element.text}", element.y < size.heightMm - 36.0)
                        }
                    }
                }
            }
        } }
    }

    @Test fun `single cut end boundaries do not add a splice detail sheet`() {
        val ordinary = result(DeckInput())
        assertTrue(DrawingGenerator.generate(ordinary).none { it.code == "D06" })
    }

    private fun texts(sheet: DrawingSheet) = sheet.elements.filterIsInstance<DrawingElement.Text>().joinToString(" ") { it.text }
    private fun bound(sheet: DrawingSheet, x: Double, y: Double) {
        assertTrue("Finite x within sheet: $x", x.isFinite() && x >= 0.0 && x <= sheet.widthMm)
        assertTrue("Finite y within sheet: $y", y.isFinite() && y >= 0.0 && y <= sheet.heightMm)
    }
}
