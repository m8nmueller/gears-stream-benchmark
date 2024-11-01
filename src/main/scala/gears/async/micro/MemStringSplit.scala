package gears.async.micro

import cats.effect.IO
import cats.effect.instances.*
import cats.effect.unsafe.implicits.global
import gears.async.Async
import gears.async.Future
import gears.async.default.given
import gears.async.stream as gstream
import gears.async.stream.BufferedStreamChannel
import gears.async.stream.StreamResult
import io.reactivex.rxjava3.core as rxcore
import io.reactivex.rxjava3.schedulers as rxschedulers
import org.openjdk.jmh.annotations.*

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.util.boundary

@State(Scope.Benchmark)
class MssAsyncState extends AnyAsyncState:
  @Param(Array("32", "64", "128", "256", "512"))
  var bufSize: Int = uninitialized

@State(Scope.Benchmark)
class MssAsyncGearsState extends MssAsyncState with AnyAsyncGearsState:
  @Param(Array("1", "2", "4", "8"))
  var parallelism: Int = uninitialized

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(10)
@State(Scope.Benchmark)
class MemStringSplit:
  val content: String = Files.readString(Paths.get("src/main/resources/gears/async/micro/lorem.txt"))

  var sourceConfig: String = "80:80"
  var numOfContent: Int = uninitialized
  var firstBufSize: Int = uninitialized

  var gearsSrc: gstream.PullReaderStream[String] = uninitialized
  var fs2Src: fs2.Stream[IO, String] = uninitialized
  var rxSrc: rxcore.Flowable[String] = uninitialized

  @Setup
  def setup(): Unit =
    val parts = sourceConfig.split(":")
    numOfContent = parts(0).toInt
    firstBufSize = parts(1).toInt

    gearsSrc = gstream.Stream.fromArray(Array.fill(numOfContent)(content))
    fs2Src = fs2.Stream.emits(List.fill(numOfContent)(content))
    rxSrc = rxcore.Flowable.fromArray(Array.fill(numOfContent)(content)*)

  private def gears_(using AsyncShift[gstream.PullReaderStream]): Map[String, Int] =
    Async.blocking:
      gearsSrc
        .shift(firstBufSize)
        .flatMap()(line => gstream.Stream.fromArray(line.split("\\s+")))
        .shift()
        .filter(_.length >= 4)
        .shift()
        .fold(gstream.StreamFolder.toMap(identity, _ => 1, (_, v1, v2) => v1 + v2))
        .get

  private def fs2_(using AsyncShift[StreamFS2]): Map[String, Int] =
    fs2Src
      .shift(firstBufSize)
      .flatMap(line => fs2.Stream(line.split("\\s+")*))
      .shift()
      .filter(_.length >= 4)
      .shift()
      .compile
      .fold(Map.empty[String, Int]) { (map, word) => map.updatedWith(word)(_.map(_ + 1).orElse(Some(1))) }
      .unsafeRunSync()

  private def rx_(using AsyncShift[rxcore.Flowable]): mutable.Map[String, Int] =
    rxSrc
      .shift(firstBufSize)
      .flatMap(line => rxcore.Flowable.fromArray(line.split("\\s+")*))
      .shift()
      .filter(_.length >= 4)
      .shift()
      .collectInto(
        mutable.HashMap[String, Int](),
        (counts, word) => counts.updateWith(word)(_.map(_ + 1).orElse(Some(1)))
      )
      .blockingGet()

  @Benchmark def gearsBenchmark() = gears_(using noShift())
  @Benchmark def gearsBenchmarkAsync(state: MssAsyncGearsState) = gears_(using state.toShift())

  @Benchmark def fs2Benchmark() = fs2_(using noShift())
  @Benchmark def fs2BenchmarkAsync(state: MssAsyncState) = fs2_(using state.toShift(fs2Shift))

  @Benchmark def rxBenchmark() = rx_(using noShift())
  @Benchmark def rxBenchmarkAsync(state: MssAsyncState) = rx_(using state.toShift(rxShift))
