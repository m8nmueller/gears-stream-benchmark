package gears.async.micro

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import gears.async.{stream => gstream}
import scala.compiletime.uninitialized
import gears.async.stream.StreamIteratorFlow._
import scala.collection.mutable
import gears.async.Async
import gears.async.default.given
import gears.async.stream.StreamResult
import scala.util.boundary
import io.reactivex.rxjava3.{core => rxcore}
import java.nio.file.Files
import java.nio.file.Paths
import io.reactivex.rxjava3.{schedulers => rxschedulers}
import java.util.concurrent.atomic.AtomicInteger
import gears.async.stream.Stream.ChannelFactory
import gears.async.stream.BufferedStreamChannel
import java.nio.file.StandardOpenOption

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(5)
@State(Scope.Benchmark)
class MemStringSplit:

  val content: String = Files.readString(
    Paths.get("src/main/resources/gears/async/micro/lorem.txt")
  )

  @Param(Array("80:20"))
  var sourceConfig: String = uninitialized
  var numOfContent: Int = uninitialized
  var firstBufSize: Int = uninitialized

  @Param(Array("10", "50", "200", "600"))
  var bufSize: Int = uninitialized

  var gearsSrc: gstream.Stream[String] = uninitialized
  var fs2Src: fs2.Stream[fs2.Pure, String] = uninitialized
  var rxSrc: rxcore.Flowable[String] = uninitialized

  var fac1: ChannelFactory = uninitialized
  var fac2: ChannelFactory = uninitialized

  @Setup
  def setup(): Unit =
    val parts = sourceConfig.split(":")
    numOfContent = parts(0).toInt
    firstBufSize = parts(1).toInt

    gearsSrc = gstream.Stream: out =>
      for i <- 0 to numOfContent
      do out.send(content)
      out.close()
    fs2Src = fs2.Stream.emits(List.fill(numOfContent)(content))
    rxSrc = rxcore.Flowable.fromArray(Array.fill(numOfContent)(content)*)

    fac1 = ChannelFactory {
      [T] => () => BufferedStreamChannel[T](firstBufSize)
    }
    fac2 = ChannelFactory { [T] => () => BufferedStreamChannel[T](bufSize) }

  @Benchmark
  def gearsBenchmark(): mutable.HashMap[String, Int] =
    Async.blocking:
      gearsSrc
        .mapMulti(_.split("\\s+"))(using fac1)
        .filter(_.length >= 4)(using fac2)
        .run: src =>
          val counts = mutable.HashMap[String, Int]()
          boundary:
            while true do
              src.readStream() match
                case StreamResult.Data(word) =>
                  counts.updateWith(word)(_.map(_ + 1).orElse(Some(1)))
                case _ => boundary.break()
          counts
        (using fac2)

  @Benchmark
  def fs2Benchmark(): Map[String, Int] =
    fs2Src
      .flatMap(line => fs2.Stream(line.split("\\s+")*))
      .filter(_.length >= 4)
      .compile
      .fold(Map.empty[String, Int]): (map, word) =>
        map.updatedWith(word)(_.map(_ + 1).orElse(Some(1)))

  @Benchmark
  def rxBenchmark(): mutable.HashMap[String, Int] =
    rxSrc
      .flatMap(line => rxcore.Flowable.fromArray(line.split("\\s+")*))
      .filter(_.length >= 4)
      .collectInto(
        mutable.HashMap[String, Int](),
        (counts, word) => counts.updateWith(word)(_.map(_ + 1).orElse(Some(1)))
      )
      .blockingGet()

  @Benchmark
  def rxBenchmarkAsync(): mutable.HashMap[String, Int] =
    val sched = rxschedulers.Schedulers.computation()
    rxSrc
      .observeOn(sched)
      .flatMap(line => rxcore.Flowable.fromArray(line.split("\\s+")*))
      .observeOn(sched)
      .filter(_.length >= 4)
      .observeOn(sched)
      .collectInto(
        mutable.HashMap[String, Int](),
        (counts, word) => counts.updateWith(word)(_.map(_ + 1).orElse(Some(1)))
      )
      .blockingGet()
