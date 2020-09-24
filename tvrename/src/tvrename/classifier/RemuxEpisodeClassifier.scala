package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._

case class UnknownRemuxEpisode(fileName: String) extends UnknownEpisode

class RemuxEpisodeClassifier(config: RemuxJobConfig, fileSystem: FileSystem)
    extends EpisodeClassifier[UnknownRemuxEpisode](config, fileSystem) {
  def findUnknownEpisodes(): Seq[UnknownRemuxEpisode] = {
    val validExtensions = List(".mkv")
    val knownPattern: Regex = """.*s([0-9]{2})e([0-9]{3})\..*""".r

    def isValid(fileName: String) = validExtensions.exists(fileName.endsWith)
    def isUnknown(fileName: String) = !knownPattern.matches(fileName)

    fileSystem
      .walk(config.mediaFolder)
      .filter(f => isValid(f) && isUnknown(f))
      .map(f => UnknownRemuxEpisode(f))
  }
}
