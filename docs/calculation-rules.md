# Version 1 calculation rules

These rules implement the supplied provisional estimating brief. Outputs are **preliminary estimating / set-out information**. They are not structural design verification, verified construction documentation, or a claim of compliance with NZS 3604 or the New Zealand Building Code. Profile choices do not constitute a span check or treatment recommendation.

The calculation engine has no Android, database, file-system, or network dependency. `DeckCalculator.calculate(DeckInput)` returns either a complete valid result or explanatory constraints. Coordinates and cut lengths remain millimetres; material schedules convert exact lengths to lineal metres. There are no waste factors, stock purchasing schedules, pricing, or labour calculations.

## Coordinate and corner convention

`u` follows the bearers and decking; `v` follows the joists. The bearer run is `B`; the joist run is `J`. LENGTHWAYS maps `(u,v)` to outside framing `(x=v,y=u)`; WIDTHWAYS maps to `(x=u,y=v)`. Width and length are outside timber framing dimensions.

With joist thickness `t`, the two parallel side boundary pairs have member centrelines at `u=t/2, 1.5t, B-1.5t, B-t/2`. Each member runs the entire `J`. The two perpendicular end boundary pairs occupy `v=0..2t` and `J-2t..J`; their members run between the side boundary pairs, `u=2t..B-2t`. This gives eight full boundary runs with no overlapping corner timber. Interior joists fit between the end boundary pairs, `v=2t..J-2t`.

The centre-to-centre distance between the inner side boundaries is divided into the minimum number of equal intervals that satisfy the selected maximum joist spacing. All side boundary centres and internal joists are included in the geometry. Members may not overlap; a maximum spacing below profile thickness is rejected. `actualJoistSpacingMm` is the largest actual centre spacing, including doubled boundary members.

## Bearers, piles and splices

Bearer positions are the centrelines of doubled bearer pairs. Both the outside framing edge and the actual interior joist cut end must have a 90–300 mm cantilever to the first/last bearer centreline. Short decks use one centred bearer if both end cantilevers remain valid. For longer decks, the minimum possible number of lines is established using 300 mm outside cantilevers. That count is then spread symmetrically as widely as permitted, with equal spacing capped at 1,800 mm and outside cantilevers no smaller than `2t+90`. This first minimises lines, then maximises practical spacing. For the default 3,600 mm joist run, bearer pair centrelines are 180, 1,800 and 3,420 mm. Doubled bearers and physical piles cannot overlap.

Each doubled bearer consists of two distinct physical members, whose centrelines are offset from the bearer-pair centre by half the bearer thickness. Bearers run the full `B`. Piles are centred below each pair, first and last at exactly 200 mm from bearer ends. The intervening pile span is divided evenly into intervals of at most 1,300 mm. A 400 mm bearer uses one central pile, giving 200 mm cantilevers at both ends; shorter bearers are invalid.

Cuts at or below 6,000 mm remain continuous. Longer bearer runs splice only at calculated pile positions. A deterministic dynamic program first minimises piece count, then the sum of squared cut lengths to favour balanced cuts. Layer 0 is calculated first; its splice positions are excluded from layer 1. This gives staggered splices without changing pile spacing. The evenly spaced supports have enough intermediate positions to stagger the layers in accepted layouts. If a supported decomposition cannot be found, the layout is rejected.

Long joists use the same supported-cut algorithm with bearer centrelines as allowed splice positions. Joist splices may coincide between adjacent joists. Exact cut lengths include both cantilevered portions. The maximum 6,000 mm cut applies to all bearers, joists and boundary joists.

**Unresolved boundary detail:** perpendicular end boundary joists run parallel to the bearers and outside the bearer support lines. A supported splice arrangement for these members has not been supplied. An orientation whose perpendicular boundary cut `B-4t` exceeds 6,000 mm is rejected explicitly; automatic orientation may select a valid alternative. The app does not invent unsupported joins or additional supports. Supporting arbitrary decks wider than this limit requires an agreed construction detail.

Pile above-ground length is `finishedHeight - deckingThickness - joistDepth - bearerDepth`. For post holes with concrete, add 500 mm embedment; negative above-ground length is rejected. For brackets on existing concrete, use only the above-ground length and require a positive cut. Bracket lift is taken as zero, following the specified ground-to-bottom-of-bearer measurement. Piles are 125 × 125 mm; treatment is unspecified because the brief does not provide it. Flat, level ground is assumed. Bracket mode lists one bracket per pile, with bracket/anchor specification to be confirmed; no anchor size, count, existing slab capacity or structural suitability is inferred.

**Unresolved overlapping-footing detail:** the brief gives individual 400 × 400 mm holes but no merged-footing arrangement. In post-hole mode, a layout with hole centrelines less than 400 mm apart is rejected rather than double-counting excavation and concrete or inventing a combined footing. Some narrow/small dimensions are therefore unsupported, although a 400 × 400 mm deck with one bearer and one pile is valid. Bracket mode omits the hole-overlap check but retains physical pile/bearer overlap checks. A combined-footing construction detail would be needed to lift the post-hole restriction.

## Blocking

At least one intermediate nog row is supplied between the end boundaries. More rows are added as required. End boundary inner members count as terminal restraint rows. Base row positions are distributed evenly between their centrelines. Adjacent bays alternate a half-joist-thickness offset in opposite directions, allowing end-nailing. To keep even the staggered end gaps below 1,800 mm, the row subdivision uses `1,800 - t/2` as its target interval. Every actual bay is verified after staggering. Nogs stay within the clear interior and have exact cut length `rightJoistCentre - leftJoistCentre - t`. Zero-clearance gaps inside doubled boundary pairs do not receive nogs. Ordinary decking layouts have no extra perimeter decking support blocking. Picture-frame support is described below.

## Decking

Finished dimensions add the selected uniform overhang to all four sides. Boards run along `u`, perpendicular to joists, from `-overhang` to `B+overhang`. Their count covers `J+2*overhang` using actual finished board width.

All full-width arrangements are searched first. For `n` boards, the equal gap is `(finishedCoverage - n*width)/(n-1)` and must be 4–6 mm. The valid full-width arrangement closest to a 5 mm gap is preferred, with fewer boards breaking ties. Only if no full-width solution exists does the engine search arrangements with one starting board ripped to 60 mm through full width. It chooses the feasible equal gap closest to 5 mm, then the widest starting board, then fewer boards. Impossible coverage is an explicit error. A single board has no inter-board gap.

Each board is treated as continuous for drawing and exact length, even when longer than available stock. Decking joins and stock lengths are deliberately outside V1. Ripping does not change the lineal quantity of the board run; the first board is separately labelled with its finished ripped width. A board entirely beyond the framing, with no positive contact area, is rejected with an overhang/layout conflict. This prevents unattached outer courses without inventing a structural allowable overhang.

### Picture-frame option

Four full-width perimeter boards form mitred polygons around the finished rectangle. Mitres meet at the corners with no additional corner gap; the computed 4–6 mm gap applies at every infill/frame interface and between infill boards. Perimeter boards remain full width. Cut lengths are longitudinal **long-point to long-point** lengths: two at `B + 2*overhang` and two at `J + 2*overhang`. These are the physical mitred piece lengths, without a waste factor.

For `n` infill boards, solve `J + 2*overhang = 2*boardWidth + sum(infillWidths) + (n+1)*gap`. Search full-width infill first, then allow only the first infill board to rip to 60 mm through full width, using the same deterministic gap preference as ordinary decking. Each infill cut is `B + 2*overhang - 2*boardWidth - 2*gap`. Gaps at both longitudinal butt ends equal the selected board gap. Reject layouts with no valid infill, nonpositive cuts, unsupported interfaces or overlapping framing.

Let `d = actualBoardWidth - overhang`. Backing centrelines for the perpendicular perimeter boards and the adjacent infill ends are `u=d` and `u=B-d`. For 140 mm boards and 20 mm overhang, `d=120 mm` exactly. Backing uses the joist profile and runs continuously between the doubled end boundaries; any split is supported by a bearer and every cut is at most 6,000 mm. It is included in the nogs/blocking category and the framing-efficiency comparison. Reuse an existing doubled side boundary where it already backs the interface rather than adding overlapping timber. Otherwise retain the fixed backing centres and subdivide the intervening joist zones evenly to the selected maximum spacing. Ordinary staggered nog rows fit between the resulting member centres.

Where a picture-frame backing line leaves a clear edge strip narrower than the joist thickness, retain blocking positions using **ripped timber packers**, as specified by the user. For 140/20 decking with 45 mm joists, the 120 mm support centre leaves a 7.5 mm strip beside the 90 mm doubled boundary. Packers retain joist depth, rip to the clear strip width, and are cut to the joist thickness (45 mm here) along the support direction. They appear at every corresponding staggered nog position. The source joist-profile timber is included in the consolidated takeoff with one source cut per packer; no multiple-strip stock optimisation is applied. The plans and calculation notes identify finished rip width, cut length and count. Thin-packer fixing specifications remain to be confirmed; they are listed as fixing sets rather than assigning four 90 mm end nails to a 7.5 mm strip.

Fixings for these backing members include four end nails per cut and two nails at each bearer intersection. For infill decking, count two screws at each distinct perpendicular joist or backing contact, using its physical footprint so shortened infill does not receive phantom screws at distant boundary members. For each perimeter board, place estimating screw stations on its continuous supported rail, between the doubled corner boundaries (`2t` to `run-2t`), including both endpoints. Distribute them evenly with no interval exceeding the selected maximum joist spacing and count two screws per station. This station rule was confirmed by the user; it remains a provisional estimating convention.

## Concrete and fixings

In post-hole mode, each footing hole is 400 × 400 × 600 mm: excavation is `0.096 m³` per pile. Embedded pile displacement is `0.125*0.125*0.5 = 0.0078125 m³`, giving concrete `0.0881875 m³` per pile. Only the embedded 500 mm displaces concrete. Bag count is `ceil(totalConcrete / configuredYieldPer20kgBag)`, evaluated with decimal arithmetic so a mathematically exact bag boundary does not produce a phantom extra bag. Concrete yield is configurable and independent of the profile selection. In bracket mode, excavation and concrete are zero and all three excavation/concrete/bag schedule lines are omitted. A preserved concrete-yield entry does not constrain a bracket-only calculation.

The following fastening counting conventions resolve quantities without inventing fastener geometry. Fixings appear only in the takeoff:

- Four 90 mm Paslode nails per doubled bearer/pile connection.
- Four lamination nails at each station along a doubled bearer, including both ends. Stations are evenly distributed at no more than 600 mm, giving `4*(ceil(B/600)+1)` nails per bearer pair.
- Two nails per physical parallel joist segment/bearer-pair intersection. Both meeting ends at a supported joist splice receive two nails each. Parallel end boundary members do not intersect bearer lines.
- Four nails per nog.
- Two decking screws per board crossing of each distinct perpendicular physical joist, including both members of doubled side boundaries. Internal joists are counted only when their cut span actually overlaps that board. A joist run is counted once even when spliced. Perpendicular end boundaries run parallel to decking and have no discrete board crossing; an additional fastening interval for that continuous contact has not been supplied.

These fixing assumptions should be confirmed before extending construction detailing. The default decking screw is 10g × 65 mm and its specification remains editable.

## Orientation and consolidation

Both framing orientations are calculated and independently validated. Invalid alternatives display their constraints. Among valid alternatives, automatic selection compares the **total exact length of bearer, joist, boundary and nog timber**, then pile count, then chooses LENGTHWAYS on a tie. It never substitutes minimum piece count for timber use. Decking, pile lengths and fixings are reported for both the selected orientation and resulting takeoff; they are not part of the timber-efficiency ranking specified for V1. Users may force either orientation, but an invalid override remains an explicit error.

Takeoff timber lengths come from generated member cuts. Consolidation combines only identical material type, specification and unit, preserving all individual cut lengths. Joists and nogs with an identical timber profile may share one consolidated timber line. Decking species, finished width, thickness and nominal profile stay in its identity. Concrete bag yield and screw specification also remain part of their identities. Unlike units are never summed into a numerical grand total.

## Resource limits and verification

To bound on-device geometry allocation, V1 accepts dimensions up to 30,000 mm, height up to 10,000 mm, joist spacing 40–1,800 mm, actual decking width 60–400 mm, and overhang 0–300 mm. Profiles permit depth 45–600 mm and thickness 20–125 mm; decking thickness is 1–100 mm. These are application resource/validation limits, not structural span rules. Configured bag yield must be finite and between 0.000001 and 1 m³ to bound whole-bag quantities. Unsupported boundary splices constrain some large layouts more tightly.

Every successful layout runs a separate invariant validator for positive lengths, maximum cut lengths, pile/bearer/joist spacing, both cantilever definitions, supported and staggered joins, pile height reconciliation, actual staggered blocking spacing, equal board gaps, exact decking coverage and footing volumes. The public validator also reconciles lineal metres against cut lengths. Unit tests cover independently calculated ordinary and small decks, spacing boundaries, large supported joist/bearer splices, invalid unsupported boundaries, overrides, profiles, rip limits, screws, concrete, consolidation and 180 deterministic seeded configurations.
