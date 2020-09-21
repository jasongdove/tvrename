package tvrename

import pureconfig._
import pureconfig.generic.auto._

object Main {
  def main(args: Array[String]): Unit = {
    val configFolder =
      sys.env.getOrElse("TVRENAME_CONFIG_FOLDER", s"${System.getProperty("user.home")}/.config/tvrename")

    val config = ConfigSource.file(s"$configFolder/tvrename.conf").load[TVRenameConfig]

    config match {
      case Left(failures) => println(failures)
      case Right(config) => {
        val fileSystem: FileSystem = FileSystemImpl
        val logger: Logger = LoggerImpl

        val tvdb: TVDB = new TVDBImpl(config.tvdbConfig)
        val classifier: EpisodeClassifier = new EpisodeClassifierImpl(config, fileSystem)
        val coreLogic: CoreLogic = new CoreLogicImpl(config, tvdb, classifier, logger)

        coreLogic.run()
      }
    }
  }
}
