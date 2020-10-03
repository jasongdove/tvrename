package tvrename.subtitles

import tvrename.FileSystem
import tvrename.config.TVRenameConfig
import com.github.dnbn.submerge.api.parser.SRTParser
import java.io.File
import collection.JavaConverters._
import cats.effect.IO
import cats.implicits._
import cats.syntax._

case class UnknownProcessedSubtitledEpisode(fileName: String, lines: List[String])

trait SubtitleProcessor {
  def processEpisodes(episodes: List[UnknownSubtitledEpisode]): IO[List[UnknownProcessedSubtitledEpisode]]
}

class ExternalSubtitleProcessor(config: TVRenameConfig, fileSystem: FileSystem) extends SubtitleProcessor {
  val subripParser = new SRTParser

  def processEpisodes(episodes: List[UnknownSubtitledEpisode]): IO[List[UnknownProcessedSubtitledEpisode]] =
    episodes.traverse { episode =>
      episode.subtitles match {
        case Some(subtitles) =>
          for {
            lines <- convertToLines(subtitles)
            cleanedLines = cleanLines(lines)
          } yield UnknownProcessedSubtitledEpisode(episode.fileName, cleanedLines)
        case None => IO(UnknownProcessedSubtitledEpisode(episode.fileName, List.empty))
      }
    }

  private def convertToLines(subtitles: Subtitles): IO[List[String]] = {
    subtitles match {
      case pgs @ PGS(_) =>
        ocr(pgs).flatMap(subRip => readLinesFromFile(subRip.primaryFileName))
      case vob @ VobSub(_) =>
        ocr(vob).flatMap(subRip => readLinesFromFile(subRip.primaryFileName))
      case subRip @ SubRip(_) =>
        readLinesFromFile(subRip.primaryFileName)
    }
  }

  private def cleanLines(lines: List[String]): List[String] = {
    val whitespace = "[\\s]*".r
    val badApostrophe = "(?<=\\w)\\s*['’](?=\\w)".r
    val namesAndDashes = "-[\\s\\w\\d#]*[:\\s]*".r
    val sdh = "[\\(\\[].*[\\)\\]]".r
    val lyrics = ".*J[‘']\\s.*".r
    val italicTags = "<[\\/]*i>".r
    lines
      .map(s => s.toLowerCase)
      .map(s => s.replace("||", "ll"))
      .map(s => s.replace("♪", ""))
      .map(s => badApostrophe.replaceAllIn(s, "'"))
      .map(s => namesAndDashes.replaceAllIn(s, ""))
      .map(s => italicTags.replaceAllIn(s, ""))
      .map(s => s.trim())
      .filter {
        case whitespace() => false
        case sdh()        => false
        case lyrics()     => false
        case _            => true
      }
  }

  private def ocr(subtitles: PGS): IO[SubRip] =
    IO {
      val subRip = SubRip(subtitles.baseFileName)
      if (!fileSystem.exists(subRip.primaryFileName)) {
        fileSystem.call(
          "docker",
          "run",
          "--user",
          "1000:1000",
          "--rm",
          "-v",
          s"${config.cacheFolder}:/data",
          "-e",
          s"INPUT=/data/${fileSystem.relativeTo(subtitles.primaryFileName, config.cacheFolder)}",
          "-e",
          s"OUTPUT=/data/${fileSystem.relativeTo(subRip.primaryFileName, config.cacheFolder)}",
          "-e",
          "LANGUAGE=eng",
          "local/pgstosrt"
        )
      }
      subRip
    }

  private def ocr(subtitles: VobSub): IO[SubRip] =
    IO {
      val subRip = SubRip(subtitles.baseFileName)
      if (!fileSystem.exists(subRip.primaryFileName)) {
        fileSystem.call("vobsub2srt", "-l", "en", subtitles.baseFileName)
      }
      subRip
    }

  private def readLinesFromFile(fileName: String): IO[List[String]] =
    IO {
      val subtitles = subripParser.parse(new File(fileName))
      subtitles.getLines.asScala.toList.flatMap(_.getTextLines.asScala.toList)
    }
}
