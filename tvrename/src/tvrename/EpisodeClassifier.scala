package tvrename

import java.time.{Instant, LocalDate, ZoneId}

import scala.util.matching.Regex

trait EpisodeClassifier {
  def findUnknownEpisodes(): Seq[UnknownEpisode]
  def renameEpisode(episode: UnknownEpisode, seasonNumber: SeasonNumber, episodeNumber: Int): (String, String)
}

class EpisodeClassifierImpl(config: TVRenameConfig) extends EpisodeClassifier {
  def findUnknownEpisodes(): Seq[UnknownEpisode] = {
    val validExtensions = List("mkv", "ts")
    val knownPattern: Regex = """.*s([0-9]{2})e([0-9]{3})\..*""".r

    def isInvalid(p: os.Path) = !validExtensions.contains(p.ext)
    def isKnown(p: os.Path) = knownPattern.matches(p.toString)

    // TODO: use DATE_BROADCASTED tag (mkv), or General;Duration_Start (ts)
    def dateBroadcasted(file: os.Path): LocalDate =
      Instant
        .ofEpochMilli(
          os.mtime(file) - 7200000
        ) // offset by two hours until we read metadata
        .atZone(ZoneId.systemDefault)
        .toLocalDate

    val unknown = os
      .walk(os.Path(config.targetFolder), skip = isInvalid)
      .filter(!isKnown(_))

    unknown.map(ep => UnknownEpisode(ep.toString, dateBroadcasted(ep)))
  }

  def renameEpisode(
    episode: UnknownEpisode,
    seasonNumber: SeasonNumber,
    episodeNumber: Int
  ): (String, String) = {
    val sourceFile = os.Path(episode.fileName)
    val targetFile = os.Path(
      episode.fileName.replace(
        s".${sourceFile.ext}",
        f" - s${seasonNumber.value}%02de$episodeNumber%03d.${sourceFile.ext}"
      )
    )

    os.move(sourceFile, targetFile)

    (sourceFile.last, targetFile.last)
  }
}

case class UnknownEpisode(fileName: String, date: LocalDate)
