package tvrename.config

import org.rogach.scallop._

class TerminalConfig(arguments: Seq[String]) extends ScallopConf(arguments) {
  val renameCommand = new Subcommand("rename") {
    val job = trailArg[String]()
  }
  val verifyCommand = new Subcommand("verify") {
    val job = trailArg[String]()
  }
  addSubcommand(renameCommand)
  addSubcommand(verifyCommand)
  requireSubcommand()

  verify()
}
