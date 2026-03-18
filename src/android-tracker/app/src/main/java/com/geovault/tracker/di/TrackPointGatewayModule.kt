package com.geovault.tracker.di

import com.geovault.tracker.pipeline.TrackPointBusGateway
import com.geovault.tracker.pipeline.TrackPointEventPublisher
import com.geovault.tracker.pipeline.TrackPointEventStream
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrackPointGatewayModule {
    @Provides
    @Singleton
    fun provideTrackPointEventPublisher(): TrackPointEventPublisher = TrackPointBusGateway

    @Provides
    @Singleton
    fun provideTrackPointEventStream(): TrackPointEventStream = TrackPointBusGateway
}
