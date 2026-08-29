package com.example.road.di

import android.content.Context
import com.example.road.data.local.database.AppDatabase
import com.example.road.data.local.repository.GraphRepository
import com.example.road.data.local.repository.TrafficRepository
import com.example.road.domain.resilience.ResilienceManager
import com.example.road.domain.routing.RouteCalculator
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideTrafficRepository(db: AppDatabase): TrafficRepository {
        return TrafficRepository(db.trafficDao())
    }

    @Provides
    @Singleton
    fun provideTrafficPredictor(repo: TrafficRepository): TrafficPredictor {
        return TrafficPredictor(repo)
    }

    @Provides
    @Singleton
    fun provideGraphRepository(@ApplicationContext context: Context): GraphRepository {
        return GraphRepository(context)
    }

    @Provides
    @Singleton
    fun provideRouteCalculator(repo: GraphRepository): RouteCalculator {
        // This forces graph loading. In production, use async init.
        val graph = runBlocking { repo.loadGraph() }
        return RouteCalculator(graph)
    }

    @Provides
    @Singleton
    fun provideResilienceManager(
        @ApplicationContext context: Context,
        graphRepo: GraphRepository,
        trafficPredictor: TrafficPredictor
    ): ResilienceManager {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        return ResilienceManager(sensorManager, graphRepo, trafficPredictor)
    }
}