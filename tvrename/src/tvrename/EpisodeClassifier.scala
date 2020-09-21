package tvrename

import java.time.{Instant, LocalDate, ZoneId}

import scala.util.matching.Regex

trait EpisodeClassifier {
  def findUnknownEpisodes(): Seq[UnknownEpisode]
  def renameEpisode(episode: UnknownEpisode, seasonNumber: SeasonNumber, episodeNumber: Int): (String, String)
}

class EpisodeClassifierImpl(config: TVRenameConfig, fileSystem: FileSystem) extends EpisodeClassifier {
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
      .walk(config.targetFolder)
      .filter(f => isValid(f) && isUnknown(f))
      .map(f => UnknownEpisode(f, dateBroadcasted(f)))
  }

  def renameEpisode(
    episode: UnknownEpisode,
    seasonNumber: SeasonNumber,
    episodeNumber: Int
  ): (String, String) = {
    val sourceFile = episode.fileName

    val insertIndex = sourceFile.lastIndexOf('.')
    val title = sourceFile.slice(0, insertIndex)
    val seasonAndEpisode = f" - s${seasonNumber.value}%02de$episodeNumber%03d"
    val ext = sourceFile.substring(insertIndex)

    val targetFile = title + seasonAndEpisode + ext

    fileSystem.rename(sourceFile, targetFile)

    (sourceFile, targetFile)
  }
}

case class UnknownEpisode(fileName: String, date: LocalDate)
