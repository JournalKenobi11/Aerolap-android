package com.runwise.app.data.model

data class ExerciseDetail(
    val name: String,
    val badge: String,
    val tabIndex: Int, // 0 = Calf & Strength, 1 = Plan Exercises, 2 = Stretching
    val defaultSets: String,
    val equipment: String,
    val instructions: String,
    val keyBenefit: String
)

object ExerciseCatalog {
    val allExercises = listOf(
        // Tab 0: Calf & Home Strength (with 2kg DBs)
        ExerciseDetail(
            name = "Single-Leg Calf Raise Capacity Test",
            badge = "BENCHMARK: 20 REPS",
            tabIndex = 0,
            defaultSets = "3 sets to fatigue (per leg)",
            equipment = "Bodyweight (wall for balance)",
            instructions = "Stand barefoot on flat floor or step edge on one leg. Lift heel up smoothly for 2s, hold top 1s, lower slowly for 2s. Do not bend knee or claw toes. Rest 60s between legs.",
            keyBenefit = "Builds gastrocnemius endurance to prevent late-race 10K cramping."
        ),
        ExerciseDetail(
            name = "Bent-Knee Soleus Dumbbell Raise",
            badge = "2KG DUMBBELLS",
            tabIndex = 0,
            defaultSets = "3 sets × 15 reps (per leg)",
            equipment = "Pair of 2kg Dumbbells + Chair",
            instructions = "Sit on a chair, place your 2kg dumbbells on your lower thigh just above the knee. With knee bent at 90°, elevate your heel as high as possible. Pause at top, lower under control.",
            keyBenefit = "Soleus absorbs up to 8x bodyweight during running. Crucial for holding 6:15 pace."
        ),
        ExerciseDetail(
            name = "Eccentric Heel Drop Over Step Edge",
            badge = "ACHILLES SHIELD",
            tabIndex = 0,
            defaultSets = "3 sets × 15 reps",
            equipment = "Step edge or staircase",
            instructions = "Rise up on both feet on a step. Shift weight to single leg, then take 3 full seconds to slowly lower heel below step level. Push back up with both feet.",
            keyBenefit = "Lengthens calf muscle fibers under load and fortifies the Achilles tendon."
        ),
        ExerciseDetail(
            name = "Isometric Wall Soleus Hold",
            badge = "PAIN INHIBITION",
            tabIndex = 0,
            defaultSets = "3 sets × 45 seconds",
            equipment = "Wall",
            instructions = "Lean against a wall in a slight quarter squat. Rise onto the balls of your feet and hold statically at 75% max height for 45 seconds.",
            keyBenefit = "Analgesic tendon relief; stiffens Achilles spring for energy return."
        ),
        ExerciseDetail(
            name = "Dumbbell Exercises",
            badge = "HOME STRENGTH",
            tabIndex = 0,
            defaultSets = "3 sets × 12 reps",
            equipment = "Pair of 2kg Dumbbells",
            instructions = "Perform controlled dumbbell goblet squats, lunges, and bent-knee calf raises with your 2kg dumbbells to build core and leg stiffness.",
            keyBenefit = "Develops muscular stamina for steady race cadence."
        ),

        // Tab 2: Stretching & Mobility
        ExerciseDetail(
            name = "Wall Calf Stretch",
            badge = "POST-RUN",
            tabIndex = 2,
            defaultSets = "2 sets × 40s per leg",
            equipment = "Wall",
            instructions = "Place hands on wall at shoulder height. Step one leg back with heel flat on ground and back knee straight. Push hips forward gently to feel calf stretch.",
            keyBenefit = "Decompresses gastrocnemius after high-cadence tempos."
        ),
        ExerciseDetail(
            name = "Calf Stretches",
            badge = "POST-RUN",
            tabIndex = 2,
            defaultSets = "3 sets × 40s per leg",
            equipment = "Wall or Step edge",
            instructions = "Alternate straight-knee gastrocnemius stretch with bent-knee soleus wall stretch. Hold relaxed breathing without bouncing.",
            keyBenefit = "Prevents delayed-onset tendon tightness and restores ankle dorsiflexion."
        ),
        ExerciseDetail(
            name = "Hip Flexor Stretch",
            badge = "POST-RUN",
            tabIndex = 2,
            defaultSets = "2 sets × 45s per leg",
            equipment = "Floor / Mat",
            instructions = "Kneel in half-kneeling lunge. Tuck pelvis under (posterior tilt) and squeeze back glute until stretch is felt in front of hip.",
            keyBenefit = "Unlocks tight hip flexors and counteracts long seated hours."
        ),
        ExerciseDetail(
            name = "Standing Quadriceps & Hip Flexor Stretch",
            badge = "POST-RUN",
            tabIndex = 2,
            defaultSets = "2 sets × 45s per leg",
            equipment = "Bodyweight",
            instructions = "Stand tall, bend one knee and grab your ankle behind you. Keep knees together and tuck your pelvis slightly under to feel deep quad and hip flexor stretch.",
            keyBenefit = "Relieves anterior pelvic tilt and knee patellar load after pacing runs."
        ),
        ExerciseDetail(
            name = "Dynamic Hamstring Scoop & Hinge",
            badge = "PRE & POST RUN",
            tabIndex = 2,
            defaultSets = "2 sets × 10 reps per leg",
            equipment = "Bodyweight",
            instructions = "Step one heel forward with toe pointed up. Hinge at hips with straight back, reach hands down and sweep arms forward in a fluid scooping motion.",
            keyBenefit = "Mobilizes posterior chain and sciatic nerve glide for a free stride."
        ),
        ExerciseDetail(
            name = "Plantar Fascia & Arch Rolling",
            badge = "DAILY",
            tabIndex = 2,
            defaultSets = "2 minutes per foot",
            equipment = "2kg Dumbbell handle or ball",
            instructions = "Place 2kg dumbbell handle or small ball under the arch of your foot while seated. Apply moderate pressure and roll from heel to ball of foot.",
            keyBenefit = "Releases plantar fascia tension directly connected to calf tightness."
        ),
        ExerciseDetail(
            name = "Post-Run Mobility",
            badge = "RECOVERY",
            tabIndex = 2,
            defaultSets = "10 mins flow",
            equipment = "Floor / Mat",
            instructions = "Perform gentle hip 90/90 switches, ankle circles, downward dog calf pedals, and child's pose breathing to flush lactic acid.",
            keyBenefit = "Downregulates central nervous system and accelerates recovery."
        )
    )

    fun findDetail(queryName: String): ExerciseDetail {
        val q = queryName.trim().lowercase()
        return allExercises.firstOrNull { it.name.lowercase() == q }
            ?: allExercises.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
            ?: allExercises.firstOrNull {
                when {
                    q.contains("calf") -> it.name.contains("Calf", ignoreCase = true)
                    q.contains("soleus") -> it.name.contains("Soleus", ignoreCase = true)
                    q.contains("stretch") -> it.name.contains("Stretch", ignoreCase = true)
                    q.contains("dumbbell") -> it.name.contains("Dumbbell", ignoreCase = true)
                    q.contains("mobility") -> it.name.contains("Mobility", ignoreCase = true)
                    else -> false
                }
            }
            ?: ExerciseDetail(
                name = queryName,
                badge = "PROTOCOL",
                tabIndex = 1,
                defaultSets = "3 sets",
                equipment = "Bodyweight / Home Equipment",
                instructions = "Perform strictly as prescribed in your Aerolap 10K coaching plan. Focus on smooth cadence and controlled motion.",
                keyBenefit = "Supports muscular recovery and joint stability."
            )
    }
}
