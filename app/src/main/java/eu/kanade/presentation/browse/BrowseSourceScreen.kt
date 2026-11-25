package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.network.enableSecureDns
import eu.kanade.tachiyomi.network.secureDnsOffer
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Security
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    allowSecureDns: Boolean = false,
) {
    val context = LocalContext.current
    val networkPreferences = remember { context.appGraph.networkPreferences }

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    // Dipantau, tidak dibaca sekali saat komposisi: koneksi bisa hilang sambil layar error
    // tetap terbuka, dan tawaran DNS yang tertinggal setelah itu menyesatkan. Aliran jaringan
    // hanya pemicu; nilainya tetap Context.isOnline() supaya satu definisi "online" dipakai
    // di seluruh app (NET_CAPABILITY_VALIDATED sering false di emulator).
    var online by remember { mutableStateOf(context.isOnline()) }
    LaunchedEffect(context) {
        context.networkStateFlow().collect { online = context.isOnline() }
    }

    // Hanya layar sumber yang menawarkan (allowSecureDns) dan hanya saat online: gagal koneksi
    // tanpa jaringan bukan ulah DNS, dan pencarian migrasi belum perlu ditawari.
    val failedThrowable = (errorState as? LoadState.Error)?.error
    val offerSecureDns = allowSecureDns && online &&
        secureDnsOffer(failedThrowable?.message, networkPreferences.dohProvider.get())

    var showSecureDnsDialog by remember { mutableStateOf(false) }
    var secureDnsEnabledTick by remember { mutableStateOf(0) }

    val secureDnsEnabledMessage = stringResource(MR.strings.secure_dns_enabled)

    // Tick, bukan boolean: menulis ulang kuncinya di dalam efek ini membatalkan
    // coroutine-nya sendiri, sehingga snackbar hilang sebelum sempat terlihat.
    LaunchedEffect(secureDnsEnabledTick) {
        if (secureDnsEnabledTick == 0) return@LaunchedEffect
        // Tidak ada aksi "restart": begitu pref berubah, client dibangun ulang di tempat,
        // jadi yang perlu dilakukan pengguna cuma mencoba lagi. Mematikan aplikasi atas
        // nama pengguna juga bukan opsi — Android 10+ membatalkan peluncuran activity
        // dari proses yang sudah dimatikan, sehingga aplikasi hilang tanpa kembali.
        snackbarHostState.showSnackbar(secureDnsEnabledMessage)
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource) {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = MaterialSymbols.AutoMirroredRounded.Help,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                buildList {
                    add(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = MaterialSymbols.Rounded.Refresh,
                            onClick = mangaList::refresh,
                        ),
                    )
                    if (offerSecureDns) {
                        add(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_use_secure_dns,
                                icon = MaterialSymbols.Rounded.Security,
                                onClick = { showSecureDnsDialog = true },
                            ),
                        )
                    }
                    add(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_open_in_web_view,
                            icon = MaterialSymbols.Rounded.Public,
                            onClick = onWebViewClick,
                        ),
                    )
                    add(
                        EmptyScreenAction(
                            stringRes = MR.strings.label_help,
                            icon = MaterialSymbols.AutoMirroredRounded.Help,
                            onClick = onHelpClick,
                        ),
                    )
                }
            },
        )

        if (showSecureDnsDialog) {
            AlertDialog(
                onDismissRequest = { showSecureDnsDialog = false },
                title = { Text(stringResource(MR.strings.secure_dns_dialog_title)) },
                text = {
                    Text(stringResource(MR.strings.secure_dns_dialog_message, source?.name ?: ""))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            networkPreferences.enableSecureDns()
                            showSecureDnsDialog = false
                            secureDnsEnabledTick++
                        },
                    ) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSecureDnsDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
