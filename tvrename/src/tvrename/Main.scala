package tvrename

import cats.data.EitherT
import cats.effect._
import pureconfig._
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import tvrename.classifier._
import tvrename.config._
import tvrename.logic._
import tvrename.subtitles._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    resources(args)
      .use {
        case Right(coreLogic) => coreLogic.run()
        case Left(value) => IO(println(value))
      }
      .as(ExitCode.Success)
  }

  private def resources(args: List[String]): Resource[IO, Either[TVRenameError, CoreLogic]] = {
    val configFolder =
      sys.env.getOrElse("TVRENAME_CONFIG_FOLDER", s"${System.getProperty("user.home")}/.config/tvrename")

    val logger: Logger = LoggerImpl
    val terminalConfig = new TerminalConfig(args)
    val commandIO: IO[Command] = terminalConfig.subcommand match {
      case Some(terminalConfig.renameCommand) => IO.pure(Rename)
      case Some(terminalConfig.verifyCommand) => IO.pure(Verify)
      case _                                  => IO.raiseError(new Exception())
    }

    for {
      blocker <- Blocker[IO]
      httpClient <- BlazeClientBuilder[IO](blocker.blockingContext).resource
      followRedirectClient = FollowRedirect(1)(httpClient)
      config <- ConfigSource.file(s"$configFolder/tvrename.conf").loadF[IO, TVRenameConfig](blocker).asResource
      command <- commandIO.asResource
      jobConfig <- loadConfig(command, terminalConfig, blocker).value.asResource
    } yield {
      val fileSystem: FileSystem = new FileSystemImpl(blocker)
      jobConfig match {
        case Right(broadcastJobConfig: BroadcastJobConfig) =>
          val tvdb: TVDB = new TVDBImpl(config.tvdbConfig, httpClient)
          val classifier = new BroadcastEpisodeClassifier(broadcastJobConfig, fileSystem)
          val coreLogic: CoreLogic = new BroadcastCoreLogic(broadcastJobConfig, tvdb, classifier, logger)
          Right(coreLogic)
        case Right(remuxJobConfig: RemuxJobConfig) =>
          val subtitleDownloader: ReferenceSubtitleDownloader =
            new ReferenceSubtitleDownloaderImpl(config, remuxJobConfig, followRedirectClient, fileSystem, logger)
          val classifier = new RemuxEpisodeClassifier(command, remuxJobConfig, fileSystem)
          val subtitleExtractor: SubtitleExtractor =
            new SubtitleExtractorImpl(config, fileSystem, logger)
          val subtitleProcessor: SubtitleProcessor = new ExternalSubtitleProcessor(config, fileSystem, logger)
          val subtitleMatcher: SubtitleMatcher = new SubtitleMatcherImpl(config, remuxJobConfig, fileSystem)
          val coreLogic: CoreLogic = command match {
            case Rename =>
              new RemuxCoreLogic(
                remuxJobConfig,
                terminalConfig.renameCommand.dryRun(),
                classifier,
                subtitleDownloader,
                subtitleExtractor,
                subtitleProcessor,
                subtitleMatcher,
                fileSystem,
                logger
              )
            case Verify =>
              new VerifyRemuxCoreLogic(
                remuxJobConfig,
                classifier,
                subtitleDownloader,
                subtitleExtractor,
                subtitleProcessor,
                subtitleMatcher,
                fileSystem,
                logger
              )
          }

          Right(coreLogic)
        case Left(value) => Left(value)
      }
    }
  }

  private def loadConfig(
    command: Command,
    terminalConfig: TerminalConfig,
    blocker: Blocker
  ): EitherT[IO, TVRenameError, JobConfig] =
    command match {
      case Rename =>
        EitherT.right(ConfigSource.file(terminalConfig.renameCommand.job()).loadF[IO, JobConfig](blocker))
      case Verify =>
        EitherT.right(ConfigSource.file(terminalConfig.verifyCommand.job()).loadF[IO, JobConfig](blocker))
      case _ =>
        EitherT.leftT(InvalidJobConfiguration)
    }
}
