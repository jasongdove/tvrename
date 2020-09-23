package tvrename

import pureconfig._
import pureconfig.generic.auto._

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

        (config, jobConfig) match {
          case (Left(failures), _) => println(failures)
          case (_, Left(failures)) => println(failures)
          case (Right(config), Right(jobConfig: BroadcastJobConfig)) => {
            val tvdb: TVDB = new TVDBImpl(config.tvdbConfig)
            val classifier: EpisodeClassifier = new BroadcastEpisodeClassifier(jobConfig, fileSystem)
            val coreLogic: CoreLogic = new BroadcastCoreLogic(jobConfig, tvdb, classifier, logger)

            coreLogic.run()
          }
        }
    }
  }
}
