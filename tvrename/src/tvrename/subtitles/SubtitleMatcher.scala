package tvrename.subtitles

import tvrename._
import tvrename.config._
import com.github.dnbn.submerge.api.parser.SRTParser
import scala.util.Try
import java.io.File
import scala.util.Success
import scala.jdk.CollectionConverters._

case class EpisodeMatch(seasonNumber: Int, episodeNumber: Int, confidence: Int)

trait SubtitleMatcher {
  def matchToReference(episodeLines: List[String]): Option[EpisodeMatch]
}

class SubtitleMatcherImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem)
    extends SubtitleMatcher {
  lazy val referenceSubtitles: Map[String, String] = {
    val parser = new SRTParser
    val targetFolder =
      f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"
    fileSystem
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
  }

  override def matchToReference(episodeLines: List[String]): Option[EpisodeMatch] = {
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
      case pattern(season, episode) => Some(EpisodeMatch(season.toInt, episode.toInt, confidence))
      case _                        => None
    }
  }
}
