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
        .map { case (k,  v) => EpisodeSearchResults(k, v) }
        .toList
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
          _ <- logger.debug(s"${jobConfig.seriesName} Season ${jobConfig.seasonNumber.value} has ${lastEpisode} episodes")
          _ <- fileSystem.writeToFile(episodeCountFile, lastEpisode.toString)
          _ <- downloadAllEpisodes(searchResults)
        } yield ()
    }
  }

  private def downloadAllEpisodes(seasonSearchResults: List[EpisodeSearchResults]): IO[Unit] = {
    val parser = new SRTParser

    seasonSearchResults.map { episode =>
        val sortedSearchResults = episode.searchResults
          .sortBy(_.isWebDL)(Ordering[Boolean].reverse)
          .sortBy(_.Score)(Ordering[Double].reverse)

        val template = jobConfig.template
          .replace("[series]", jobConfig.seriesName)
          .replace("[season]", f"${jobConfig.seasonNumber.value}%02d")
          .replace("[episode]", f"${episode.episodeNumber}%02d")

        val targetFile = f"${targetFolder}/${template}.srt"

        if (!fileSystem.exists(targetFile)) {
          val tempFile = fileSystem.getTempFileName()

          val validSubtitle = sortedSearchResults.find { subtitle =>
            logger.debug(subtitle.SubDownloadLink)
            val downloadAndParseAttempt = Try {
              val stream = requests.get.stream(subtitle.SubDownloadLink, check = false)
              fileSystem.streamCommandToFile(stream, "gunzip", tempFile)
              parser.parse(new File(tempFile))
            }
            downloadAndParseAttempt.isSuccess
          }

          validSubtitle match {
            case Some(subtitle) =>
              logger.debug(targetFile)
              fileSystem.rename(tempFile, targetFile)
            case None =>
              logger.warn(s"Unable to locate valid subtitle for episode ${episode.episodeNumber}")
          }
        }

        IO.unit
    }.sequence_
  }
}
