package app.atlante.library.saga.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** A work as a volume of a saga, in reading order. */
data class SeriesVolume(@Embedded val work: WorkEntity, val ordinal: String?, val sortKey: Double, val membership: String, val source: String)

/** A saga with how many volumes it has and how many are linked to the personal library. */
data class SeriesSummary(@Embedded val series: SeriesEntity, val volumes: Int, val owned: Int)

@Dao
interface SagaDao {
    // ---- works, authors, editions ----
    @Query("SELECT * FROM works WHERE wikidataId = :qid LIMIT 1") suspend fun workByWikidata(qid: String): WorkEntity?
    @Query("SELECT * FROM works WHERE openLibraryId = :olid LIMIT 1") suspend fun workByOpenLibrary(olid: String): WorkEntity?
    @Query("SELECT * FROM works WHERE id = :id") suspend fun work(id: Long): WorkEntity?
    @Insert suspend fun insertWork(w: WorkEntity): Long
    @Update suspend fun updateWork(w: WorkEntity)

    @Query("SELECT * FROM authors WHERE wikidataId = :qid LIMIT 1") suspend fun authorByWikidata(qid: String): AuthorEntity?
    @Query("SELECT * FROM authors WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun authorByName(name: String): AuthorEntity?
    @Insert suspend fun insertAuthor(a: AuthorEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkAuthor(x: WorkAuthor)
    @Query("SELECT a.* FROM authors a JOIN work_authors wa ON wa.authorId = a.id WHERE wa.workId = :workId") suspend fun authorsOf(workId: Long): List<AuthorEntity>
    @Query("SELECT w.* FROM works w JOIN work_authors wa ON wa.workId = w.id WHERE wa.authorId = :authorId ORDER BY w.firstPublished") suspend fun worksBy(authorId: Long): List<WorkEntity>

    @Query("SELECT * FROM editions WHERE workId = :workId") suspend fun editionsOf(workId: Long): List<EditionEntity>
    @Query("SELECT * FROM editions WHERE isbn = :isbn LIMIT 1") suspend fun editionByIsbn(isbn: String): EditionEntity?
    @Insert suspend fun insertEdition(e: EditionEntity): Long

    // ---- sagas ----
    @Query("SELECT * FROM series WHERE wikidataId = :qid LIMIT 1") suspend fun seriesByWikidata(qid: String): SeriesEntity?
    @Query("SELECT * FROM series WHERE id = :id") suspend fun series(id: Long): SeriesEntity?
    @Insert suspend fun insertSeries(s: SeriesEntity): Long
    @Update suspend fun updateSeries(s: SeriesEntity)
    @Query("SELECT * FROM series WHERE parentId = :id ORDER BY name") suspend fun subseries(id: Long): List<SeriesEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEntry(e: SeriesEntry)
    @Query("DELETE FROM series_entries WHERE seriesId = :seriesId AND source != 'manual'") suspend fun clearAutomaticEntries(seriesId: Long)
    @Query("""SELECT w.*, e.ordinal, e.sortKey, e.membership, e.source FROM series_entries e JOIN works w ON w.id = e.workId
              WHERE e.seriesId = :seriesId ORDER BY e.sortKey, w.firstPublished, w.originalTitle""")
    suspend fun volumes(seriesId: Long): List<SeriesVolume>
    @Query("SELECT s.* FROM series s JOIN series_entries e ON e.seriesId = s.id WHERE e.workId = :workId") suspend fun seriesOf(workId: Long): List<SeriesEntity>
    @Query("""SELECT s.*, (SELECT COUNT(*) FROM series_entries e WHERE e.seriesId = s.id) AS volumes,
              (SELECT COUNT(*) FROM series_entries e JOIN book_links l ON l.workId = e.workId WHERE e.seriesId = s.id) AS owned
              FROM series s ORDER BY s.name""")
    suspend fun allSeries(): List<SeriesSummary>
    @Query("SELECT * FROM universes WHERE wikidataId = :qid LIMIT 1") suspend fun universeByWikidata(qid: String): UniverseEntity?
    @Insert suspend fun insertUniverse(u: UniverseEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putRelation(r: WorkRelation)
    @Query("SELECT * FROM work_relations WHERE fromWorkId = :workId") suspend fun relationsFrom(workId: Long): List<WorkRelation>

    // ---- links to the personal library ----
    @Query("SELECT * FROM book_links WHERE bookId = :bookId") suspend fun link(bookId: String): BookLink?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLink(l: BookLink)
    @Query("SELECT * FROM book_links") suspend fun links(): List<BookLink>
    @Query("SELECT * FROM book_links WHERE workId IN (:workIds)") suspend fun linksForWorks(workIds: List<Long>): List<BookLink>

    // ---- provenance and user corrections ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSource(m: MetadataSource)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOverride(o: UserOverride)
    @Query("SELECT * FROM user_overrides WHERE entityType = :type AND entityId = :id") suspend fun overrides(type: String, id: String): List<UserOverride>

    // ---- temporary cache ----
    @Query("SELECT * FROM cache_entries WHERE `key` = :key AND expiresAt > :now") suspend fun cached(key: String, now: Long): CacheEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCache(c: CacheEntry)
    @Query("DELETE FROM cache_entries WHERE expiresAt <= :now") suspend fun deleteExpired(now: Long): Int
    @Query("DELETE FROM cache_entries") suspend fun clearCache(): Int
    @Query("SELECT COALESCE(SUM(LENGTH(json)), 0) FROM cache_entries") suspend fun cacheBytes(): Long

    @Transaction suspend fun replaceAutomaticEntries(seriesId: Long, entries: List<SeriesEntry>) {
        clearAutomaticEntries(seriesId); entries.forEach { putEntry(it) }
    }
}
