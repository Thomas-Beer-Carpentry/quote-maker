# Quote Maker — Timber Takeoff

Native Android, offline carpentry estimating. Version 1 calculates freestanding timber decks with dynamic architectural plans and exact material takeoffs. Pricing and quoting are intentionally outside this version.

**All outputs are preliminary estimating / set-out information using provisional development assumptions. They are not verified construction documentation or structural design checks. No compliance with NZS 3604, the New Zealand Building Code, or any structural standard is claimed.**

## Build from this repository

Every push to `main` and pull request runs [Android build and tests](https://github.com/Thomas-Beer-Carpentry/quote-maker/actions/workflows/android.yml). Open **Actions → Android build and tests → the latest successful run → Artifacts → quote-maker-debug-apk**, unzip the download, and install `app-debug.apk` on an Android 8.0+ device. Android may ask you to allow installations from your browser/file manager. This is a development APK, not a Play Store release.

The workflow compiles the app, runs calculation and drawing tests, runs Android lint, and produces real vector SVG examples from the calculation engine. A second job runs Room persistence, phone workflow and PDF tests on an Android emulator. Test results and sample plans are separate downloadable artifacts. A source commit alone does not establish that its checks passed; inspect the run status.

## Android Studio

1. Clone this repository and open its root in Android Studio Meerkat (2024.3.1) or newer with support for Android Gradle Plugin 8.9.2.
2. Use JDK 17 or 21 for Gradle. Install Android SDK Platform 35 and Build Tools 35.0.0 through SDK Manager.
3. Sync Gradle, select the `app` run configuration and run on an API 26+ phone/emulator.
4. No account, server, API keys or internet connection is needed by the installed application. The first development build downloads Gradle and Maven dependencies.

Command line:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :core:sampleDrawings
```

The complete standard Gradle wrapper is included and targets 8.13. Android Studio and CI use the same root project.

## Use

Create a client, add a job, then add one or more named **Deck — Freestanding Timber** tasks. Each task has its own raw inputs, calculation, sheets and takeoff. The drawing workspace opens first; pinch to zoom, drag to pan and use **Fit sheet** to reset. Parameters autosave locally, including unfinished/invalid entries. Invalid specifications show their constraint conflicts and cannot export a stale plan.

Width and length are outside framing dimensions in millimetres. Height is to the finished decking surface above flat ground. Select actual timber profiles and actual finished decking width. Automatic orientation compares exact framing timber length, then pile count; both options and rejected alternatives remain visible. Manual overrides retain validation. Expand material categories for cuts and quantities; job consolidation combines only identical specifications and units.

Export the combined D01 framing, D02 decking, D03 section and paginated M01 takeoff as vector PDF using Android's file picker. A3 landscape is preferred; A4 is available. Android Print uses the same vector scene and respects paper settings and printer margins. Print at the indicated scale. Drawings are read-only and are always derived from the same result as the schedule.

## Architecture

- `core/`: pure Kotlin deterministic `DeckCalculator`, millimetre geometry, profiles, task registry, materials/consolidation, invariant validator and vector drawing scene. No Android/database dependencies.
- `app/data/`: Room client, job and task entities; foreign keys; observable repository; JSON `DeckDraft` preserving raw edits. Only the latest inputs are stored. Calculations and sheets are regenerated.
- `app/ui/`: Jetpack Compose phone workflows, ordered autosave in an activity ViewModel, live recalculation, orientation comparison, grouped schedules, read-only pan/zoom viewport.
- `app/drawing/`: shared Android Canvas renderer, vector `PdfDocument` export, system print adapter. Core primitives use physical paper millimetres, line weights and a minimum 2.2 mm text height.
- `core/src/test/`: independent expected quantities, spacing/cantilever boundaries, invalid configurations, supported/staggered joins, profile/orientation changes, ripping, decimal concrete rounding, consolidation, seeded invariants, and sheet/pagination tests.
- `app/src/androidTest/`: real Room persistence and Android workflow/PDF checks.

Additional task types have a registry seam and independent task records; future pricing, stock optimisation, structural rules and cloud features are not implemented.

## Construction assumptions and limitations

Read [the complete calculation rules](docs/calculation-rules.md) for the coordinate/corner arrangement, doubled boundary footprint, bearer optimisation, supported cut algorithm, nog staggering, board layout, quantities and fixing conventions.

- Profiles are actual dimensions and estimating selections, with no structural span/treatment verification. Pile treatment has not been specified.
- Cantilevers reference bearer pair centrelines and are validated from both exterior boundaries and internal joist cut ends. End boundaries fit between full-length doubled side boundaries to avoid overlapping corners.
- End boundary members run parallel to bearers. Layouts requiring an end boundary cut over 6,000 mm are rejected pending a supported splice detail; automatic orientation can sometimes resolve this. No unsupported joins are invented.
- Separate 400 mm square footing holes cannot overlap. Some small/narrow layouts are rejected until a merged-footing detail is supplied. This avoids incorrect excavation/concrete volumes.
- Decking is treated as continuous runs, without joins, waste or stock-length optimisation. Excessive overhangs producing wholly unsupported outer boards are rejected.
- Bearer lamination nail stations include both ends at no more than 600 mm spacing. Joist spliced ends each receive two nails. Decking screws count two per distinct perpendicular physical joist contact, including both members of doubled side boundaries. Parallel end rail contact has no supplied fastening interval and is excluded; this detailing assumption needs confirmation before construction use.
- Concrete uses hole volume less the embedded pile displacement. The initial 20 kg bag yield is 0.010 m³; change it to the product manufacturer's actual yield.
- Exact quantities are retained with no purchase/waste allowances. Display decimals are formatting only. Unlike units have no numerical grand total.
- Geometry limits bound on-phone memory: dimensions 30,000 mm, height 10,000 mm, joist spacing 40–1,800 mm, finished board width 60–400 mm, overhang 0–300 mm. These are application limits, not structural permissions.
- Data stays in Room on the device; Android backups are disabled. Uninstalling/clearing the app removes local client/job data. There is no revision history, cloud sync or data-transfer feature in V1.

House-attached decks, structural compliance, pricing/labour, margins, inventory, accounts and cloud services are excluded.
