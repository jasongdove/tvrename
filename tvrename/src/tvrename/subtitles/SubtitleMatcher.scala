package tvrename.subtitles

import tvrename._
import tvrename.config._
import com.github.dnbn.submerge.api.parser.SRTParser
import scala.util.Try
import java.io.File
import scala.util.Success
import scala.jdk.CollectionConverters._
import cats.effect.IO
import cats.implicits._
import scala.util.matching.Regex.Match

case class EpisodeMatch(seasonNumber: Int, episodeNumber: Int, confidence: Int)
case class MatchedSubtitledEpisode(fileName: String, seasonNumber: Int, episodeNumber: Int, confidence: Int)

trait SubtitleMatcher {
  def matchEpisode(episode: UnknownProcessedSubtitledEpisode): IO[Option[MatchedSubtitledEpisode]]
}

case class ReferenceSubtitle(fileName: String, contents: String)
case class ReferenceSubtitleMatch(fileName: String, confidence: Int)

class SubtitleMatcherImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem)
    extends SubtitleMatcher {
  lazy val referenceSubtitles: IO[collection.mutable.Set[ReferenceSubtitle]] = {
    val parser = new SRTParser
    val targetFolder =
      f"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${jobConfig.seasonNumber.value}%02d"
    fileSystem
      .walk(targetFolder)
      .map { files =>
        val result = files.filter(_.endsWith(".srt")).map { fileName =>
          val parseAttempt = Try { parser.parse(new File(fileName)) }
          val lines = parseAttempt match {
            case Success(parsedSubtitles) =>
              parsedSubtitles.getLines.asScala.toList.flatMap(_.getTextLines.asScala.toList)
            case _ => List("")
          }
          val whitespace = "[\\s]*".r
          val contents = lines
            .map(s => s.replace("-", "").toLowerCase)
            .filter {
              case whitespace() => false
              case _            => true
            }
            .mkString(" ")
          ReferenceSubtitle(fileName, contents)
        }
        collection.mutable.Set.from(result)
      }
  }

  def matchEpisode(episode: UnknownProcessedSubtitledEpisode): IO[Option[MatchedSubtitledEpisode]] =
    for {
      episodeMatch <- matchToReference(episode.lines)
    } yield {
      episodeMatch match {
        case Some(em) =>
          Some(MatchedSubtitledEpisode(episode.fileName, em.seasonNumber, em.episodeNumber, em.confidence))
        case None => None
      }
    }

  private def matchToReference(episodeLines: List[String]): IO[Option[EpisodeMatch]] = {
    for {
      subtitles <- referenceSubtitles
      topMatch <- IO(findTopMatch(episodeLines, subtitles))
    } yield {
      val pattern = ".*s([\\d]{2})e([\\d]{2}).*".r
      topMatch.map { m =>
        m.fileName match {
          case pattern(season, episode) =>
            if (m.confidence > jobConfig.minimumConfidence)
              subtitles.find(_.fileName == m.fileName).map(subtitles.-=)
            EpisodeMatch(season.toInt, episode.toInt, m.confidence)
        }
      }
    }
  }

  private def findTopMatch(
    episodeLines: List[String],
    subtitles: collection.mutable.Set[ReferenceSubtitle]
  ): Option[ReferenceSubtitleMatch] =
    subtitles
      .map {
        case ReferenceSubtitle(fileName, contents) =>
          val count = episodeLines.count(line => contents.contains(line))
          val confidence = (count.toDouble / episodeLines.size * 100).toInt
          ReferenceSubtitleMatch(fileName, confidence)
      }
      .toList
      .sortWith(_.confidence > _.confidence)
      .headOption
}
