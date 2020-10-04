package tvrename.subtitles

import tvrename._
import tvrename.config._
import com.github.dnbn.submerge.api.parser.SRTParser
import scala.util.Try
import java.io.File
import scala.util.Success
import scala.jdk.CollectionConverters._
import cats.effect.IO
import scala.util.matching.Regex.Match

case class EpisodeMatch(seasonNumber: Int, episodeNumber: Int, confidence: Int)
case class MatchedSubtitledEpisode(fileName: String, seasonNumber: Int, episodeNumber: Int, confidence: Int)

trait SubtitleMatcher {
  def matchEpisode(episode: UnknownProcessedSubtitledEpisode): Option[MatchedSubtitledEpisode]
}

class SubtitleMatcherImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem)
    extends SubtitleMatcher {
  lazy val referenceSubtitles: collection.mutable.Map[String, String] = {
    val parser = new SRTParser
    val targetFolder =
      f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"
    val map = fileSystem
      .walk(targetFolder)
      .filter(_.endsWith(".srt"))
      .map { file =>
        val parseAttempt = Try { parser.parse(new File(file)) }
        val lines = parseAttempt match {
          case Success(parsedSubtitles) =>
            parsedSubtitles.getLines.asScala.toList.flatMap(_.getTextLines.asScala.toList)
          case _ => List("")
        }
        val whitespace = "[\\s]*".r
        val output = lines
          .map(s => s.replace("-", "").toLowerCase)
          .filter {
            case whitespace() => false
            case _            => true
          }
          .mkString(" ")
        (file, output)
      }
      .toMap
    collection.mutable.Map(map.toSeq: _*)
  }

  def matchEpisode(episode: UnknownProcessedSubtitledEpisode): Option[MatchedSubtitledEpisode] =
    matchToReference(episode.lines).map(em =>
      MatchedSubtitledEpisode(episode.fileName, em.seasonNumber, em.episodeNumber, em.confidence)
    )

  private def matchToReference(episodeLines: List[String]): Option[EpisodeMatch] = {
    val rankedMatches = referenceSubtitles
      .map {
        case (key, value) => {
          val count = episodeLines.count(line => value.contains(line))
          (count, key)
        }
      }
      .toSeq
      .sortWith(_._1 > _._1)

    val topMatch = rankedMatches.head
    val confidence = (topMatch._1.toDouble / episodeLines.size * 100).toInt
    val pattern = ".*s([\\d]{2})e([\\d]{2}).*".r
    topMatch._2 match {
      case pattern(season, episode) =>
        if (confidence > jobConfig.minimumConfidence) referenceSubtitles -= topMatch._2
        Some(EpisodeMatch(season.toInt, episode.toInt, confidence))
      case _ =>
        None
    }
  }
}
