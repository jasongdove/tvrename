package tvrename.classifier

import tvrename._
import tvrename.config._
import cats.effect.IO

trait UnknownEpisode { def fileName: String }

abstract case class EpisodeClassifier[A <: UnknownEpisode](jobConfig: JobConfig, fileSystem: FileSystem) {
  def findUnknownEpisodes(): IO[List[A]]

  def renameEpisode(
    episode: A,
    seasonNumber: SeasonNumber,
    episodeNumber: Int
  ): IO[(String, String)] = {
    val sourceFile = episode.fileName

    val formattedSeason = f"${seasonNumber.value}%02d"
    val formattedEpisode = f"${episodeNumber}%02d"
    val insertIndex = sourceFile.lastIndexOf('.')
    val ext = sourceFile.substring(insertIndex)
    val titleSeasonAndEpisode = jobConfig.template
      .replace("[season]", formattedSeason)
      .replace("[episode]", formattedEpisode)

    val newFileName = titleSeasonAndEpisode + ext
    val targetFile = fileSystem.absoluteToRelative(newFileName, sourceFile)

    fileSystem
      .rename(sourceFile, targetFile)
      .map(_ => sourceFile -> targetFile)
  }
}
