package tvrename.subtitles

import java.io.File

import cats.effect._
import cats.implicits._
import com.github.dnbn.submerge.api.parser.SRTParser
import tvrename._
import tvrename.config._
import upickle.default._

import scala.util.Try

trait ReferenceSubtitleDownloader {
  def download(): IO[Unit]
}

class ReferenceSubtitleDownloaderImpl(
  config: TVRenameConfig,
  jobConfig: RemuxJobConfig,
  fileSystem: FileSystem,
  logger: Logger
) extends ReferenceSubtitleDownloader {

  case class SearchResult(
    SubFileName: String,
    InfoFormat: String,
    SubFormat: String,
    SeriesSeason: String,
    SeriesEpisode: String,
    SubDownloadLink: String,
    Score: Double
  ) {
    def isWebDL: Boolean = Option(InfoFormat).getOrElse("").toLowerCase == "web-dl"
  }

  case object SearchResult {
    implicit val reader: Reader[SearchResult] = macroR[SearchResult]
  }

  case class EpisodeSearchResults(episodeNumber: Int, searchResults: List[SearchResult])

  private val targetFolder =
    f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"

  private val episodeCountFile = s"${targetFolder}/.episode-count"

  private val parser = new SRTParser

  private def expectedEpisodeCount: IO[Option[Int]] =
    IO(
      if (fileSystem.exists(episodeCountFile)) fileSystem.readLines(episodeCountFile).headOption.map(_.toInt) else None
    )

  private def actualEpisodeCount: IO[Int] = IO(fileSystem.walk(targetFolder).count(f => f.endsWith(".srt")))

  private def search(): IO[List[EpisodeSearchResults]] =
    IO {
      val r = requests.get(
        s"https://rest.opensubtitles.org/search/imdbid-${jobConfig.seriesId.value}/season-${jobConfig.seasonNumber.value}/sublanguageid-eng",
        headers = Map("user-agent" -> "tvrename v1")
      )

      upickle.default
        .read[List[SearchResult]](r.text)
        .filterNot(_.SubFileName.toLowerCase.contains(".ita.")) // sometimes the wrong language is returned ???
        .filter(_.SubFormat.toLowerCase == "srt")
        .groupBy(_.SeriesEpisode.toInt)
        .map { case (k, v) => EpisodeSearchResults(k, v) }
        .toList
        .sortBy(_.episodeNumber)
    }

  override def download(): IO[Unit] =
    for {
      _ <- IO(fileSystem.makeDirs(targetFolder))
      expected <- expectedEpisodeCount
      actual <- actualEpisodeCount
      _ <- downloadIfNeeded(expected, actual)
    } yield ()

  private def downloadIfNeeded(expected: Option[Int], actual: Int): IO[Unit] = {
    expected match {
      case Some(a) if a == actual => IO.unit
      case _ =>
        for {
          searchResults <- search()
          // TODO: handle no episodes found for series/season
          lastEpisode = searchResults.map(_.episodeNumber).max
          _ <-
            logger.debug(s"${jobConfig.seriesName} Season ${jobConfig.seasonNumber.value} has ${lastEpisode} episodes")
          _ <- fileSystem.writeToFile(episodeCountFile, lastEpisode.toString)
          _ <- downloadAllEpisodes(searchResults)
        } yield ()
    }
  }

  private def downloadAllEpisodes(seasonSearchResults: List[EpisodeSearchResults]): IO[Unit] = {
    seasonSearchResults.traverse_ { episode =>
      val sortedSearchResults = episode.searchResults
        .sortBy(_.isWebDL)(Ordering[Boolean].reverse)
        .sortBy(_.Score)(Ordering[Double].reverse)

      val template = jobConfig.template
        .replace("[series]", jobConfig.seriesName)
        .replace("[season]", f"${jobConfig.seasonNumber.value}%02d")
        .replace("[episode]", f"${episode.episodeNumber}%02d")

      val targetFile = f"${targetFolder}/${template}.srt"

      if (!fileSystem.exists(targetFile)) {
        downloadValidSubtitleForEpisode(sortedSearchResults).flatMap { tempFile =>
          tempFile match {
            case Some(fileName) =>
              for {
                _ <- logger.debug(targetFile)
                _ <- fileSystem.rename(fileName, targetFile)
              } yield ()
            case None =>
              logger.warn(s"Unable to locate valid subtitle for episode ${episode.episodeNumber}")
          }
        }
      } else IO.unit
    }
  }

  def downloadValidSubtitleForEpisode(searchResults: List[SearchResult]): IO[Option[String]] = {
    searchResults.foldLeft[IO[Option[String]]](IO(None)) { (acc, next) =>
      acc.flatMap {
        case Some(str) => IO(Some(str))
        case None      => downloadAndParseSubtitle(next)
      }
    }
  }

  def downloadAndParseSubtitle(searchResult: SearchResult): IO[Option[String]] = {
    val tempFile = fileSystem.getTempFileName()
    for {
      _ <- logger.debug(searchResult.SubDownloadLink)
      stream <- IO(requests.get.stream(searchResult.SubDownloadLink, check = false))
      _ <- IO(fileSystem.streamCommandToFile(stream, "gunzip", tempFile))
      attempt <- Try { parser.parse(new File(tempFile)) }.attempt.liftTo[IO]
    } yield attempt.map(_ => tempFile).toOption
  }
}
