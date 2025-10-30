package com.gradecal.plus.features.bput.ui.utils

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.*
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import java.util.UUID

/**
 * ---------------------------------------------------------------------
 * MVI (Model-View-Intent) Architecture - REACTIVE + PAGED
 *
 * This file is updated to use:
 * 1. DAO: `Flow` for reads, `PagingSource` for paged reads, `suspend fun` for writes.
 * 2. Repo:
 * - `getUserList()`: Reactive read, returns `Flow<DataState<List>>`.
 * - `getUsersPaged()`: Paged read, returns `Flow<PagingData>`.
 * - `insertUser()`: Stateful write, returns `Flow<DataState<Unit>>`.
 * 3. ViewModel:
 * - Exposes `StateFlow` for simple list.
 * - Exposes `Flow` (cached) for paged list.
 * - Exposes `StateFlow` for write operations (e.g., button loading).
 * ---------------------------------------------------------------------
 */

/* DataState represents the different states of data handling in the app.*/
sealed class DataState<out R> {
    data class Success<out T>(val data: T) : DataState<T>() // Successful data state
    data class Error(val exception: Exception) : DataState<Nothing>() // Error state
    data object Loading : DataState<Nothing>() // Loading state
    data object Empty : DataState<Nothing>() // Empty state
}

/**
 * User data class representing the application's data model.
 */
@Entity(tableName = "user")
data class User(
    @PrimaryKey(autoGenerate = false)
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val age: Int = 0,
    @JvmField
    val isChild: Boolean = age < 18
)

/**
 * UserDao is Database access Object (Room).
 *
 * UPDATED: Added PagingSource for paged reads.
 */
@Dao
interface UserDao {
    /**
     * READ (Reactive): For a simple, auto-updating list.
     */
    @Query("SELECT * FROM user ORDER BY age DESC")
    fun getUserList(): Flow<List<User>>

    /**
     * READ (Paged): Returns a PagingSource factory for Paging 3.
     */
    @Query("SELECT * FROM user ORDER BY age DESC")
    fun getUsersPaged(): PagingSource<Int, User>

    /**
     * WRITE (One-Shot): Inserts a user.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    /**
     * WRITE (One-Shot): Deletes a user.
     */
    @Delete
    suspend fun deleteUser(user: User)
}


/**
 * Repository class responsible for data operations.
 *
 * UPDATED: Added stateful writes and paged reads.
 */
class UserRepository constructor(
    private val dao: UserDao
) {

    /**
     * READ (Reactive): For the simple list screen.
     */
    fun getUserList(): Flow<DataState<List<User>>> {
        return dao.getUserList()
            .map<List<User>, DataState<List<User>>> { userList ->
                if (userList.isNotEmpty()) {
                    DataState.Success(userList)
                } else {
                    DataState.Empty
                }
            }
            .onStart { emit(DataState.Loading) }
            .catch { e -> if (e is Exception) emit(DataState.Error(e)) else throw e }
    }

    /**
     * READ (Paged): For the paged list screen.
     */
    fun getUsersPaged(): Flow<PagingData<User>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { dao.getUsersPaged() }
        ).flow
    }


    /**
     * WRITE (Stateful): Uses flow builder to report Loading/Success/Error.
     */
    fun insertUser(user: User): Flow<DataState<Unit>> = flow {
        emit(DataState.Loading)
        try {
            dao.insertUser(user)
            emit(DataState.Success(Unit)) // Unit signifies success with no data
        } catch (e: Exception) {
            emit(DataState.Error(e))
        }
    }

    /**
     * WRITE (Stateful): Uses flow builder to report Loading/Success/Error.
     */
    fun deleteUser(user: User): Flow<DataState<Unit>> = flow {
        emit(DataState.Loading)
        try {
            dao.deleteUser(user)
            emit(DataState.Success(Unit))
        } catch (e: Exception) {
            emit(DataState.Error(e))
        }
    }
}

/**
 * UserEvent represents different user actions or intents.
 *
 * UPDATED: Added event to reset write state.
 */
sealed class UserEvent {
    data class AddNewUser(val name: String, val age: Int) : UserEvent()
    data class DeleteUser(val user: User) : UserEvent()
    data object ResetWriteState : UserEvent() // To clear snackbars/messages
}

/**
 * ViewModel class with Hilt dependency injection.
 *
 * UPDATED: Exposes paged flow and stateful write flow.
 */
@HiltViewModel
class UserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repo: UserRepository
) : ViewModel() {

    // 1. "READ" SIDE (Simple List)
    val userUiState: StateFlow<DataState<List<User>>> =
        repo.getUserList()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = DataState.Loading
            )

    // 2. "READ" SIDE (Paged List)
    val usersPagedFlow: Flow<PagingData<User>> =
        repo.getUsersPaged().cachedIn(viewModelScope)


    // 3. "WRITE" SIDE (Stateful Actions)
    // This state is for the *operation* (e.g., button loading, snackbar)
    // null = Idle
    private val _writeState = MutableStateFlow<DataState<Unit>?>(null)
    val writeState: StateFlow<DataState<Unit>?> = _writeState


    // 4. "INTENT" HANDLING
    fun triggerEvent(event: UserEvent) {
        viewModelScope.launch {
            when (event) {
                is UserEvent.AddNewUser -> {
                    val newUser = User(name = event.name, age = event.age)
                    // Collect the flow from the repo
                    repo.insertUser(newUser).collect { state ->
                        _writeState.value = state
                    }
                }

                is UserEvent.DeleteUser -> {
                    repo.deleteUser(event.user).collect { state ->
                        _writeState.value = state
                    }
                }

                is UserEvent.ResetWriteState -> {
                    _writeState.value = null // Set back to Idle
                }
            }
        }
    }
}


// ---------------------------------------------------------------------
// EXAMPLE 1: SIMPLE LIST APPLICATION
// ---------------------------------------------------------------------

/**
 * Example composable function for the SIMPLE list.
 */
@Composable
fun UserApplication() {
    val viewModel: UserViewModel = viewModel()
    Box {
        UserListScreen(viewModel = viewModel)
    }
}

/**
 * The main UI screen for the SIMPLE list.
 *
 * UPDATED: Observes writeState to show loading/success/error.
 */
@Composable
fun UserListScreen(viewModel: UserViewModel) {

    // 1. Collect the READ state for the list
    val uiState by viewModel.userUiState.collectAsStateWithLifecycle()

    // 2. Collect the WRITE state for actions
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    var writeMessage by remember { mutableStateOf<String?>(null) }
    val isWriting = writeState is DataState.Loading

    // 3. Handle transient write states (like snackbars)
    LaunchedEffect(writeState) {
        when (val state = writeState) {
            is DataState.Success -> {
                writeMessage = "Success!"
                delay(2000) // Show for 2 seconds
                viewModel.triggerEvent(UserEvent.ResetWriteState)
                writeMessage = null
            }
            is DataState.Error -> {
                writeMessage = "Error: ${state.exception.message}"
                delay(3000) // Show for 3 seconds
                viewModel.triggerEvent(UserEvent.ResetWriteState)
                writeMessage = null
            }
            else -> {} // Do nothing on Loading or Idle
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Simple controls to add a new user
        Button(
            onClick = {
                val age = (10..50).random() // Random age
                viewModel.triggerEvent(
                    UserEvent.AddNewUser(name = "User ${age}", age = age)
                )
            },
            // Disable button while writing
            enabled = !isWriting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            if (isWriting) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            } else {
                Text("Add Random User")
            }
        }

        // Show transient message
        AnimatedVisibility(visible = writeMessage != null) {
            Text(
                text = writeMessage ?: "",
                color = if (writeState is DataState.Error) Color.Red else Color.Green,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // 2. Use our helper to show the list state
        uiState.HandleUiState(
            success = { userList ->
                UserListSuccessUI(
                    list = userList,
                    onDelete = { user ->
                        viewModel.triggerEvent(UserEvent.DeleteUser(user))
                    }
                )
            },
            loading = { UserListLoadingUI() },
            empty = { UserEmptyListUI() },
            error = { exception -> UserListErrorUI(exception) }
        )
    }
}

// ---------------------------------------------------------------------
// EXAMPLE 2: PAGED LIST APPLICATION
// ---------------------------------------------------------------------

/**
 * Example composable function for the PAGED list.
 */
@Composable
fun UserApplication_Paged() {
    val viewModel: UserViewModel = viewModel()
    Box {
        UserListScreen_Paged(viewModel = viewModel)
    }
}

/**
 * The main UI screen for the PAGED list.
 */
@Composable
fun UserListScreen_Paged(viewModel: UserViewModel) {

    // 1. Collect the PAGED flow
    val lazyUserItems = viewModel.usersPagedFlow.collectAsLazyPagingItems()

    // 2. Collect the WRITE state (same as the simple list)
    val writeState by viewModel.writeState.collectAsStateWithLifecycle()
    var writeMessage by remember { mutableStateOf<String?>(null) }
    val isWriting = writeState is DataState.Loading

    // 3. Handle transient write states (same as the simple list)
    LaunchedEffect(writeState) {
        when (val state = writeState) {
            is DataState.Success -> {
                writeMessage = "Success! List will update."
                delay(2000)
                viewModel.triggerEvent(UserEvent.ResetWriteState)
                writeMessage = null
            }
            is DataState.Error -> {
                writeMessage = "Error: ${state.exception.message}"
                delay(3000)
                viewModel.triggerEvent(UserEvent.ResetWriteState)
                writeMessage = null
            }
            else -> {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls (same as simple list)
        Button(
            onClick = {
                val age = (10..50).random()
                viewModel.triggerEvent(
                    UserEvent.AddNewUser(name = "User ${age}", age = age)
                )
            },
            enabled = !isWriting,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            if (isWriting) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            } else {
                Text("Add Random User (for Paged List)")
            }
        }

        // Show transient message
        AnimatedVisibility(visible = writeMessage != null) {
            Text(
                text = writeMessage ?: "",
                color = if (writeState is DataState.Error) Color.Red else Color.Green,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // 4. Use our PAGING helper to show the list state
        lazyUserItems.DisplayPagedLazyColumn(
            key = { it.id },
            itemContent = { user ->
                // This is the composable for ONE row
                if (user != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${user.name} (Age: ${user.age})",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "DELETE",
                            color = Color.Red,
                            modifier = Modifier.clickable {
                                // Deleting from a paged list works the same way
                                viewModel.triggerEvent(UserEvent.DeleteUser(user))
                            }
                        )
                    }
                } else {
                    // This is the placeholder (if enabled)
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(16.dp)) {
                        Text("Loading...")
                    }
                }
            }
            // We can customize the other slots too:
            // loadingContent = { MyCustomPagingSpinner() },
            // emptyContent = { MyCustomEmptyScreen() }
        )
    }
}


// ---------------------------------------------------------------------
// REUSABLE UI STATE HANDLER (for Simple List)
// ---------------------------------------------------------------------
@Composable
fun <T> DataState<T>.HandleUiState(
    success: @Composable (data: T) -> Unit,
    loading: @Composable () -> Unit = { UserListLoadingUI() },
    empty: @Composable () -> Unit = { UserEmptyListUI() },
    error: @Composable (exception: Exception) -> Unit = { exception ->
        UserListErrorUI(exception = exception)
    }
) {
    when (this) {
        is DataState.Loading -> loading()
        is DataState.Success -> success(this.data)
        is DataState.Empty -> empty()
        is DataState.Error -> error(this.exception)
    }
}

// ---------------------------------------------------------------------
// DEFAULT UI STATE COMPOSABLES (for Simple List)
// ---------------------------------------------------------------------
@Composable
fun UserListLoadingUI() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun UserListSuccessUI(list: List<User>, onDelete: (User) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { user ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${user.name} (Age: ${user.age})",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "DELETE",
                    color = Color.Red,
                    modifier = Modifier.clickable { onDelete(user) }
                )
            }
        }
    }
}

@Composable
fun UserEmptyListUI() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No users found. Add some!")
    }
}

@Composable
fun UserListErrorUI(exception: Exception) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Error: ${exception.message}", color = Color.Red)
    }
}


// ---------------------------------------------------------------------
// REUSABLE UI STATE HANDLER (for Paged List)
// ---------------------------------------------------------------------

/**
 * A generic, reusable Composable that abstracts away all the boilerplate
 * for handling Paging 3 states (loading, error, empty, append).
 */
@Composable
fun <T : Any> LazyPagingItems<T>.DisplayPagedLazyColumn(
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T?) -> Unit,
    key: ((item: T) -> Any)? = null,
    loadingContent: @Composable () -> Unit = { DefaultPagingLoadingUI() },
    emptyContent: @Composable () -> Unit = { DefaultPagingEmptyUI() },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { e, retry ->
        DefaultPagingErrorUI(exception = e, onRetry = retry)
    },
    appendLoadingContent: @Composable () -> Unit = { DefaultPagingAppendLoadingUI() },
    appendErrorContent: @Composable (Throwable, () -> Unit) -> Unit = { e, retry ->
        DefaultPagingAppendErrorUI(exception = e, onRetry = retry)
    }
) {
    when (val refreshState = this@DisplayPagedLazyColumn.loadState.refresh) {
        is LoadState.Loading -> {
            loadingContent()
        }
        is LoadState.Error -> {
            errorContent(refreshState.error) { this@DisplayDisplayPagedLazyColumn.retry() }
        }
        is LoadState.NotLoading -> {
            if (this@DisplayPagedLazyColumn.itemCount == 0) {
                emptyContent()
            } else {
                LazyColumn(modifier = modifier) {
                    items(
                        count = this@DisplayPagedLazyColumn.itemCount,
                        key = if (key == null) null else { index ->
                            val item = this@DisplayPagedLazyColumn.peek(index)
                            if (item == null) index else key(item)
                        }
                    ) { index ->
                        itemContent(this@DisplayPagedLazyColumn[index])
                    }

                    item {
                        when (val appendState = this@DisplayPagedLazyColumn.loadState.append) {
                            is LoadState.Loading -> appendLoadingContent()
                            is LoadState.Error -> appendErrorContent(appendState.error) { this@DisplayPagedLazyColumn.retry() }
                            is LoadState.NotLoading -> {}
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// DEFAULT UI STATE COMPOSABLES (for Paged List)
// ---------------------------------------------------------------------

@Composable
fun DefaultPagingLoadingUI() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun DefaultPagingEmptyUI() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No items found.", modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DefaultPagingErrorUI(exception: Throwable, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Error: ${exception.message} \n (Click to retry)",
            modifier = Modifier.padding(16.dp).clickable { onRetry() }
        )
    }
}

@Composable
fun DefaultPagingAppendLoadingUI() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun DefaultPagingAppendErrorUI(exception: Throwable, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            "Error loading more: ${exception.message} \n (Click to retry)",
            modifier = Modifier.clickable { onRetry() }
        )
    }
}


/**
 *
 * Here are the dependencies you would need for this code:
 *
 * // ... (other dependencies like Coroutines, Hilt, Room)
 *
 * ----------------------------------------------------------------------
 * Jetpack Compose Lifecycle: For lifecycle-aware collection of Flow in Compose.
 * implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.7.0' // Use latest
 *
 * ----------------------------------------------------------------------
 * Jetpack Paging 3: For paged lists.
 * implementation 'androidx.paging:paging-runtime:3.3.0' // Use latest
 * implementation 'androidx.paging:paging-compose:3.3.0' // Use latest
 *
 * ----------------------------------------------------------------------
 * Hilt: For dependency injection.
 * implementation 'com.google.dagger:hilt-android:2.51.1' // Use latest
 * kapt 'com.google.dagger:hilt-compiler:2.51.1'
 * implementation 'androidx.hilt:hilt-navigation-compose:1.2.0' // Use latest
 *
 * ----------------------------------------------------------------------
 * Room: For accessing your database.
 * implementation 'androidx.room:room-runtime:2.6.1' // Use latest
 * kapt 'androidx.room:room-compiler:2.6.1'
 * implementation 'androidx.room:room-ktx:2.6.1'
 *
 *
 * Please replace the version numbers with the latest ones at the time of your development.
 *
 * */

