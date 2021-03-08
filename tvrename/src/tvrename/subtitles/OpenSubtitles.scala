package tvrename.subtitles

import cats.effect.IO
import cats.implicits._
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import tvrename.config._

case class SearchResult(
  SubFileName: String,
  InfoFormat: Option[String],
  SubFormat: String,
  SeriesSeason: String,
  SeriesEpisode: String,
  SubDownloadLink: String,
  Score: Double
) {
  def isWebDL: Boolean = InfoFormat.getOrElse("").toLowerCase == "web-dl"
}

case class EpisodeSearchResults(episodeNumber: EpisodeNumber, searchResults: List[SearchResult])

trait OpenSubtitles {
  def search(seriesId: SeriesId, seasonNumber: SeasonNumber): IO[List[EpisodeSearchResults]]
}

class OpenSubtitlesImpl(httpClient: Client[IO]) extends OpenSubtitles {
  implicit val decoder: EntityDecoder[IO, List[SearchResult]] = jsonOf[IO, List[SearchResult]]

  def search(seriesId: SeriesId, seasonNumber: SeasonNumber): IO[List[EpisodeSearchResults]] = {
    val seasonSearchUri =
      s"https://rest.opensubtitles.org/search/imdbid-${seriesId.value.toString}/season-${seasonNumber.value.toString}/sublanguageid-eng"
    for {
      uri <- Uri.fromString(seasonSearchUri).liftTo[IO]
      request <- Method.GET(uri).map(_.withHeaders(Header("user-agent", "tvrename v1")))
      response <- httpClient.expect[List[SearchResult]](request)
    } yield {
      response
        .filterNot(_.SubFileName.toLowerCase.contains(".ita.")) // sometimes the wrong language is returned ???
        .filter(_.SubFormat.toLowerCase == "srt")
        .groupBy(s => EpisodeNumber(s.SeriesEpisode.toInt))
        .map { case (episodeNumber, results) => EpisodeSearchResults(episodeNumber, results) }
        .toList
        .sortBy(_.episodeNumber.value)
    }
  }

}
