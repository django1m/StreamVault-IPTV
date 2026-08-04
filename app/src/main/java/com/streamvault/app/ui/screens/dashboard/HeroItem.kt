package com.streamvault.app.ui.screens.dashboard

import androidx.compose.runtime.Immutable
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

@Immutable
data class HeroItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val eyebrow: String? = null,
    val meta: String? = null,
    val backdropUrl: String?,
    val ctaRoute: String? = null,
    val source: Source
) {
    enum class Source { MOVIE, SERIES, CHANNEL, RECOMMENDATION }

    companion object {
        fun fromMovie(movie: Movie): HeroItem = HeroItem(
            id = "movie-${movie.id}",
            title = movie.name,
            subtitle = movie.plot?.takeIf { it.isNotBlank() },
            eyebrow = listOfNotNull(movie.year, movie.genre?.takeIf { it.isNotBlank() })
                .firstOrNull() ?: "Film",
            meta = buildString {
                movie.year?.let { append(it) }
                movie.duration?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
                if (movie.rating > 0f) {
                    if (isNotEmpty()) append(" · ")
                    append("★ ${"%.1f".format(movie.rating)}")
                }
            }.takeIf { it.isNotBlank() },
            backdropUrl = movie.backdropUrl?.takeIf { it.isNotBlank() }
                ?: movie.posterUrl?.takeIf { it.isNotBlank() },
            source = Source.MOVIE
        )

        fun fromSeries(series: Series): HeroItem = HeroItem(
            id = "series-${series.id}",
            title = series.name,
            subtitle = series.plot?.takeIf { it.isNotBlank() },
            eyebrow = "Série",
            meta = buildString {
                series.releaseDate?.let { append(it) }
                if (series.rating > 0f) {
                    if (isNotEmpty()) append(" · ")
                    append("★ ${"%.1f".format(series.rating)}")
                }
            }.takeIf { it.isNotBlank() },
            backdropUrl = series.backdropUrl?.takeIf { it.isNotBlank() }
                ?: series.posterUrl?.takeIf { it.isNotBlank() },
            source = Source.SERIES
        )
    }
}

/**
 * Pick a short curated list of hero items from whatever is available in the
 * dashboard state. Preference: movies with a backdrop > series with a backdrop
 * > movies with a poster > series with a poster. Cap at [max].
 */
fun buildHeroItemsFrom(
    movies: List<Movie>,
    series: List<Series>,
    max: Int = 5
): List<HeroItem> {
    val pool = buildList {
        movies.asSequence()
            .filter { !it.backdropUrl.isNullOrBlank() }
            .take(max)
            .forEach { add(HeroItem.fromMovie(it)) }
        series.asSequence()
            .filter { !it.backdropUrl.isNullOrBlank() }
            .take(max)
            .forEach { add(HeroItem.fromSeries(it)) }
        movies.asSequence()
            .filter { it.backdropUrl.isNullOrBlank() && !it.posterUrl.isNullOrBlank() }
            .take(max)
            .forEach { add(HeroItem.fromMovie(it)) }
        series.asSequence()
            .filter { it.backdropUrl.isNullOrBlank() && !it.posterUrl.isNullOrBlank() }
            .take(max)
            .forEach { add(HeroItem.fromSeries(it)) }
    }
    // Deduplicate by id and cap.
    return pool.distinctBy { it.id }.take(max)
}
