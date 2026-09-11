package com.oki

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
                animationSpec = tween(420, easing = FastOutSlowInEasing),
            )
        }
    }
    fun editor(
        doctor: Boolean,
        id: String? = null,
        draft: String? = null,
        source: String = "IMAGE_SCAN",
        assistantMessageId: String? = null,
    ) {
        val destination = if (doctor) "doctorEdit" else "taskEdit"
        nav.navigate("$destination?id=${id.orEmpty()}")
        nav.currentBackStackEntry?.savedStateHandle?.apply {
            draft?.let { set("draft", it) }
            set("draftSource", source)
            assistantMessageId?.let { set("assistantMessageId", it) }
        }
    }
    LaunchedEffect(openingTask) {
        openingTask?.let {
            editor(false, it)
            consumeTask()
        }
    }
    Scaffold(
        // App bars own their respective vertical insets; content additionally protects cutouts.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (primary) "Athii"
                        else
                            when {
                                route.startsWith("taskEdit") -> "Task details"
                                route.startsWith("doctorEdit") -> "Doctor details"
                                route.startsWith("doctor/") -> "Doctor"
                                route.startsWith("scan") -> "Scan image"
                                else -> "Settings"
                            },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (!primary)
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                },
                actions = {
                    if (primary) {
                        if (route == "assistant")
                            IconButton(onClick = assistant::showHistory) {
                                Icon(Icons.Outlined.History, "Chat history")
                            }
                        IconButton(onClick = { nav.navigate("settings") }) {
                            Icon(Icons.Outlined.Settings, "Settings")
                        }
                    }
                },
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
        bottomBar = {
            if (primary) AthiiBottomBar(pager.targetPage) { tab(tabs[it]) }
            else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        },
        floatingActionButton = {
            if (route == "tasks" || route == "doctors")
                ExtendedFloatingActionButton(
                    onClick = { add = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(if (route == "doctors") "Add doctor" else "Add task") },
                )
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            enterTransition = {
                slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it }
            },
            exitTransition = {
                slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 }
            },
            popEnterTransition = {
                slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 }
            },
            popExitTransition = {
                slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it }
            },
        ) {
            composable("home") {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().testTag("main-pager"),
                    key = { tabs[it] },
                    beyondViewportPageCount = 0,
                ) { page ->
                    when (page) {
                        0 -> TasksScreen(tasks) { editor(false, it) }
                        1 -> DoctorsScreen(doctors) { nav.navigate("doctor/$it") }
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
            composable("settings") {
                SettingsScreen(settings, assistant::clear, { nav.popBackStack("home", false) })
            }
            composable("doctor/{id}") { back ->
                DoctorDetailScreen(
                    back.arguments!!.getString("id")!!,
                    doctors,
                    { editor(true, back.arguments!!.getString("id")) },
                    { nav.popBackStack() },
                )
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
                        nav.popBackStack()
                    },
                )
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
                                        createSavedStateHandle(),
                                        id,
                                        back.savedStateHandle.get<String>("draft"),
                                    )
                                }
                            }
                    )
                DoctorEditorScreen(
                    vm = vm,
                    editing = id != null,
                    savedBack = {
                        back.savedStateHandle.get<String>("assistantMessageId")?.let {
                            assistant.markDraftSaved(it)
                        }
                        nav.popBackStack()
                    },
                    back = { nav.popBackStack() },
                )
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
                ScanScreen(
                    vm,
                    doctor,
                    { editor(doctor, draft = it) },
                    { editor(doctor) },
                    { nav.navigate("settings") },
                )
            }
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
