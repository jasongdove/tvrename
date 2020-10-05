package tvrename

import os.copy.over
import cats.effect.IO

trait FileSystem {
  def walk(path: String, recursive: Boolean = false): IndexedSeq[String]
  def getModifyTime(path: String): Long
  def rename(source: String, dest: String): IO[Unit]
  def absoluteToRelative(path: String, relativeTo: String): String
  def relativeTo(path: String, relativeTo: String): String
  def makeDirs(path: String): Unit
  def streamCommandToFile(stream: geny.Readable, command: String, targetFile: String)
  def exists(path: String): Boolean
  def getTempFileName(): String
  def call(command: String*): IO[Unit]
  def readLines(path: String): IO[Seq[String]]
  def getFileName(path: String): String
  def writeToFile(path: String, contents: String): IO[Unit]
  def concatPaths(one: String, two: String): String
}

object FileSystemImpl extends FileSystem {
  override def walk(path: String, recursive: Boolean = false): IndexedSeq[String] =
    os.walk(os.Path(path), maxDepth = if (recursive) Int.MaxValue else 1).map(_.toString)

  override def getModifyTime(path: String): Long = os.mtime(os.Path(path))

  override def rename(source: String, dest: String): IO[Unit] =
    IO(os.move(os.Path(source), os.Path(dest), replaceExisting = true))

  override def absoluteToRelative(path: String, relativeTo: String): String =
    (os.Path(relativeTo) / os.up / os.RelPath(path)).toString

  override def relativeTo(path: String, relativeTo: String): String =
    os.Path(path).relativeTo(os.Path(relativeTo)).toString

  override def makeDirs(path: String): Unit = os.makeDir.all(os.Path(path))

  override def streamCommandToFile(stream: geny.Readable, command: String, targetFile: String): Unit =
    os.proc(command).call(stdin = stream, stdout = os.Path(targetFile), mergeErrIntoOut = true)

  override def exists(path: String): Boolean =
    os.exists(os.Path(path))

  override def getTempFileName(): String = os.temp().toString

  override def call(command: String*): IO[Unit] = IO(os.proc(command).call(mergeErrIntoOut = true))

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
