package tvrename.classifier

import scala.util.matching.Regex
import java.time.{Instant, LocalDate, ZoneId}

import tvrename._
import tvrename.config._
import cats.effect.IO

case class UnknownBroadcastEpisode(fileName: String, date: LocalDate) extends UnknownEpisode

class BroadcastEpisodeClassifier(jobConfig: BroadcastJobConfig, fileSystem: FileSystem)
    extends EpisodeClassifier[UnknownBroadcastEpisode](jobConfig, fileSystem) {
  def findUnknownEpisodes(): IO[Seq[UnknownBroadcastEpisode]] =
    IO {
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
        .walk(jobConfig.mediaFolder, jobConfig.recursive)
        .filter(isValid)
        .filter(isUnknown)
        .map(f => UnknownBroadcastEpisode(f, dateBroadcasted(f)))
    }
}
