package tvrename.subtitles

import tvrename.FileSystem
import tvrename.config.TVRenameConfig

trait SubtitleProcessor {
  def convertToText(subtitles: Subtitles): String
  def cleanText(text: String): String
}

class ExternalSubtitleProcessor(config: TVRenameConfig, fileSystem: FileSystem) extends SubtitleProcessor {
  def convertToText(subtitles: Subtitles): String = {
    subtitles match {
      case pgs @ PGS(_) =>
        val subRip = ocr(pgs)
        fileSystem.read(subRip.primaryFileName)
      case vob @ VobSub(_) =>
        val subRip = ocr(vob)
        fileSystem.read(subRip.primaryFileName)
      case srt @ SubRip(_) =>
        fileSystem.read(srt.primaryFileName)
    }
  }

  def cleanText(text: String): String = text

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
