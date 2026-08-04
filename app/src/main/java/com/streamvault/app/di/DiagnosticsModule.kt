package com.streamvault.app.di

import android.content.Context
import androidx.room.Room
import com.streamvault.data.local.DiagnosticsDatabase
import com.streamvault.data.local.dao.DiagnosticDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the isolated diagnostics queue database, kept separate from the
 * user-data StreamVaultDatabase. [com.streamvault.data.repository.DiagnosticsRepository]
 * is @Inject-constructed from the DAO provided here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    @Provides
    @Singleton
    fun provideDiagnosticsDatabase(@ApplicationContext context: Context): DiagnosticsDatabase =
        Room.databaseBuilder(
            context,
            DiagnosticsDatabase::class.java,
            "diagnostics.db"
        ).build()

    @Provides
    fun provideDiagnosticDao(db: DiagnosticsDatabase): DiagnosticDao = db.diagnosticDao()
}
