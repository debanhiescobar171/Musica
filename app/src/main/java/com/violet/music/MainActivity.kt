package com.violet.music

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.UUID

// ───────────── Actividad ─────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            VioletTheme { MainScreen() }
        }
    }
}

// ───────────── Tema ─────────────

object VioletColors {
    val Black = Color(0xFF05010A)
    val Deep = Color(0xFF12041F)
    val Glow = Color(0xFF2A0B4D)
    val CardBg = Color(0xFF160B26)
    val CardHigh = Color(0xFF211138)
    val Violet = Color(0xFFA855F7)
    val VioletBright = Color(0xFFC084FC)
    val VioletDeep = Color(0xFF7C3AED)
    val VioletDark = Color(0xFF3B0764)
    val TextPrimary = Color(0xFFF5EEFF)
    val TextSecondary = Color(0xFFB7A6D1)
    val Outline = Color(0x33A855F7)
}

private val VioletScheme = darkColorScheme(
    primary = VioletColors.Violet,
    onPrimary = Color.White,
    secondary = VioletColors.VioletBright,
    background = VioletColors.Black,
    onBackground = VioletColors.TextPrimary,
    surface = VioletColors.CardBg,
    onSurface = VioletColors.TextPrimary,
    surfaceVariant = VioletColors.CardHigh,
    onSurfaceVariant = VioletColors.TextSecondary
)

@Composable
fun VioletTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VioletScheme, typography = Typography(), content = content)
}

// ───────────── Datos ─────────────

@Entity(tableName = "songs", indices = [Index(value = ["uri"], unique = true)])
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverPath: String?,
    val addedAt: Long
)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: Song): Long

    @Query("SELECT COUNT(*) FROM songs WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int
}

@Database(entities = [Song::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "violet.db"
                ).build().also { instance = it }
            }
    }
}

// ───────────── Importación ─────────────

data class ImportResult(val added: Int, val duplicates: Int, val failed: Int)

class AudioImporter(private val context: Context, private val dao: SongDao) {

    private enum class Outcome { ADDED, DUPLICATE, FAILED }

    private class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val art: ByteArray?
    )

    suspend fun importAll(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var duplicates = 0
        var failed = 0
        for (uri in uris.distinct()) {
            ensureActive()
            try {
                when (importOne(uri)) {
                    Outcome.ADDED -> added++
                    Outcome.DUPLICATE -> duplicates++
                    Outcome.FAILED -> failed++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        ImportResult(added, duplicates, failed)
    }

    private suspend fun importOne(uri: Uri): Outcome {
        val uriString = uri.toString()
        if (dao.countByUri(uriString) > 0) return Outcome.DUPLICATE

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Sin permiso persistente; se continúa.
        }

        val meta = readMetadata(uri) ?: return Outcome.FAILED
        val fileTitle = displayName(uri)?.substringBeforeLast('.')?.clean()
        val coverPath = meta.art?.let { saveCover(it) }

        val rowId = dao.insert(
            Song(
                uri = uriString,
                title = meta.title.clean() ?: fileTitle ?: "Sin título",
                artist = meta.artist.clean() ?: "Artista desconocido",
                album = meta.album.clean() ?: "Álbum desconocido",
                durationMs = meta.durationMs,
                coverPath = coverPath,
                addedAt = System.currentTimeMillis()
            )
        )
        if (rowId == -1L) {
            coverPath?.let { File(it).delete() }
            return Outcome.DUPLICATE
        }
        return Outcome.ADDED
    }

    private fun readMetadata(uri: Uri): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            if (hasAudio != "yes" && !mime.startsWith("audio")) {
                null
            } else {
                Meta(
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L,
                    art = retriever.embeddedPicture
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Sin acción.
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun saveCover(bytes: ByteArray): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return null

            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

// ───────────── ViewModel ─────────────

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val isImporting: Boolean = false
)

private val DiacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")

private fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(DiacriticsRegex, "")
        .lowercase()

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).songDao()
    private val importer = AudioImporter(application, dao)

    private val query = MutableStateFlow("")
    private val importing = MutableStateFlow(false)
    private val messageChannel = Channel<String>(Channel.BUFFERED)

    val messages = messageChannel.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> =
        combine(dao.observeAll(), query, importing) { songs, q, isImporting ->
            val needle = q.trim().normalized()
            val filtered = if (needle.isEmpty()) {
                songs
            } else {
                songs.filter {
                    it.title.normalized().contains(needle) ||
                        it.artist.normalized().contains(needle)
                }
            }
            LibraryUiState(
                songs = filtered,
                totalCount = songs.size,
                isLoading = false,
                isImporting = isImporting
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty() || importing.value) return
        viewModelScope.launch {
            importing.value = true
            try {
                messageChannel.send(summarize(importer.importAll(uris)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send("No se pudo completar la importación.")
            } finally {
                importing.value = false
            }
        }
    }

    private fun summarize(r: ImportResult): String {
        val parts = mutableListOf<String>()
        if (r.added > 0) parts += if (r.added == 1) "1 canción importada" else "${r.added} canciones importadas"
        if (r.duplicates > 0) parts += if (r.duplicates == 1) "1 ya existía" else "${r.duplicates} ya existían"
        if (r.failed > 0) parts += if (r.failed == 1) "1 no se pudo leer" else "${r.failed} no se pudieron leer"
        return if (parts.isEmpty()) "No se importó ningún archivo" else parts.joinToString(" · ")
    }
}

// ───────────── Interfaz ─────────────

private enum class AppTab(val label: String, val icon: ImageVector) {
    Library("Biblioteca", Icons.Rounded.LibraryMusic),
    Favorites("Favoritas", Icons.Rounded.Favorite),
    Playlists("Playlists", Icons.Rounded.QueueMusic)
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importAudio(uris) }
    val launchPicker: () -> Unit = { picker.launch(arrayOf("audio/*", "application/ogg")) }

    LaunchedEffect(Unit) { vm.setQuery(query) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbarHostState.showSnackbar(it) } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to VioletColors.Glow,
                    0.35f to VioletColors.Deep,
                    1f to VioletColors.Black
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { VioletNavBar(selected = tabIndex, onSelect = { tabIndex = it }) }
        ) { padding ->
            Crossfade(
                targetState = tabIndex,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                label = "tabs"
            ) { current ->
                if (current == 0) {
                    LibraryScreen(
                        state = state,
                        query = query,
                        onQueryChange = {
                            query = it
                            vm.setQuery(it)
                        },
                        onImport = launchPicker
                    )
                } else {
                    val tab = AppTab.values()[current]
                    MessageState(
                        icon = tab.icon,
                        title = tab.label,
                        subtitle = "Esta sección estará disponible en una próxima etapa."
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onImport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(count = state.totalCount, onImport = onImport)

        AnimatedVisibility(visible = state.isImporting) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(50)),
                color = VioletColors.VioletBright,
                trackColor = VioletColors.CardHigh
            )
        }

        SearchField(query = query, onQueryChange = onQueryChange)

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VioletColors.VioletBright)
            }

            state.totalCount == 0 -> EmptyLibrary(onImport = onImport)

            state.songs.isEmpty() -> MessageState(
                icon = Icons.Rounded.Search,
                title = "Sin resultados",
                subtitle = "No encontramos canciones para \"${query.trim()}\"."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.songs, key = { it.id }) { song -> SongCard(song) }
            }
        }
    }
}

@Composable
private fun Header(count: Int, onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "VIOLET",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        brush = Brush.horizontalGradient(
                            listOf(VioletColors.VioletBright, VioletColors.VioletDeep)
                        )
                    ),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Music",
                    color = VioletColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Text(
                text = when (count) {
                    0 -> "Tu biblioteca está vacía"
                    1 -> "1 canción"
                    else -> "$count canciones"
                },
                color = VioletColors.TextSecondary,
                fontSize = 13.sp
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(VioletColors.VioletBright, VioletColors.VioletDeep))
                )
                .clickable(onClick = onImport),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Importar música",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        placeholder = { Text("Buscar canción o artista") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Borrar búsqueda")
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = VioletColors.CardBg,
            unfocusedContainerColor = VioletColors.CardBg,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = VioletColors.VioletBright,
            focusedTextColor = VioletColors.TextPrimary,
            unfocusedTextColor = VioletColors.TextPrimary,
            focusedPlaceholderColor = VioletColors.TextSecondary,
            unfocusedPlaceholderColor = VioletColors.TextSecondary,
            focusedLeadingIconColor = VioletColors.VioletBright,
            unfocusedLeadingIconColor = VioletColors.TextSecondary,
            focusedTrailingIconColor = VioletColors.TextSecondary,
            unfocusedTrailingIconColor = VioletColors.TextSecondary
        )
    )
}

@Composable
private fun SongCard(song: Song) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = VioletColors.CardBg,
        border = BorderStroke(1.dp, VioletColors.Outline)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(path = song.coverPath, size = 60.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = VioletColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    color = VioletColors.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (song.durationMs > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = formatDuration(song.durationMs),
                    color = VioletColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun Cover(path: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(VioletColors.VioletDeep, VioletColors.VioletDark))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = VioletColors.VioletBright,
            modifier = Modifier.size(size / 2)
        )
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BigIcon(Icons.Rounded.LibraryMusic)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Tu biblioteca está vacía",
            color = VioletColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Importa canciones de tu teléfono para empezar a construir tu colección.",
            color = VioletColors.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(
                containerColor = VioletColors.Violet,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(50)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Importar música")
        }
    }
}

@Composable
private fun MessageState(icon: ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BigIcon(icon)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            color = VioletColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = VioletColors.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BigIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(VioletColors.CardHigh, VioletColors.CardBg))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VioletColors.VioletBright,
            modifier = Modifier.size(44.dp)
        )
    }
}

@Composable
private fun VioletNavBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xF0100620), tonalElevation = 0.dp) {
        AppTab.values().forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = VioletColors.VioletBright,
                    indicatorColor = VioletColors.VioletDeep,
                    unselectedIconColor = VioletColors.TextSecondary,
                    unselectedTextColor = VioletColors.TextSecondary
                )
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
