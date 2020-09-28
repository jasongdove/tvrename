package tvrename.logic

import cats.effect.IO

trait CoreLogic {
  def run(): IO[Unit]
}
