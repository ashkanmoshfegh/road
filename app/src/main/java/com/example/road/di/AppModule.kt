package com.example.road.di

import android.content.Context
import android.hardware.SensorManager
import com.example.road.data.m.local.database.AppDatabase
import com.example.road.data.m.local.repository.GraphRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.example.road.data.m.local.database.TrafficDao

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }
    @Provides
    fun provideTrafficDao(appDatabase: AppDatabase): TrafficDao {
        return appDatabase.trafficDao()
    }
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
    // RouteCalculator and ResilienceManager are provided via @Inject constructors
    // They will initialize asynchronously inside ResilienceManager.initialize()
}