package tvrename.subtitles

import java.io.File

import cats.effect.IO
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

  private val targetFolder =
    f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"

  private val episodeCountFile = s"${targetFolder}/.episode-count"

  private def expectedEpisodeCount: Option[Int] =
    if (fileSystem.exists(episodeCountFile)) fileSystem.readLines(episodeCountFile).headOption.map(_.toInt) else None

  private def actualEpisodeCount: Int = fileSystem.walk(targetFolder).count(f => f.endsWith(".srt"))

  private def search(): Map[Int, List[SearchResult]] = {
    val r = requests.get(
      s"https://rest.opensubtitles.org/search/imdbid-${jobConfig.seriesId.value}/season-${jobConfig.seasonNumber.value}/sublanguageid-eng",
      headers = Map("user-agent" -> "tvrename v1")
    )

    upickle.default
      .read[List[SearchResult]](r.text)
      .filterNot(_.SubFileName.toLowerCase.contains(".ita.")) // sometimes the wrong language is returned ???
      .filter(_.SubFormat.toLowerCase == "srt")
      .groupBy(_.SeriesEpisode.toInt)
  }

  override def download(): IO[Unit] = {
    fileSystem.makeDirs(targetFolder)

    if (!expectedEpisodeCount.contains(actualEpisodeCount)) {
      val seasonInt = jobConfig.seasonNumber.value

      val seasonSearchResults = search()
      // TODO: handle no episodes found for series/season
      val lastEpisode = seasonSearchResults.keys.max
      logger.debug(s"${jobConfig.seriesName} Season ${seasonInt} has ${lastEpisode} episodes")

      fileSystem.writeToFile(episodeCountFile, lastEpisode.toString)

      val parser = new SRTParser

      seasonSearchResults.foreach {
        case (episodeNumber, searchResults) =>
          val sortedSearchResults = searchResults
            .sortBy(_.isWebDL)(Ordering[Boolean].reverse)
            .sortBy(_.Score)(Ordering[Double].reverse)

          val template = jobConfig.template
            .replace("[series]", jobConfig.seriesName)
            .replace("[season]", f"${seasonInt}%02d")
            .replace("[episode]", f"${episodeNumber}%02d")

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
                logger.warn(s"Unable to locate valid subtitle for episode ${episodeNumber}")
            }
          }
      }
    }

    IO.unit
  }
}
