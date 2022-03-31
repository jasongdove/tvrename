package tvrename.subtitles

import tvrename._
import tvrename.config._
import tvrename.classifier.UnknownRemuxEpisode
import org.ebml.io.FileDataSource
import org.ebml.matroska.{MatroskaFile, MatroskaFileTrack}
import org.ebml.matroska.MatroskaFileTrack.TrackType
import cats.effect.IO
import cats.implicits._
import cats.data.NonEmptyList

case class UnknownSubtitledEpisode(fileName: String, subtitles: Option[Subtitles])

trait SubtitleExtractor {
  def extractFromEpisode(episode: UnknownRemuxEpisode, movieHash: String): IO[UnknownSubtitledEpisode]
}

class SubtitleExtractorImpl(config: TVRenameConfig, fileSystem: FileSystem, logger: Logger) extends SubtitleExtractor {

  private case class SubtitlesTrack(trackNumber: Int, subtitles: Subtitles)

  private object SubtitlesTrack {
    def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[SubtitlesTrack] =
      Subtitles.fromTrack(track, baseFileName).map(SubtitlesTrack(track.getTrackNo - 1, _))
  }

  def extractFromEpisode(episode: UnknownRemuxEpisode, movieHash: String): IO[UnknownSubtitledEpisode] =
    for {
      subtitlesTrack <- identifySubtitlesTrack(episode, movieHash)
      extracted <-
        if (subtitlesTrack.isDefined) extractFromEpisode(episode, subtitlesTrack)
        else generateFromEpisode(episode, movieHash)
    } yield UnknownSubtitledEpisode(episode.fileName, extracted)

  private def identifySubtitlesTrack(episode: UnknownRemuxEpisode, movieHash: String): IO[Option[SubtitlesTrack]] = {
    val folderOne = s"${movieHash.substring(0, 2)}"
    val folderTwo = s"${movieHash.substring(2, 4)}"

    val targetFolder = s"${config.cacheFolder}/extracted/${folderOne}/${folderTwo}"
    for {
      _ <- fileSystem.makeDirs(targetFolder)
    } yield {
      val cacheFileNameWithoutExtension = s"${targetFolder}/$movieHash"

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
  }

  private def extractFromEpisode(
    episode: UnknownRemuxEpisode,
    subtitles: Option[SubtitlesTrack]
  ): IO[Option[Subtitles]] =
    subtitles match {
      case Some(subtitlesTrack) =>
        for {
          allExist <- checkAllExist(subtitlesTrack.subtitles.fileNames)
          extracted <-
            if (allExist)
              IO.pure(Some(subtitlesTrack.subtitles))
            else
              extractAllFromEpisode(episode, subtitlesTrack)
        } yield extracted
      case None => IO.pure(None)
    }

  private def generateFromEpisode(episode: UnknownRemuxEpisode, movieHash: String): IO[Option[Subtitles]] = {
    val folderOne = s"${movieHash.substring(0, 2)}"
    val folderTwo = s"${movieHash.substring(2, 4)}"

    val targetFolder = s"${config.cacheFolder}/extracted/${folderOne}/${folderTwo}"
    val cacheFileNameWithoutExtension = s"${targetFolder}/$movieHash"
    val subRip = SubRip(cacheFileNameWithoutExtension)

    val episodeFileName = fileSystem.getFileName(episode.fileName)

    val dockerIO = fileSystem.call(
      "docker",
      "run",
      "--gpus",
      "all",
      "--user",
      "1000:1000",
      "--rm",
      "-v",
      s"${fileSystem.getParent(episode.fileName)}:/input",
      "-v",
      s"/tmp:/output",
      "autosub",
      "--file",
      s"/input/${episodeFileName}",
      "--format",
      "srt"
    )

    for {
      exists <- fileSystem.exists(subRip.primaryFileName)
      _ <- fileSystem.makeDirs(targetFolder).whenA(!exists)
      _ <- logger.debug("\tGenerating subtitles from audio").whenA(!exists)
      _ <- dockerIO.whenA(!exists)
      _ <- fileSystem.rename(fileSystem.changeExtension(s"/tmp/$episodeFileName", ".srt"), subRip.primaryFileName).whenA(!exists)
      exists <- fileSystem.exists(subRip.primaryFileName)
    } yield if (exists) Some(subRip) else None
  }

  private def checkAllExist(fileNames: NonEmptyList[String]): IO[Boolean] =
    fileNames.foldLeft[IO[Boolean]](IO.pure(true)) { (acc, fileName) =>
      for {
        success <- fileSystem.exists(fileName)
        accSuccess <- acc
      } yield accSuccess && success
    }

  private def extractAllFromEpisode(
    episode: UnknownRemuxEpisode,
    subtitlesTrack: SubtitlesTrack
  ): IO[Option[Subtitles]] = {
    val trackType = subtitlesTrack.subtitles.getClass.getSimpleName
    val tempFileName = fileSystem.getTempFileName().replace(".", "")

    for {
      _ <- logger.debug(s"\tExtracting track ${subtitlesTrack.trackNumber.toString} of type $trackType")
      _ <- fileSystem.call(
        "mkvextract",
        episode.fileName,
        "tracks",
        s"${subtitlesTrack.trackNumber.toString}:${tempFileName}"
      )
      _ <- renameTempFiles(subtitlesTrack, tempFileName)
    } yield Some(subtitlesTrack.subtitles)
  }

  private def renameTempFiles(subtitlesTrack: SubtitlesTrack, tempFileName: String): IO[Unit] = {
    val appendExtension = subtitlesTrack.subtitles.extensions.size > 1
    subtitlesTrack.subtitles.extensions.toList.traverse_ { extension =>
      val sourceFileName = if (appendExtension) s"$tempFileName.$extension" else tempFileName
      fileSystem.rename(sourceFileName, s"${subtitlesTrack.subtitles.baseFileName}.${extension}")
    }
  }
}
