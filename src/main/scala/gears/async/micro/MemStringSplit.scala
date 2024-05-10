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
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
class MemStringSplit:

  val content: String = Files.readString(
    Paths.get("src/main/resources/gears/async/micro/lorem.txt")
  )

  @Param(Array("20"/*, "40", "60", "80", "100", "150", "200"*/))
  var numOfContent: Int = uninitialized

  var gearsSrc: gstream.Stream[String] = uninitialized
  var fs2Src: fs2.Stream[fs2.Pure, String] = uninitialized
  var rxSrc: rxcore.Flowable[String] = uninitialized

  @Setup
  def setup(): Unit =
    gearsSrc = gstream.Stream: out =>
      for i <- 0 to numOfContent
      do out.send(content)
      out.close()
    fs2Src = fs2.Stream.emits(List.fill(numOfContent)(content))
    rxSrc = rxcore.Flowable.fromArray(Array.fill(numOfContent)(content)*)

  // @TearDown
  // def teardown(): Unit =
  //   Files.writeString(
  //     Paths.get("results/memstringsplit/stat.txt"),
  //     buf.mkString(s"$numOfContent (${pos.get()}): ", " ", "\n"),
  //     StandardOpenOption.APPEND,
  //     StandardOpenOption.CREATE
  //   )
  //   pos.set(0)

  // private val buf: Array[Byte] = new Array[Byte](1000)
  // private var pos: AtomicInteger = AtomicInteger(0)

  // private def fac(num: Byte) =
  //   ChannelFactory(
  //     [T] =>
  //       () =>
  //         BufferedStreamChannel(
  //           10, {
  //             if Math.random() < 0.1 then
  //               val p = pos.getAndIncrement()
  //               if p < buf.size then buf(p) = num
  //           }
  //       )
  //   )

  // val fac1 = ChannelFactory { [T] => () => BufferedStreamChannel[T](5) }
  // val fac2 = ChannelFactory { [T] => () => BufferedStreamChannel[T](30) }
  // val fac3 = ChannelFactory { [T] => () => BufferedStreamChannel[T](10) }

  @Benchmark
  def gearsBenchmark(): mutable.HashMap[String, Int] =
    Async.blocking:
      gearsSrc
        .mapMulti(_.split("\\s+")) // (using fac1)
        .filter(_.length >= 4) // (using fac2)
        .run: src =>
          val counts = mutable.HashMap[String, Int]()
          boundary:
            while true do
              src.readStream() match
                case StreamResult.Data(word) =>
                  counts.updateWith(word)(_.map(_ + 1).orElse(Some(1)))
                case _ => boundary.break()
          counts
      // (using fac3)

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
