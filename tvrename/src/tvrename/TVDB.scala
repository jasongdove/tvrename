package tvrename

import tvrename.config._

import cats.effect._
import cats.implicits._
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.io._

case class Login(token: String)
case class Links(first: Int, last: Int)
case class EpisodeData(firstAired: String, airedEpisodeNumber: Int)
case class Episodes(links: Links, data: List[EpisodeData])

trait TVDB {
  def episodesForSeason(seriesId: SeriesId, seasonNumber: SeasonNumber): IO[List[EpisodeData]]
}

class TVDBImpl(config: TVDBConfig, httpClient: Client[IO]) extends TVDB {

  implicit val loginDecoder: EntityDecoder[IO, Login] = jsonOf[IO, Login]
  implicit val linksDecoder: EntityDecoder[IO, Links] = jsonOf[IO, Links]
  implicit val episodeDataDecoder: EntityDecoder[IO, EpisodeData] = jsonOf[IO, EpisodeData]
  implicit val episodeDecoder: EntityDecoder[IO, Episodes] = jsonOf[IO, Episodes]
  implicit val configEncoder: EntityEncoder[IO, TVDBConfig] = jsonEncoderOf[IO, TVDBConfig]

  private val baseUrl = "https://api.thetvdb.com"

  def episodesForSeason(seriesId: SeriesId, seasonNumber: SeasonNumber): IO[List[EpisodeData]] =
    for {
      jwt <- getJwt()
      firstPage <- pagedEpisodesForSeason(seriesId, seasonNumber, 1, jwt)
      range <- IO.pure(Iterator.range(2, firstPage.links.last + 1).toList)
      remainingPages <-
        range.flatTraverse(page => pagedEpisodesForSeason(seriesId, seasonNumber, page, jwt).map(_.data))
    } yield firstPage.data ++ remainingPages

  private def pagedEpisodesForSeason(
    seriesId: SeriesId,
    seasonNumber: SeasonNumber,
    page: Int,
    jwt: String
  ): IO[Episodes] =
    for {
      uri <-
        Uri
          .fromString(
            s"$baseUrl/series/${seriesId.value.toString}/episodes/query?airedSeason=${seasonNumber.value.toString}&page=${page.toString}"
          )
          .liftTo[IO]
      headers <- IO.pure(List(Header("Authorization", s"Bearer $jwt"), Header("Accept", "application/json")))
      request <- Method.GET(uri).map(_.withHeaders(headers: _*))
      response <- httpClient.expect[Episodes](request)
    } yield response

  private def getJwt(): IO[String] =
    for {
      uri <- Uri.fromString(s"$baseUrl/login").liftTo[IO]
      request <- Method.POST(config, uri)
      response <- httpClient.expect[Login](request)
    } yield response.token
}
