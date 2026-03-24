package com.geovault.tracker.fragments

import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.GroupManagementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EditSharedGroupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun leaveGroup_success_setsDidLeave() = runTest {
        val vm = EditSharedGroupViewModel(
            groupRepository = object : GroupManagementRepository {
                override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<List<Group>> =
                    RepositoryResult.Success(emptyList())

                override suspend fun loadGroup(groupId: String): RepositoryResult<Group> =
                    RepositoryResult.Failure(AppError.NotFound)

                override suspend fun createGroup(name: String): RepositoryResult<Group> =
                    RepositoryResult.Failure(AppError.Unknown)

                override suspend fun patchGroup(
                    groupId: String,
                    request: com.geovault.tracker.GroupPatchRequest,
                    publishToStore: Boolean
                ): RepositoryResult<Group> = RepositoryResult.Failure(AppError.Unknown)

                override suspend fun deleteGroup(groupId: String): RepositoryResult<Unit> =
                    RepositoryResult.Failure(AppError.Unknown)

                override suspend fun addGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> =
                    RepositoryResult.Failure(AppError.Unknown)

                override suspend fun removeGroupTrack(groupId: String, trackId: String): RepositoryResult<Group> =
                    RepositoryResult.Failure(AppError.Unknown)

                override suspend fun leaveGroup(groupId: String): RepositoryResult<Unit> =
                    RepositoryResult.Success(Unit)

                override suspend fun acceptGroupShare(groupId: String): RepositoryResult<Group> =
                    RepositoryResult.Failure(AppError.Unknown)
            }
        )

        vm.leaveGroup("g1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.didLeave)
    }
}
