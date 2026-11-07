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
        MetadataSource::class, UserOverride::class, BookLink::class, CacheEntry::class, LibraryFts::class, RepairJob::class],
    version = 2, exportSchema = true,
)
abstract class AtlanteDatabase : RoomDatabase() {
    abstract fun saga(): SagaDao

    companion object {
        const val NAME = "atlante-bibliography.db"
        /**
         * 1 → 2: adds the full-text index of the personal library (library_fts) and the log of background repairs
         * (repair_jobs). Nothing existing is touched: both are new tables; the index is filled from library.json.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `library_fts` USING FTS4(`bookId` TEXT NOT NULL, `titles` TEXT NOT NULL, `authors` TEXT NOT NULL, " +
                    "`series` TEXT NOT NULL, `hash` TEXT NOT NULL, `catalogWork` TEXT NOT NULL, notindexed=`bookId`, notindexed=`hash`, notindexed=`catalogWork`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `repair_jobs` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, `phase` TEXT NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `total` INTEGER NOT NULL, `done` INTEGER NOT NULL, `found` INTEGER NOT NULL, " +
                    "`duplicates` INTEGER NOT NULL, `proposals` INTEGER NOT NULL, `applied` INTEGER NOT NULL, `errors` INTEGER NOT NULL, `offline` INTEGER NOT NULL, " +
                    "`checkpoint` INTEGER NOT NULL, `message` TEXT NOT NULL, `result` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repair_jobs_state` ON `repair_jobs` (`state`)")
            }
        }
        /** Versioned migrations, never destructive (checked against app/schemas by SagaDatabaseTest). */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>(MIGRATION_1_2)
        @Volatile private var instance: AtlanteDatabase? = null

        @JvmStatic fun get(context: Context): AtlanteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AtlanteDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
