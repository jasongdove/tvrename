package tvrename.subtitles

import cats.effect.IO
import java.io.{File, FileInputStream}
import java.nio.{ByteBuffer, ByteOrder, LongBuffer}
import java.nio.channels.FileChannel.MapMode
import scala.math.{min, max}

// from https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes#Scala
object OpenSubtitlesHasher {
  private val hashChunkSize = 64L * 1024L

  def computeHash(fileName: String): IO[String] =
    IO {
      val file = new File(fileName)

      val fileSize = file.length
      val chunkSizeForFile = min(fileSize, hashChunkSize)

      val fileChannel = new FileInputStream(file).getChannel

      try {
        val head = computeHashForChunk(fileChannel.map(MapMode.READ_ONLY, 0, chunkSizeForFile))
        val tail = computeHashForChunk(
          fileChannel.map(MapMode.READ_ONLY, max(fileSize - hashChunkSize, 0), chunkSizeForFile)
        )

        "%016x".format(fileSize + head + tail)
      } finally {
        fileChannel.close()
      }
    }

  private def computeHashForChunk(buffer: ByteBuffer): Long = {
    def doCompute(longBuffer: LongBuffer, hash: Long): Long = {
      longBuffer.hasRemaining match {
        case false => hash
        case true  => doCompute(longBuffer, hash + longBuffer.get)
      }
    }
    val longBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
    doCompute(longBuffer, 0L)
  }
}
