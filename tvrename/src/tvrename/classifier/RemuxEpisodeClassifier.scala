package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._
import tvrename.subtitles.OpenSubtitlesHasher
import java.io.File

case class UnknownRemuxEpisode(fileName: String) extends UnknownEpisode {
  lazy val movieHash = OpenSubtitlesHasher.computeHash(new File(fileName))
}

class RemuxEpisodeClassifier(jobConfig: RemuxJobConfig, fileSystem: FileSystem)
    extends EpisodeClassifier[UnknownRemuxEpisode](jobConfig, fileSystem) {
  def findUnknownEpisodes(): Seq[UnknownRemuxEpisode] = {
    val validExtensions = List(".mkv")
    val knownPattern: Regex = """.*s([0-9]{2})e([0-9]{2})\..*""".r

    def isValid(fileName: String) = validExtensions.exists(fileName.endsWith)
    def isUnknown(fileName: String) = !knownPattern.matches(fileName)

    fileSystem
      .walk(jobConfig.mediaFolder, jobConfig.recursive)
      .filter(f => isValid(f) && isUnknown(f))
      .map(f => UnknownRemuxEpisode(f))
  }
}
