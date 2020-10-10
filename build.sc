import mill._, scalalib._

object jebml extends JavaModule {
  def ivyDeps =
    Agg(
      ivy"org.slf4j:slf4j-api:1.7.5",
      ivy"junit:junit:4.11",
      ivy"org.mockito:mockito-core:1.9.+",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )
}

object tvrename extends ScalaModule {
  def scalaVersion = "2.13.3"
  def moduleDeps = Seq(jebml)
  val http4sVersion = "0.21.7"
  def ivyDeps =
    Agg(
      ivy"org.typelevel::cats-effect:2.1.3",
      ivy"com.lihaoyi::os-lib:0.6.2",
      ivy"com.github.pureconfig::pureconfig:0.13.0",
      ivy"com.github.pureconfig::pureconfig-cats-effect:0.13.0",
      ivy"org.rogach::scallop:3.5.1",
      ivy"ch.qos.logback:logback-classic:1.2.3",
      ivy"com.github.dnbn.submerge:submerge-api:1.9.2",
      ivy"org.http4s::http4s-dsl:$http4sVersion",
      ivy"org.http4s::http4s-blaze-client:$http4sVersion",
      ivy"org.http4s::http4s-circe:$http4sVersion",
      ivy"io.circe::circe-generic:0.12.3",
      ivy"co.fs2::fs2-core:2.4.4",
      ivy"co.fs2::fs2-io:2.4.4"
    )

  def scalacOptions =
    Seq(
      "-deprecation",
      "-encoding",
      "utf-8",
      "-explaintypes",
      "-feature",
      "-language:reflectiveCalls",
      "-unchecked",
      "-Yrangepos",
      "-Xcheckinit",
      "-Xfatal-warnings",
      "-Xlint:constant",
      "-Xlint:delayedinit-select",
      "-Xlint:doc-detached",
      "-Xlint:inaccessible",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:nullary-unit",
      "-Xlint:option-implicit",
      "-Xlint:package-object-classes",
      "-Xlint:poly-implicit-overload",
      "-Xlint:private-shadow",
      "-Xlint:stars-align",
      "-Xlint:type-parameter-shadow",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:implicits",
      "-Ywarn-unused:imports",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:params",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-value-discard"
    )
}
