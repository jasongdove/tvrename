import mill._, scalalib._

object tvrename extends ScalaModule {
  def scalaVersion = "2.13.3"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::os-lib:0.6.2",
    ivy"com.lihaoyi::upickle:0.9.5",
    ivy"com.lihaoyi::requests:0.5.1",
    ivy"com.github.pureconfig::pureconfig:0.13.0"
  )
}
