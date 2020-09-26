package tvrename.subtitles

import tvrename.FileSystem
import tvrename.config.TVRenameConfig
import com.github.dnbn.submerge.api.parser.SRTParser
import java.io.File
import collection.JavaConverters._

trait SubtitleProcessor {
  def convertToLines(subtitles: Subtitles): List[String]
  def cleanLines(lines: List[String]): List[String]
}

class ExternalSubtitleProcessor(config: TVRenameConfig, fileSystem: FileSystem) extends SubtitleProcessor {
  val subripParser = new SRTParser

  def convertToLines(subtitles: Subtitles): List[String] = {
    subtitles match {
      case pgs @ PGS(_) =>
        val subRip = ocr(pgs)
        readLinesFromFile(subRip.primaryFileName)
      case vob @ VobSub(_) =>
        val subRip = ocr(vob)
        readLinesFromFile(subRip.primaryFileName)
      case subRip @ SubRip(_) =>
        readLinesFromFile(subRip.primaryFileName)
    }
  }

  def cleanLines(lines: List[String]): List[String] = {
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

  private def ocr(subtitles: PGS): SubRip = {
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
        "tentacule/pgstosrt"
      )
    }
    subRip
  }

  private def ocr(subtitles: VobSub): SubRip = {
    val subRip = SubRip(subtitles.baseFileName)
    if (!fileSystem.exists(subRip.primaryFileName)) {
      fileSystem.call("vobsub2srt", "-l", "en", subtitles.baseFileName)
    }
    subRip
  }

  private def readLinesFromFile(fileName: String): List[String] = {
    val subtitles = subripParser.parse(new File(fileName))
    subtitles.getLines.asScala.toList.flatMap(_.getTextLines.asScala.toList)
  }
}
