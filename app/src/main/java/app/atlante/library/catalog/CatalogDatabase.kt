package app.atlante.library.catalog

import android.content.Context
import androidx.room.*

/*
 * The offline catalog: 10,000 real works from Wikidata and Open Library (both CC0, so they can ship with the app),
 * built and indexed beforehand by scripts/catalog/build_catalog.py and copied from the APK's assets on first use.
 * Read-only: nothing personal is ever written here. Ids are the Wikidata numbers (Q12345 → 12345).
 */

/** A work (not an edition): its original title, its Italian title when Wikidata or an Italian edition gives one. */
@Entity(tableName = "catalog_works", indices = [Index("openLibraryId")])
data class CatalogWork(
    @PrimaryKey val id: Long,
    val title: String,
    val originalTitle: String,
    val italianTitle: String?,
    val language: String?,
    val year: Int?,
    val authors: String,
    val genre: String?,
    val genres: String,
    val openLibraryId: String?,
    val coverUrl: String?,
    val popularity: Int,
)

@Entity(tableName = "catalog_authors")
data class CatalogAuthor(@PrimaryKey val id: Long, val name: String, val italianName: String?, val openLibraryId: String?)

@Entity(tableName = "catalog_work_authors", primaryKeys = ["workId", "authorId"], indices = [Index("authorId")])
data class CatalogWorkAuthor(val workId: Long, val authorId: Long)

/** A saga or a sub-series ([parentId]: the saga it belongs to). */
@Entity(tableName = "catalog_series", indices = [Index("parentId")])
data class CatalogSeries(@PrimaryKey val id: Long, val name: String, val italianName: String?, val parentId: Long?)

/** A work in a saga, with the number Wikidata gives it (P1545); [sortKey] is that number, or null when there is none. */
@Entity(tableName = "catalog_entries", primaryKeys = ["seriesId", "workId"], indices = [Index("workId")])
data class CatalogEntry(val seriesId: Long, val workId: Long, val ordinal: String?, val sortKey: Double?)

/** "follows" / "followed_by" between works (P155 / P156). */
@Entity(tableName = "catalog_relations", primaryKeys = ["fromId", "toId", "type"], indices = [Index("toId")])
data class CatalogRelation(val fromId: Long, val toId: Long, val type: String)

/** An edition of a work (Italian and English ones, with ISBN when Wikidata has it). */
@Entity(tableName = "catalog_editions", indices = [Index("workId"), Index("isbn13")])
data class CatalogEdition(@PrimaryKey val id: Long, val workId: Long, val title: String, val language: String?, val isbn13: String?,
                          val publisher: String?, val year: Int?)

/** Full-text index of the works: every title (original, Italian, editions), the authors and the sagas. rowid = work id. */
@Fts4
@Entity(tableName = "catalog_fts")
data class CatalogFts(@PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long, val titles: String, val authors: String, val series: String)

data class CatalogHit(@Embedded val work: CatalogWork, val rank: Double)

@Dao
interface CatalogDao {
    /** [match]: an FTS4 query ("ultim* imper*"); best known works first. */
    @Query("""SELECT w.*, 0.0 AS rank FROM catalog_fts f JOIN catalog_works w ON w.id = f.rowid
              WHERE catalog_fts MATCH :match ORDER BY w.popularity DESC LIMIT :limit""")
    suspend fun search(match: String, limit: Int = 30): List<CatalogHit>

    @Query("SELECT * FROM catalog_works WHERE id = :id") suspend fun work(id: Long): CatalogWork?
    @Query("SELECT * FROM catalog_works WHERE id IN (:ids)") suspend fun works(ids: List<Long>): List<CatalogWork>
    @Query("SELECT * FROM catalog_entries WHERE workId = :workId") suspend fun entriesOf(workId: Long): List<CatalogEntry>
    @Query("SELECT * FROM catalog_entries WHERE seriesId = :seriesId") suspend fun entries(seriesId: Long): List<CatalogEntry>
    @Query("SELECT * FROM catalog_series WHERE id = :id") suspend fun series(id: Long): CatalogSeries?
    @Query("SELECT * FROM catalog_series WHERE parentId = :id") suspend fun subseries(id: Long): List<CatalogSeries>
    @Query("SELECT * FROM catalog_editions WHERE workId = :workId") suspend fun editions(workId: Long): List<CatalogEdition>
    @Query("SELECT * FROM catalog_editions WHERE isbn13 = :isbn LIMIT 1") suspend fun editionByIsbn(isbn: String): CatalogEdition?
    @Query("SELECT * FROM catalog_relations WHERE fromId = :id OR toId = :id") suspend fun relations(id: Long): List<CatalogRelation>
    @Query("SELECT COUNT(*) FROM catalog_works") suspend fun count(): Int
    /** Every title of a work as indexed (folded): original, Italian, editions. */
    @Query("SELECT titles FROM catalog_fts WHERE rowid = :id") suspend fun titlesOf(id: Long): String?
    @Query("SELECT a.* FROM catalog_authors a JOIN catalog_work_authors wa ON wa.authorId = a.id WHERE wa.workId = :workId") suspend fun authorsOf(workId: Long): List<CatalogAuthor>
}

@Database(entities = [CatalogWork::class, CatalogAuthor::class, CatalogWorkAuthor::class, CatalogSeries::class, CatalogEntry::class,
    CatalogRelation::class, CatalogEdition::class, CatalogFts::class], version = 1, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao

    companion object {
        const val NAME = "atlante-catalog.db"
        const val ASSET = "catalog/atlante-catalog.db"
        @Volatile private var instance: CatalogDatabase? = null

        /** Copied once from the APK (already built and indexed); a newer app version brings a newer catalog. */
        @JvmStatic fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CatalogDatabase::class.java, NAME)
                .createFromAsset(ASSET).fallbackToDestructiveMigration()
                // Read-only and indexed (a lookup takes a few ms): imports may check it while showing their preview.
                .allowMainThreadQueries().build().also { instance = it }
        }
    }
}
