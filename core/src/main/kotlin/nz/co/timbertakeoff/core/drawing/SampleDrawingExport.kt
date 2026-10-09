package nz.co.timbertakeoff.core.drawing

import java.io.File
import nz.co.timbertakeoff.core.*

/** Inspectable examples generated from the real engine; never used as application drawings. */
fun main(args: Array<String>) {
    val directory = File(args.firstOrNull() ?: "build/sample-drawings").apply { mkdirs() }
    val examples = listOf(
        "" to DeckInput(),
        "decking-setout-" to DeckInput(widthMm = 535.0, lengthMm = 4800.0,
            decking = Profiles.decking[1], orientation = FramingOrientation.LENGTHWAYS),
        "picture-frame-" to DeckInput(decking = Profiles.decking[1], pictureFrame = true),
        "bracket-picture-frame-" to DeckInput(decking = Profiles.decking[1], pictureFrame = true,
            pileConnection = PileConnection.EXISTING_CONCRETE_BRACKETS, orientation = FramingOrientation.WIDTHWAYS)
    )
    for ((prefix, input) in examples) {
        val result = (DeckCalculator.calculate(input) as CalculationOutcome.Success).result
        for (size in SheetSize.entries) {
            val sheets = DrawingGenerator.generate(result, DrawingTitle("Example garden deck", "Example client", "Freestanding timber deck"), size)
            for (sheet in sheets) {
                File(directory, "$prefix${size.name}-${sheet.code}.svg").writeText(toSvg(sheet))
            }
        }
    }
    println("Generated calculated A3/A4 vector sheets in ${directory.absolutePath}")
}

private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun toSvg(sheet: DrawingSheet): String = buildString {
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${sheet.widthMm}mm\" height=\"${sheet.heightMm}mm\" viewBox=\"0 0 ${sheet.widthMm} ${sheet.heightMm}\">\n")
    append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n")
    for (element in sheet.elements) {
        val shape = when (element) {
            is DrawingElement.Line -> "<line x1=\"${element.x1}\" y1=\"${element.y1}\" x2=\"${element.x2}\" y2=\"${element.y2}\" stroke=\"black\" stroke-width=\"${element.weightMm}\"${if(element.dashed) " stroke-dasharray=\"2 1\"" else ""}/>"
            is DrawingElement.Rect -> "<rect x=\"${element.x}\" y=\"${element.y}\" width=\"${element.width}\" height=\"${element.height}\" stroke=\"black\" stroke-width=\"${element.weightMm}\" fill=\"${if(element.fill) "black" else "none"}\"${if(element.dashed) " stroke-dasharray=\"2 1\"" else ""}/>"
            is DrawingElement.Circle -> "<circle cx=\"${element.x}\" cy=\"${element.y}\" r=\"${element.radiusMm}\" stroke=\"black\" stroke-width=\"${element.weightMm}\" fill=\"${if(element.fill) "black" else "none"}\"/>"
            is DrawingElement.Polygon -> "<polygon points=\"${element.points.joinToString(" ") { "${it.x},${it.y}" }}\" stroke=\"black\" stroke-width=\"${element.weightMm}\" stroke-linejoin=\"miter\" fill=\"${if(element.fill) "black" else "none"}\"/>"
            is DrawingElement.Text -> {
                val anchor = when(element.align) { TextAlign.LEFT -> "start"; TextAlign.CENTER -> "middle"; TextAlign.RIGHT -> "end" }
                "<text x=\"${element.x}\" y=\"${element.y}\" font-family=\"Arial, sans-serif\" font-size=\"${element.sizeMm}\" text-anchor=\"$anchor\" font-weight=\"${if(element.bold) "bold" else "normal"}\" transform=\"rotate(${element.rotationDegrees} ${element.x} ${element.y})\">${xml(element.text)}</text>"
            }
        }
        append(shape).append('\n')
    }
    append("</svg>\n")
}
