package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._

case class UnknownBroadcastEpisode(fileName: String, date: LocalDate) extends UnknownEpisode

class BroadcastEpisodeClassifier(config: BroadcastJobConfig, fileSystem: FileSystem)
    extends EpisodeClassifier[UnknownBroadcastEpisode](config, fileSystem) {
  def findUnknownEpisodes(): Seq[UnknownBroadcastEpisode] = {
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
      .map(f => UnknownBroadcastEpisode(f, dateBroadcasted(f)))
  }
}
