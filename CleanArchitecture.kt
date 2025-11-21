package com.estimator.utilities

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// Remote ==========================================================================
data class UserDto(
    val id: String,
    val fullName: String
)

class UserApi() {
    suspend fun fetchUser(id: String): UserDto {
        return UserDto(id = id, fullName = "John Doe") // Mock data
    }
}

// Local ==========================================================================
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    fun observeUserById(id: String): Flow<UserEntity?>

    @Upsert
    suspend fun upsert(entity: UserEntity)
}


// Mapper ==========================================================================
fun UserDto.toEntity() = UserEntity(id = id, name = fullName)
fun UserEntity.toDomain() = User(id = id, name = name)

// Repository ==========================================================================
class UserRepositoryImpl(
    private val api: UserApi,
    private val dao: UserDao,
    @IoDispatcher private val io: CoroutineDispatcher
) : UserRepository {
    override fun observeUser(id: String): Flow<User?> =
        dao.observeUserById(id)
            .map { it?.toDomain() }
            .flowOn(io)

    override suspend fun refresh(id: String) {
        withContext(io) {
            val dto = api.fetchUser(id)
            dao.upsert(dto.toEntity())
        }
    }
}


// ==========================================================================
// DOMAIN LAYER
// ==========================================================================

// Model ======================================================================
data class User(
    val id: String,
    val name: String
)

// Repository =====================================================================
interface UserRepository {
    fun observeUser(id: String): Flow<User?>
    suspend fun refresh(id: String)
}

// UseCase ======================================================================
class GetUserUseCase(
    private val repo: UserRepository
) {
    operator fun invoke(id: String): Flow<User?> = repo.observeUser(id)
}


class RefreshUserUseCase(
    private val repo: UserRepository
) {
    suspend operator fun invoke(id: String) = repo.refresh(id)
}


//==========================================================================
//                          UI layer
//==========================================================================


// Model ===================================================================

data class UserUi(
    val id: String,
    val displayName: String
)


data class UserUiState(
    val isLoading: Boolean = false,
    val user: UserUi? = null
)

sealed interface UserUiIntent {
    object Load : UserUiIntent
    data class Refresh(val id: String) : UserUiIntent
}

sealed interface UserUiEffect {
    object ShowError : UserUiEffect
}


//Mappers ===================================================================

fun User.toUi() = UserUi(id = id, displayName = name)

// Viewmodel ===================================================================
@HiltViewModel
class UserViewModel @Inject constructor(
    private val getUser: GetUserUseCase,
    private val refreshUser: RefreshUserUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(UserUiState())
    val state: StateFlow<UserUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<UserUiEffect>()
    val effects: SharedFlow<UserUiEffect> = _effects.asSharedFlow()

    fun dispatch(intent: UserUiIntent) {
        when (intent) {
            UserUiIntent.Load -> reduceLoading(true)
            is UserUiIntent.Refresh -> refresh(intent.id)
        }
    }

    fun start(id: String) {
        viewModelScope.launch {
            getUser(id)
                .onStart { reduceLoading(true) }
                .catch { _effects.emit(UserUiEffect.ShowError) }
                .collect { user ->
                    _state.update {
                        it.copy(
                            isLoading = false, user =
                                user?.toUi()
                        )
                    }
                }
        }
    }

    private fun reduceLoading(loading: Boolean) {
        _state.update { it.copy(isLoading = loading) }
    }

    private fun refresh(id: String) {
        viewModelScope.launch {
            try {
                refreshUser(id)
            } catch (e: Exception) {
                _effects.emit(UserUiEffect.ShowError)
            }
        }
    }
}

// Screen ===================================================================

@Composable
fun UserScreen(
    viewModel: UserViewModel = hiltViewModel(),
    userId: String
) {

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { viewModel.start(userId) }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                UserUiEffect.ShowError -> {}
            }
        }
    }
    Column {
        if (state.isLoading)
            Text("Loading...")

        state.user?.let {
            Text("Hello, ${it.displayName}")
        }

        Button(onClick = {
            viewModel.dispatch(UserUiIntent.Refresh(userId))
        }) {
            Text("Refresh")
        }
    }
}

// database ===================================================================

@Database(
    entities = [UserEntity::class],
    version = 1,
    exportSchema = false
)

abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "app_db"
    }

    abstract fun userDao(): UserDao
}


// di ===================================================================

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun providesDatabase(application: Application): AppDatabase {
        return Room.databaseBuilder(
            application,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    fun provideUserApi(): UserApi = UserApi()

    @Provides
    fun provideUserDao(db: AppDatabase) = db.userDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    fun provideUserRepositoryImpl(
        api: UserApi,
        dao: UserDao,
        io: CoroutineDispatcher
    ): UserRepository {
        return UserRepositoryImpl(api, dao, io)
    }

    @Provides
    fun provideGetUserUseCase(repo: UserRepository) = GetUserUseCase(repo)

    @Provides
    fun provideRefreshUserUseCase(repo: UserRepository) = RefreshUserUseCase(repo)
}