package tvrename

import pureconfig._
import pureconfig.generic.auto._

import tvrename.config._
import tvrename.classifier._
import tvrename.logic._
import tvrename.subtitles._

object Main {
  def main(args: Array[String]): Unit = {
    val configFolder =
      sys.env.getOrElse("TVRENAME_CONFIG_FOLDER", s"${System.getProperty("user.home")}/.config/tvrename")

    val config = ConfigSource.file(s"$configFolder/tvrename.conf").load[TVRenameConfig]

    val terminalConfig = new TerminalConfig(args)
    terminalConfig.subcommand map {
      case terminalConfig.renameCommand =>
        val jobConfig = ConfigSource.file(terminalConfig.renameCommand.job()).load[JobConfig]

        val fileSystem: FileSystem = FileSystemImpl
        val logger: Logger = LoggerImpl

        val coreLogic = (config, jobConfig) match {
          case (Left(failures), _) =>
            println(failures)
            None
          case (_, Left(failures)) =>
            println(failures)
            None
          case (Right(config), Right(jobConfig: BroadcastJobConfig)) => {
            val tvdb: TVDB = new TVDBImpl(config.tvdbConfig)
            val classifier = new BroadcastEpisodeClassifier(jobConfig, fileSystem)
            val coreLogic: CoreLogic = new BroadcastCoreLogic(jobConfig, tvdb, classifier, logger)
            Some(coreLogic)
          }
          case (Right(config), Right(jobConfig: RemuxJobConfig)) => {
            val subtitleDownloader: ReferenceSubtitleDownloader =
              new ReferenceSubtitleDownloaderImpl(config, jobConfig, fileSystem, logger)
            val classifier = new RemuxEpisodeClassifier(jobConfig, fileSystem)
            val subtitleExtractor: SubtitleExtractor = new SubtitleExtractorImpl(config, jobConfig, fileSystem, logger)
            val subtitleProcessor: SubtitleProcessor = new ExternalSubtitleProcessor(config, fileSystem)
            val coreLogic: CoreLogic =
              new RemuxCoreLogic(
                jobConfig,
                classifier,
                subtitleDownloader,
                subtitleExtractor,
                subtitleProcessor,
                logger
              )
            Some(coreLogic)
          }
        }

        coreLogic.map(_.run())
    }
  }
}
