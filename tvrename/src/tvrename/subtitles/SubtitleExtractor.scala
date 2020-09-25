package tvrename.subtitles

import tvrename._
import tvrename.config._
import org.ebml.io.FileDataSource
import org.ebml.matroska.{MatroskaFile, MatroskaFileTrack}
import org.ebml.matroska.MatroskaFileTrack.TrackType
import java.io.File

sealed trait Subtitles {
  def baseFileName: String
  def trackNumber: Int
  def extensions: Set[String]
  def priority: Int

  def fileNames: Set[String] = extensions.map(e => s"${baseFileName}.${e}")
}

object Subtitles {
  def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[Subtitles] =
    track.getCodecID match {
      case "S_VOBSUB"    => Some(VobSub(track.getTrackNo - 1, baseFileName))
      case "S_HDMV/PGS"  => Some(PGS(track.getTrackNo - 1, baseFileName))
      case "S_TEXT/UTF8" => Some(SubRip(track.getTrackNo - 1, baseFileName))
      case s @ _ =>
        println(s"Unexpected subtitle codec ${s}")
        None
    }
}

case class SubRip(trackNumber: Int, baseFileName: String) extends Subtitles {
  def extensions = Set("srt")
  def priority = 1
}

case class VobSub(trackNumber: Int, baseFileName: String) extends Subtitles {
  def extensions = Set("sub", "idx")
  def priority = 3
}

case class PGS(trackNumber: Int, baseFileName: String) extends Subtitles {
  def extensions = Set("sup")
  def priority = 2
}

trait SubtitleExtractor {
  def extractFromFile(fileName: String): Option[Subtitles]
}

class SubtitleExtractorImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem, logger: Logger)
    extends SubtitleExtractor {
  def extractFromFile(fileName: String): Option[Subtitles] = {
    val movieHash = OpenSubtitlesHasher.computeHash(new File(fileName))
    val folderOne = s"${movieHash.substring(0, 2)}"
    val folderTwo = s"${movieHash.substring(2, 4)}"

    val targetFolder = s"${config.cacheFolder}/extracted/${folderOne}/${folderTwo}"
    fileSystem.makeDirs(targetFolder)

    val cacheFileNameWithoutExtension = s"${targetFolder}/${movieHash}"

    val dataSource = new FileDataSource(fileName)
    val matroska = new MatroskaFile(dataSource)
    matroska.readFile()
    var subtitles = matroska.getTrackList
      .filter(t => t.getTrackType == TrackType.SUBTITLE)
      .flatMap(t => Subtitles.fromTrack(t, cacheFileNameWithoutExtension))
      .sortBy(s => s.priority)
      .headOption

    subtitles match {
      case Some(subtitles) =>
        if (!subtitles.fileNames.forall(fileSystem.exists)) {
          logger.debug(
            s"Extracting track ${subtitles.trackNumber} of type ${subtitles.getClass.getSimpleName}"
          )
          val tempFileName = fileSystem.getTempFileName.replace(".", "")
          fileSystem.call("mkvextract", fileName, "tracks", s"${subtitles.trackNumber}:${tempFileName}")
          val appendExtension = subtitles.extensions.size > 1
          subtitles.extensions.foreach { extension =>
            val sourceFileName = if (appendExtension) s"${tempFileName}.${extension}" else tempFileName
            fileSystem.rename(sourceFileName, s"${cacheFileNameWithoutExtension}.${extension}")
          }
        }
        Some(subtitles)
      case None => None
    }
  }
}
