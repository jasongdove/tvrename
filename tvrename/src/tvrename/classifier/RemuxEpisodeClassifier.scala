package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._
import tvrename.subtitles.OpenSubtitlesHasher
import java.io.File
import cats.effect.IO

case class UnknownRemuxEpisode(fileName: String) extends UnknownEpisode {
  lazy val movieHash = OpenSubtitlesHasher.computeHash(new File(fileName))
}

class RemuxEpisodeClassifier(command: Command, jobConfig: RemuxJobConfig, fileSystem: FileSystem)
    extends EpisodeClassifier[UnknownRemuxEpisode](jobConfig, fileSystem) {
  def findUnknownEpisodes(): IO[List[UnknownRemuxEpisode]] = {
    val validExtensions = List(".mkv")
    val knownPattern: Regex = """.*s([0-9]{2})e([0-9]{2})\..*""".r

    def isValid(fileName: String) = validExtensions.exists(fileName.endsWith)
    def isUnknown(fileName: String) = !knownPattern.matches(fileName)

    fileSystem
      .walk(jobConfig.mediaFolder, jobConfig.recursive)
      .map(
        _.filter(isValid)
          .filter(command == Verify || isUnknown(_))
          .map(UnknownRemuxEpisode)
          .sortBy(_.fileName)
          .toList
      )
  }
}
