# Geographic flight maps and charcoal pilot tablet

Both **Pilot → Navigation** and **Live explainability → Flight path** now draw a 2D OpenStreetMap basemap underneath the route and aircraft. The same `FlightPathMap` and Web Mercator camera render both raster tiles and geographic overlays. Roads, water, place names and mapped airport areas provide context. The map is a street basemap, not an aviation chart or a terrain/obstacle-clearance calculation.

## Open and use

From CCAT-soar-research:

```powershell
.\run-soar-demo.cmd -Live -Maps   # Research sensors plus both map views; monitoring only
.\run-alia.cmd -Pilot            # Standard controller/dashboard and pilot Navigation
.\run-alia.cmd -MapPreview       # Labelled visible UI preview; no simulator connection/control
```

Load ALIA in X-Plane for live positioning. The map appears as soon as aircraft coordinates arrive, even before a mission starts. With no coordinates, it says it is waiting instead of placing a fictitious aircraft. The simulator was offline during final visual QA, so the inspected map screenshots are explicitly labelled preview data, not a new flight.

- **+ / −** and the mouse wheel change zoom. Drag the map to pan. **Fit** restores the route/aircraft view.
- Dashed blue/cyan is the planned route; the solid line is the recent measured track from the existing history. Aircraft heading rotates the marker.
- Home, destination and the current controller target have separate markers. Amber alternate routes remain previews and have no destination/control authority.
- A stale aircraft keeps its last known marker, shown muted and labelled LAST KNOWN POSITION. Map tiles never substitute for missing aircraft telemetry.
- Airport missions end at their destination. The old dashboard's extra return-to-home line is removed for airport missions; hover-test return routes retain their loop.

## Tablet palette

| Role | Colour |
|---|---|
| Base | `#121212` |
| Cards | `#18181B` |
| Primary text | `#E0E0E0` |
| Secondary text | `#A3A3A3` |
| Borders | `#343438` |
| Selected/raised controls | `#2B2B30` |

The palette covers tablet cards, navigation, forms, sensor tabs, combo boxes, spinners, scrollbars and the alternate-route dialog. The pilot basemap is converted to a muted dark rendering; the research dashboard keeps its existing light theme and uses normal map colours. Safety amber/red, warning prominence, the fixed alert height and recovery latch remain intact. Styling is local to the tablet and does not change global Swing defaults or recolour the light dashboard.

## Tile service and failure behavior

Default service: `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. Visible attribution is always drawn on the map and links to [OpenStreetMap copyright/licensing](https://www.openstreetmap.org/copyright). Implementation follows the [OSMF tile usage policy](https://operations.osmfoundation.org/policies/tiles/): identifiable application User-Agent, HTTPS, on-screen viewports only, two bounded background workers, persistent HTTP caching and conditional requests. There is no bulk/offline-region download or prefetch feature. Hidden/headless previews do not fetch tiles.

Initial uncached map areas need internet access. Previously viewed tiles are reused from ignored `research/local/map-cache/`; cache duration honors HTTP headers, with seven days as the fallback when headers are unavailable. A failed provider backs off rather than retrying every frame. When tiles are unavailable, the route/aircraft overlay and geographic scale remain, with an explicit map status. Tile failures do not pause the aircraft or enter the flight controller's fault path.

Java properties `ccat.map.tileUrl`, `ccat.map.attribution`, and `ccat.map.attributionUrl` allow a different HTTPS provider with its required attribution. Caches are partitioned by provider. `ccat.map.offline=true` is used by automated tests to disable network access. Flight state and control commands are never sent to the tile service.

## Validation

- **8,052 Java checks passed**, including 47 new map/theme checks: known Mercator coordinates, local-to-geographic inversion, route fitting at tablet/dashboard sizes, dateline wrapping, pan alignment, polar limits, airport-vs-hover route semantics, cache expiration and global-theme isolation. A pixel-level test catches native combo boxes repainting white.
- **265 real-JSoar integration checks passed** after the shared UI integration.
- Visible preview windows loaded 12 actual tiles for their displayed viewports. Tablet and dashboard captures show matching geographic route placement. The dark form controls, compact warnings, and portrait/landscape layouts were also rendered and inspected.
- Existing banner and controller regression checks pass. `HoverMission`, `AirportMission`, flight-control equations, UDP control mapping and mission-start logic are unchanged.

Preview artifacts remain local: `build/alia/pilot-map-charcoal-preview.png`, `dashboard-basemap-preview.png`, `pilot-*-preview.png`, and `research/local/sensor-demo/pilot-*.png`. Original CCAT remains separately preserved; changes apply only to the research worktree.
