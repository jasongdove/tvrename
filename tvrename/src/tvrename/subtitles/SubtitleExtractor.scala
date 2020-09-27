package tvrename.subtitles

import tvrename._
import tvrename.config._
import tvrename.classifier.UnknownRemuxEpisode
import org.ebml.io.FileDataSource
import org.ebml.matroska.{MatroskaFile, MatroskaFileTrack}
import org.ebml.matroska.MatroskaFileTrack.TrackType
import java.io.File

trait SubtitleExtractor {
  def extractFromEpisode(episode: UnknownRemuxEpisode): Option[Subtitles]
}

class SubtitleExtractorImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem, logger: Logger)
    extends SubtitleExtractor {

  private case class SubtitlesTrack(trackNumber: Int, subtitles: Subtitles)

  private object SubtitlesTrack {
    def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[SubtitlesTrack] =
      Subtitles.fromTrack(track, baseFileName).map(SubtitlesTrack(track.getTrackNo - 1, _))
  }

  def extractFromEpisode(episode: UnknownRemuxEpisode): Option[Subtitles] = {
    val folderOne = s"${episode.movieHash.substring(0, 2)}"
    val folderTwo = s"${episode.movieHash.substring(2, 4)}"

    val targetFolder = s"${config.cacheFolder}/extracted/${folderOne}/${folderTwo}"
    fileSystem.makeDirs(targetFolder)

    val cacheFileNameWithoutExtension = s"${targetFolder}/${episode.movieHash}"

    val dataSource = new FileDataSource(episode.fileName)
    val matroska = new MatroskaFile(dataSource)
    matroska.readFile()
    var subtitles = matroska.getTrackList
      .filter(t => t.getTrackType == TrackType.SUBTITLE)
      .filter(t => t.isFlagDefault)
      .flatMap(t => SubtitlesTrack.fromTrack(t, cacheFileNameWithoutExtension))
      .sortBy(st => st.subtitles.priority)
      .headOption

    subtitles match {
      case Some(subtitlesTrack) =>
        if (!subtitlesTrack.subtitles.fileNames.forall(fileSystem.exists)) {
          logger.debug(
            s"\tExtracting track ${subtitlesTrack.trackNumber} of type ${subtitlesTrack.subtitles.getClass.getSimpleName}"
          )
          val tempFileName = fileSystem.getTempFileName.replace(".", "")
          fileSystem.call("mkvextract", episode.fileName, "tracks", s"${subtitlesTrack.trackNumber}:${tempFileName}")
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
