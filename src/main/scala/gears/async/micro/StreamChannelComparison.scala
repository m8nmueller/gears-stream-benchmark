package gears.async.micro

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import gears.async.Async
import gears.async.default.given
import gears.async.stream.BufferedStreamChannel
import scala.compiletime.uninitialized
import gears.async.Future
import gears.async.stream.StreamResult
import scala.util.boundary

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@State(Scope.Benchmark)
class StreamChannelComparison:

  @Param(Array("1", "4", "16", "64", "256", "1024"))
  var bufSize: Int = uninitialized

  val SENDER_CNT = 4
  val READER_CNT = SENDER_CNT
  val SEND_CNT = 10_000

  @Benchmark
  def bufferedChannel() =
    Async.blocking:
      val ch = BufferedStreamChannel[Int](bufSize)
      val senders =
        (1 to SENDER_CNT).map(_ =>
          Future { (1 to SEND_CNT).foreach(i => ch.send(i)) }
        )
      Future:
        senders.awaitAll
        ch.close()
      val readers = (1 to READER_CNT).map(_ =>
        Future:
          var num = 0
          boundary:
            while true do
              ch.readStream() match
                case StreamResult.Data(data) => num = num + data
                case _                       => boundary.break()
          num
      )

      val sum = readers.awaitAll.sum
      assert(sum == SEND_CNT * (SEND_CNT + 1) / 2 * SENDER_CNT)
