package tvrename

import cats.effect._
import pureconfig._
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import tvrename.classifier._
import tvrename.config._
import tvrename.logic._
import tvrename.subtitles._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    resources(args)
      .use {
        case (coreLogic) => {
          coreLogic.run()
        }
      }
      .as(ExitCode.Success)
  }

  private def resources(args: List[String]): Resource[IO, CoreLogic] = {
    val configFolder =
      sys.env.getOrElse("TVRENAME_CONFIG_FOLDER", s"${System.getProperty("user.home")}/.config/tvrename")

    val logger: Logger = LoggerImpl
    val terminalConfig = new TerminalConfig(args)
    val commandIO: IO[Command] = terminalConfig.subcommand match {
      case Some(terminalConfig.renameCommand) => IO(Rename)
      case Some(terminalConfig.verifyCommand) => IO(Verify)
      case _                                  => IO.raiseError(new Exception())
    }

    for {
      blocker <- Blocker[IO]
      config <- ConfigSource.file(s"$configFolder/tvrename.conf").loadF[IO, TVRenameConfig](blocker).asResource
      command <- commandIO.asResource
      jobConfig <- command match {
        case Rename =>
          ConfigSource.file(terminalConfig.renameCommand.job()).loadF[IO, JobConfig](blocker).asResource
        case Verify =>
          ConfigSource.file(terminalConfig.verifyCommand.job()).loadF[IO, JobConfig](blocker).asResource
      }
    } yield {
      val fileSystem: FileSystem = FileSystemImpl
      jobConfig match {
        case broadcastJobConfig: BroadcastJobConfig =>
          val tvdb: TVDB = new TVDBImpl(config.tvdbConfig)
          val classifier = new BroadcastEpisodeClassifier(broadcastJobConfig, fileSystem)
          val coreLogic: CoreLogic = new BroadcastCoreLogic(broadcastJobConfig, tvdb, classifier, logger)
          coreLogic
        case remuxJobConfig: RemuxJobConfig =>
          val subtitleDownloader: ReferenceSubtitleDownloader =
            new ReferenceSubtitleDownloaderImpl(config, remuxJobConfig, fileSystem, logger)
          val classifier = new RemuxEpisodeClassifier(command, remuxJobConfig, fileSystem)
          val subtitleExtractor: SubtitleExtractor =
            new SubtitleExtractorImpl(config, remuxJobConfig, fileSystem, logger)
          val subtitleProcessor: SubtitleProcessor = new ExternalSubtitleProcessor(config, fileSystem)
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

          coreLogic
      }
    }
  }
}
