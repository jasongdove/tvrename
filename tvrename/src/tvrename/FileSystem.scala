package tvrename

import cats.effect._
import fs2.Stream
import java.nio.file._

trait FileSystem {
  def walk(path: String, recursive: Boolean): IO[IndexedSeq[String]]
  def getModifyTime(path: String): Long
  def rename(source: String, dest: String): IO[Unit]
  def absoluteToRelative(path: String, relativeTo: String): String
  def relativeTo(path: String, relativeTo: String): String
  def makeDirs(path: String): IO[Unit]
  def gunzipToFile(stream: Stream[IO, Byte], targetFile: String): Stream[IO, Unit]
  def exists(path: String): IO[Boolean]
  def getTempFileName(): String
  def call(command: String*): IO[Unit]
  def readLines(path: String): IO[Seq[String]]
  def getFileName(path: String): String
  def writeToFile(path: String, contents: String): IO[Unit]
  def concatPaths(one: String, two: String): String
}

class FileSystemImpl(blocker: Blocker)(implicit cs: ContextShift[IO]) extends FileSystem {
  override def walk(path: String, recursive: Boolean): IO[IndexedSeq[String]] =
    IO(os.walk(os.Path(path), maxDepth = if (recursive) Int.MaxValue else 1).map(_.toString))

  override def getModifyTime(path: String): Long = os.mtime(os.Path(path))

  override def rename(source: String, dest: String): IO[Unit] =
    IO(os.move(os.Path(source), os.Path(dest), replaceExisting = true))

  override def absoluteToRelative(path: String, relativeTo: String): String =
    (os.Path(relativeTo) / os.up / os.RelPath(path)).toString

  override def relativeTo(path: String, relativeTo: String): String =
    os.Path(path).relativeTo(os.Path(relativeTo)).toString

  override def makeDirs(path: String): IO[Unit] = IO(os.makeDir.all(os.Path(path)))

  override def gunzipToFile(stream: Stream[IO, Byte], targetFile: String): Stream[IO, Unit] =
    stream
      .through(fs2.compression.gunzip(1024))
      .flatMap { gunzip =>
        gunzip.content.through(fs2.io.file.writeAll(Paths.get(targetFile), blocker))
      }

  override def exists(path: String): IO[Boolean] =
    IO(os.exists(os.Path(path)))

  override def getTempFileName(): String = os.temp().toString

  override def call(command: String*): IO[Unit] = IO { val _ = os.proc(command).call(mergeErrIntoOut = true) }

  override def readLines(path: String): IO[Seq[String]] = IO(os.read.lines(os.Path(path)))

  override def getFileName(path: String): String = {
    val p = os.Path(path)
    s"${p.baseName}.${p.ext}"
  }

  override def writeToFile(path: String, contents: String): IO[Unit] =
    IO(os.write.over(os.Path(path), contents))

  override def concatPaths(one: String, two: String): String =
    (os.Path(one) / two).toString
}
