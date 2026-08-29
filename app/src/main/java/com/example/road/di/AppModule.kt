package com.example.road.di

import android.content.Context
import com.example.road.data.m.local.database.AppDatabase
import com.example.road.data.m.local.repository.GraphRepository
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
    fun provideGraphRepository(@ApplicationContext context: Context): GraphRepository {
        return GraphRepository(context)
    }

    // RouteCalculator and ResilienceManager are provided via @Inject constructors
    // They will initialize asynchronously inside ResilienceManager.initialize()
}