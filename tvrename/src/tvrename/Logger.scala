package tvrename

import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory
import cats.effect.IO

trait Logger {
  def debug(msg: String): IO[Unit]
  def info(msg: String): IO[Unit]
  def warn(msg: String): IO[Unit]
}

object LoggerImpl extends Logger {
  val logger = LoggerFactory
    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[ch.qos.logback.classic.Logger]

  logger.setLevel(Level.DEBUG)

  def debug(msg: String): IO[Unit] =
    IO {
      if (logger.isDebugEnabled) logger.debug(msg)
    }

  def info(msg: String): IO[Unit] =
    IO {
      if (logger.isInfoEnabled) logger.info(msg)
    }

  def warn(msg: String): IO[Unit] =
    IO {
      if (logger.isWarnEnabled) logger.warn(msg)
    }
}
