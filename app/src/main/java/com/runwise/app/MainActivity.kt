package com.runwise.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.runwise.app.data.api.ApiClient
import com.runwise.app.data.model.CustomWorkout
import com.runwise.app.data.model.StepType
import com.runwise.app.data.model.TargetType
import com.runwise.app.data.model.WorkoutStep
import com.runwise.app.service.TrackingService
import com.runwise.app.ui.active.ActiveRunScreen
import com.runwise.app.ui.activities.ActivitiesScreen
import com.runwise.app.ui.activities.ActivityDetailScreen
import com.runwise.app.ui.dashboard.DashboardScreen
import com.runwise.app.ui.exercises.ExercisesScreen
import com.runwise.app.ui.plan.PlanScreen
import com.runwise.app.ui.settings.SettingsScreen
import com.runwise.app.ui.theme.*
import com.runwise.app.ui.trends.TrendsScreen
import com.runwise.app.ui.workout.WorkoutBuilderScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Coach", Icons.Default.Dashboard)
    object WorkoutBuilder : Screen("workouts", "Workouts", Icons.Default.Speed)
    object Exercises : Screen("exercises", "Exercises", Icons.Default.FitnessCenter)
    object Trends : Screen("trends", "Trends", Icons.Default.BarChart)
    object Activities : Screen("activities", "Activities", Icons.Default.DirectionsRun)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object ActiveRun : Screen("active_run", "Active Run", Icons.Default.PlayArrow)
}

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val app = application as RunwiseApplication
        val repository = app.repository

        requestRequiredPermissions()

        setContent {
            RunwiseTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val runState by TrackingService.runState.collectAsState()

                // If workout is actively tracking or preparing, show full-screen Active HUD
                if ((runState.isRunning || runState.isPreStart) && currentRoute != Screen.ActiveRun.route) {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.ActiveRun.route) {
                            popUpTo(Screen.Dashboard.route) { inclusive = false }
                        }
                    }
                }

                val bottomNavScreens = listOf(
                    Screen.Dashboard,
                    Screen.WorkoutBuilder,
                    Screen.Exercises,
                    Screen.Trends,
                    Screen.Activities,
                    Screen.Settings
                )

                val showBottomBar = currentRoute != Screen.ActiveRun.route && !runState.isRunning && !runState.isPreStart

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(
                                containerColor = SurfaceDark,
                                contentColor = TextPrimaryDark,
                                tonalElevation = 8.dp
                            ) {
                                bottomNavScreens.forEach { screen ->
                                    val isSelected = currentRoute == screen.route
                                    NavigationBarItem(
                                        icon = {
                                            Icon(
                                                screen.icon,
                                                contentDescription = screen.title,
                                                tint = if (isSelected) RunwiseOrange else TextSecondaryDark
                                            )
                                        },
                                        label = {
                                            Text(
                                                screen.title,
                                                color = if (isSelected) RunwiseOrange else TextSecondaryDark,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        selected = isSelected,
                                        onClick = {
                                            if (currentRoute != screen.route) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(Screen.Dashboard.route) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = CardDark
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                repository = repository,
                                onStartWorkout = { workout ->
                                    startStructuredWorkout(workout, "Run")
                                    navController.navigate(Screen.ActiveRun.route)
                                },
                                onQuickStartActivity = { sportType ->
                                    val customWorkout = CustomWorkout(
                                        name = "Free $sportType",
                                        steps = listOf(
                                            WorkoutStep(
                                                title = "Free $sportType",
                                                stepType = StepType.RUN,
                                                targetType = TargetType.OPEN,
                                                targetPaceSecPerKm = 0
                                            )
                                        )
                                    )
                                    startStructuredWorkout(customWorkout, sportType)
                                    navController.navigate(Screen.ActiveRun.route)
                                },
                                onNavigateToPlan = { navController.navigate("plan") },
                                onNavigateToTrends = { navController.navigate(Screen.Trends.route) },
                                onNavigateToCalf = { navController.navigate(Screen.Exercises.route) },
                                onNavigateToWorkoutBuilder = { navController.navigate(Screen.WorkoutBuilder.route) }
                            )
                        }

                        composable("plan") {
                            PlanScreen(
                                repository = repository,
                                onStartWorkout = { workout ->
                                    startStructuredWorkout(workout, "Run")
                                    navController.navigate(Screen.ActiveRun.route)
                                },
                                onNavigateToExercises = { navController.navigate(Screen.Exercises.route) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("activity_detail/{activityId}") { backStackEntry ->
                            val activityId = backStackEntry.arguments?.getString("activityId") ?: ""
                            ActivityDetailScreen(
                                activityId = activityId,
                                repository = repository,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.WorkoutBuilder.route) {
                            WorkoutBuilderScreen(
                                repository = repository,
                                onStartWorkout = { workout ->
                                    startStructuredWorkout(workout, "Run")
                                    navController.navigate(Screen.ActiveRun.route)
                                }
                            )
                        }

                        composable(Screen.Exercises.route) {
                            ExercisesScreen(repository = repository)
                        }

                        composable(Screen.Trends.route) {
                            TrendsScreen(repository = repository)
                        }

                        composable(Screen.Activities.route) {
                            ActivitiesScreen(
                                repository = repository,
                                onActivityClick = { activityId ->
                                    navController.navigate("activity_detail/$activityId")
                                }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(repository = repository)
                        }

                        composable(Screen.ActiveRun.route) {
                            ActiveRunScreen(
                                onFinishRun = {
                                    navController.navigate(Screen.Dashboard.route) {
                                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startStructuredWorkout(workout: CustomWorkout, sportType: String = "Run") {
        val moshi = ApiClient.getMoshi()
        val workoutJson = moshi.adapter(CustomWorkout::class.java).toJson(workout)
        val firstStepPace = workout.steps.firstOrNull()?.targetPaceSecPerKm ?: 0

        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_PREPARE
            putExtra(TrackingService.EXTRA_WORKOUT_JSON, workoutJson)
            putExtra(TrackingService.EXTRA_TARGET_PACE, firstStepPace)
            putExtra(TrackingService.EXTRA_WORKOUT_TYPE, workout.name)
            putExtra(TrackingService.EXTRA_SPORT_TYPE, sportType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
