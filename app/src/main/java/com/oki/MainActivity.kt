package com.oki

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.*
import androidx.navigation.compose.*
import com.oki.core.storage.*
import com.oki.core.ui.*
import com.oki.feature.assistant.*
import com.oki.feature.doctors.*
import com.oki.feature.scan.*
import com.oki.feature.settings.*
import com.oki.feature.tasks.*
import com.oki.feature.tutorial.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val openingTask = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openingTask.value = intent.getStringExtra("task_id")
        val c = (application as OkiApplication).container
        setContent {
            val preferences by c.settings.settings.collectAsStateWithLifecycle(Settings())
            val task by openingTask.collectAsStateWithLifecycle()
            val dark =
                preferences.appearance == Appearance.DARK ||
                    (preferences.appearance == Appearance.SYSTEM && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            OkiTheme(preferences.appearance) { OkiApp(c, task) { openingTask.value = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openingTask.value = intent.getStringExtra("task_id")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OkiApp(c: AppContainer, openingTask: String?, consumeTask: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destinationRoute = entry?.destination?.route ?: "home"
    val tabs = listOf("tasks", "doctors", "assistant")
    val pager = rememberPagerState(pageCount = { tabs.size })
    val primary = destinationRoute == "home"
    val route = if (primary) tabs[pager.currentPage] else destinationRoute
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val tasks: TasksViewModel =
        viewModel(factory = viewModelFactory { initializer { TasksViewModel(c) } })
    val doctors: DoctorsViewModel =
        viewModel(factory = viewModelFactory { initializer { DoctorsViewModel(c) } })
    val assistant: AssistantViewModel =
        viewModel(factory = viewModelFactory { initializer { AssistantViewModel(c) } })
    val settings: SettingsViewModel =
        viewModel(factory = viewModelFactory { initializer { SettingsViewModel(c) } })
    val tutorial: TutorialViewModel =
        viewModel(
            factory =
                viewModelFactory { initializer { TutorialViewModel(c, createSavedStateHandle()) } }
        )
    val tourActive by tutorial.isTourActive.collectAsStateWithLifecycle()
    val tourStep by tutorial.currentStep.collectAsStateWithLifecycle()
    val tourIndex by tutorial.currentStepIndex.collectAsStateWithLifecycle()
    val tourNavRequest by tutorial.pendingNavigation.collectAsStateWithLifecycle()
    val tourShowSkip by tutorial.showSkipConfirm.collectAsStateWithLifecycle()
    // Start tour on first launch.
    LaunchedEffect(Unit) { tutorial.startTourIfNeeded() }
    // Handle tutorial navigation requests.
    LaunchedEffect(tourNavRequest) {
        val request = tourNavRequest ?: return@LaunchedEffect
        tutorial.consumeNavigation()
        when (request.route) {
            "home/tasks" -> {
                if (destinationRoute != "home") nav.popBackStack("home", false)
                scope.launch {
                    pager.animateScrollToPage(
                        0,
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                    )
                }
            }
            "home/doctors" -> {
                if (destinationRoute != "home") nav.popBackStack("home", false)
                scope.launch {
                    pager.animateScrollToPage(
                        1,
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                    )
                }
            }
            "settings" -> {
                if (destinationRoute != "settings")
                    nav.navigate("settings") { launchSingleTop = true }
            }
            "doctor_detail" -> {
                // Navigate to first doctor if one exists.
                val allDoctors = doctors.doctors.value
                val firstId = allDoctors?.firstOrNull()?.id
                if (firstId != null && !destinationRoute.startsWith("doctor/"))
                    nav.navigate("doctor/$firstId")
            }
        }
    }
    var add by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var closingAddSheet by remember { mutableStateOf(false) }
    fun dismissThenOpen(open: () -> Unit) {
        if (closingAddSheet) return
        closingAddSheet = true
        scope.launch {
            try {
                addSheetState.hide()
                if (!addSheetState.isVisible) {
                    add = false
                    open()
                }
            } finally {
                closingAddSheet = false
            }
        }
    }
    // First run: collect every permission reminders depend on, rather than leaving the user to
    // find them in Settings. Shown only while something essential is missing.
    val storedSettings by c.settings.settings.collectAsStateWithLifecycle(null)
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    val setupNeeded =
        storedSettings?.let {
            !it.setupPromptDismissed &&
                !setupDismissed &&
                (!c.publisher.canNotify() ||
                    !c.reminders.hasExactAccess() ||
                    !c.publisher.ignoresBatteryOptimizations())
        } == true
    if (setupNeeded)
        PermissionSetupSheet(c) {
            setupDismissed = true
            scope.launch { c.settings.setSetupPromptDismissed(true) }
        }
    LifecycleResumeEffect(Unit) {
        val job =
            scope.launch {
                c.recover()
                // Self-heal while visible too, even when exact-alarm access is unavailable.
                while (true) {
                    delay(30_000)
                    c.doctors.ensureToday()
                }
            }
        onPauseOrDispose { job.cancel() }
    }
    fun tab(destination: String) {
        scope.launch {
            pager.animateScrollToPage(
                tabs.indexOf(destination),
                animationSpec = tween(250, easing = FastOutSlowInEasing),
            )
        }
    }
    fun editor(
        doctor: Boolean,
        id: String? = null,
        draft: String? = null,
        source: String = "IMAGE_SCAN",
        assistantMessageId: String? = null,
        scanDraftId: String? = null,
    ) {
        val destination = if (doctor) "doctorEdit" else "taskEdit"
        nav.navigate("$destination?id=${id.orEmpty()}")
        nav.currentBackStackEntry?.savedStateHandle?.apply {
            draft?.let { set("draft", it) }
            set("draftSource", source)
            assistantMessageId?.let { set("assistantMessageId", it) }
            scanDraftId?.let { set("scanDraftId", it) }
        }
    }
    LaunchedEffect(openingTask) {
        openingTask?.let {
            editor(false, it)
            consumeTask()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    tween(280, easing = FastOutSlowInEasing),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    tween(280, easing = FastOutSlowInEasing),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(280, easing = FastOutSlowInEasing),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    tween(280, easing = FastOutSlowInEasing),
                )
            },
        ) {
            composable("home") {
                AthiiScreenFrame(
                    title = "Athii",
                    actions = {
                        if (pager.currentPage == 2) {
                            IconButton(onClick = assistant::newChat) {
                                Icon(Icons.Outlined.AddComment, "New chat")
                            }
                            IconButton(onClick = assistant::showHistory) {
                                Icon(Icons.Outlined.History, "Chat history")
                            }
                        }
                        IconButton(
                            onClick = { nav.navigate("settings") { launchSingleTop = true } },
                            modifier = Modifier.tutorialTarget("settings_icon", c.tutorialTargets),
                        ) {
                            Icon(Icons.Outlined.Settings, "Settings")
                        }
                    },
                    bottomBar = { AthiiBottomBar(pager.targetPage) { tab(tabs[it]) } },
                    floatingActionButton = {
                        if (pager.currentPage != 2)
                            ExtendedFloatingActionButton(
                                onClick = { add = true },
                                icon = { Icon(Icons.Outlined.Add, null) },
                                text = {
                                    Text(if (pager.currentPage == 1) "Add doctor" else "Add task")
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.tutorialTarget("add_fab", c.tutorialTargets),
                            )
                    },
                ) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize().testTag("main-pager"),
                        key = { tabs[it] },
                        beyondViewportPageCount = 1,
                    ) { page ->
                        when (page) {
                            0 -> TasksScreen(tasks, c.tutorialTargets) { editor(false, it) }
                            1 ->
                                DoctorsScreen(
                                    doctors,
                                    { nav.navigate("doctor/$it") },
                                    c.tutorialTargets,
                                )
                            2 ->
                                AssistantScreen(
                                    assistant,
                                    { messageId, draft ->
                                        editor(
                                            false,
                                            draft = draft,
                                            source = "AI_CHAT",
                                            assistantMessageId = messageId,
                                        )
                                    },
                                    { messageId, draft ->
                                        editor(
                                            true,
                                            draft = draft,
                                            source = "AI_CHAT",
                                            assistantMessageId = messageId,
                                        )
                                    },
                                    { nav.navigate("settings") },
                                )
                        }
                    }
                }
            }
            composable("settings") {
                AthiiScreenFrame("Settings", { nav.popBackStack() }) {
                    SettingsScreen(
                        settings,
                        assistant::clear,
                        { nav.popBackStack("home", false) },
                        replayTour = {
                            nav.popBackStack("home", false)
                            tutorial.replayTour()
                        },
                        tutorialTargets = c.tutorialTargets,
                    )
                }
            }
            composable("doctor/{id}") { back ->
                AthiiScreenFrame("Doctor", { nav.popBackStack() }) {
                    DoctorDetailScreen(
                        back.arguments!!.getString("id")!!,
                        doctors,
                        { editor(true, back.arguments!!.getString("id")) },
                        { nav.popBackStack() },
                        tutorialTargets = c.tutorialTargets,
                    )
                }
            }
            composable(
                "taskEdit?id={id}",
                arguments = listOf(navArgument("id") { defaultValue = "" }),
            ) { back ->
                val vm: TaskEditorViewModel =
                    viewModel(
                        factory =
                            viewModelFactory {
                                initializer {
                                    TaskEditorViewModel(
                                        c,
                                        createSavedStateHandle().apply {
                                            set(
                                                "draftSource",
                                                back.savedStateHandle.get<String>("draftSource")
                                                    ?: "IMAGE_SCAN",
                                            )
                                        },
                                        back.arguments?.getString("id")?.ifBlank { null },
                                        back.savedStateHandle.get<String>("draft"),
                                    )
                                }
                            }
                    )
                AthiiScreenFrame("Task details", { nav.popBackStack() }) {
                    TaskEditorScreen(
                        vm,
                        { nav.popBackStack() },
                        c.publisher::canNotify,
                        c.reminders::hasExactAccess,
                        { nav.navigate("settings") },
                        {
                            back.savedStateHandle.get<String>("assistantMessageId")?.let {
                                assistant.markDraftSaved(it)
                            }
                            back.savedStateHandle.get<String>("scanDraftId")?.let {
                                nav.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("savedScanDraftId", it)
                            }
                            nav.popBackStack()
                        },
                    )
                }
            }
            composable(
                "doctorEdit?id={id}",
                arguments = listOf(navArgument("id") { defaultValue = "" }),
            ) { back ->
                val id = back.arguments?.getString("id")?.ifBlank { null }
                val vm: DoctorEditorViewModel =
                    viewModel(
                        factory =
                            viewModelFactory {
                                initializer {
                                    DoctorEditorViewModel(
                                        c,
                                        createSavedStateHandle().apply {
                                            set(
                                                "draftSource",
                                                back.savedStateHandle.get<String>("draftSource")
                                                    ?: "IMAGE_SCAN",
                                            )
                                        },
                                        id,
                                        back.savedStateHandle.get<String>("draft"),
                                    )
                                }
                            }
                    )
                AthiiScreenFrame("Doctor details", { nav.popBackStack() }) {
                    DoctorEditorScreen(
                        vm = vm,
                        editing = id != null,
                        savedBack = {
                            back.savedStateHandle.get<String>("assistantMessageId")?.let {
                                assistant.markDraftSaved(it)
                            }
                            back.savedStateHandle.get<String>("scanDraftId")?.let {
                                nav.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("savedScanDraftId", it)
                            }
                            nav.popBackStack()
                        },
                        back = { nav.popBackStack() },
                    )
                }
            }
            composable("scan/{kind}") { back ->
                val doctor = back.arguments?.getString("kind") == "doctor"
                val vm: ScanViewModel =
                    viewModel(
                        factory =
                            viewModelFactory {
                                initializer {
                                    ScanViewModel(
                                        c,
                                        context.applicationContext,
                                        createSavedStateHandle(),
                                    )
                                }
                            }
                    )
                val savedDraftId by
                    back.savedStateHandle
                        .getStateFlow<String?>("savedScanDraftId", null)
                        .collectAsStateWithLifecycle()
                LaunchedEffect(savedDraftId) {
                    savedDraftId?.let {
                        vm.markSaved(it)
                        back.savedStateHandle["savedScanDraftId"] = null
                    }
                }
                AthiiScreenFrame("Scan image", { nav.popBackStack() }) {
                    ScanScreen(
                        vm,
                        doctor,
                        { draftId, draft -> editor(doctor, draft = draft, scanDraftId = draftId) },
                        { editor(doctor) },
                    )
                }
            }
        }
    }
    // Tutorial overlay — drawn above everything.
    if (tourActive) {
        tourStep?.let { step ->
            TutorialOverlay(
                step = step,
                stepIndex = tourIndex,
                totalSteps = tutorial.totalSteps,
                registry = c.tutorialTargets,
                onNext = tutorial::next,
                onPrevious = tutorial::previous,
                onSkip = tutorial::requestSkip,
                onFinish = tutorial::finish,
                onStartTour = tutorial::next,
                showSkipConfirm = tourShowSkip,
                onConfirmSkip = tutorial::confirmSkip,
                onCancelSkip = tutorial::cancelSkip,
                onTargetUnavailable = tutorial::skipUnavailableTarget,
            )
        }
    }
    if (add)
        ModalBottomSheet(
            onDismissRequest = { add = false },
            sheetState = addSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (route == "doctors") "Add to your directory" else "What’s next?",
                    style = MaterialTheme.typography.headlineSmall,
                )
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val doctor = route == "doctors"
                                dismissThenOpen { editor(doctor) }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("Create manually")
                        }
                        OutlinedButton(
                            onClick = {
                                val kind = if (route == "doctors") "doctor" else "task"
                                dismissThenOpen { nav.navigate("scan/$kind") }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("Scan image")
                        }
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AthiiScreenFrame(
    title: String,
    back: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    },
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Each destination owns a stable, opaque frame, so bars travel with its content.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    back?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    }
                },
                actions = actions,
                windowInsets =
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    ),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}
