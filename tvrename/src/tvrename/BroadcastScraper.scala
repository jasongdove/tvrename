package tvrename

import cats.effect.IO
import cats.implicits._
import tvrename.config._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import io.circe._
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId

case class Episode(airDate: LocalDate, seasonNumber: SeasonNumber, episodeNumber: EpisodeNumber)

trait BroadcastScraper {
  def episodesForSeason(seasonNumber: SeasonNumber): IO[List[Episode]]
}

class BroadcastScraperImpl(jobConfig: BroadcastJobConfig, httpClient: Client[IO]) extends BroadcastScraper {
  def episodesForSeason(seasonNumber: SeasonNumber): IO[List[Episode]] =
    for {
      uri <- Uri.fromString(jobConfig.jsonUrl).liftTo[IO]
      request <- Method.GET(uri)
      response <- httpClient.expect[Json](request)
      array <- getArray(response.hcursor)
      episodes <- getEpisodes(array.values)
    } yield episodes

  def getArray(cursor: HCursor): IO[ACursor] = {
    @scala.annotation.tailrec
    def inner(fields: List[String], cursor: ACursor): IO[ACursor] = {
      if (fields.isEmpty) IO.pure(cursor)
      else inner(fields.tail, cursor.downField(fields.head))
    }

    val fields = jobConfig.arraySelector.split('.').toList
    inner(fields, cursor)
  }

  def getEpisodes(array: Option[Iterable[Json]]): IO[List[Episode]] =
    array match {
      case Some(array) =>
        array
          .map { json =>
            val cursor = json.hcursor
            for {
              dateString <- cursor.downField(jobConfig.isoDateProperty).as[String].liftTo[IO]
              seasonNumberInt <- cursor.downField(jobConfig.seasonNumberProperty).as[Int].liftTo[IO]
              episodeNumberInt <- cursor.downField(jobConfig.episodeNumberProperty).as[Int].liftTo[IO]
            } yield Episode(
              OffsetDateTime.parse(dateString).toInstant.atZone(ZoneId.systemDefault).toLocalDate,
              SeasonNumber(seasonNumberInt),
              EpisodeNumber(episodeNumberInt)
            )
          }
          .toList
          .sequence
      case None => IO.pure(List.empty)
    }
}
