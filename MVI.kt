package com.rabi.mviexample

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.*
/**
 * ---------------------------------------------------------------------
 * MVI (Model-View-Intent) Architecture
 *
 * ---------------------------------------------------------------------
 * In the MVI pattern:
 * ----------------------------------------------------------------------
 * Model (M): Represents the application's data and business logic.
 * In this case, this includes the DataState sealed class and the User data class.
 *
 * ---------------------------------------------------------------------
 * View (V): Represents the UI components and their logic.
 * Compose UI functions (UserListUI, UserListLoadingUI, etc.) fall into this category.
 *
 * ---------------------------------------------------------------------
 * Intent (I): Represents the user's intent or actions that trigger changes in the application state.
 * In this code, the UserEvent sealed class and the triggerEvent function in the UserViewModel handle user intents.
 *
 *
 *
 * */

/* DataState represents the different states of data handling in the app.*/
sealed class DataState<out R> {
    data class Success<out T>(val data: T) : DataState<T>() // Successful data state
    data class Error(val exception: Exception) : DataState<Nothing>() // Error state
    data object Loading : DataState<Nothing>() // Loading state
    data object Empty : DataState<Nothing>() // Empty state
}

/**
 *  User data class representing the application's data model.
 *
 * */

@Entity(tableName = "user")
data class User(
    @PrimaryKey(autoGenerate = false)
    val id: String = "",
    val name: String = "",
    val age: Int = 0,

    @JvmField
    val isChild:Boolean = false
)

/**
 * UserDao is Database access Object (Room).
 */

@Dao
interface UserDao {
    @Query("SELECT * FROM user")
    suspend fun getUserList(): List<User>
}


/**
 * Repository class responsible for data operations.
 */

class UserRepository constructor(
    private val dao: UserDao
) {

    // Function to fetch a list of users in a reactive way using Flow.
    suspend fun getUserList(): Flow<DataState<List<User>>> = flow {
        emit(DataState.Loading)
        try {
            val result = dao.getUserList()

            // Apply business logic, if needed, e.g., filtering users based on a condition.
            val filteredResult = result.filter { it.age > 18 }

            if (filteredResult.isNotEmpty()) {
                emit(DataState.Success(filteredResult))
            } else emit(DataState.Empty)

        } catch (e: Exception) {
            emit(DataState.Error(e))
        }
    }
}

/**
 * UserEvent represents different user actions or intents.
 * */
sealed class UserEvent {
    data object GetUsersEvent : UserEvent() // User action to fetch the list of users
}

/**
 * ViewModel class with Hilt dependency injection.
 * */
@HiltViewModel
class UserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repo: UserRepository
) : ViewModel() {

    private var _userUiState: MutableStateFlow<DataState<List<User>>> =
        MutableStateFlow(DataState.Loading)
    val userUiState: StateFlow<DataState<List<User>>>
        get() = _userUiState

    // Function to handle user intents and trigger corresponding events.
    fun triggerEvent(event: UserEvent) {
        viewModelScope.launch {
            when (event) {
                is UserEvent.GetUsersEvent -> {
                    repo.getUserList().collect {
                        _userUiState.value = it
                    }
                }
            }
        }
    }
}



/**
 * Example composable function from where i call userListUI
 * */
@Composable
fun UserApplication() {
    val viewModel: UserViewModel = viewModel()
    Box {
        UserListUI(viewModel = viewModel)
    }
}



@Composable
fun UserListUI(viewModel: UserViewModel) {

    // Trigger the event to fetch the list of users when the composable is first composed.
    LaunchedEffect(key1 = true) {
        viewModel.triggerEvent(UserEvent.GetUsersEvent)
    }

    // Collect the UI state using collectAsStateWithLifecycle().
    val uiState by viewModel.userUiState.collectAsStateWithLifecycle()

    // Handle different states and display the appropriate UI.
    when (uiState) {
        is DataState.Loading -> UserListLoadingUI()
        is DataState.Success -> {
            val successState = uiState as DataState.Success<List<User>>
            UserListSuccessUI(successState.data)
        }

        is DataState.Empty -> UserEmptyListUI()
        is DataState.Error -> {
            val exceptionState = uiState as DataState.Error
            UserListErrorUI(exceptionState.exception)
        }
    }
}

// Composable function for displaying a loading state.
@Composable
fun UserListLoadingUI() {
    // Compose UI for loading state.
}

// Composable function for displaying a successful state with a list of users.
@Composable
fun UserListSuccessUI(list: List<User>) {
    // Compose UI for displaying the list of users.
}

// Composable function for displaying an empty state.
@Composable
fun UserEmptyListUI() {
    // Compose UI for empty state.
}

// Composable function for displaying an error state.
@Composable
fun UserListErrorUI(exception: Exception) {
    // Compose UI for error state.
}


/**
 *
 * Here are the dependencies you would need for the code you provided:
 *
 * Kotlin Coroutines: For handling asynchronous tasks and returning results as Flow.
 * implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2'
 * implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2'
 *
 * ----------------------------------------------------------------------
 * Jetpack Compose: For building your UI.
 * implementation 'androidx.compose.ui:ui:1.1.0'
 * implementation 'androidx.compose.material:material:1.1.0'
 * implementation 'androidx.compose.ui:ui-tooling:1.1.0'
 * implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.6.0-alpha03'
 * implementation 'androidx.hilt:hilt-navigation-compose:1.0.0'
 *
 * ----------------------------------------------------------------------
 * Jetpack Compose Lifecycle: For lifecycle-aware collection of Flow in Compose.
 * implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.4.0'
 * implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:1.0.0-alpha07'
 * implementation 'androidx.lifecycle:lifecycle-runtime-compose:1.0.0-alpha07'
 *
 * ----------------------------------------------------------------------
 * Hilt: For dependency injection.
 * implementation 'com.google.dagger:hilt-android:2.40.5'
 * kapt 'com.google.dagger:hilt-compiler:2.40.5'
 *
 * ----------------------------------------------------------------------
 * Room: For accessing your database. UserDao is typically an interface in Room.
 * implementation 'androidx.room:room-runtime:2.4.1'
 * kapt 'androidx.room:room-compiler:2.4.1'
 * implementation 'androidx.room:room-ktx:2.4.1'
 *
 *
 * Please replace the version numbers with the latest ones at the time of your development. Also, don’t forget to apply the necessary plugins for Hilt and Kotlin Kapt in your module-level build.gradle file:
 *
 * apply plugin: 'kotlin-kapt'
 * apply plugin: 'dagger.hilt.android.plugin'
 *
 * And in your project-level build.gradle file, you need to include the Hilt classpath:
 *
 * classpath 'com.google.dagger:hilt-android-gradle-plugin:2.40.5'
 *
 * */
