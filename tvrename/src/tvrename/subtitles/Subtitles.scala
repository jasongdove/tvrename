package tvrename.subtitles

import org.ebml.matroska.MatroskaFileTrack
import cats.data.NonEmptyList

sealed trait Subtitles {
  def baseFileName: String
  def extensions: NonEmptyList[String]
  def priority: Int

  def fileNames: NonEmptyList[String] = extensions.map(e => s"${baseFileName}.${e}")
  def primaryFileName = fileNames.head
}

object Subtitles {
  def fromTrack(track: MatroskaFileTrack, baseFileName: String): Option[Subtitles] =
    track.getCodecID match {
      case "S_VOBSUB"    => Some(VobSub(baseFileName))
      case "S_HDMV/PGS"  => Some(PGS(baseFileName))
      case "S_TEXT/UTF8" => Some(SubRip(baseFileName))
      case s @ _ =>
        println(s"Unexpected subtitle codec ${s}")
        None
    }
}

case class SubRip(baseFileName: String) extends Subtitles {
  def extensions = NonEmptyList.of("srt")

  // these are most likely CC => text from makemkv
  def priority = 3
}

case class VobSub(baseFileName: String) extends Subtitles {
  def extensions = NonEmptyList.of("sub", "idx")
  def priority = 2
}

case class PGS(baseFileName: String) extends Subtitles {
  def extensions = NonEmptyList.of("sup")
  def priority = 1
}
