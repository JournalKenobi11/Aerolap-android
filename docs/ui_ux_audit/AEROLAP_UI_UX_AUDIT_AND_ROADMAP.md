# Aerolap Comprehensive UI/UX Audit & Findings

> [!NOTE]
> This audit covers the full user experience, visual hierarchy, mobile layout ergonomics, and component consistency across both the **Aerolap Android Application** (`runwise-android`) and the **Dreeve Server** web interface.

---

## 1. Dreeve Server Color Theme Status

### Server State Verification
- **Tailwind Assets:** Rebuilt and verified with **Aerolap Electric Blue (`#2DB6FA`)** as the primary theme color.
- **Cache Invalidation:** Appended `-aerolap-2` to the asset versioning strategy (`/css/dist/tailwind.min.css?v5.6.0-aerolap-2`). This busts the immutable browser disk cache (`max-age=31536000`).
- **Leaflet Polylines:** Updated `DEFAULT_POLYLINE_COLOR` from Strava orange (`#fc6719`) to `#2DB6FA`.
- **Browser Chrome & Manifest:** Updated all `<meta name="theme-color">` tags and `manifest.json` from `#F26822` to `#2DB6FA`.
- **Production Cache:** Cleared Symfony production cache (`php bin/console cache:clear`).

> [!TIP]
> If your browser still shows orange on `dreeve.home`, perform a hard-refresh (`Ctrl + F5` on desktop, or pull down to reload in mobile Chrome) to fetch the updated `-aerolap-2` stylesheet.

---

## 2. Screen-by-Screen UI/UX Audit Findings

### 📱 Screen 1: Coach / Dashboard Screen
![Dashboard Audit](/home/journalkenobi/artifacts/screen_aerolap_ui_audit_main.png)

1. **Critical Defect: Concatenated Metrics Row in Planned Workout**
   - **Observation:** The parameters row directly beneath the workout title renders as:
     `DISTANCE   TARGET PACE   TARGET HR`
     `8.52 km6:42 / km avg158 bpm avg (173 max)`
   - **Root Cause:** In [`DashboardScreen.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/dashboard/DashboardScreen.kt#L283-L304), the `Row` uses `Arrangement.SpaceBetween` without child `Modifier.weight(...)`. Because the three strings are wide, they push together with zero horizontal margin, creating an illegible concatenated block.
   - **Fix:** Convert to an evenly weighted 3-column grid (`Modifier.weight(1f)`) or 3 dedicated stat tiles with vertical dividers and explicit text alignment.

2. **UX Ambiguity: Workout CTA State**
   - **Observation:** The card displays a `COMPLETED` tag in the top right, but the primary button remains a bright blue `▶ START TODAY'S WORKOUT`.
   - **UX Impact:** Creates confusion about whether the run was recorded, and risks accidental double-triggering.
   - **Fix:** If `todayWorkout?.badge == "COMPLETED"`, transform the CTA into a subtle completed state (`✓ WORKOUT COMPLETED - 8.52 KM LOGGED`), with a secondary option to view summary or restart if desired.

3. **Visual Inconsistency: Quick Record Activity Cards**
   - **Observation:** `Run` and `Walk` have dark subtle borders, while `Hike` has a prominent green border.
   - **Fix:** Standardize card borders to `CardBorderDark` with matching active hover/tap feedback and dedicated sport icon badges.

---

### 📱 Screen 2: Workouts Screen (Pacing & Custom Routines)
![Workouts Audit](/home/journalkenobi/artifacts/screen_audit_workouts.png)

1. **Visual Glitch: Redundant Double Plus Sign**
   - **Observation:** The primary button displays `+ + BUILD CUSTOM WORKOUT`.
   - **Root Cause:** In [`WorkoutBuilderScreen.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/workout/WorkoutBuilderScreen.kt#L96), both an `Icons.Default.Add` vector and a literal `"+ BUILD CUSTOM WORKOUT"` string are rendered.
   - **Fix:** Remove the literal `"+"` from the text string so it cleanly reads `BUILD CUSTOM WORKOUT`.

2. **Layout Overflow: Lap Row Splitting**
   - **Observation:** In `Lap 2: 10K Target Pace Lock`, the distance `5.0 km` floats awkwardly in the middle of the row, and `Target: 6:15/km` breaks across two lines (`Target:` on line 1, `6:15/km` on line 2).
   - **Root Cause:** In `WorkoutCard` ([`WorkoutBuilderScreen.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/workout/WorkoutBuilderScreen.kt#L487-L515)), the left and right rows lack flex weight constraints.
   - **Fix:** Assign `Modifier.weight(1f)` with `TextOverflow.Ellipsis` to the lap title, and lock distance and pace into a fixed-width, right-aligned column.

3. **Logical Contradiction: Warm-Up & Cool-Down Pace Targets**
   - **Observation:** The preset routines still show `Target: 7:00/km` for Warm Up and `Target: 7:15/km` for Cool Down.
   - **UX Impact:** Direct contradiction to the user directive that warm-ups and cool-downs must have free/open pacing without audio nagging.
   - **Fix:** Set `targetPaceSecPerKm = 0` for Warm Up, Cool Down, and Recovery steps in `RunwiseRepository.kt`, rendering as `Free Pace`.

---

### 📱 Screen 3: Trends & Analytics Screen
![Trends Audit](/home/journalkenobi/artifacts/screen_audit_trends.png)

1. **Severe Visual Collision: Running vs. Walking Colors**
   - **Observation:** `Running` (`299.7 km`) and `Walking` (`181.0 km`) both use virtually identical blue dots and metrics (`#2DB6FA` vs `#00B0FF`).
   - **UX Impact:** The user cannot differentiate between sports at a glance in the Career Totals or Weekly Breakdown bars.
   - **Fix:** Reassign `SportWalk` to a distinct, high-contrast color (e.g. Modern Violet `#818CF8` or Warm Amber `#F59E0B`), reserving `#2DB6FA` exclusively for Running.

2. **Incomplete Visualization: Training Load Curve (90 Days)**
   - **Observation:** The CTL / ATL / TSB chart draws floating lines with no Y-axis values (no scale for 0, 20, 40, 80), no date markers on the X-axis, and an artificial flat green line during empty history periods.
   - **Fix:**
     - Add Y-axis reference points (`+40`, `0`, `-20`).
     - Display current numeric stat pills at the top right: `CTL: 34`, `ATL: 48`, `TSB: -14`.
     - Filter out leading unrecorded zero-load dates so curves start at real activity data.

---

### 📱 Screen 4: Exercises & Recovery Screen
![Exercises Audit](/home/journalkenobi/artifacts/screen_audit_exercises.png)

1. **Outdoor Legibility Defect: Low Contrast Helper Text**
   - **Observation:** The description in "Athlete Equipment & Focus" uses `TextMutedDark` (`#6B7280`) on a `#1E222A` card, making it nearly invisible under outdoor sunlight.
   - **Fix:** Upgrade body copy to `TextSecondaryDark` (`#94A3B8`) with increased line-height.

2. **Affordance Defect: Inactive-Looking Set Checkboxes**
   - **Observation:** The `S1`, `S2`, `S3` buttons appear as flat, borderless dark squares, looking like static tags rather than tappable controls.
   - **Fix:** Add a distinct 1.5dp border (`CardBorderDark`), subtle hover/pressed states, and a bold checkmark animation when logged.

---

### 📱 Screen 5: Activities & Splits View
![Activities Audit](/home/journalkenobi/artifacts/screen_audit_activities.png)

1. **Missing Table Headers in 1 KM Splits**
   - **Observation:** The split rows display `7:17 /km`, `07:17`, and `-1m` without column headers.
   - **UX Impact:** Users cannot intuitively discern whether `07:17` is split elapsed time vs total time, or whether `-1m` is elevation change vs pace variance.
   - **Fix:** Add a table header row: `KM`, `PACE`, `TIME`, `ELEV`.

---

## 3. Implementation Plan

| Step | Screen | Action | Target File |
| :--- | :--- | :--- | :--- |
| **1** | Dashboard | Convert concatenated metrics row to 3-column weighted card with dividers | `DashboardScreen.kt` |
| **2** | Dashboard | Update completed workout CTA to "✓ WORKOUT COMPLETED" | `DashboardScreen.kt` |
| **3** | Workouts | Remove duplicate `+` in custom workout button | `WorkoutBuilderScreen.kt` |
| **4** | Workouts | Fix Lap row text overflow & alignment using flex weights | `WorkoutBuilderScreen.kt` |
| **5** | Workouts | Set default Warm Up / Cool Down paces to `0` ("Free Pace") | `RunwiseRepository.kt` |
| **6** | Trends | Reassign `SportWalk` to Indigo (`#818CF8`) to eliminate blue collision | `Color.kt` |
| **7** | Trends | Add Y-axis labels and current stat chips to Training Load chart | `TrendsScreen.kt` |
| **8** | Exercises | Add borders and click affordances to Set Logging buttons | `ExercisesScreen.kt` |
| **9** | Activities | Add explicit table headers (`KM`, `PACE`, `TIME`, `ELEV`) | `ActiveRunScreen.kt` & `ActivityDetailScreen.kt` |
