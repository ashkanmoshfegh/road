package com.example.road.di

import android.content.Context
import android.hardware.SensorManager
import com.example.road.data.m.local.repository.GraphRepository
import com.example.road.domain.resilience.ResilienceManager
import com.example.road.domain.routing.TrafficPredictor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGraphRepository(@ApplicationContext context: Context): GraphRepository {
        return GraphRepository(context)
    }

    @Provides
    @Singleton
    fun provideSensorManager(@ApplicationContext context: Context): SensorManager {
        return context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @Provides
    @Singleton
    fun provideTrafficPredictor(): TrafficPredictor {
        return TrafficPredictor()
    }

    @Provides
    @Singleton
    fun provideResilienceManager(
        @ApplicationContext context: Context,
        graphRepository: GraphRepository,
        trafficPredictor: TrafficPredictor,
    ): ResilienceManager {
        return ResilienceManager(context, graphRepository, trafficPredictor)
    }
}

// RouteCalculator is provided via @Inject constructor in its own class.