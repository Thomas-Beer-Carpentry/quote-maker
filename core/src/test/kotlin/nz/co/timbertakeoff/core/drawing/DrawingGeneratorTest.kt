package nz.co.timbertakeoff.core.drawing

import nz.co.timbertakeoff.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DrawingGeneratorTest {
    @Test fun `equal cuts group despite numerical noise while retaining their total`() {
        val cuts = listOf(379.09090909090907, 379.0909090909091, 379.090909090909, 379.09090909090946)
        val entries = CutSchedule.entries(cuts)
        assertEquals(1, entries.size)
        assertEquals(4, entries.single().pieces)
        assertEquals(cuts.sum(), entries.sumOf { it.lengthMm * it.pieces }, 0.000000001)
        assertEquals(2, CutSchedule.entries(listOf(1000.0, 1000.01)).size)
    }

    private fun result(input: DeckInput = DeckInput()): DeckResult {
        val outcome = DeckCalculator.calculate(input)
        assertTrue("Expected valid deck: $outcome", outcome is CalculationOutcome.Success)
        return (outcome as CalculationOutcome.Success).result
    }

    @Test fun `sheets are generated for both print sizes with legible finite vector coordinates`() {
        val inputs = listOf(
            DeckInput(),
            DeckInput(widthMm = 400.0, lengthMm = 400.0),
            DeckInput(widthMm = 20000.0, lengthMm = 4800.0, orientation = FramingOrientation.LENGTHWAYS),
            DeckInput(widthMm = 4800.0, lengthMm = 20000.0, orientation = FramingOrientation.WIDTHWAYS),
            DeckInput(heightMm = 10000.0),
            DeckInput(orientation = FramingOrientation.WIDTHWAYS),
            DeckInput(decking = Profiles.decking[1], pictureFrame = true),
            DeckInput(decking = Profiles.decking[1], pictureFrame = true, orientation = FramingOrientation.WIDTHWAYS,
                pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS)
        )
        inputs.forEach { input -> SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(result(input), DrawingTitle("Test job", "Client", "Deck"), size)
            assertEquals(listOf("D01", "D02", "D03"), sheets.take(3).map { it.code })
            assertEquals("M01", sheets[3].code)
            sheets.forEach { sheet ->
                assertEquals(size.widthMm, sheet.widthMm, 0.0)
                assertEquals(size.heightMm, sheet.heightMm, 0.0)
                assertTrue(sheet.elements.isNotEmpty())
                sheet.elements.forEach { element ->
                    when (element) {
                        is DrawingElement.Line -> { bound(sheet, element.x1, element.y1); bound(sheet, element.x2, element.y2) }
                        is DrawingElement.Rect -> {
                            assertTrue(element.width >= 0.0 && element.height >= 0.0)
                            bound(sheet, element.x, element.y); bound(sheet, element.x + element.width, element.y + element.height)
                        }
                        is DrawingElement.Circle -> { bound(sheet, element.x - element.radiusMm, element.y - element.radiusMm); bound(sheet, element.x + element.radiusMm, element.y + element.radiusMm) }
                        is DrawingElement.Polygon -> { assertTrue(element.points.size >= 3); element.points.forEach { bound(sheet, it.x, it.y) } }
                        is DrawingElement.Text -> { bound(sheet, element.x, element.y); assertTrue("Text must remain readable at printed scale", element.sizeMm >= 2.2) }
                    }
                }
            }
        } }
    }

    @Test fun `geometry edits alter drawings and dimension strings`() {
        val initial = DrawingGenerator.generate(result(DeckInput()), sheetSize = SheetSize.A3)
        val changed = DrawingGenerator.generate(result(DeckInput(widthMm = 3900.0, lengthMm = 5200.0)), sheetSize = SheetSize.A3)
        assertNotEquals(initial[0].elements, changed[0].elements)
        assertTrue(texts(changed[0]).contains("3900"))
        assertTrue(texts(changed[0]).contains("5200"))
        assertTrue(texts(changed[1]).contains("3940"))
        assertTrue(texts(changed[1]).contains("5240"))
        assertTrue(texts(initial[0]).joinToString(" ").contains("180 mm side boundaries"))
        assertTrue(texts(initial[0]).joinToString(" ").contains("90 mm internal"))
    }

    @Test fun `decking scene contains every individual board in the calculated orientation`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val result = result(DeckInput(orientation = orientation))
            val sheet = DrawingGenerator.generate(result)[1]
            val rects = sheet.elements.filterIsInstance<DrawingElement.Rect>()
            val drawnBoards = rects.filter { rect -> result.geometry.boards.any { board ->
                val width = (if (board.runsAlongX) board.lengthMm else board.widthMm) / sheet.scaleDenominator!!
                val height = (if (board.runsAlongX) board.widthMm else board.lengthMm) / sheet.scaleDenominator!!
                abs(rect.width - width) < 0.00001 && abs(rect.height - height) < 0.00001
            } }
            assertEquals("No individual board may be dropped from the scene", result.geometry.boards.size, drawnBoards.size)
            assertEquals("Boards must occupy independent positions", result.geometry.boards.size, drawnBoards.map { it.x to it.y }.distinct().size)
            result.geometry.boards.forEach { board ->
                val width = (if (board.runsAlongX) board.lengthMm else board.widthMm) / sheet.scaleDenominator!!
                val height = (if (board.runsAlongX) board.widthMm else board.lengthMm) / sheet.scaleDenominator!!
                assertTrue("Each calculated board must be drawn at scale", rects.any { abs(it.width - width) < 0.00001 && abs(it.height - height) < 0.00001 })
            }
            assertTrue(texts(sheet).any { it.contains("Equal gap") })
        }
    }

    @Test fun `schedule paginates full exact cuts without dropping quantities`() {
        val base = result()
        val cuts = (1..100).map { 1000.0 + it / 10.0 }
        val custom = base.copy(materials = listOf(MaterialLine(MaterialCategory.JOISTS,
            MaterialKey("Timber", "140 × 45 mm · radiata", "lm"), cuts.sum() / 1000.0, cuts)))
        val schedules = DrawingGenerator.generate(custom, sheetSize = SheetSize.A4).drop(3)
        assertTrue("A long cut schedule needs continuation pages", schedules.size > 1)
        val texts = schedules.flatMap(::texts)
        cuts.forEach { cut -> assertTrue("Missing cut $cut", texts.contains("1 × ${DrawingGenerator.mm(cut)} mm")) }
        assertEquals(100, CutSchedule.entries(cuts).sumOf { it.pieces })
        assertTrue(texts.any { it == "OVERALL MATERIALS" })
        assertTrue(texts.indexOf("OVERALL MATERIALS") < texts.indexOf("MATERIAL BREAKDOWN"))
        assertTrue(texts.any { it == "${DrawingGenerator.amount(cuts.sum() / 1000.0)} lm" })
    }

    @Test fun `material schedule starts with combined profile totals before category cuts`() {
        SheetSize.entries.forEach { size ->
            val scheduleTexts = DrawingGenerator.generate(result(), sheetSize = size).drop(3).flatMap(::texts)
            val overallStart = scheduleTexts.indexOf("OVERALL MATERIALS")
            val breakdownStart = scheduleTexts.indexOf("MATERIAL BREAKDOWN")
            assertTrue("Overall materials must precede the breakdown", overallStart >= 0 && breakdownStart > overallStart)
            val overall = scheduleTexts.subList(overallStart, breakdownStart)
            assertEquals("All default 140 × 45 joists and nogs share one overall line", 1, overall.count { it.contains("140 × 45 mm") })
            assertTrue("Joists, boundary joists and nogs total 71.25 lm", overall.contains("71.25 lm"))
            assertTrue("Overall totals must not be interrupted by cut schedules", overall.none { it.contains("exact cut schedule") })
            val breakdown = scheduleTexts.drop(breakdownStart)
            assertTrue(breakdown.contains("JOISTS AND BOUNDARY JOISTS"))
            assertTrue(breakdown.contains("NOGS / BLOCKING"))
            assertTrue(breakdown.any { it.contains("exact cut schedule") })
        }
    }

    @Test fun `pile section is derived from input profile depths and finished height`() {
        val input = DeckInput(heightMm = 1200.0, bearer = Profiles.framing[2], joist = Profiles.framing[1])
        val result = result(input)
        val section = DrawingGenerator.generate(result)[2]
        val expected = 1200.0 - 19.0 - 190.0 - 240.0 + 500.0
        assertTrue(texts(section).contains("${DrawingGenerator.mm(expected)} PILE CUT"))
        assertTrue(texts(section).contains("1200 FINISHED HEIGHT"))
        assertTrue(texts(section).contains("500 EMBEDMENT"))
        assertTrue(texts(section).contains("600 HOLE"))
        assertTrue(texts(section).contains("400 HOLE"))
    }

    @Test fun `picture frame mitres and first infill rip come from calculated board geometry`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val input = DeckInput(widthMm = 3670.0, lengthMm = 4800.0, decking = Profiles.decking[1],
                pictureFrame = true, orientation = orientation)
            val calculated = result(input)
            SheetSize.entries.forEach { size ->
                val sheet = DrawingGenerator.generate(calculated, sheetSize = size)[1]
                val scale = sheet.scaleDenominator!!
                val perimeter = sheet.elements.filterIsInstance<DrawingElement.Rect>().single { rect ->
                    abs(rect.width - (input.widthMm + 40.0) / scale) < 0.00001 &&
                        abs(rect.height - (input.lengthMm + 40.0) / scale) < 0.00001 && rect.weightMm == 0.4
                }
                val polygons = sheet.elements.filterIsInstance<DrawingElement.Polygon>()
                assertEquals("Each of the four full-width mitred perimeter boards is a vector polygon", 4, polygons.size)
                calculated.geometry.boards.filter { it.role == DeckBoardRole.PICTURE_FRAME }.forEach { board ->
                    val expected = board.outline.map { Point(perimeter.x + (it.x + input.overhangMm) / scale,
                        perimeter.y + (it.y + input.overhangMm) / scale) }
                    assertTrue("The frame outline must match the takeoff geometry at printed scale", polygons.any { polygon ->
                        polygon.points.size == expected.size && polygon.points.zip(expected).all { (actual, wanted) ->
                            abs(actual.x - wanted.x) < 0.00001 && abs(actual.y - wanted.y) < 0.00001
                        }
                    })
                }
                val allText = texts(sheet).joinToString(" ")
                assertTrue(allText.contains("4 full-width frame boards"))
                assertTrue(allText.contains("45° mitred corners"))
                assertTrue(allText.contains("to frame and between infill"))
                if (calculated.geometry.startingBoardWidthMm < input.actualDeckingWidthMm - 0.001) {
                    assertTrue(allText.contains("FIRST INFILL AFTER FRAME: RIP"))
                    assertTrue(allText.contains("INFILL RIP ${DrawingGenerator.mm(calculated.geometry.startingBoardWidthMm)}"))
                }
            }
        }
    }

    @Test fun `nogs have stronger outlines and picture frame interfaces show their actual offsets`() {
        val calculated = result(DeckInput(decking = Profiles.decking[1], pictureFrame = true))
        SheetSize.entries.forEach { size ->
            val sheets = DrawingGenerator.generate(calculated, sheetSize = size)
            val framing = sheets[0]
            val scale = framing.scaleDenominator!!
            val nogs = calculated.geometry.members.filter { it.kind == MemberKind.NOG }
            assertTrue(nogs.isNotEmpty())
            val drawnNogs = framing.elements.filterIsInstance<DrawingElement.Rect>().filter { it.weightMm == 0.4 }
            nogs.forEach { nog ->
                val horizontal = abs(nog.start.y - nog.end.y) < 0.00001
                assertTrue("Nogs retain their actual cuts with an obvious line weight", drawnNogs.any { rect ->
                    abs(rect.width - (if (horizontal) nog.lengthMm else nog.thicknessMm) / scale) < 0.00001 &&
                        abs(rect.height - (if (horizontal) nog.thicknessMm else nog.lengthMm) / scale) < 0.00001
                })
            }
            assertEquals("140 mm boards less 20 mm overhang give 120 mm interface centres", 2, texts(framing).count { it == "120" })
            assertTrue(texts(framing).joinToString(" ").contains("PF1 interface CL 120 mm"))
            assertTrue(framing.elements.filterIsInstance<DrawingElement.Rect>().any { rect -> rect.weightMm == 0.45 &&
                (abs(rect.width - 45.0 / scale) < 0.00001 || abs(rect.height - 45.0 / scale) < 0.00001) })
            val detail = sheets.single { it.code == "D04" }
            assertEquals("M01 stays the fourth sheet", "M01", sheets[3].code)
            assertTrue(detail.scaleDenominator!! < scale)
            val detailText = texts(detail).joinToString(" ")
            assertTrue(detailText.contains("PK1 finished 140 × 7.5 mm, 45 mm cut"))
            assertTrue(texts(detail).contains("7.5"))
            assertTrue(texts(detail).contains("45 CUT"))
            assertTrue(texts(framing).joinToString(" ").contains("enlarged detail D04"))
            assertTrue("Detail notes must end above the title block", detail.elements.filterIsInstance<DrawingElement.Text>()
                .filter { it.x == size.widthMm - 77.0 }.all { it.y < size.heightMm - 36.0 })
        }
    }

    @Test fun `bracket section cuts posts at ground and omits holes and embedment`() {
        val input = DeckInput(heightMm = 1200.0, bearer = Profiles.framing[2], joist = Profiles.framing[1],
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS)
        SheetSize.entries.forEach { size ->
            val sheet = DrawingGenerator.generate(result(input), sheetSize = size)[2]
            val labels = texts(sheet)
            val allText = labels.joinToString(" ")
            assertTrue(labels.contains("751 POST CUT"))
            assertTrue(labels.contains("1200 FINISHED HEIGHT"))
            assertFalse(allText.contains("HOLE"))
            assertFalse(allText.contains("EMBEDMENT"))
            assertFalse(allText.contains("Footing 400"))
            assertTrue(allText.contains("Bracket / anchor specification TBC"))
            assertTrue(labels.contains("EXISTING CONCRETE / GROUND LEVEL"))
            assertTrue("New footing aggregate must be absent", sheet.elements.filterIsInstance<DrawingElement.Circle>().isEmpty())
        }
    }

    @Test fun `central packer detail labels actual picture frame supports instead of invented joists`() {
        val calculated = result(DeckInput(widthMm = 2000.0, lengthMm = 540.0, actualDeckingWidthMm = 255.0,
            pictureFrame = true, orientation = FramingOrientation.LENGTHWAYS,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
        SheetSize.entries.forEach { size ->
            val detail = DrawingGenerator.generate(calculated, sheetSize = size).single { it.code == "D04" }
            val labels = texts(detail)
            assertTrue(labels.contains("PF1"))
            assertTrue(labels.contains("PF2"))
            assertFalse("The opposing picture-frame support is not a J1 joist", labels.contains("J1"))
            assertTrue(labels.contains("25"))
            assertTrue(labels.joinToString(" ").contains("1 piece of this finished size"))
            detail.elements.filterIsInstance<DrawingElement.Rect>().forEach { rect ->
                bound(detail, rect.x, rect.y)
                bound(detail, rect.x + rect.width, rect.y + rect.height)
            }
        }
    }

    @Test fun `mixed packer sizes identify their own quantity and refer to the full schedule`() {
        val calculated = result(DeckInput(decking = Profiles.decking[1], pictureFrame = true,
            orientation = FramingOrientation.LENGTHWAYS, maxJoistSpacingMm = 60.0))
        assertEquals(4, calculated.geometry.members.count { it.kind == MemberKind.PICTURE_FRAME_PACKER })
        SheetSize.entries.forEach { size ->
            val detail = DrawingGenerator.generate(calculated, sheetSize = size).single { it.code == "D04" }
            val note = texts(detail).joinToString(" ")
            assertTrue(note.contains("PK1 finished 140 × 7.5 mm, 45 mm cut. 2 pieces of this finished size"))
            assertTrue(note.contains("Other packer widths occur"))
            assertTrue("All packer notes must fit above the title block", detail.elements.filterIsInstance<DrawingElement.Text>()
                .filter { it.x == size.widthMm - 77.0 }.all { it.y < size.heightMm - 36.0 })
        }
    }

    @Test fun `picture frame callouts remain distinct and bracket ground label clears post outlines`() {
        listOf(FramingOrientation.LENGTHWAYS, FramingOrientation.WIDTHWAYS).forEach { orientation ->
            val calculated = result(DeckInput(decking = Profiles.decking[1], pictureFrame = true,
                orientation = orientation, pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS))
            SheetSize.entries.forEach { size ->
                val sheets = DrawingGenerator.generate(calculated, sheetSize = size)
                val framingText = sheets[0].elements.filterIsInstance<DrawingElement.Text>()
                val pf = framingText.single { it.text == "PF1" }
                val pk = framingText.single { it.text == "PK1" }
                val nog = framingText.single { it.text == "N1" }
                assertLabelsSeparate(pf, pk)
                assertLabelsSeparate(pf, nog)
                assertLabelsSeparate(pk, nog)
                val deckingText = sheets[1].elements.filterIsInstance<DrawingElement.Text>()
                val frame = deckingText.single { it.text == "PF" }
                val overhang = deckingText.single { it.text.endsWith(" OVERHANG") }
                assertLabelsSeparate(frame, overhang)
                val section = sheets[2]
                val ground = section.elements.filterIsInstance<DrawingElement.Text>()
                    .single { it.text == "EXISTING CONCRETE / GROUND LEVEL" }
                val scale = section.scaleDenominator!!
                val posts = section.elements.filterIsInstance<DrawingElement.Rect>().filter { rect ->
                    abs(rect.width - 125.0 / scale) < 0.00001 &&
                        abs(rect.height - calculated.geometry.pileAboveGroundMm / scale) < 0.00001
                }
                assertTrue(posts.isNotEmpty())
                posts.forEach { post -> assertTrue("Ground text must sit below the post and concrete surface notation",
                    ground.y - ground.sizeMm > post.y + post.height + 2.3) }
            }
        }
    }

    /** Conservative Latin label bounds; the regression concerns placement, independent of exact font shaping. */
    private fun assertLabelsSeparate(first: DrawingElement.Text, second: DrawingElement.Text) {
        fun left(text: DrawingElement.Text): Double {
            val width = text.text.length * text.sizeMm * 0.65
            return text.x - when (text.align) { TextAlign.LEFT -> 0.0; TextAlign.CENTER -> width / 2.0; TextAlign.RIGHT -> width }
        }
        val horizontallySeparate = left(first) + first.text.length * first.sizeMm * 0.65 < left(second) ||
            left(second) + second.text.length * second.sizeMm * 0.65 < left(first)
        val verticallySeparate = first.y + first.sizeMm * 0.2 < second.y - second.sizeMm ||
            second.y + second.sizeMm * 0.2 < first.y - first.sizeMm
        assertTrue("Drawing labels '${first.text}' and '${second.text}' must not collide", horizontallySeparate || verticallySeparate)
    }

    private fun texts(sheet: DrawingSheet) = sheet.elements.filterIsInstance<DrawingElement.Text>().map { it.text }
    private fun bound(sheet: DrawingSheet, x: Double, y: Double) {
        assertTrue("${sheet.code} coordinate ($x,$y) must be finite", x.isFinite() && y.isFinite())
        assertTrue("${sheet.code} x $x outside ${sheet.widthMm}", x in 0.0..sheet.widthMm)
        assertTrue("${sheet.code} y $y outside ${sheet.heightMm}", y in 0.0..sheet.heightMm)
    }
}
