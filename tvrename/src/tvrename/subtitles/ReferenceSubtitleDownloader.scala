package tvrename.subtitles

import java.io.File

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import com.github.dnbn.submerge.api.parser.SRTParser
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import tvrename._
import tvrename.config._

import scala.util.Try
import scala.util.matching.Regex

trait ReferenceSubtitleDownloader {
  def download(): IO[Unit]
}

case class ValidSubtitleFile(fileName: String)

class ReferenceSubtitleDownloaderImpl(
  config: TVRenameConfig,
  jobConfig: RemuxJobConfig,
  openSubtitles: OpenSubtitles,
  httpClient: Client[IO],
  fileSystem: FileSystem,
  logger: Logger
) extends ReferenceSubtitleDownloader {

  private val targetFolder: String =
    s"${config.cacheFolder}/reference/${jobConfig.seriesName}/Season ${"%02d".format(jobConfig.seasonNumber.value)}"

  private val episodeCountFile: String = s"${targetFolder}/.episode-count"

  private val parser: SRTParser = new SRTParser

  private def expectedEpisodeCount: IO[Option[Int]] =
    for {
      exists <- fileSystem.exists(episodeCountFile)
      lines <- if (exists) fileSystem.readLines(episodeCountFile) else IO.pure(List.empty)
    } yield lines.headOption.map(_.toInt)

  private def actualEpisodeCount: IO[Int] =
    fileSystem.walk(targetFolder, recursive = false).map(_.count(f => f.endsWith(".srt")))

  override def download(): IO[Unit] =
    for {
      _ <- fileSystem.makeDirs(targetFolder)
      expected <- expectedEpisodeCount
      actual <- actualEpisodeCount
      _ <- downloadIfNeeded(expected, actual)
    } yield ()

  private def downloadIfNeeded(expected: Option[Int], actual: Int): IO[Unit] = {
    expected match {
      case Some(a) if a == actual => IO.unit
      case _ =>
        for {
          searchResults <- openSubtitles.search(jobConfig.seriesId, jobConfig.seasonNumber)
          // TODO: handle no episodes found for series/season
          lastEpisode = searchResults.foldLeft(0)((acc, result) => math.max(acc, result.episodeNumber.value))
          _ <- logger.debug(
            s"${jobConfig.seriesName} Season ${jobConfig.seasonNumber.value.toString} has ${lastEpisode.toString} episodes"
          )
          _ <- fileSystem.writeToFile(episodeCountFile, lastEpisode.toString)
          _ <- downloadAllEpisodes(searchResults)
        } yield ()
    }
  }

  private def downloadAllEpisodes(seasonSearchResults: List[EpisodeSearchResults]): IO[Unit] = {
    seasonSearchResults.traverse_ { episode =>
      val sortedSearchResults = episode.searchResults
        .sortBy(_.isWebDL)(Ordering[Boolean].reverse)
        .sortBy(_.Score)(Ordering[Double].reverse)

      val template = jobConfig.template
        .replace("[series]", jobConfig.seriesName)
        .replace("[season]", f"${jobConfig.seasonNumber.value}%02d")
        .replace("[episode]", f"${episode.episodeNumber.value}%02d")

      val targetFile = s"$targetFolder/$template.srt"

      val downloadAll: OptionT[IO, Unit] = for {
        exists <- OptionT.liftF(fileSystem.exists(targetFile))
        subtitleFile <- OptionT(if (!exists) downloadValidSubtitleForEpisode(sortedSearchResults) else IO.pure(None))
        _ <- OptionT.liftF(logger.debug(s"\t => $template.srt"))
        _ <- OptionT.liftF(fileSystem.rename(subtitleFile.fileName, targetFile))
      } yield ()

      downloadAll.value
    }
  }

  def downloadValidSubtitleForEpisode(searchResults: List[SearchResult]): IO[Option[ValidSubtitleFile]] = {
    searchResults.foldLeft[IO[Option[ValidSubtitleFile]]](IO.pure(None)) { (acc, next) =>
      acc.flatMap {
        case Some(value) => IO.pure(Some(value))
        case None        => downloadAndParseSubtitle(next)
      }
    }
  }

  def downloadAndParseSubtitle(searchResult: SearchResult): IO[Option[ValidSubtitleFile]] = {
    val tempFile = fileSystem.getTempFileName()
    for {
      _ <- logger.debug(searchResult.SubDownloadLink)
      uri <- Uri.fromString(searchResult.SubDownloadLink).liftTo[IO]
      request <- Method.GET(uri)
      _ <- httpClient.stream(request).flatMap(s => fileSystem.gunzipToFile(s.body, tempFile)).compile.drain
      attempt <- cleanupAndParse(tempFile)
    } yield attempt
  }

  private def cleanupAndParse(fileName: String): IO[Option[ValidSubtitleFile]] = {
    for {
      lines <- fileSystem.readLines(fileName)
      _ <- fileSystem.writeToFile(fileName, cleanupLines(lines))
      attempt <- Try { parser.parse(new File(fileName)) }.attempt.liftTo[IO]
      result <- if (attempt.isLeft) IO.pure(None) else IO.pure(Some(ValidSubtitleFile(fileName)))
    } yield result
  }

  private def cleanupLines(lines: Seq[String]): String = {
    // many subrip files are formatted with timestamps like 00:00:0,000 or 00:00:00,00 which isn't valid for the parser
    // we don't really care about timestamps, though, so we'll try to make it valid
    val badSeconds =
      new Regex("([\\d]{2}):([\\d]{2}):(\\d),([\\d]{2,3})", "hours", "minutes", "seconds", "milliseconds")
    val badMilliseconds =
      new Regex("([\\d]{2}):([\\d]{2}):([\\d]{2}),([\\d]{2})(?!\\d)", "hours", "minutes", "seconds", "milliseconds")
    lines
      .map(line =>
        badSeconds.replaceAllIn(
          line,
          m => s"${m group "hours"}:${m group "minutes"}:0${m group "seconds"},${m group "milliseconds"}"
        )
      )
      .map(line =>
        badMilliseconds.replaceAllIn(
          line,
          m => s"${m group "hours"}:${m group "minutes"}:${m group "seconds"},${m group "milliseconds"}0"
        )
      )
      .mkString("\n")
  }
}
