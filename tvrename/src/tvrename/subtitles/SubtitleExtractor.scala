package tvrename.subtitles

import tvrename._
import tvrename.config._
import tvrename.classifier.UnknownRemuxEpisode
import org.ebml.io.FileDataSource
import org.ebml.matroska.{MatroskaFile, MatroskaFileTrack}
import org.ebml.matroska.MatroskaFileTrack.TrackType
import java.io.File
import cats.effect.IO
import cats.implicits._
import os.temp

case class UnknownSubtitledEpisode(fileName: String, subtitles: Option[Subtitles])

trait SubtitleExtractor {
  def extractFromEpisode(episode: UnknownRemuxEpisode): IO[UnknownSubtitledEpisode]
}

class SubtitleExtractorImpl(config: TVRenameConfig, jobConfig: RemuxJobConfig, fileSystem: FileSystem, logger: Logger)
    extends SubtitleExtractor {

  private case class SubtitlesTrack(trackNumber: Int, subtitles: Subtitles)

  private object SubtitlesTrack {
    def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[SubtitlesTrack] =
      Subtitles.fromTrack(track, baseFileName).map(SubtitlesTrack(track.getTrackNo - 1, _))
  }

  def extractFromEpisode(episode: UnknownRemuxEpisode): IO[UnknownSubtitledEpisode] =
    for {
      subtitlesTrack <- identifySubtitlesTrack(episode)
      extracted <- extractFromEpisode(episode, subtitlesTrack)
    } yield UnknownSubtitledEpisode(episode.fileName, extracted)

  private def identifySubtitlesTrack(episode: UnknownRemuxEpisode): IO[Option[SubtitlesTrack]] =
    IO {
      val folderOne = s"${episode.movieHash.substring(0, 2)}"
      val folderTwo = s"${episode.movieHash.substring(2, 4)}"

      val targetFolder = s"${config.cacheFolder}/extracted/${folderOne}/${folderTwo}"
      fileSystem.makeDirs(targetFolder)

      val cacheFileNameWithoutExtension = s"${targetFolder}/${episode.movieHash}"

      val dataSource = new FileDataSource(episode.fileName)
      val matroska = new MatroskaFile(dataSource)
      matroska.readFile()
      matroska.getTrackList
        .filter(t => t.getTrackType == TrackType.SUBTITLE)
        .filter(t => t.isFlagDefault)
        .flatMap(t => SubtitlesTrack.fromTrack(t, cacheFileNameWithoutExtension))
        .sortBy(st => st.subtitles.priority)
        .headOption
    }

  private def extractFromEpisode(
    episode: UnknownRemuxEpisode,
    subtitles: Option[SubtitlesTrack]
  ): IO[Option[Subtitles]] =
    subtitles match {
      case Some(subtitlesTrack) =>
        if (!subtitlesTrack.subtitles.fileNames.forall(fileSystem.exists)) {
          val trackType = subtitlesTrack.subtitles.getClass.getSimpleName
          val tempFileName = fileSystem.getTempFileName.replace(".", "")

          for {
            _ <- logger.debug(s"\tExtracting track ${subtitlesTrack.trackNumber} of type $trackType")
            _ <- IO(
              fileSystem.call(
                "mkvextract",
                episode.fileName,
                "tracks",
                s"${subtitlesTrack.trackNumber}:${tempFileName}"
              )
            )
            _ <- renameTempFiles(subtitlesTrack, tempFileName)
          } yield Some(subtitlesTrack.subtitles)
        } else {
          IO(Some(subtitlesTrack.subtitles))
        }
      case None => IO(None)
    }

  private def renameTempFiles(subtitlesTrack: SubtitlesTrack, tempFileName: String): IO[Unit] = {
    val appendExtension = subtitlesTrack.subtitles.extensions.size > 1
    subtitlesTrack.subtitles.extensions.toList.traverse_ { extension =>
      val sourceFileName = if (appendExtension) s"$tempFileName.$extension" else tempFileName
      fileSystem.rename(sourceFileName, s"${subtitlesTrack.subtitles.baseFileName}.${extension}")
    }
  }
}
