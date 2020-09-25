package tvrename.subtitles

import upickle.default._
import tvrename._
import tvrename.config._

trait ReferenceSubtitleDownloader {
  def download(): Unit
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

  override def download(): Unit = {
    val seasonInt = jobConfig.seasonNumber.value

    val r = requests.get(
      s"https://rest.opensubtitles.org/search/imdbid-${jobConfig.seriesId.value}/season-${seasonInt}/sublanguageid-eng",
      headers = Map("user-agent" -> "TemporaryUserAgent")
    )
    val allResults = upickle.default.read[List[SearchResult]](r.text)
    val lastEpisode = allResults.map(_.SeriesEpisode.toInt).max
    logger.debug(s"${jobConfig.seriesName} Season ${seasonInt} has ${lastEpisode} episodes")

    val bestSubtitles = Range(1, lastEpisode + 1).flatMap { episodeNumber =>
      allResults
        .filterNot(_.SubFileName.toLowerCase.contains(".ita.")) // sometimes the wrong language is returned ???
        .filter(_.SubFormat.toLowerCase == "srt")
        .filter(_.SeriesEpisode.toInt == episodeNumber)
        .sortBy(s => Option(s.InfoFormat).getOrElse("").toLowerCase == "web-dl")
        .sortBy(_.Score)(Ordering[Double].reverse)
        .headOption
    }

    val targetFolder = f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${seasonInt}%02d"
    fileSystem.makeDirs(targetFolder)

    bestSubtitles.foreach { subtitle =>
      val template = jobConfig.template
        .replace("[series]", jobConfig.seriesName)
        .replace("[season]", f"${seasonInt}%02d")
        .replace("[episode]", f"${subtitle.SeriesEpisode.toInt}%02d")
      val targetFile = f"${targetFolder}/${template}.srt"
      if (!fileSystem.exists(targetFile)) {
        logger.debug(s"\t${subtitle.SubDownloadLink}")
        val stream = requests.get.stream(subtitle.SubDownloadLink)
        fileSystem.streamCommandToFile(stream, "gunzip", targetFile)
      }
    }
  }
}
