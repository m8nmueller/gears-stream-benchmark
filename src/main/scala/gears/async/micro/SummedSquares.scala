package gears.async.micro

import cats.effect.IO
import cats.effect.instances.*
import cats.effect.unsafe.implicits.global
import gears.async.Async
import gears.async.default.given
import gears.async.stream as gstream
import gears.async.stream.StreamFolder
import io.reactivex.rxjava3.core as rxcore
import io.reactivex.rxjava3.schedulers as rxschedulers
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import scala.compiletime.uninitialized

@State(Scope.Benchmark)
class IntAsyncState extends AnyAsyncState:
  @Param(Array("64", "512"))
  var bufSize: Int = uninitialized

@State(Scope.Benchmark)
class IntAsyncGearsState extends IntAsyncState with AnyAsyncGearsState:
  @Param(Array("1", "8"))
  var parallelism: Int = uninitialized

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(5)
@State(Scope.Benchmark)
class SummedSquares:
  @Param(Array("10000", "100000", "1000000"))
  var upto: Int = uninitialized

  var numbers: Array[Long] = uninitialized

  val gSum = StreamFolder.reducing(0L)(_ + _)
  val rxSum = Collectors.summingLong[Long](identity)

  @Setup
  def setup(): Unit = numbers = Array.tabulate(upto)(_.toLong % 1000)

  private def gears_push_sum(): Long = Async.blocking(gstream.Stream.fromArray(numbers).toPushStream().fold(gSum).get)
  private def gears_sum(): Long = Async.blocking(gstream.Stream.fromArray(numbers).fold(gSum).get)
  private def fs2_sum(): Long = fs2.Stream.emits[IO, Long](numbers).compile.fold(0L)(_ + _).unsafeRunSync()
  private def rx_sum(): Long = rxcore.Flowable.fromArray(numbers*).collect(rxSum).blockingGet()

  private def gears_push_sq_sum(using AsyncShift[gstream.PushSenderStream]): Long =
    Async.blocking(gstream.Stream.fromArray(numbers).toPushStream().map(x => x * x).shift().fold(gSum).get)
  private def gears_sq_sum(using AsyncShift[gstream.PullReaderStream]): Long =
    Async.blocking(gstream.Stream.fromArray(numbers).map(x => x * x).shift().fold(gSum).get)
  private def fs2_sq_sum(using AsyncShift[StreamFS2]): Long =
    fs2.Stream.emits(numbers).map(x => x * x).shift().compile.fold(0L)(_ + _).unsafeRunSync()
  private def rx_sq_sum(using AsyncShift[rxcore.Flowable]): Long =
    rxcore.Flowable.fromArray(numbers*).map(x => x * x).shift().collect(rxSum).blockingGet()

  private def gears_push_sq_even_sum(using AsyncShift[gstream.PushSenderStream]): Long =
    Async.blocking:
      gstream.Stream.fromArray(numbers).toPushStream().filter(x => x % 2 == 0).map(x => x * x).shift().fold(gSum).get
  private def gears_sq_even_sum(using AsyncShift[gstream.PullReaderStream]): Long =
    Async.blocking(gstream.Stream.fromArray(numbers).filter(x => x % 2 == 0).map(x => x * x).shift().fold(gSum).get)
  private def fs2_sq_even_sum(using AsyncShift[StreamFS2]): Long =
    fs2.Stream.emits(numbers).filter(x => x % 2 == 0).map(x => x * x).shift().compile.fold(0L)(_ + _).unsafeRunSync()
  private def rx_sq_even_sum(using AsyncShift[rxcore.Flowable]): Long =
    rxcore.Flowable.fromArray(numbers*).filter(x => x % 2 == 0).map(x => x * x).shift().collect(rxSum).blockingGet()

  @Benchmark def gearsPushSumSync() = gears_push_sum()
  @Benchmark def gearsSumSync() = gears_sum()
  @Benchmark def fs2SumSync() = fs2_sum()
  @Benchmark def rxSumSync() = rx_sum()

  @Benchmark def gearsPushSqSumSync() = gears_push_sq_sum(using noShift())
  @Benchmark def gearsSqSumSync() = gears_sq_sum(using noShift())
  @Benchmark def fs2SqSumSync() = fs2_sq_sum(using noShift())
  @Benchmark def rxSqSumSync() = rx_sq_sum(using noShift())

  @Benchmark def gearsPushSqSumAsync(a: IntAsyncGearsState) = gears_push_sq_sum(using a.toShift())
  @Benchmark def gearsSqSumAsync(a: IntAsyncGearsState) = gears_sq_sum(using a.toShift())
  @Benchmark def fs2SqSumAsync(a: IntAsyncState) = fs2_sq_sum(using a.toShift(fs2Shift))
  @Benchmark def rxSqSumAsync(a: IntAsyncState) = rx_sq_sum(using a.toShift(rxShift))

  @Benchmark def gearsPushSqEvenSumSync() = gears_push_sq_even_sum(using noShift())
  @Benchmark def gearsSqEvenSumSync() = gears_sq_even_sum(using noShift())
  @Benchmark def fs2SqEvenSumSync() = fs2_sq_even_sum(using noShift())
  @Benchmark def rxSqEvenSumSync() = rx_sq_even_sum(using noShift())

  @Benchmark def gearsPushSqEvenSumAsync(a: IntAsyncGearsState) = gears_push_sq_even_sum(using a.toShift())
  @Benchmark def gearsSqEvenSumAsync(a: IntAsyncGearsState) = gears_sq_even_sum(using a.toShift())
  @Benchmark def fs2SqEvenSumAsync(a: IntAsyncState) = fs2_sq_even_sum(using a.toShift(fs2Shift))
  @Benchmark def rxSqEvenSumAsync(a: IntAsyncState) = rx_sq_even_sum(using a.toShift(rxShift))
end SummedSquares
