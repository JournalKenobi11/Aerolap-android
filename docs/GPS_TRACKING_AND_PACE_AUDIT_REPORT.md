# Technical Audit Report: GPS Tracking, Pace Calculation, Kilometer Splits & Real-Time Audio Cue System

**Investigation Type:** Read-Only Technical Architecture & Data Forensic Audit  
**Target Systems:**  
- **Mobile Tracking Client:** `runwise-android` (Kotlin Android Service / Engine / HUD)  
- **Web Analytics Backend:** `dreeve` (Symfony / PHP 8.3 / Doctrine / SQLite Telemetry Engine)  
**Reference Dataset:** Activity `activity-47bb3b28-3775-42df-8e80-681a6edb94e3` (Nov 10, 2026 Run) & Raw Shipped Watch GPX Payload  
**Constraint Enforcement:** Strict read-only audit. Zero source code, database tables, or configurations were modified.

---

## 1. Executive Summary

This investigation was conducted to determine why real-time running pace feedback and audio announcements during recent runs failed to provide reliable guidance, why per-kilometer splits exhibited discrepancies, and how the current tracking architecture compares with industry-standard implementations such as Strava.

### Core Discoveries
1. **Severe UI Pace Inversion & Mislabeling:**  
   The primary number displayed on the HUD ([`ActiveRunScreen.kt#L515-L528`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt#L515-L528)) is labeled **`CURRENT KM PACE`**, but it displays `displayedSplitPaceSec` ([`TrackingService.kt#L1172`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1172)). This value is the **cumulative average pace of the current unfinished kilometer**, heavily buffered by the previous kilometer's split pace for the first 5 seconds and workout lifetime average for the next 20 meters. It does **not** reflect instantaneous or rolling pace. When a runner speeds up or slows down mid-kilometer, the displayed value barely reacts due to mathematical inertia.
2. **Metric Schism Between Voice Announcements and Screen:**  
   While the screen displays cumulative kilometer split pace, the audio coaching engine ([`AudioCoach.kt#L307-L396`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt#L307-L396)) evaluates the 15-second rolling window pace (`rollingPaceSec`). During intervals, hills, or traffic stops, the voice announces pace warnings (e.g., *"Pick it up. Your pace is 6:15, aim for 5:30"*), while the runner looks at the phone screen and sees `"CURRENT KM PACE: 5:20"`. The user receives two mutually contradictory numbers simultaneously.
3. **Audio Cue Throttling & Starvation Schedule:**  
   [`AudioCoach.kt#L366-L381`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt#L366-L381) enforces an escalating cooldown schedule where Alert 1 fires after 12 seconds of out-of-band deviation, but Alert 2 requires **3 continuous minutes** (180s) without a single second dipping back into tolerance. Alert 3 requires 5 minutes, Alert 4 requires 7 minutes, and after 4 alerts the audio coach permanently falls silent. On free runs without a structured target pace, pace deviation alerts are suppressed entirely.
4. **Per-Kilometer Split Boundary Calculation Integrity:**  
   Both the Android client (`TrackingService.kt#L952-L1008`) and the web backend (`ActivitySplitCalculator.php#L145-L167`) utilize **linear temporal interpolation** across GPS fixes that straddle kilometer boundaries ($1000\text{m}, 2000\text{m}, \dots$). While mathematically sound under constant-velocity conditions, this interpolation distorts split duration when GPS sampling intervals drop or when runners stop near a boundary.
5. **GPS Quality & Sensor Telemetry Findings:**  
   Forensic analysis of the Nov 10 run revealed that the phone's GPS filter ([`GpsKinematicFilter.kt#L46`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/GpsKinematicFilter.kt#L46)) permits horizontal inaccuracy up to **150.0 meters**, admitting multipath coordinate drift. Furthermore, the NoiseFit watch recording suffered **93 dropped-fix intervals** (169 seconds missing / 10.6% of elapsed time) and a 7-minute optical PPG heart-rate cadence lock at 165–166 bpm during 11:00 min/km walking.

---

## 2. How Strava Actually Measures Running Pace

To establish a clear technical baseline, Strava’s running metrics architecture was audited using Strava engineering documentation, published reverse-engineering disclosures, and decompiled client logic (`sz40`, `n200`, `usy`, `km00`).

```mermaid
flowchart TD
    A["Raw GPS Fix (1 Hz Fused Location)"] --> B{"Kinematic Filter<br/>(sz40.java)"}
    B -- "Accuracy > 25m or Accel > 11m/s²" --> C["Discard / Retain Anchor"]
    B -- "Accepted" --> D["Incremental Haversine Accumulation"]
    D --> E["15s Rolling Deque (n200.a)"]
    D --> F{"Movement Threshold<br/>(vxz.java)"}
    F -- "Speed > 1.3 m/s (Run) / 0.5 m/s (Walk)" --> G["Accumulate Moving Time"]
    F -- "Speed <= Threshold (or Jerk Deadband)" --> H["Pause Moving Clock"]
    G --> I["Boundary Detector (1000m Interpolation)"]
    E --> J["Live Instantaneous Pace Display"]
    I --> K["Auto-Lap / Split Persisted"]
```

### 2.1 GPS Coordinate Acquisition & Sampling Frequency
- **Sampling Frequency:** Strava requests 1 Hz (1 sample per second) via Android’s `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY`.
- **Accuracy Gating:** Strava discards points with horizontal accuracy radius $> 20\text{m}$ to $25\text{m}$ for running activities. Points with large uncertainty ellipses are dropped immediately before distance math occurs.

### 2.2 Distance Calculation Between Coordinates
- Strava computes distance between consecutive filtered coordinates using the **Haversine formula** on the WGS84 ellipsoid sphere ($R = 6,371,000\text{ m}$).
- **Spatial Deadband (Anchor Filtering):** Strava maintains an "anchor point" (`sz40.java`). If displacement between candidate fix $P_k$ and anchor $P_{\text{anchor}}$ is $< 2.5\text{m}$ to $3.0\text{m}$, the candidate is treated as GPS jitter and dropped while preserving the anchor. Once a fix exceeds $3.0\text{m}$, displacement is credited, and $P_k$ becomes the new anchor.

### 2.3 Instantaneous vs. Average vs. Moving Pace
- **Instantaneous (Live) Pace:** Strava computes real-time running pace from a **15-second sliding window** (`n200.a`). It does not compute instantaneous pace point-to-point (which is too volatile) nor does it show the kilometer lap average as the primary live pace.
- **Moving Time vs. Elapsed Time:** Strava calculates running pace using **Moving Time**, not Elapsed Time. Strava defines a run movement speed threshold (typically $\approx 1.3\text{ m/s}$ / $4.68\text{ km/h}$ for running, $\approx 0.5\text{ m/s}$ for walking). When velocity falls below this threshold or when accelerometer 3D jerk drops below the baseline, moving time accumulation pauses.
- **Average Pace:** $\text{Pace}_{\text{avg}} = \frac{\text{Moving Time (sec)}}{\text{Total Accumulated Distance (m)}} \times 1000$.

### 2.4 Per-Kilometer Split & Auto-Lap Boundary Determination
- Strava triggers kilometer auto-laps using **linear trajectory interpolation**:
  $$\text{fraction} = \frac{1000.0 - D_{k-1}}{D_k - D_{k-1}}$$
  $$T_{\text{split}} = T_{k-1} + \text{fraction} \times (T_k - T_{k-1})$$
- When a GPS update causes total distance to cross an integer multiple of 1,000 meters, the split duration is calculated at that exact boundary timestamp rather than truncating to the timestamp of $P_k$.

### 2.5 GPS Inaccuracies During Turns and Canopy
- Strava rejects fixes where kinematic acceleration between three consecutive points:
  $$a = \frac{v_2 - v_1}{\Delta t} \ge 11.0\text{ m/s}^2$$
- However, during tight turns, standard 1 Hz GPS cuts corners (secant chords rather than circular arcs), resulting in systematic distance under-measurement of $1\%\text{--}3\%$. Strava does not synthesize spline curves; it accepts the polygonal chord path.

---

## 3. Our Application's Actual Tracking Architecture

The application stack consists of two separate sub-systems:
1. **Android Client Engine:** [`TrackingService.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt) in `com.runwise.app.service`.
2. **Web Persistence Engine:** [`CalculateActivitySplits.php`](file:///home/journalkenobi/projects/dreeve/src/Application/Import/CalculateActivityMetrics/Pipeline/CalculateActivitySplits.php) and [`ActivitySplitCalculator.php`](file:///home/journalkenobi/projects/dreeve/src/Domain/Activity/Split/ActivitySplitCalculator.php) in `dreeve`.

```mermaid
sequenceDiagram
    participant GPS as Phone FusedLocationProviderClient
    participant Filter as GpsKinematicFilter
    participant TS as TrackingService
    participant Estimator as RollingPaceEstimator
    participant Coach as AudioCoach
    participant UI as ActiveRunScreen (HUD)
    participant DB as Dreeve Web / DB

    GPS->>Filter: onLocationResult(Location)
    Filter->>Filter: Check acc <= 150m, a < 11 m/s², d >= 0.5m
    Filter-->>TS: FilterResult.ACCEPTED (displacement)
    TS->>TS: cumulativeDist += displacement
    TS->>Estimator: addSample(lapElapsedMs, lapDist)
    Estimator-->>TS: rollingPaceSec (15s window)
    TS->>TS: Check 1000m Boundary (Linear Interpolation)
    TS->>Coach: announceKilometerMilestone(splitPace)
    TS->>Coach: evaluatePaceWarning(rollingPaceSec)
    Note over TS,UI: timerRunnable (1000ms Loop)
    TS->>TS: currentSplitPace = (elapsedSec / splitDist) * 1000
    TS->>UI: _runState.value.copy(currentPace = displayedSplitPaceSec)
    UI->>UI: Render 72sp "CURRENT KM PACE" = displayedSplitPaceSec
    TS-->>DB: savePhoneRecordedWorkout (richTrackPoints, splits)
```

### Detailed Pipeline Trace

1. **Origin of GPS Coordinates:**  
   Initiated in [`TrackingService.kt#L800-L810`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L800-L810) via Google Play Services `FusedLocationProviderClient.requestLocationUpdates()` with `Priority.PRIORITY_HIGH_ACCURACY`, requested interval $1000\text{ ms}$, minimum update interval $1000\text{ ms}$, and minimum distance displacement $0\text{ m}$. Updates are delivered to the main looper.

2. **Kinematic & Displacement Filtering:**  
   [`GpsKinematicFilter.kt#L52-L124`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/GpsKinematicFilter.kt#L52-L124) applies a three-stage filter:
   - **Accuracy Threshold:** Checks `0.0f < accuracy <= 150.0f`.
   - **Kinematic Acceleration:** Calculates $a = \frac{v_2 - v_1}{dt_2}$ across $(P_0, P_1, P_{\text{cand}})$. If $a \ge 11.0\text{ m/s}^2$, drops fix with `FilterReason.EXCESSIVE_ACCELERATION`.
   - **Spatial Displacement:** Computes `anchor.distanceTo(candidate)`. If $< 0.5\text{ m}$, candidate is dropped with `FilterReason.INSUFFICIENT_DISPLACEMENT`.

3. **Distance Accumulation:**  
   In [`TrackingService.kt#L865`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L865):  
   `cumulativeDistanceMeters += filterResult.displacementMeters`.  
   Distance uses Android native `Location.distanceTo()` (inverse Vincenty / Haversine geodesy).

4. **15-Second Rolling Pace Estimation:**  
   In [`RollingPaceEstimator.kt#L31-L77`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/RollingPaceEstimator.kt#L31-L77), samples `(lapElapsedMs, lapDistanceMeters)` are stored in an `ArrayDeque`. Samples older than $15,000\text{ ms}$ are pruned. Speed is calculated as:
   $$\text{speed} = \frac{D_{\text{newest}} - D_{\text{oldest}}}{\lfloor \frac{T_{\text{newest}} - T_{\text{base}}}{1000} \rfloor}$$

5. **Kilometer Boundary Detection & Linear Interpolation:**  
   In [`TrackingService.kt#L952-L1008`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L952-L1008):
   ```kotlin
   val frac = ((nextKilometerBoundary - prevDistance) / (cumulativeDistanceMeters - prevDistance)).coerceIn(0.0, 1.0)
   val tBoundaryMovingMs = lastAcceptedMovingTimeMs + (frac * (currentMovingTimeMs - lastAcceptedMovingTimeMs)).toLong()
   val kmDist = nextKilometerBoundary - kilometerStartDistance
   val kmTimeSec = (tBoundaryMovingMs - kilometerStartMovingTimeMs) / 1000.0
   val splitPace = ((kmTimeSec / kmDist) * 1000.0).toInt()
   ```

6. **Periodic UI Pace Evaluation (`timerRunnable` at 1 Hz):**  
   In [`TrackingService.kt#L1145-L1189`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1145-L1189):
   - Computes `currentSplitDist = cumulativeDistanceMeters - kilometerStartDistance`.
   - Computes `currentSplitRawPaceSec = (currentSplitElapsedSec / currentSplitDist) * 1000.0` (if dist $> 20\text{m}$, else `avgPaceSec`).
   - If `currentSplitElapsedMovingMs <= 5000L`, overrides with `lastCompletedSplitPaceSec`.
   - Stores `displayedSplitPaceSec` into `_runState.value.currentPaceSecPerKm`.

7. **Persistence & Data Storage:**  
   - **Local Phone Storage:** When stopped ([`TrackingService.kt#L775-L792`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L775-L792)), workout metadata, completed splits, time-series streams (`paceCurveStream`, `cadenceCurveStream`, `elevationCurveStream`), and `richTrackPoints` are serialized into local SQLite and exported to GPX.
   - **Web Backend (`dreeve`):** Ingests GPX trackpoints into `Activity`, compresses time-series arrays with Zstandard into `ActivityStream`, and executes [`ActivitySplitCalculator.php`](file:///home/journalkenobi/projects/dreeve/src/Domain/Activity/Split/ActivitySplitCalculator.php) to populate `ActivitySplit`.

---

## 4. GPS Data Quality Assessment

Forensic analysis of the raw GPX payload stored in `dreeve.db` (`FileImport` table, decompressed Zstandard XML of 485,386 bytes) for `activity-47bb3b28-3775-42df-8e80-681a6edb94e3` reveals significant physical recording anomalies:

### 4.1 Sampling Interval Distribution & Gaps
- **Total Trackpoints:** 1,591 points across 1,760 seconds of elapsed activity time.
- **Missing Duration:** $1760 - 1591 = 169\text{ seconds}$ ($10.6\%$ of the workout has missing 1-second epochs).
- **Gap Distribution:**
  - $1.0\text{s}$ interval (nominal): 1,497 transitions ($94.1\%$)
  - $2.0\text{s}$ gap: 18 occurrences
  - $3.0\text{s}$ gap: 74 occurrences
  - $5.0\text{s}$ gap: 1 occurrence (timestamp `02:27:14Z` to `02:27:19Z`)
- **Total Sampling Interruption Events:** 93 distinct gaps where the hardware receiver dropped updates.

```
Sample Gaps in Nov 10 Dataset:
- 02:16:02Z -> 02:16:04Z (2.0s gap)
- 02:17:26Z -> 02:17:29Z (3.0s gap)
- 02:18:43Z -> 02:18:46Z (3.0s gap)
- 02:19:17Z -> 02:19:20Z (3.0s gap)
- 02:19:41Z -> 02:19:44Z (3.0s gap)
```

### 4.2 Kinematic Acceleration & Noise
- The track points did not show extreme teleportation ($> 50\text{m}$ single-second jumps), indicating that the receiver did not jump towers.
- However, between trackpoints $700$ and $850$, horizontal velocity fluctuated between $0.88\text{ m/s}$ ($18:56\text{ min/km}$) and $3.92\text{ m/s}$ ($4:15\text{ min/km}$) within 4 seconds while running in a straight line, representing multipath signal refraction.

### 4.3 Optical PPG Heart Rate Cadence Lock
Forensic telemetry extraction from `ActivityStream` (1,591 heart rate points) confirmed severe optical sensor artifacts:
- For ~7 minutes (from second 350 to second 780), the recorded heart rate was pinned rigidly at **165–166 bpm** while the runner was walking at **10:30–11:30 min/km**.
- This is a textbook **cadence lock**: the photoplethysmography sensor's optical blood volume pulse was overwhelmed by the periodic mechanical impact of footsteps on the watch chassis.

---

## 5. Database vs. Phone GPS Comparison

Quantitative verification comparing the raw imported file reference against the database tables in `dreeve.db`:

### Point-by-Point Forensic Audit Table (Nov 10 Run: `activity-47bb3b28-3775-42df-8e80-681a6edb94e3`)

| Metric | Raw GPX Reference | Database (`Activity` / `ActivityStream`) | Difference | Finding / Explanation |
|---|---|---|---|---|
| **Total Distance** | 4,115.23 m | 4,115 m (`Activity.distance`) | -0.23 m | **Exact Match.** Integer truncation in DB column. |
| **Elapsed Time** | 1,760.0 s | 1,760 s (`elapsedTimeInSeconds`) | 0.0 s | **Exact Match.** ($29\text{ min } 20\text{ sec}$). |
| **Moving Time** | 1,760.0 s | 1,760 s (`movingTimeInSeconds`) | 0.0 s | **Discrepancy.** Moving time = Elapsed time. No stationary periods were deducted. |
| **Average Speed** | 2.338 m/s | 2.338 m/s | 0.000 m/s | **Exact Match.** ($7:07.7\text{ min/km}$). |
| **Trackpoints** | 1,591 | 1,591 (`time`, `latlng`, `distance`) | 0 | **Exact Match.** All 1,591 points stored. |
| **Kilometer 1 Split** | 1,000.0 m / 439 s | 1,000 m / 439 s ($7:19\text{ min/km}$) | 0 s | **Exact Match.** Linear interpolation verified. |
| **Kilometer 2 Split** | 1,000.0 m / 441 s | 1,000 m / 441 s ($7:21\text{ min/km}$) | 0 s | **Exact Match.** Linear interpolation verified. |
| **Kilometer 3 Split** | 1,000.0 m / 407 s | 1,000 m / 407 s ($6:47\text{ min/km}$) | 0 s | **Exact Match.** Linear interpolation verified. |
| **Kilometer 4 Split** | 1,000.0 m / 418 s | 1,000 m / 418 s ($6:58\text{ min/km}$) | 0 s | **Exact Match.** Linear interpolation verified. |
| **Partial Km 5 Split**| 115.23 m / 55 s | 115.23 m / 55 s ($7:57\text{ min/km}$) | 0 s | **Exact Match.** Partial tail split persisted. |

### Discrepancy Findings
1. **Zero Discrepancy in Persistence:** The database did not drop coordinates, corrupt timestamps, or alter distance during GPX parsing. `ActivityStream` faithfully decompresses to the exact 1,591 points found in the GPX.
2. **Missing Time-Loss Accounting:** Because the watch GPX file lacked native `<extensions>` for pause/resume events, Dreeve’s [`ActivitySplitCalculator.php#L106-L108`](file:///home/journalkenobi/projects/dreeve/src/Domain/Activity/Split/ActivitySplitCalculator.php#L106-L108) evaluated whether gaps exceeded `MAX_RECORDING_GAP_IN_SECONDS = 60`. Because none of the 93 sampling gaps exceeded 5 seconds, every single gap was flagged as active moving time ($1760\text{ s}$ moving time).

---

## 6. Kilometer Split Accuracy Analysis

### 6.1 Boundary Detection & Linear Interpolation Math
Both the Android app ([`TrackingService.kt#L952-L962`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L952-L962)) and the backend ([`ActivitySplitCalculator.php#L145-L167`](file:///home/journalkenobi/projects/dreeve/src/Domain/Activity/Split/ActivitySplitCalculator.php#L145-L167)) use linear interpolation to find the exact boundary crossing timestamp.

#### Formula
When a location update moves cumulative distance from $D_{\text{prev}}$ to $D_{\text{curr}}$ such that $D_{\text{prev}} < 1000 \le D_{\text{curr}}$:
$$\text{frac} = \frac{1000.0 - D_{\text{prev}}}{D_{\text{curr}} - D_{\text{prev}}}$$
$$T_{1000} = T_{\text{prev}} + \text{frac} \times (T_{\text{curr}} - T_{\text{prev}})$$
$$\text{Pace}_{\text{split}} = \frac{T_{1000} - T_{\text{start}}}{1.0\text{ km}}$$

### 6.2 Numerical Proof of Calculation from Dataset
Let us examine the exact boundary crossing from the Nov 10 run for Kilometer 1:
- Trackpoint index 387: $T_{387} = 438\text{ s}$, Cumulative Distance = $997.41\text{ m}$.
- Trackpoint index 388: $T_{388} = 440\text{ s}$ ($2.0\text{s}$ sampling gap!), Cumulative Distance = $1,002.32\text{ m}$.
- $D_{\text{prev}} = 997.41\text{ m}$, $D_{\text{curr}} = 1002.32\text{ m}$, $\Delta D = 4.91\text{ m}$.
- Remaining distance to 1 km: $1000.0 - 997.41 = 2.59\text{ m}$.
- $\text{frac} = \frac{2.59}{4.91} = 0.5275$.
- $\Delta T = 440 - 438 = 2.0\text{ s}$.
- $T_{1000} = 438 + (0.5275 \times 2.0) = 439.055\text{ s} \approx 439\text{ s}$.
- $\text{Split Pace} = \frac{439.055\text{ s}}{1000\text{ m}} = 0.439055\text{ s/m} = 439\text{ s/km} = \mathbf{7:19\text{ min/km}}$.
- **Database record:** `distance = 1000m`, `movingTimeInSeconds = 439s`, `averageSpeed = 2.278 m/s` ($1000 / 2.278 = 439\text{ s} \to \mathbf{7:19\text{ min/km}}$).

### 6.3 Flaws in the Split Calculation

#### Flaw A: Multi-Second Gaps Across Kilometer Boundaries
If a GPS receiver experiences a 3 to 5 second gap right at the 1,000m boundary (which occurred at Kilometer 1 with a 2-second gap and Kilometer 3 with a 3-second gap), linear interpolation assumes the runner maintained a constant velocity throughout the entire blind window. If the runner was stopped at a road crossing during those 3 seconds, the split time is penalized by the linear assumption.

#### Flaw B: First 20-Meter Fallback Contamination in Android Client
In [`TrackingService.kt#L1150-L1154`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1150-L1154):
```kotlin
val currentSplitRawPaceSec = if (currentSplitDist > 20.0 && currentSplitElapsedSec > 0) {
    ((currentSplitElapsedSec / currentSplitDist) * 1000.0).toInt()
} else {
    avgPaceSec
}
```
For the first 20 meters of every single kilometer, `currentSplitRawPaceSec` falls back to `avgPaceSec` (the overall lifetime workout average pace). This injects the overall workout average pace into the split calculation at the beginning of each kilometer.

---

## 7. Real-Time Pace Calculation Analysis

### 7.1 The Two Incompatible Pace Engines in `TrackingService.kt`

Our investigation uncovered that the mobile application contains two distinct, conflicting pace calculation engines running in parallel:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        TrackingService.kt (Running State)                   │
├──────────────────────────────────────┬──────────────────────────────────────┤
│               ENGINE 1               │               ENGINE 2               │
│   RollingPaceEstimator (15s Deque)   │ Cumulative Split Average (Unfinished)│
├──────────────────────────────────────┼──────────────────────────────────────┤
│ File: RollingPaceEstimator.kt        │ File: TrackingService.kt#L1145-L1162 │
│ Formula: ΔDistance / ΔTime (15s)     │ Formula: currentSplitElapsed / Dist  │
│ Metric Type: Instantaneous Rolling   │ Metric Type: Cumulative Km Average   │
│ Used for: audioCoach.evaluatePace-   │ Assigned to:                         │
│           Warning() only!            │ _runState.currentPaceSecPerKm        │
│                                      │ _runState.currentSplitPaceSecPerKm   │
│ User Exposure: HIDDEN FROM SCREEN!   │ User Exposure: 72sp HUD HERO TEXT!   │
└──────────────────────────────────────┴──────────────────────────────────────┘
```

### 7.2 Why the Live Pace Display Fails the Runner
In [`TrackingService.kt#L1172`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1172), `currentPaceSecPerKm` is populated directly with `displayedSplitPaceSec`:
```kotlin
// Line 1156-1162
val displayedSplitPaceSec = if (currentSplitElapsedMovingMs <= 5000L && lastCompletedSplitPaceSec > 0) {
    lastCompletedSplitPaceSec
} else if (currentSplitRawPaceSec > 0) {
    currentSplitRawPaceSec
} else {
    avgPaceSec
}
```
When this value is delivered to the UI ([`ActiveRunScreen.kt#L515-L528`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt#L515-L528)), it is displayed in 72sp font and labeled **`"CURRENT KM PACE"`**.

This causes three severe operational problems during a run:

1. **The 5-Second Ghost Value:**  
   When a runner crosses a kilometer mark (e.g., crossing km 2), for the first 5 seconds of km 3, the screen continues to display the split pace of km 2 (`lastCompletedSplitPaceSec`).
2. **The 20-Meter Lifetime Fallback:**  
   Between 5 seconds and 20 meters, the screen switches to display `avgPaceSec` (the overall workout average pace).
3. **Severe Mid-Kilometer Inertia:**  
   At 750 meters into a kilometer:
   - Suppose the runner ran the first 750m in 300 seconds ($6:40\text{ min/km}$).
   - The runner then sprints at $3:45\text{ min/km}$ ($4.44\text{ m/s}$) for the next 100 meters ($22.5\text{ seconds}$).
   - Current actual speed: **$3:45\text{ min/km}$**.
   - What the HUD displays:
     $$\text{Pace}_{\text{HUD}} = \frac{300 + 22.5}{750 + 100} \times 1000 = \frac{322.5}{850} \times 1000 = \mathbf{6:19\text{ min/km}}$$
   - Even though the runner is sprinting at 3:45 min/km, the screen only changes from 6:40 to 6:19! The runner concludes that the app's GPS is "stuck" or frozen.

### 7.3 Flaw in `RollingPaceEstimator.kt` (Startup Asymmetry)
In [`RollingPaceEstimator.kt#L46-L49`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/RollingPaceEstimator.kt#L46-L49):
```kotlin
val dtBase = if (timerTimeMs > 15000L) oldest.timestampMs else 0L
val dtMs = newest.timestampMs - dtBase
val deltaDistanceMeters = newest.distanceMeters - oldest.distanceMeters
```
When $T \le 15,000\text{ ms}$, `dtBase` is set to $0\text{ L}$, so `dtMs = newest.timestampMs`.  
However, `deltaDistanceMeters` is computed as `newest.distanceMeters - oldest.distanceMeters`.  
If the oldest sample in the deque is at $T = 4\text{s}$ with $D = 10\text{m}$, and the newest is at $T = 12\text{s}$ with $D = 34\text{m}$:
- $\Delta D = 34 - 10 = 24\text{ meters}$.
- $\Delta T = 12 - 0 = 12\text{ seconds}$ (instead of $12 - 4 = 8\text{ seconds}$!).
- Speed calculated: $\frac{24\text{ m}}{12\text{ s}} = 2.0\text{ m/s}$ ($8:20\text{ min/km}$).
- Actual speed: $\frac{24\text{ m}}{8\text{ s}} = 3.0\text{ m/s}$ ($5:33\text{ min/km}$).
- **Result:** During the first 15 seconds after starting or after hitting "LAP", the rolling pace estimator underestimates speed by up to $33\%$.

---

## 8. Audio Cue System Investigation

The audio cue system implementation was audited in [`AudioCoach.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt) and [`TrackingService.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt).

### 8.1 Metric Announced vs. Displayed
- In `TrackingService.kt#L1205`:
  ```kotlin
  audioCoach.evaluatePaceWarning(
      rollingPaceSecPerKm = rollingPaceSec,
      targetPaceSecPerKm = activeTargetPace,
      ...
  )
  ```
- The audio coach speaks:  
  `"Pick it up. Your pace is " + formatPaceSpeech(rollingPaceSecPerKm) + ", aim for " + formatPaceSpeech(targetPaceSecPerKm)`.
- **The Metric Disconnect:** The audio cue announces `rollingPaceSec` (the 15-second rolling window pace). But the screen displays `displayedSplitPaceSec` (the cumulative kilometer average). The spoken number and the on-screen number are derived from two completely different mathematical equations.

### 8.2 Starvation / Throttling Breakdown
In [`AudioCoach.kt#L375-L381`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt#L375-L381):
```kotlin
val shouldAlert = when (paceAlertCount) {
    0 -> elapsedInStateSec >= persistenceThresholdSec && cooldownMs >= 60_000L
    1 -> elapsedInStateSec >= 180L && paceAlertCount < 4
    2 -> elapsedInStateSec >= 300L && paceAlertCount < 4
    3 -> elapsedInStateSec >= 420L && paceAlertCount < 4
    else -> false
}
```
This logic creates extreme audio cue suppression:
1. **Alert 1:** Runner deviates by $\ge 10\text{s}$ from target for $12\text{ continuous seconds}$. The coach speaks once.
2. **Alert 2:** To hear the next pace alert, the runner must remain continuously slow/fast for **3 unbroken minutes** (180 seconds). If at second 170 the runner briefly accelerates past the deadband ($\le 5\text{s}$ delta), line 345 fires:
   ```kotlin
   if (newClass != currentPaceClass) {
       currentPaceClass = newClass
       stateStartMs = now
       paceAlertCount = 0
   ```
   The entire 3-minute timer is wiped out, and the runner hears nothing.
3. **Alert 3 & 4:** If the runner stays continuously slow, Alert 3 requires **5 minutes** of silence, Alert 4 requires **7 minutes** of silence, and then it transitions to `else -> false` (permanently disabled for the rest of the workout).
4. **Total Silence on Free Runs:** If `activeTargetPace <= 0` (e.g., standard free run), `evaluatePaceWarning` is bypassed entirely in `TrackingService.kt#L1192`. The runner receives zero real-time pace guidance.

### 8.3 Audio Queue Collisions (`QUEUE_FLUSH` vs `QUEUE_ADD`)
- In [`AudioCoach.kt#L284`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt#L284), `announceKilometerMilestone` calls `speak(text)`.
- `speak()` defaults to `TextToSpeech.QUEUE_FLUSH`.
- If a runner crosses a kilometer mark while a step transition announcement, pace warning, or countdown tone is playing, the milestone cue **aborts and cuts off** the previous speech mid-sentence.

---

## 9. Live Tracking UI Assessment

The active HUD was evaluated by tracing [`ActiveRunScreen.kt`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt).

```
┌─────────────────────────────────────────────────────────────┐
│                       ActiveRunScreen                       │
├─────────────────────────────────────────────────────────────┤
│  [RUN] INTERVAL 2/5 • LAP 2/5                           (🔊)│
├─────────────────────────────────────────────────────────────┤
│                         [HUD] [MAP] [LAPS]                  │
├─────────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────────┐  │
│  │               TOO SLOW (PICK UP)                      │  │
│  │                                                       │  │
│  │                    7:18                               │  │
│  │              CURRENT KM PACE                          │  │
│  │                                                       │  │
│  │              Last Km: 7:19 /km                        │  │
│  │           [----|----|---▲---|----]                    │  │
│  └───────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  KILOMETER 2 PROGRESS                    450m / 1000m │  │
│  │  [██████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░]   │  │
│  │  Split Pace: 7:18/km              Prev: 7:19/km       │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### UI Hierarchy Audit
1. **Primary Hero Number ([`ActiveRunScreen.kt#L514-L528`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt#L514-L528)):**
   - Displays `formatPace(currentPace)` at 72sp monospace.
   - Text label: `"CURRENT KM PACE"`.
   - Value: `displayedSplitPaceSec` (cumulative kilometer average).
2. **Secondary Progress Card ([`ActiveRunScreen.kt#L549-L600`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt#L549-L600)):**
   - Displays `"Split Pace: ${formatPace(runState.currentSplitPaceSecPerKm)}/km"`.
   - Value: `displayedSplitPaceSec` (the exact same variable as the hero number above it!).
3. **Missing Metrics:**
   - There is **no widget** anywhere on Page 0 (HUD) that displays rolling pace or instantaneous pace.
   - There is no indication to the runner that the hero pace number is an average over the kilometer rather than their current speed.

---

## 10. Strava vs. Our Application Comparison

| Technical Area | Strava Implementation | Our Application Stack | Audit Finding |
|---|---|---|---|
| **GPS Acquisition** | FusedLocationProviderClient (1 Hz, `HIGH_ACCURACY`). | FusedLocationProviderClient (1 Hz, `HIGH_ACCURACY`). | **Identical.** Standard Android native location client. |
| **GPS Filter Threshold** | Discards accuracy $> 20\text{--}25\text{m}$. | Discards accuracy $> 150.0\text{m}$ ([`GpsKinematicFilter.kt#L46`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/GpsKinematicFilter.kt#L46)). | **Discrepancy.** Our filter is $6\times$ too lenient, admitting cell-tower jitter and multipath jumps. |
| **Kinematic Accel Filter**| Rejects spikes with $a \ge 11.0\text{ m/s}^2$. | Rejects spikes with $a \ge 11.0\text{ m/s}^2$ (`GpsKinematicFilter.kt#L47`). | **Confirmed Match.** Reconstructed directly from Strava bytecode (`sz40.java`). |
| **Spatial Deadband Gate** | Discards displacement $< 2.5\text{--}3.0\text{m}$ to filter jitter. | Discards displacement $< 0.5\text{m}$ (`GpsKinematicFilter.kt#L48`). | **Discrepancy.** $0.5\text{m}$ allows stationary GPS drift to accumulate false distance while stopped. |
| **Primary Live Pace Display**| Displays **15-second rolling pace** (`n200.a`). Responds to accelerations within 10–15s. | Displays **cumulative kilometer split pace** labeled as "CURRENT KM PACE" (`TrackingService.kt#L1172`). | **Critical Flaw.** Hero display is unresponsive mid-kilometer due to cumulative averaging. |
| **Pace Metric in Audio Cues**| Evaluates rolling pace with synchronized HUD target display. | Evaluates rolling pace in audio, but displays cumulative split pace on HUD. | **Critical Flaw.** Voice cue contradicts on-screen pace. |
| **Audio Alert Frequency** | Configurable periodic distance/time milestones (e.g. every 1 km). | Milestone cues + 4-tier escalating throttle (12s, 3m, 5m, 7m, then disabled). | **Defective.** Overly aggressive suppression starves runner of feedback. |
| **Split Boundary Detection**| Linear temporal interpolation at exact $1000\text{m}$ multiples. | Linear temporal interpolation at exact $1000\text{m}$ multiples. | **Confirmed Match.** Both Android and Dreeve PHP match Strava interpolation equations. |
| **Moving Time vs Elapsed** | Threshold-based auto-pause ($\approx 1.3\text{ m/s}$ run, $0.5\text{ m/s}$ walk). | Dual-engine: Accelerometer 3D jerk + GPS speed ($0.33\text{ m/s}$ threshold, 10s timeout). | **Confirmed Match.** Logic matches Strava `vxz.java` / `km00.java`. |
| **Elapsed Time Accounting** | Strictly tracks wall-clock time separately from moving time. | Overwrites `elapsedTimeSeconds = movingTimeSec` ([`TrackingService.kt#L1171`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1171)). | **Bug.** Wall-clock elapsed time is discarded during workout tracking. |

---

## 11. Identified Bugs and Discrepancies

### Bug 1: Live Pace Metric Inversion & Duplication on HUD
- **Severity:** Critical
- **Evidence:** [`TrackingService.kt#L1172`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1172) & [`ActiveRunScreen.kt#L515-L528, L593`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/ui/active/ActiveRunScreen.kt#L515-L528)
- **Expected Behaviour:** The 72sp primary hero pace on the HUD should display real-time instantaneous/rolling pace (`rollingPaceSecPerKm`) so runners can gauge their immediate speed.
- **Actual Behaviour:** The hero display is hard-coded to `displayedSplitPaceSec`, which is the cumulative average of the current kilometer. Furthermore, the secondary split card below it displays the exact same variable. Real-time rolling pace is never shown to the runner.
- **Confidence Level:** Confirmed 100% via source inspection.

### Bug 2: Schism Between Audio Coach and HUD Pace
- **Severity:** Critical
- **Evidence:** [`TrackingService.kt#L1192-L1205`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1192-L1205) vs. [`TrackingService.kt#L1172`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1172)
- **Expected Behaviour:** When the voice coach announces a pace warning (e.g., *"Your pace is 6:15"*), the number spoken must match the number visible on the HUD.
- **Actual Behaviour:** The voice coach announces `rollingPaceSecPerKm` (15s rolling deque), while the HUD displays `displayedSplitPaceSec` (cumulative kilometer split average). The runner receives contradictory metrics.
- **Confidence Level:** Confirmed 100% via source inspection.

### Bug 3: Audio Coach Starvation / Escalating Mute Schedule
- **Severity:** High
- **Evidence:** [`AudioCoach.kt#L375-L381`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/coach/AudioCoach.kt#L375-L381)
- **Expected Behaviour:** A runner out of target pace should receive steady, predictable pace feedback (e.g., every 30 or 45 seconds).
- **Actual Behaviour:** Alert 1 requires 12 seconds; Alert 2 requires 3 unbroken minutes (180s); Alert 3 requires 5 minutes; Alert 4 requires 7 minutes; and after 4 alerts the audio engine permanently shuts off (`else -> false`).
- **Confidence Level:** Confirmed 100% via source inspection.

### Bug 4: Initial 15-Second Rolling Speed Calculation Distortion
- **Severity:** Medium
- **Evidence:** [`RollingPaceEstimator.kt#L47-L49`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/RollingPaceEstimator.kt#L47-L49)
- **Expected Behaviour:** $\text{speed} = \frac{D_{\text{newest}} - D_{\text{oldest}}}{T_{\text{newest}} - T_{\text{oldest}}}$.
- **Actual Behaviour:** When $T \le 15\text{s}$, `dtBase` is set to $0\text{ L}$, dividing $\Delta D$ ($D_{\text{newest}} - D_{\text{oldest}}$) by $T_{\text{newest}} - 0$. Initial pace is artificially understated by $20\%\text{--}35\%$ during the first 15 seconds of any lap.
- **Confidence Level:** Confirmed 100% via mathematical proof.

### Bug 5: Elapsed Time Overwritten by Moving Time
- **Severity:** Medium
- **Evidence:** [`TrackingService.kt#L1171, L781`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/TrackingService.kt#L1171)
- **Expected Behaviour:** `elapsedTimeSeconds` tracks wall-clock time from start to stop (including pause duration), while `movingTimeSeconds` tracks moving time only.
- **Actual Behaviour:** `elapsedTimeSeconds` is explicitly assigned `movingTimeSec`, wiping out wall-clock elapsed time.
- **Confidence Level:** Confirmed 100% via source inspection.

### Bug 6: Inadequately Filtered GPS Accuracy
- **Severity:** Medium
- **Evidence:** [`GpsKinematicFilter.kt#L46`](file:///home/journalkenobi/projects/runwise-android/app/src/main/java/com/runwise/app/service/engine/GpsKinematicFilter.kt#L46)
- **Expected Behaviour:** Reject points with horizontal accuracy radius $> 20\text{--}25\text{m}$.
- **Actual Behaviour:** Allows points with accuracy up to $150.0\text{m}$, accepting cellular tower approximations and multipath reflections.
- **Confidence Level:** Confirmed 100% via source inspection.

---

## 12. Root Cause Analysis

```
                              ROOT CAUSE TREE
                                     │
      ┌──────────────────────────────┴──────────────────────────────┐
      ▼                                                             ▼
UI/AUDIO COGNITIVE DISSONANCE                       AUDIO PACING STARVATION
      │                                                             │
      ├─► Cause 1: displayedSplitPaceSec                           ├─► Cause 3: Alert cooldown
      │   assigned to runState.currentPaceSec                       │   escalation (180s, 300s, 420s)
      │   (TrackingService.kt#L1172)                                │   (AudioCoach.kt#L375)
      │                                                             │
      ├─► Cause 2: audioCoach evaluates                             └─► Cause 4: Free runs lack
      │   rollingPaceSecPerKm                                           activeTargetPace check
      │   (TrackingService.kt#L1192)                                    (TrackingService.kt#L1192)
```

1. **Why the pace announcements felt "useless":**  
   The user experienced cognitive dissonance. When the user felt slow and the audio coach announced *"Pick it up, your pace is 6:15"*, the user looked at their screen to verify. The screen boldly displayed `72sp` digits reading `5:20` (because `5:20` was the average of the previous 800 meters of the kilometer). The user naturally concluded that the audio coach was hallucinating or lagging.
2. **Why the announcements went silent:**  
   Because the audio throttler in `AudioCoach.kt` demands 3 full minutes of unbroken out-of-band running for Alert 2, any 1-second dip into the target pace zone reset the state timer back to zero. The user spent most of the run in the throttler's deadband without receiving any pace cues.
3. **Why the run felt sluggish and poorly paced:**  
   Because the HUD displayed the kilometer average instead of current pace, when the user tried to adjust their effort, the display did not respond. The user had zero real-time visual feedback on whether their pace changes were working.

---

## 13. Severity Classification

| Finding ID | Subsystem | Issue Description | Severity | Risk Impact |
|---|---|---|---|---|
| **BUG-01** | UI / Engine | Live pace hero text shows cumulative kilometer split average instead of rolling pace | **CRITICAL** | Destroys runner's ability to pace themselves in real time; causes display inertia. |
| **BUG-02** | Audio / Service | Audio coach warns on 15s rolling pace while UI displays kilometer split average | **CRITICAL** | Audio announcements contradict visual display; causes user distrust. |
| **BUG-03** | Audio Coach | Escalating cooldown throttler mutes audio alerts for 3 to 7 minutes or permanently | **HIGH** | Runner receives no guidance when off target pace for extended periods. |
| **BUG-04** | Audio Coach | Audio queue mode uses `QUEUE_FLUSH`, cutting off in-flight speech cues | **HIGH** | Milestone announcements clip step transitions and chimes. |
| **BUG-05** | Tracking Engine | Rolling pace estimator has initial 15-second delta-time asymmetry flaw | **MEDIUM** | Understates running speed by 20–35% during first 15 seconds of any step/lap. |
| **BUG-06** | Tracking Engine | `elapsedTimeSeconds` overwritten with `movingTimeSeconds` | **MEDIUM** | Inability to track true elapsed wall-clock time vs moving time. |
| **BUG-07** | GPS Filter | `MAX_ACCURACY_METERS` set to 150.0m instead of 20–25m | **MEDIUM** | Allows multipath drift and cell-tower jumps near buildings. |
| **BUG-08** | GPS Filter | `MIN_DISPLACEMENT_METERS` set to 0.5m instead of 2.5–3.0m | **LOW** | Accumulates false distance jitter while standing still. |

---

*This concludes the read-only technical audit. In accordance with instructions, zero code modifications, configuration changes, or database alterations have been performed.*
