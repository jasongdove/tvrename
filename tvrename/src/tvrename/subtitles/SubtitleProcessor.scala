package tvrename.subtitles

import tvrename.FileSystem
import tvrename.config.TVRenameConfig

trait SubtitleProcessor {
  def convertToLines(subtitles: Subtitles): Seq[String]
  def cleanLines(lines: Seq[String]): Seq[String]
}

class ExternalSubtitleProcessor(config: TVRenameConfig, fileSystem: FileSystem) extends SubtitleProcessor {
  def convertToLines(subtitles: Subtitles): Seq[String] = {
    subtitles match {
      case pgs @ PGS(_) =>
        val subRip = ocr(pgs)
        fileSystem.readLines(subRip.primaryFileName)
      case vob @ VobSub(_) =>
        val subRip = ocr(vob)
        fileSystem.readLines(subRip.primaryFileName)
      case subRip @ SubRip(_) =>
        fileSystem.readLines(subRip.primaryFileName)
    }
  }

  def cleanLines(lines: Seq[String]): Seq[String] = {
    val numericLine = "[\\d].*".r
    val whitespace = "[\\s]*".r
    val badApostrophe = "(?<=\\w)\\s*['’](?=\\w)".r
    val namesAndDashes = "-[\\s\\w\\d#]*[:\\s]*".r
    val sdh = "[\\(\\[].*[\\)\\]]".r
    val lyrics = ".*J[‘']\\s.*".r
    lines
      .map(s => s.toLowerCase)
      .map(s => s.replace("||", "ll"))
      .map(s => s.trim())
      .map(s => badApostrophe.replaceAllIn(s, "'"))
      .map(s => namesAndDashes.replaceAllIn(s, ""))
      .filter {
        case numericLine() => false
        case whitespace()  => false
        case sdh()         => false
        case lyrics()      => false
        case _             => true
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
}
