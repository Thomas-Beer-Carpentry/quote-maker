package nz.co.timbertakeoff.data

import java.math.BigDecimal
import nz.co.timbertakeoff.core.CalculationOutcome
import nz.co.timbertakeoff.core.DeckInput
import nz.co.timbertakeoff.core.DeckingProfile
import nz.co.timbertakeoff.core.FramingOrientation
import nz.co.timbertakeoff.core.Profiles
import nz.co.timbertakeoff.core.TaskLibrary
import org.json.JSONObject

sealed class DraftInputResult {
    data class Valid(val input: DeckInput) : DraftInputResult()
    data class Invalid(val errors: List<String>) : DraftInputResult()
}

/**
 * Editable input is separate from numerical calculation input. Even an empty field or "12."
 * survives reopening a job; it is never silently replaced with the last valid number.
 * Preset names identify the fixed V1 profile catalogue, with species separately selectable.
 */
data class DeckDraft(
    val widthMm: String = numericText(defaultInput.widthMm),
    val lengthMm: String = numericText(defaultInput.lengthMm),
    val heightMm: String = numericText(defaultInput.heightMm),
    val bearerProfileId: String = defaultInput.bearer.name,
    val joistProfileId: String = defaultInput.joist.name,
    val maxJoistSpacingMm: String = numericText(defaultInput.maxJoistSpacingMm),
    val deckingProfileId: String = defaultInput.decking.nominal,
    val actualDeckingWidthMm: String = numericText(defaultInput.actualDeckingWidthMm),
    val deckingSpecies: String = defaultInput.deckingSpecies,
    val orientation: FramingOrientation = defaultInput.orientation,
    val overhangMm: String = numericText(defaultInput.overhangMm),
    val screwSpecification: String = defaultInput.screwSpecification,
    val concreteYieldM3PerBag: String = numericText(defaultInput.concreteYieldM3PerBag)
) {
    fun withDeckingProfile(profile: DeckingProfile): DeckDraft = copy(
        deckingProfileId = profile.nominal,
        actualDeckingWidthMm = numericText(profile.finishedWidthMm),
        deckingSpecies = profile.species
    )

    fun toInput(): DraftInputResult {
        val errors = mutableListOf<String>()
        fun parse(raw: String, label: String, allowZero: Boolean = false): Double {
            val number = raw.trim().toDoubleOrNull()
            if (number == null || !number.isFinite()) {
                errors += "$label must be a finite number."
                return 0.0
            }
            if (number < 0.0 || (!allowZero && number == 0.0)) {
                errors += "$label must be ${if (allowZero) "zero or greater" else "greater than zero"}."
            }
            return number
        }
        val width = parse(widthMm, "Deck width (mm)")
        val length = parse(lengthMm, "Deck length (mm)")
        val height = parse(heightMm, "Deck height (mm)")
        val spacing = parse(maxJoistSpacingMm, "Maximum joist spacing (mm)")
        val boardWidth = parse(actualDeckingWidthMm, "Actual finished decking width (mm)")
        val overhang = parse(overhangMm, "Decking overhang (mm)", allowZero = true)
        val yield = parse(concreteYieldM3PerBag, "Concrete yield per 20 kg bag (m³)")
        val bearer = Profiles.framing.find { it.name == bearerProfileId }
        val joist = Profiles.framing.find { it.name == joistProfileId }
        val decking = Profiles.decking.find { it.nominal == deckingProfileId }
        if (bearer == null) errors += "Select a supported bearer profile."
        if (joist == null) errors += "Select a supported joist profile."
        if (decking == null) errors += "Select a supported decking profile."
        if (deckingSpecies.isBlank()) errors += "Enter the decking species."
        if (screwSpecification.isBlank()) errors += "Enter the decking screw specification."
        if (errors.isNotEmpty()) return DraftInputResult.Invalid(errors)
        return DraftInputResult.Valid(DeckInput(
            widthMm = width,
            lengthMm = length,
            heightMm = height,
            bearer = checkNotNull(bearer),
            joist = checkNotNull(joist),
            maxJoistSpacingMm = spacing,
            decking = checkNotNull(decking),
            actualDeckingWidthMm = boardWidth,
            deckingSpecies = deckingSpecies.trim(),
            orientation = orientation,
            overhangMm = overhang,
            screwSpecification = screwSpecification.trim(),
            concreteYieldM3PerBag = yield
        ))
    }

    fun calculate(typeId: String = "deck.freestanding.v1"): CalculationOutcome = when (val parsed = toInput()) {
        is DraftInputResult.Valid -> TaskLibrary.calculate(typeId, parsed.input)
        is DraftInputResult.Invalid -> CalculationOutcome.Invalid(parsed.errors)
    }

    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("widthMm", widthMm)
        put("lengthMm", lengthMm)
        put("heightMm", heightMm)
        put("bearerProfileId", bearerProfileId)
        put("joistProfileId", joistProfileId)
        put("maxJoistSpacingMm", maxJoistSpacingMm)
        put("deckingProfileId", deckingProfileId)
        put("actualDeckingWidthMm", actualDeckingWidthMm)
        put("deckingSpecies", deckingSpecies)
        put("orientation", orientation.name)
        put("overhangMm", overhangMm)
        put("screwSpecification", screwSpecification)
        put("concreteYieldM3PerBag", concreteYieldM3PerBag)
    }.toString()

    companion object {
        private val defaultInput = DeckInput()

        fun fromInput(input: DeckInput): DeckDraft = DeckDraft(
            widthMm = numericText(input.widthMm),
            lengthMm = numericText(input.lengthMm),
            heightMm = numericText(input.heightMm),
            bearerProfileId = input.bearer.name,
            joistProfileId = input.joist.name,
            maxJoistSpacingMm = numericText(input.maxJoistSpacingMm),
            deckingProfileId = input.decking.nominal,
            actualDeckingWidthMm = numericText(input.actualDeckingWidthMm),
            deckingSpecies = input.deckingSpecies,
            orientation = input.orientation,
            overhangMm = numericText(input.overhangMm),
            screwSpecification = input.screwSpecification,
            concreteYieldM3PerBag = numericText(input.concreteYieldM3PerBag)
        )

        /** Unknown formats fail explicitly; corrupt inputs are never silently recalculated as defaults. */
        fun fromJson(json: String): DeckDraft {
            val data = JSONObject(json)
            require(data.optInt("schemaVersion", 1) == 1) { "This saved task uses an unsupported input format." }
            val defaults = DeckDraft()
            return DeckDraft(
                widthMm = data.optString("widthMm", defaults.widthMm),
                lengthMm = data.optString("lengthMm", defaults.lengthMm),
                heightMm = data.optString("heightMm", defaults.heightMm),
                bearerProfileId = data.optString("bearerProfileId", defaults.bearerProfileId),
                joistProfileId = data.optString("joistProfileId", defaults.joistProfileId),
                maxJoistSpacingMm = data.optString("maxJoistSpacingMm", defaults.maxJoistSpacingMm),
                deckingProfileId = data.optString("deckingProfileId", defaults.deckingProfileId),
                actualDeckingWidthMm = data.optString("actualDeckingWidthMm", defaults.actualDeckingWidthMm),
                deckingSpecies = data.optString("deckingSpecies", defaults.deckingSpecies),
                orientation = FramingOrientation.valueOf(data.optString("orientation", defaults.orientation.name)),
                overhangMm = data.optString("overhangMm", defaults.overhangMm),
                screwSpecification = data.optString("screwSpecification", defaults.screwSpecification),
                concreteYieldM3PerBag = data.optString("concreteYieldM3PerBag", defaults.concreteYieldM3PerBag)
            )
        }

        private fun numericText(number: Double): String = BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
    }
}
