package tvrename.subtitles

import upickle.default._
import tvrename._
import tvrename.config._
import java.io.File
import scala.util.Try
import com.github.dnbn.submerge.api.parser.SRTParser

trait ReferenceSubtitleDownloader {
  def downloadIfNeeded(): Unit
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
  )

  case object SearchResult {
    implicit val reader = macroR[SearchResult]
  }

  private val targetFolder =
    f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"

    private val episodeCountFile = s"${targetFolder}/.episode-count"

  override def downloadIfNeeded(): Unit = {
    if (subtitlesAreNeeded()) {
      val seasonInt = jobConfig.seasonNumber.value

      val r = requests.get(
        s"https://rest.opensubtitles.org/search/imdbid-${jobConfig.seriesId.value}/season-${seasonInt}/sublanguageid-eng",
        headers = Map("user-agent" -> "tvrename v1")
      )
      val seasonSearchResults = upickle.default.read[List[SearchResult]](r.text)
      val lastEpisode = seasonSearchResults.map(_.SeriesEpisode.toInt).max
      // TODO: handle no episodes found for series/season
      logger.debug(s"${jobConfig.seriesName} Season ${seasonInt} has ${lastEpisode} episodes")

      fileSystem.makeDirs(targetFolder)
      fileSystem.writeToFile(episodeCountFile, lastEpisode.toString)

      val parser = new SRTParser

      Range(1, lastEpisode + 1).foreach { episodeNumber =>
        val episodeSearchResults = seasonSearchResults
          .filterNot(_.SubFileName.toLowerCase.contains(".ita.")) // sometimes the wrong language is returned ???
          .filter(_.SubFormat.toLowerCase == "srt")
          .filter(_.SeriesEpisode.toInt == episodeNumber)
          .sortBy(s => Option(s.InfoFormat).getOrElse("").toLowerCase == "web-dl")
          .sortBy(_.Score)(Ordering[Double].reverse)
        (episodeNumber, episodeSearchResults)

        val tempFile = fileSystem.getTempFileName()

        val template = jobConfig.template
          .replace("[series]", jobConfig.seriesName)
          .replace("[season]", f"${seasonInt}%02d")
          .replace("[episode]", f"${episodeNumber}%02d")

        val targetFile = f"${targetFolder}/${template}.srt"

        if (!fileSystem.exists(targetFile)) {
          val validSubtitle = episodeSearchResults.find { subtitle =>
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
  }

  private def subtitlesAreNeeded(): Boolean = {
    if (fileSystem.exists(episodeCountFile)) {
      val episodeCount = fileSystem.readLines(episodeCountFile).headOption.map(_.toInt)
      val episodesOnDisk = fileSystem
        .walk(targetFolder)
        .filter(f => f.endsWith(".srt"))
        .size

      episodeCount.exists(_ != episodesOnDisk)
    } else {
      true
    }
  }
}
