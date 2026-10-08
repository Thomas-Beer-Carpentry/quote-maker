package nz.co.timbertakeoff.core

import kotlin.math.hypot

/** All geometry and stored dimensions are millimetres. Profiles are actual dimensions. */
data class TimberProfile(val name: String, val depthMm: Double, val thicknessMm: Double, val species: String = "H3.2 treated radiata pine") {
    val label get() = "$name · $species"
}
data class DeckingProfile(val nominal: String, val finishedWidthMm: Double, val thicknessMm: Double, val species: String)
enum class FramingOrientation { AUTOMATIC, LENGTHWAYS, WIDTHWAYS }
data class DeckInput(
    val widthMm: Double = 3600.0,
    val lengthMm: Double = 4800.0,
    val heightMm: Double = 1000.0,
    val bearer: TimberProfile = Profiles.framing[1],
    val joist: TimberProfile = Profiles.framing[0],
    val maxJoistSpacingMm: Double = 450.0,
    val decking: DeckingProfile = Profiles.decking[0],
    val actualDeckingWidthMm: Double = decking.finishedWidthMm,
    val deckingSpecies: String = decking.species,
    val orientation: FramingOrientation = FramingOrientation.AUTOMATIC,
    val overhangMm: Double = 20.0,
    val screwSpecification: String = "10g × 65 mm",
    val concreteYieldM3PerBag: Double = 0.01
)
object Profiles {
    val framing = listOf(TimberProfile("140 × 45 mm", 140.0, 45.0), TimberProfile("190 × 45 mm",190.0,45.0), TimberProfile("240 × 45 mm",240.0,45.0), TimberProfile("190 × 70 mm",190.0,70.0))
    val decking = listOf(DeckingProfile("90 × 19 mm",90.0,19.0,"H3.2 treated radiata pine"), DeckingProfile("140 × 19 mm",140.0,19.0,"H3.2 treated radiata pine"), DeckingProfile("90 × 21 mm",90.0,21.0,"Kwila"))
}
data class Point(val x: Double, val y: Double)
enum class MemberKind { BEARER, JOIST, BOUNDARY, NOG }
data class TimberMember(val id: String, val kind: MemberKind, val start: Point, val end: Point, val thicknessMm: Double, val profile: TimberProfile, val runId: String, val layer: Int = 0) {
    val lengthMm get() = hypot(end.x - start.x, end.y - start.y)
}
data class Pile(val id: String, val position: Point, val lengthMm: Double)
data class MemberJoin(val position: Point, val kind: MemberKind, val runId: String, val layer: Int)
data class DeckBoard(val index: Int, val origin: Point, val widthMm: Double, val lengthMm: Double, val runsAlongX: Boolean)
data class DeckGeometry(
    val orientation: FramingOrientation,
    val bearerRunMm: Double,
    val joistRunMm: Double,
    val bearerPositionsMm: List<Double>,
    val pilePositionsMm: List<Double>,
    val joistPositionsMm: List<Double>,
    val blockingRowsMm: List<Double>,
    val members: List<TimberMember>,
    val piles: List<Pile>,
    val joins: List<MemberJoin>,
    val boards: List<DeckBoard>,
    val actualJoistSpacingMm: Double,
    val actualBearerSpacingMm: Double,
    val joistCantileverMm: Double,
    val deckingGapMm: Double,
    val startingBoardWidthMm: Double,
    val pileAboveGroundMm: Double,
    val excavationM3: Double,
    val concreteM3: Double
) {
    /** u follows bearers, v follows joists; map to outside framing x/y coordinates. */
    fun point(u: Double, v: Double): Point = if (orientation == FramingOrientation.WIDTHWAYS) Point(u,v) else Point(v,u)
}
enum class MaterialCategory(val title: String) { PILES("Piles"), BEARERS("Bearers"), JOISTS("Joists and boundary joists"), NOGS("Nogs / blocking"), DECKING("Decking"), CONCRETE("Concrete"), FIXINGS("Fixings") }
data class MaterialKey(val type: String, val specification: String, val unit: String)
data class MaterialLine(val category: MaterialCategory, val key: MaterialKey, val quantity: Double, val cutLengthsMm: List<Double> = emptyList()) {
    val pieceCount get() = cutLengthsMm.size
}
data class OrientationAlternative(val orientation: FramingOrientation, val timberLengthMm: Double?, val pileCount: Int?, val errors: List<String> = emptyList())
data class DeckResult(val input: DeckInput, val geometry: DeckGeometry, val materials: List<MaterialLine>, val alternatives: List<OrientationAlternative>, val recommended: FramingOrientation, val notes: List<String> = emptyList())
sealed class CalculationOutcome {
    data class Success(val result: DeckResult): CalculationOutcome()
    data class Invalid(val errors: List<String>, val alternatives: List<OrientationAlternative> = emptyList()): CalculationOutcome()
}
object MaterialConsolidator {
    /** Only identical specifications and units combine; cut lengths remain inspectable. */
    fun consolidate(lines: List<MaterialLine>): List<MaterialLine> = lines.groupBy { it.key }.map { (key, group) ->
        MaterialLine(group.first().category,key,group.sumOf { it.quantity },group.flatMap { it.cutLengthsMm })
    }.sortedWith(compareBy({it.category.ordinal},{it.key.specification},{it.key.unit}))
}
data class TaskDefinition(val id: String, val title: String, val description: String)
object TaskLibrary {
    val definitions = listOf(TaskDefinition("deck.freestanding.v1", "Deck — Freestanding Timber", "Local material takeoff and preliminary set-out drawings"))
    fun calculate(typeId: String, input: DeckInput): CalculationOutcome = when(typeId) {
        "deck.freestanding.v1" -> DeckCalculator.calculate(input)
        else -> CalculationOutcome.Invalid(listOf("Task type '$typeId' is not supported by this version."))
    }
}
