package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._

class BroadcastEpisodeClassifier(config: BroadcastJobConfig, fileSystem: FileSystem) extends EpisodeClassifier {
  def findUnknownEpisodes(): Seq[UnknownEpisode] = {
    val validExtensions = List(".mkv", ".ts")
    val knownPattern: Regex = """.*s([0-9]{2})e([0-9]{3})\..*""".r

    def isValid(fileName: String) = validExtensions.exists(fileName.endsWith)
    def isUnknown(fileName: String) = !knownPattern.matches(fileName)

    // TODO: use DATE_BROADCASTED tag (mkv), or General;Duration_Start (ts)
    def dateBroadcasted(fileName: String): LocalDate =
      Instant
        .ofEpochMilli(
          fileSystem.getModifyTime(fileName) - 7200000
        ) // offset by two hours until we read metadata
        .atZone(ZoneId.systemDefault)
        .toLocalDate

    fileSystem
      .walk(config.mediaFolder)
      .filter(f => isValid(f) && isUnknown(f))
      .map(f => UnknownEpisode(f, dateBroadcasted(f)))
  }

  def renameEpisode(
    episode: UnknownEpisode,
    seasonNumber: SeasonNumber,
    episodeNumber: Int
  ): (String, String) = {
    val sourceFile = episode.fileName

    val formattedSeason = f"${seasonNumber.value}%02d"
    val formattedEpisode = f"${episodeNumber}%02d"
    val insertIndex = sourceFile.lastIndexOf('.')
    val ext = sourceFile.substring(insertIndex)
    val titleSeasonAndEpisode = config.template
      .replace("[season]", formattedSeason)
      .replace("[episode]", formattedEpisode)

    val newFileName = titleSeasonAndEpisode + ext
    val targetFile = fileSystem.absoluteToRelative(newFileName, sourceFile)

    fileSystem.rename(sourceFile, targetFile)

    (sourceFile, targetFile)
  }
}
