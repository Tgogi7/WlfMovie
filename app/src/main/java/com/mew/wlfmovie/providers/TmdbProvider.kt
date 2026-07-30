package com.mew.wlfmovie.providers

import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.extractors.AfterDarkExtractor
import com.mew.wlfmovie.extractors.Extractor
import com.mew.wlfmovie.extractors.MoflixExtractor
import com.mew.wlfmovie.extractors.MoviesapiExtractor
import com.mew.wlfmovie.extractors.TwoEmbedExtractor
import com.mew.wlfmovie.extractors.VidsrcNetExtractor
import com.mew.wlfmovie.extractors.VidsrcToExtractor
import com.mew.wlfmovie.extractors.VidzeeExtractor
import com.mew.wlfmovie.extractors.VixSrcExtractor
import com.mew.wlfmovie.extractors.VidLinkExtractor
import com.mew.wlfmovie.extractors.VidsrcRuExtractor
import com.mew.wlfmovie.extractors.EinschaltenExtractor
import com.mew.wlfmovie.extractors.FrembedExtractor
import com.mew.wlfmovie.extractors.VidflixExtractor
import com.mew.wlfmovie.extractors.VidrockExtractor
import com.mew.wlfmovie.extractors.VideasyExtractor
import com.mew.wlfmovie.extractors.PrimeSrcExtractor
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.People
import com.mew.wlfmovie.models.Season
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.TMDb3
import com.mew.wlfmovie.utils.TMDb3.original
import com.mew.wlfmovie.utils.TMDb3.w500
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.safeSubList
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer

class TmdbProvider(override val language: String) : Provider {
    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val watchRegion = if (language == "en") "US" else language.uppercase()

        val mapMulti: (TMDb3.MultiItem) -> AppAdapter.Item? = { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                else -> null
            }
        }

        val trendingDeferred = async {
            awaitAll(
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 2, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularMoviesDeferred = async {
            awaitAll(
                async { TMDb3.MovieLists.popular(page = 1, language = language) },
                async { TMDb3.MovieLists.popular(page = 2, language = language) },
                async { TMDb3.MovieLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularTvShowsDeferred = async {
            awaitAll(
                async { TMDb3.TvSeriesLists.popular(page = 1, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 2, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        }

        val popularAnimeDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
            ).flatMap { it.results }
        }

        val netflixDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.NETFLIX),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.NETFLIX),
                    )
                },
            ).flatMap { it.results }
        }

        val amazonDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.AMAZON_VIDEO),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.AMAZON),
                    )
                },
            ).flatMap { it.results }
        }

        val disneyDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.DISNEY_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.DISNEY_PLUS),
                    )
                },
            ).flatMap { it.results }
        }

        val huluDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.HULU),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HULU),
                    )
                },
            ).flatMap { it.results }
        }

        val appleDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.APPLE_TV),
                    )
                },
            ).flatMap { it.results }
        }

        val hboDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 1,
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 2,
                    )
                },
            ).flatMap { it.results }
        }

        val trending = trendingDeferred.await()
        // WLFMOVIE: FEATURED eliminado - no más carousel auto-scroll de 5 carátulas

        categories.add(
            Category(
                name = getTranslation("Trending"),
                list = trending.mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Movies"),
                list = popularMoviesDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular TV Shows"),
                list = popularTvShowsDeferred.await().mapNotNull(mapMulti)
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Anime"),
                list = popularAnimeDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Netflix"),
                list = netflixDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Amazon"),
                list = amazonDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Disney+"),
                list = disneyDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Hulu"),
                list = huluDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Apple TV+"),
                list = appleDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull(mapMulti),
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on HBO"),
                list = hboDeferred.await().mapNotNull(mapMulti),
            )
        )

        // WLFMOVIE: Top 10 películas y Top 10 series más populares
        val top10MoviesDeferred = async {
            TMDb3.MovieLists.topRated(page = 1, language = language)
        }
        val top10TvDeferred = async {
            TMDb3.TvSeriesLists.topRated(page = 1, language = language)
        }

        categories.add(
            Category(
                name = "Top 10 Películas",
                list = top10MoviesDeferred.await().results.take(10).mapNotNull(mapMulti),
            )
        )
        categories.add(
            Category(
                name = "Top 10 Series",
                list = top10TvDeferred.await().results.take(10).mapNotNull(mapMulti),
            )
        )

        // WLFMOVIE: Categorías por géneros populares (Acción, Comedia, Terror)
        val actionDeferred = async {
            TMDb3.Discover.movie(
                language = language,
                withGenres = TMDb3.Params.WithBuilder("28"), // Acción
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            )
        }
        val comedyDeferred = async {
            TMDb3.Discover.movie(
                language = language,
                withGenres = TMDb3.Params.WithBuilder("35"), // Comedia
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            )
        }
        val horrorDeferred = async {
            TMDb3.Discover.movie(
                language = language,
                withGenres = TMDb3.Params.WithBuilder("27"), // Terror
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
            )
        }

        categories.add(
            Category(
                name = "Populares de Acción",
                list = actionDeferred.await().results.mapNotNull(mapMulti),
            )
        )
        categories.add(
            Category(
                name = "Populares de Comedia",
                list = comedyDeferred.await().results.mapNotNull(mapMulti),
            )
        )
        categories.add(
            Category(
                name = "Populares de Terror",
                list = horrorDeferred.await().results.mapNotNull(mapMulti),
            )
        )

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                else -> null
            }
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val movies = TMDb3.MovieLists.popular(page = page, language = language).results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w500,
                banner = movie.backdropPath?.original,
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val tvShows = TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w500,
                banner = tv.backdropPath?.original,
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = TMDb3.Movies.details(
            movieId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.original,
                banner = movie.backdropPath?.original,
                imdbId = movie.externalIds?.imdbId,

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = TMDb3.TvSeries.details(
            seriesId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.original,
                banner = tv.backdropPath?.original,
                imdbId = tv.externalIds?.imdbId,

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.original,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.original,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.cast
                    ?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                overview = multi.overview,
                                released = multi.releaseDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                overview = multi.overview,
                                released = multi.firstAirDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                        else -> null
                    }
                }
                    ?.sortedBy {
                        when (it) {
                            is Movie -> it.released
                            is TvShow -> it.released
                        }
                    }
                    ?.reversed()
                    ?: listOf()
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val lang = language.lowercase().substringBefore("-")

        Log.d("TmdbProvider", "getServers: lang=$language, simplifiedLang=$lang")

        when (lang) {
            "it" -> {
                // Se la lingua è italiano, includiamo solo i server noti per l'italiano.
                servers.add(VixSrcExtractor().server(videoType))
            }
            "de" -> {
                // Solo server tedeschi
                servers.addAll(0, MoflixExtractor().servers(videoType))
                if (videoType is Video.Type.Movie) {
                    servers.add(EinschaltenExtractor().server(videoType))
                }
                VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
            }
            "fr" -> {
                // Solo server francesi
                servers.addAll(FrembedExtractor(UserPreferences.getProviderCache(FrembedProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
                servers.addAll(AfterDarkExtractor(UserPreferences.getProviderCache(AfterDarkProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
            }
            "es" -> {
                // =================================================================
                // WLFMOVIE: TMDB español - busca en paralelo en 11 providers.
                //
                // IMPORTANTE: Para EPISODIOS, no podemos llamar provider.getServers(serieId)
                // porque la mayoria de providers necesita el ID DEL EPISODIO, no de la serie.
                // El flujo correcto es:
                //   1. search() devuelve la SERIE con su serieId
                //   2. provider.getTvShow(serieId) → devuelve seasons del provider
                //   3. provider.getEpisodesBySeason(seasonId) → devuelve episodios con sus ids
                //   4. Buscamos el episodio con el numero correcto
                //   5. provider.getServers(episodeId, videoType) → servers reales
                //
                // Para PELICULAS es directo: search() → getServers(movieId)
                // =================================================================

                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }
                val targetSeason = (videoType as? Video.Type.Episode)?.season?.number
                val targetEpisode = (videoType as? Video.Type.Episode)?.number

                Log.i("WlfMovie", "[SEARCH] -> '$targetTitle' " +
                    if (videoType is Video.Type.Movie) "(Movie)"
                    else "(TV S${targetSeason}E${targetEpisode})")

                // =================================================================
                // Matching mejorado: normaliza acentos, ignora parentesis/años
                // =================================================================
                fun normalize(s: String): String {
                    val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
                        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                    return noAccents.lowercase()
                        .replace(Regex("\\([^)]*\\)"), " ")
                        .replace(Regex("\\[[^]]*]"), " ")
                        .replace(Regex("(?i)\\btemporada\\s*\\d+\\b"), " ")
                        .replace(Regex("(?i)\\bseason\\s*\\d+\\b"), " ")
                        .replace(Regex("(?i)\\btemporada\\b"), " ")
                        .replace(Regex("(?i)\\bseason\\b"), " ")
                        .replace(Regex("(?i)\\bcompleta\\b"), " ")
                        .replace(Regex("(?i)\\bcompleto\\b"), " ")
                        .replace(Regex("[^a-z0-9 ]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }

                fun wordsOf(s: String): Set<String> {
                    return normalize(s).split(" ").filter { it.length > 2 }.toSet()
                }

                fun isMatch(item: AppAdapter.Item, target: String): Boolean {
                    val itemTitle = when (item) {
                        is Movie -> item.title
                        is TvShow -> item.title
                        else -> return false
                    }
                    val nItem = normalize(itemTitle)
                    val nTarget = normalize(target)
                    if (nItem == nTarget) return true
                    if (nItem.startsWith(nTarget) || nTarget.startsWith(nItem)) {
                        if (kotlin.math.abs(nItem.length - nTarget.length) <= 12) return true
                    }
                    val itemWords = wordsOf(itemTitle)
                    val targetWords = wordsOf(target)
                    if (itemWords.isEmpty() || targetWords.isEmpty()) return false
                    val coverage = itemWords.intersect(targetWords).size.toFloat() / targetWords.size
                    return coverage >= 0.6f
                }

                // =================================================================
                // Funcion helper: dado un provider y un serieId, resuelve el
                // episodeId correcto navegando seasons → episodes.
                // =================================================================
                suspend fun resolveEpisodeId(
                    provider: Provider,
                    serieId: String,
                    seasonNum: Int,
                    episodeNum: Int,
                ): String? {
                    return try {
                        // Paso 2: obtener la serie con sus seasons
                        val tvShow = provider.getTvShow(serieId)
                        Log.d("WlfMovie", "  [${provider.name}] getTvShow OK: ${tvShow.seasons.size} seasons")

                        // Paso 3: encontrar la season que coincide
                        val season = tvShow.seasons.firstOrNull {
                            it.number == seasonNum ||
                                it.number == seasonNum + 1  // algunos providers usan season 0 para especiales
                        } ?: tvShow.seasons.firstOrNull { it.number == seasonNum }
                            ?: return null

                        Log.d("WlfMovie", "  [${provider.name}] season encontrada: ${season.id} (num=${season.number})")

                        // Paso 4: obtener los episodios de esa season
                        val episodes = provider.getEpisodesBySeason(season.id)
                        Log.d("WlfMovie", "  [${provider.name}] getEpisodesBySeason OK: ${episodes.size} episodios")

                        // Paso 5: encontrar el episodio
                        val episode = episodes.firstOrNull { it.number == episodeNum }
                        episode?.id.also {
                            if (it != null) {
                                Log.d("WlfMovie", "  [${provider.name}] episodio encontrado: $it")
                            } else {
                                Log.d("WlfMovie", "  [${provider.name}] NO encontro episodio $episodeNum (disponibles: ${episodes.take(5).map { e -> e.number }})")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("WlfMovie", "  [${provider.name}] resolveEpisodeId fallo: ${e.message}")
                        null
                    }
                }

                coroutineScope {
                    val providers = listOf(
                        CuevanaEuProvider,
                        CineCalidadProvider,
                        PoseidonHD2Provider,
                        PelisplustoProvider,
                        PelisflixHdProvider,
                        SeriesFlixProvider,
                        FlixLatamProvider,
                        SoloLatinoProvider,
                        CableVisionHDProvider,
                        CineCityProvider,
                        DoramasflixProvider,
                    )
                    val deferred = providers.map { provider ->
                        async {
                            // Timeout ampliado a 25s para episodios (necesita varias llamadas)
                            val timeoutMs = if (videoType is Video.Type.Episode) 25_000L else 15_000L
                            withTimeoutOrNull(timeoutMs) {
                                try {
                                    val searchResults = provider.search(targetTitle, 1)
                                    if (searchResults.isEmpty()) {
                                        Log.d("WlfMovie", "[EMPTY] -> ${provider.name}")
                                        return@withTimeoutOrNull emptyList<Video.Server>()
                                    }

                                    Log.d("WlfMovie", "[RESULTS] -> ${provider.name}: ${searchResults.size} resultados = ${
                                        searchResults.take(3).joinToString {
                                            (if (it is Movie) it.title else (it as? TvShow)?.title ?: "?")
                                        }
                                    }")

                                    val bestMatch = searchResults.firstOrNull { isMatch(it, targetTitle) }
                                    val matchedId = when (bestMatch) {
                                        is Movie -> bestMatch.id
                                        is TvShow -> bestMatch.id
                                        else -> null
                                    }

                                    if (matchedId == null) {
                                        Log.d("WlfMovie", "[NO MATCH] -> ${provider.name}")
                                        return@withTimeoutOrNull emptyList()
                                    }

                                    val matchTitle = when (bestMatch) {
                                        is Movie -> bestMatch.title
                                        is TvShow -> bestMatch.title
                                        else -> ""
                                    }
                                    Log.i("WlfMovie", "[MATCH] -> ${provider.name}: '$matchTitle'")

                                    // =================================================================
                                    // Para episodios: navegar serie → season → episodio → servers
                                    // Para peliculas: directo getServers(movieId)
                                    // =================================================================
                                    val finalId = if (videoType is Video.Type.Episode && bestMatch is TvShow) {
                                        val seasonNum = targetSeason ?: return@withTimeoutOrNull emptyList()
                                        val episodeNum = targetEpisode ?: return@withTimeoutOrNull emptyList()
                                        Log.d("WlfMovie", "  [${provider.name}] resolviendo episodio S${seasonNum}E${episodeNum}...")
                                        resolveEpisodeId(provider, matchedId, seasonNum, episodeNum)
                                    } else {
                                        matchedId
                                    }

                                    if (finalId == null) {
                                        Log.d("WlfMovie", "[NO EPISODE] -> ${provider.name}: no se encontro el episodio")
                                        return@withTimeoutOrNull emptyList()
                                    }

                                    val allServers = provider.getServers(finalId, videoType)
                                    Log.i("WlfMovie", "[SERVERS] -> ${provider.name}: ${allServers.size} servers")
                                    allServers
                                } catch (e: Exception) {
                                    val errorMsg = when (e) {
                                        is retrofit2.HttpException -> "HTTP ${e.code()}"
                                        is java.net.SocketTimeoutException -> "Timeout"
                                        is java.net.UnknownHostException -> "DNS no resuelto"
                                        is javax.net.ssl.SSLException -> "SSL error"
                                        is java.io.IOException -> "IO: ${e.message}"
                                        else -> e.javaClass.simpleName + ": " + e.message
                                    }
                                    Log.e("WlfMovie", "[ERROR] -> ${provider.name}: $errorMsg")
                                    emptyList()
                                }
                            } ?: run {
                                Log.w("WlfMovie", "[TIMEOUT] -> ${provider.name} supero el timeout")
                                emptyList()
                            }
                        }
                    }
                    servers.addAll(deferred.awaitAll().flatten())
                }
            }
            else -> {
                // Per inglese (en) o altre lingue non specifiche, usiamo i server globali
                servers.addAll(listOf(
                    VixSrcExtractor().server(videoType),
                    TwoEmbedExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    VidflixExtractor().server(videoType),
                ))

                if (videoType is Video.Type.Movie) {
                    servers.add(2, MoviesapiExtractor().server(videoType))
                }

                servers.addAll(VidrockExtractor().servers(videoType))
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))

                if (language == "en") {
                    servers.addAll(1, VideasyExtractor().servers(videoType, language))
                }
            }
        }

        // =================================================================
        // WLFMOVIE: Ordenamiento final - prioriza servers con audio español.
        // Cuanto mas arriba en la lista, mas prioridad.
        // =================================================================
        val finalServers = if (language.startsWith("es")) {
            servers.sortedByDescending { server ->
                val n = server.name.uppercase()
                when {
                    // Audio latino/castellano - maxima prioridad
                    n.contains("[LAT]") || n.contains("(LAT)") || n.contains("LATINO") ||
                    n.contains("[CAST]") || n.contains("[CAS]") || n.contains("CASTELLANO") ||
                    n.contains("[ES]") || n.contains("(ESP)") || n.contains("(ES)") ||
                    n.contains("SPANISH") || n.contains("ESPAÑOL") || n.contains("ESPANOL") ||
                    n.contains("SPAIN") -> 100

                    // Hosts conocidos por tener contenido latino (sin tag explicito)
                    n.contains("FILEMOON") || n.contains("STREAMWISH") || n.contains("VIDHIDE") ||
                    n.contains("VOE") || n.contains("MIXDROP") || n.contains("UQLOAD") ||
                    n.contains("DOODLA") || n.contains("STREAMTAPE") -> 80

                    // Otros agregadores multi-idioma
                    n.contains("VIDSRC") || n.contains("VIDLINK") -> 60

                    // Subtitulos o ingles - baja prioridad
                    n.contains("[EN]") || n.contains("[SUB]") || n.contains("(EN)") || n.contains("(SUB)") -> 30

                    else -> 50  // Default - si no sabemos, lo dejamos en medio
                }
            }
        } else {
            servers
        }

        Log.i("WlfMovie", "[FINAL] -> ${finalServers.size} servers: ${finalServers.joinToString { it.name }}")
        return finalServers.distinctBy { it.id }
    }

    // =================================================================
    // WLFMOVIE: Versión streaming de getServers.
    // Emite lotes de servers a medida que cada provider termina,
    // en vez de esperar a que todos terminen.
    //
    // Usa flow { } builder que permite llamadas suspend dentro.
    // El PlayerViewModel recolecta este Flow y actualiza la lista
    // del player en vivo. El usuario puede empezar a ver nada más
    // llegar el primer server, y los demás se agregan después.
    // =================================================================
    fun getServersFlow(id: String, videoType: Video.Type): Flow<List<Video.Server>> = flow {
        if (language.lowercase().substringBefore("-") != "es") {
            // Para otros idiomas, caer al método síncrono de una sola vez
            emit(getServers(id, videoType))
            return@flow
        }

        // ===== Mismo bloque "es" de getServers, pero emitiendo incrementalmente =====
        val targetTitle = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val targetSeason = (videoType as? Video.Type.Episode)?.season?.number
        val targetEpisode = (videoType as? Video.Type.Episode)?.number

        Log.i("WlfMovie", "[STREAM SEARCH] -> '$targetTitle' " +
            if (videoType is Video.Type.Movie) "(Movie)"
            else "(TV S${targetSeason}E${targetEpisode})")

        val collectedServers = mutableListOf<Video.Server>()
        val seenIds = mutableSetOf<String>()

        fun normalize(s: String): String {
            val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            return noAccents.lowercase()
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("\\[[^]]*]"), " ")
                .replace(Regex("(?i)\\btemporada\\s*\\d+\\b"), " ")
                .replace(Regex("(?i)\\bseason\\s*\\d+\\b"), " ")
                .replace(Regex("(?i)\\btemporada\\b"), " ")
                .replace(Regex("(?i)\\bseason\\b"), " ")
                .replace(Regex("(?i)\\bcompleta\\b"), " ")
                .replace(Regex("(?i)\\bcompleto\\b"), " ")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        fun wordsOf(s: String): Set<String> = normalize(s).split(" ").filter { it.length > 2 }.toSet()
        fun isMatch(item: AppAdapter.Item, target: String): Boolean {
            val itemTitle = when (item) {
                is Movie -> item.title
                is TvShow -> item.title
                else -> return false
            }
            val nItem = normalize(itemTitle)
            val nTarget = normalize(target)
            if (nItem == nTarget) return true
            if (nItem.startsWith(nTarget) || nTarget.startsWith(nItem)) {
                if (kotlin.math.abs(nItem.length - nTarget.length) <= 12) return true
            }
            val itemWords = wordsOf(itemTitle)
            val targetWords = wordsOf(target)
            if (itemWords.isEmpty() || targetWords.isEmpty()) return false
            return itemWords.intersect(targetWords).size.toFloat() / targetWords.size >= 0.6f
        }

        suspend fun resolveEpisodeId(
            provider: Provider, serieId: String,
            seasonNum: Int, episodeNum: Int,
        ): String? = try {
            val tvShow = provider.getTvShow(serieId)
            val season = tvShow.seasons.firstOrNull { it.number == seasonNum }
                ?: tvShow.seasons.firstOrNull { it.number == seasonNum + 1 }
                ?: return null
            val episodes = provider.getEpisodesBySeason(season.id)
            episodes.firstOrNull { it.number == episodeNum }?.id
        } catch (e: Exception) { null }

        fun sortEs(servers: List<Video.Server>): List<Video.Server> {
            return servers.sortedByDescending { server ->
                val n = server.name.uppercase()
                when {
                    n.contains("[LAT]") || n.contains("(LAT)") || n.contains("LATINO") ||
                    n.contains("[CAST]") || n.contains("[CAS]") || n.contains("CASTELLANO") ||
                    n.contains("[ES]") || n.contains("(ESP)") || n.contains("(ES)") ||
                    n.contains("SPANISH") || n.contains("ESPAÑOL") || n.contains("ESPANOL") ||
                    n.contains("SPAIN") -> 100
                    n.contains("FILEMOON") || n.contains("STREAMWISH") || n.contains("VIDHIDE") ||
                    n.contains("VOE") || n.contains("MIXDROP") || n.contains("UQLOAD") ||
                    n.contains("DOODLA") || n.contains("STREAMTAPE") -> 80
                    n.contains("VIDSRC") || n.contains("VIDLINK") -> 60
                    n.contains("[EN]") || n.contains("[SUB]") || n.contains("(EN)") || n.contains("(SUB)") -> 30
                    else -> 50
                }
            }.distinctBy { it.id }
        }

        val providers = listOf(
            CuevanaEuProvider, CineCalidadProvider, PoseidonHD2Provider,
            PelisplustoProvider, PelisflixHdProvider, SeriesFlixProvider,
            FlixLatamProvider, SoloLatinoProvider, CableVisionHDProvider,
            CineCityProvider, DoramasflixProvider,
        )

        try {
            // Implementación con coroutineScope + Channel manual
            coroutineScope {
            // Channel para recibir los resultados a medida que cada provider termina
            val channel = Channel<List<Video.Server>>(11)

            // Lanzar todas las búsquedas en paralelo
            val jobs = providers.map { provider ->
                launch {
                    val timeoutMs = if (videoType is Video.Type.Episode) 25_000L else 15_000L
                    val result = withTimeoutOrNull(timeoutMs) {
                        try {
                            val searchResults = provider.search(targetTitle, 1)
                            if (searchResults.isEmpty()) {
                                emptyList<Video.Server>()
                            } else {
                                val bestMatch = searchResults.firstOrNull { isMatch(it, targetTitle) }
                                val matchedId = when (bestMatch) {
                                    is Movie -> bestMatch.id
                                    is TvShow -> bestMatch.id
                                    else -> null
                                }

                                if (matchedId == null) {
                                    emptyList()
                                } else {
                                    Log.i("WlfMovie", "[STREAM MATCH] -> ${provider.name}")

                                    val finalId = if (videoType is Video.Type.Episode && bestMatch is TvShow) {
                                        val sn = targetSeason
                                        val en = targetEpisode
                                        if (sn != null && en != null) {
                                            resolveEpisodeId(provider, matchedId, sn, en)
                                        } else null
                                    } else matchedId

                                    if (finalId == null) emptyList()
                                    else provider.getServers(finalId, videoType)
                                }
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } ?: emptyList()

                    channel.send(result)
                }
            }

            // Recolectar del canal y emitir a medida que llegan
            var received = 0
            while (received < providers.size) {
                val batch = channel.receive()
                received++

                // Filtrar duplicados por id
                val newServers = batch.filter { seenIds.add(it.id) }
                if (newServers.isNotEmpty()) {
                    collectedServers.addAll(newServers)
                    val sorted = sortEs(collectedServers)
                    collectedServers.clear()
                    collectedServers.addAll(sorted)
                    Log.i("WlfMovie", "[STREAM EMIT] -> Lote $received: +${newServers.size} (total: ${collectedServers.size})")
                    emit(sorted)
                }
            }
            channel.close()

            // Emisión final
            if (collectedServers.isEmpty()) {
                emit(emptyList())
            } else {
                emit(sortEs(collectedServers))
            }

            // Esperar a que todos los jobs terminen
            jobs.forEach { it.join() }
            }
        } catch (e: Exception) {
            Log.e("WlfMovie", "[STREAM ERROR] -> ${e.message}")
            emit(collectedServers.toList())
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifEmpty { server.id }
        Log.i("StreamFlixES", "[SERVER] -> Using: ${server.name} (URL: $url)")
        
        val video = when {
            server.video != null -> server.video!!
            else -> Extractor.extract(url, server)
        }

        // LOGICA SOTTOTITOLI FORZATI: Se siamo in spagnolo, attiviamo solo i forced di default
        if (language.startsWith("es")) {
            var forcedFound = false
            video.subtitles.forEach { sub ->
                val label = sub.label.lowercase()
                val isSpanish = label.contains("spanish") || label.contains("español") || 
                                label.contains("espanol") || label.contains("castellano") || 
                                label.contains(" lat ")
                val isForced = label.contains("forced") || label.contains("forzati") || label.contains("forzato")

                if (isSpanish && isForced) {
                    sub.default = true
                    forcedFound = true
                    Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): Selected FORCED subtitle: ${sub.label}")
                } else {
                    sub.default = false
                }
            }
            
            if (!forcedFound) {
                video.subtitles.forEach { it.default = false }
                Log.i("StreamFlixES", "[SUBTITLE] -> TMDb (es): No forced subs found, keeping them OFF")
            }
        }
        
        Log.i("StreamFlixES", "[VIDEO] -> Final source: ${video.source}")
        return video
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "it" -> when (key) {
                "Trending" -> "Di tendenza"
                "Popular Movies" -> "Film popolari"
                "Popular TV Shows" -> "Serie TV popolari"
                "Popular Anime" -> "Anime popolari"
                "Popular on Netflix" -> "Popolari su Netflix"
                "Popular on Amazon" -> "Popolari su Amazon"
                "Popular on Disney+" -> "Popolari su Disney+"
                "Popular on Hulu" -> "Popolari su Hulu"
                "Popular on Apple TV+" -> "Popolari su Apple TV+"
                "Popular on HBO" -> "Popolari su HBO"
                else -> key
            }
            "es" -> when (key) {
                "Trending" -> "Tendencias"
                "Popular Movies" -> "Películas populares"
                "Popular TV Shows" -> "Series de TV populares"
                "Popular Anime" -> "Anime populares"
                "Popular on Netflix" -> "Popular en Netflix"
                "Popular on Amazon" -> "Popular en Amazon"
                "Popular on Disney+" -> "Popular en Disney+"
                "Popular on Hulu" -> "Popular en Hulu"
                "Popular on Apple TV+" -> "Popular en Apple TV+"
                "Popular on HBO" -> "Popular en HBO"
                else -> key
            }
            "de" -> when (key) {
                "Trending" -> "Trends"
                "Popular Movies" -> "Beliebte Filme"
                "Popular TV Shows" -> "Beliebte Serien"
                "Popular Anime" -> "Beliebte Anime"
                "Popular on Netflix" -> "Beliebt bei Netflix"
                "Popular on Amazon" -> "Beliebt bei Amazon"
                "Popular on Disney+" -> "Beliebt bei Disney+"
                "Popular on Hulu" -> "Beliebt bei Hulu"
                "Popular on Apple TV+" -> "Beliebt bei Apple TV+"
                "Popular on HBO" -> "Beliebt bei HBO"
                else -> key
            }
            "fr" -> when (key) {
                "Trending" -> "Tendances"
                "Popular Movies" -> "Films populaires"
                "Popular TV Shows" -> "Séries populaires"
                "Popular Anime" -> "Animes populaires"
                "Popular on Netflix" -> "Populaire sur Netflix"
                "Popular on Amazon" -> "Populaire sur Amazon"
                "Popular on Disney+" -> "Populaire sur Disney+"
                "Popular on Hulu" -> "Populaire sur Hulu"
                "Popular on Apple TV+" -> "Populaire sur Apple TV+"
                "Popular on HBO" -> "Populaire sur HBO"
                else -> key
            }
            else -> key
        }
    }
}
