package tvrename.subtitles

import tvrename._
import tvrename.config._
import org.ebml.io.FileDataSource
import org.ebml.matroska.{MatroskaFile, MatroskaFileTrack}
import org.ebml.matroska.MatroskaFileTrack.TrackType
import java.io.File

trait SubtitleExtractor {
  def extractFromFile(fileName: String): Option[Subtitles]
}

class SubtitleExtractorImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem, logger: Logger)
    extends SubtitleExtractor {

  private case class SubtitlesTrack(trackNumber: Int, subtitles: Subtitles)

  private object SubtitlesTrack {
    def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[SubtitlesTrack] =
      Subtitles.fromTrack(track, baseFileName).map(SubtitlesTrack(track.getTrackNo - 1, _))
  }

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
      .flatMap(t => SubtitlesTrack.fromTrack(t, cacheFileNameWithoutExtension))
      .sortBy(st => st.subtitles.priority)
      .headOption

    subtitles match {
      case Some(subtitlesTrack) =>
        if (!subtitlesTrack.subtitles.fileNames.forall(fileSystem.exists)) {
          logger.debug(
            s"Extracting track ${subtitlesTrack.trackNumber} of type ${subtitlesTrack.subtitles.getClass.getSimpleName}"
          )
          val tempFileName = fileSystem.getTempFileName.replace(".", "")
          fileSystem.call("mkvextract", fileName, "tracks", s"${subtitlesTrack.trackNumber}:${tempFileName}")
          val appendExtension = subtitlesTrack.subtitles.extensions.size > 1
          subtitlesTrack.subtitles.extensions.foreach { extension =>
            val sourceFileName = if (appendExtension) s"${tempFileName}.${extension}" else tempFileName
            fileSystem.rename(sourceFileName, s"${cacheFileNameWithoutExtension}.${extension}")
          }
        }
        Some(subtitlesTrack.subtitles)
      case None => None
    }
  }
}
