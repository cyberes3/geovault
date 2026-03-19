package com.geovault.tracker.di

import com.geovault.tracker.data.TrackerDetailRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerListRepository
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.GroupTrackerEligibilityUseCase
import com.geovault.tracker.data.ApiTrackerManagementRepository
import com.geovault.tracker.data.DefaultGroupTrackerEligibilityUseCase
import com.geovault.tracker.data.TrackerRepositoryTrackerDetailRepository
import com.geovault.tracker.data.TrackerRepositoryTrackerListRepository
import com.geovault.tracker.fragments.map.MapGroupRepository
import com.geovault.tracker.fragments.map.MapStreamingRepository
import com.geovault.tracker.fragments.map.BootstrapMapTrackRepository
import com.geovault.tracker.fragments.map.MapTrackRepository
import com.geovault.tracker.fragments.map.RuntimeMapTrackRepository
import com.geovault.tracker.fragments.map.MapVisibilityRepository
import com.geovault.tracker.fragments.map.TrackPointBusStreamingRepository
import com.geovault.tracker.fragments.map.TrackerRepositoryMapGroupRepository
import com.geovault.tracker.fragments.map.TrackerRepositoryMapTrackRepository
import com.geovault.tracker.fragments.map.TrackerRepositoryMapVisibilityRepository
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerSettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTrackerListRepository(impl: TrackerRepositoryTrackerListRepository): TrackerListRepository

    @Binds
    @Singleton
    abstract fun bindTrackerManagementRepository(impl: ApiTrackerManagementRepository): TrackerManagementRepository

    @Binds
    @Singleton
    abstract fun bindGroupManagementRepository(impl: ApiTrackerManagementRepository): GroupManagementRepository

    @Binds
    @Singleton
    abstract fun bindGroupTrackerEligibilityUseCase(
        impl: DefaultGroupTrackerEligibilityUseCase
    ): GroupTrackerEligibilityUseCase

    @Binds
    @Singleton
    abstract fun bindTrackerDetailRepository(impl: TrackerRepositoryTrackerDetailRepository): TrackerDetailRepository

    @Binds
    @Singleton
    abstract fun bindMapTrackRepository(impl: TrackerRepositoryMapTrackRepository): MapTrackRepository

    @Binds
    @Singleton
    abstract fun bindRuntimeMapTrackRepository(
        impl: TrackerRepositoryMapTrackRepository
    ): RuntimeMapTrackRepository

    @Binds
    @Singleton
    abstract fun bindBootstrapMapTrackRepository(
        impl: TrackerRepositoryMapTrackRepository
    ): BootstrapMapTrackRepository

    @Binds
    @Singleton
    abstract fun bindMapGroupRepository(impl: TrackerRepositoryMapGroupRepository): MapGroupRepository

    @Binds
    @Singleton
    abstract fun bindMapVisibilityRepository(impl: TrackerRepositoryMapVisibilityRepository): MapVisibilityRepository

    @Binds
    @Singleton
    abstract fun bindMapStreamingRepository(impl: TrackPointBusStreamingRepository): MapStreamingRepository

    @Binds
    @Singleton
    abstract fun bindTrackerSettingsRepository(impl: TrackerSettingsRepositoryImpl): TrackerSettingsRepository
}
