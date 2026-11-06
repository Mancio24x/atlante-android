package app.atlante.library.saga.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Bibliographic database. Version 1 creates it; every later change must add a Migration (never
 * fallbackToDestructiveMigration) and its exported schema in app/schemas, checked by the migration tests.
 */
@Database(
    entities = [AuthorEntity::class, WorkEntity::class, WorkAuthor::class, EditionEntity::class, UniverseEntity::class,
        SeriesEntity::class, SeriesEntry::class, WorkRelation::class, GenreEntity::class, WorkGenre::class,
        MetadataSource::class, UserOverride::class, BookLink::class, CacheEntry::class],
    version = 1, exportSchema = true,
)
abstract class AtlanteDatabase : RoomDatabase() {
    abstract fun saga(): SagaDao

    companion object {
        const val NAME = "atlante-bibliography.db"
        /** Versioned migrations; empty while the schema is at version 1. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()
        @Volatile private var instance: AtlanteDatabase? = null

        @JvmStatic fun get(context: Context): AtlanteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AtlanteDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
