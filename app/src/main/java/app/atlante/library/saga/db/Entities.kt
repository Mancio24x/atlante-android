package app.atlante.library.saga.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Local bibliographic graph: works, editions, authors, sagas and their relations, as found on Wikidata and
 * Open Library (and the user's own corrections). The personal library (library.json: reading state, reviews,
 * notes, statistics) is NOT moved here; it is linked through BookLink by the existing book id.
 * Lists that are only displayed (alternative names, genres) are stored as text separated by "\n".
 */

@Entity(tableName = "authors", indices = [Index("wikidataId", unique = true), Index("openLibraryId")])
data class AuthorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val altNames: String = "",
    val wikidataId: String? = null,
    val openLibraryId: String? = null,
)

@Entity(tableName = "works", indices = [Index("wikidataId", unique = true), Index("openLibraryId")])
data class WorkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalTitle: String,
    /** Titles in other languages, e.g. the Italian title of an English novel ("\n"-separated). */
    val altTitles: String = "",
    val italianTitle: String? = null,
    val firstPublished: String? = null,
    val wikidataId: String? = null,
    val openLibraryId: String? = null,
    /** Literary work, short story, novella… as Wikidata describes it. */
    val kind: String = "",
    val updatedAt: Long = 0,
)

@Entity(tableName = "work_authors", primaryKeys = ["workId", "authorId"], indices = [Index("authorId")],
    foreignKeys = [ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AuthorEntity::class, parentColumns = ["id"], childColumns = ["authorId"], onDelete = ForeignKey.CASCADE)])
data class WorkAuthor(val workId: Long, val authorId: Long)

@Entity(tableName = "editions", indices = [Index("workId"), Index("isbn"), Index("openLibraryId")],
    foreignKeys = [ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)])
data class EditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workId: Long,
    val isbn: String? = null,
    val title: String,
    val language: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val pages: Int? = null,
    /** Remote cover address; the image itself lives in the file cache, never in the database. */
    val coverUrl: String? = null,
    val openLibraryId: String? = null,
)

@Entity(tableName = "universes", indices = [Index("wikidataId", unique = true)])
data class UniverseEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val wikidataId: String? = null)

@Entity(tableName = "series", indices = [Index("wikidataId", unique = true), Index("parentId"), Index("universeId")],
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = UniverseEntity::class, parentColumns = ["id"], childColumns = ["universeId"], onDelete = ForeignKey.SET_NULL)])
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val altNames: String = "",
    val wikidataId: String? = null,
    /** Main saga of a sub-series (Red Rising Trilogy → Red Rising Saga). */
    val parentId: Long? = null,
    val universeId: Long? = null,
    val description: String = "",
    /** The source lists volumes without numbers or the list looks partial. */
    val possiblyIncomplete: Boolean = false,
    val refreshedAt: Long = 0,
    /** Created or edited by the user: automatic refreshes never overwrite it. */
    val manual: Boolean = false,
)

@Entity(tableName = "series_entries", primaryKeys = ["seriesId", "workId"], indices = [Index("workId")],
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)])
data class SeriesEntry(
    val seriesId: Long,
    val workId: Long,
    /** Ordinal as written by the source ("1", "2.5"), null when unknown. */
    val ordinal: String? = null,
    /** Numeric sort key derived from the ordinal; unnumbered works sort last. */
    val sortKey: Double = Double.MAX_VALUE,
    /** "volume", "novella", "prequel", "spin-off"… */
    val membership: String = "volume",
    val source: String = "wikidata",
)

@Entity(tableName = "work_relations", primaryKeys = ["fromWorkId", "toWorkId", "type"], indices = [Index("toWorkId")],
    foreignKeys = [ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["fromWorkId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["toWorkId"], onDelete = ForeignKey.CASCADE)])
data class WorkRelation(val fromWorkId: Long, val toWorkId: Long, /** follows, followed_by, prequel, sequel, spin_off */ val type: String, val source: String)

@Entity(tableName = "genres", indices = [Index("name", unique = true), Index("parentId")],
    foreignKeys = [ForeignKey(entity = GenreEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)])
data class GenreEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val parentId: Long? = null, val synonyms: String = "")

@Entity(tableName = "work_genres", primaryKeys = ["workId", "genreId"], indices = [Index("genreId")],
    foreignKeys = [ForeignKey(entity = WorkEntity::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GenreEntity::class, parentColumns = ["id"], childColumns = ["genreId"], onDelete = ForeignKey.CASCADE)])
data class WorkGenre(val workId: Long, val genreId: Long)

/** Where a piece of the graph came from (provider + external id + when). */
@Entity(tableName = "metadata_sources", primaryKeys = ["entityType", "entityId", "provider"])
data class MetadataSource(val entityType: String, val entityId: Long, val provider: String, val externalId: String, val updatedAt: Long, val provenance: String = "")

/** A value set by the user; automatic refreshes read it and never replace it. */
@Entity(tableName = "user_overrides", primaryKeys = ["entityType", "entityId", "field"])
data class UserOverride(val entityType: String, val entityId: String, val field: String, val value: String, val updatedAt: Long)

/**
 * Link between a book of the personal library (Book.id) and the graph. Status: "auto" (reliable match),
 * "to_confirm" (uncertain), "confirmed" or "manual" (set by the user), "unidentified" (no saga found).
 */
@Entity(tableName = "book_links", indices = [Index("workId"), Index("seriesId")])
data class BookLink(
    @PrimaryKey val bookId: String,
    val workId: Long? = null,
    val seriesId: Long? = null,
    val score: Int = 0,
    val status: String = "unidentified",
    val reasons: String = "",
    val checkedAt: Long = 0,
)

/** Temporary data (search results, suggestions) with an expiry; personal data never goes here. */
@Entity(tableName = "cache_entries", indices = [Index("expiresAt")])
data class CacheEntry(@PrimaryKey val key: String, val kind: String, val json: String, val fetchedAt: Long, val expiresAt: Long)
