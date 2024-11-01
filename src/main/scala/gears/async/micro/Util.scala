package gears.async.micro

import io.reactivex.rxjava3.{schedulers => rxschedulers}
import io.reactivex.rxjava3.core.Flowable
import gears.async.stream.StreamOps
import cats.effect.Concurrent
import cats.effect.IO
import org.openjdk.jmh.annotations.{State, Param, Scope}
import scala.compiletime.uninitialized

// === types
type StreamFS2[T] = fs2.Stream[IO, T]

// === states for buffer sizes
trait AnyAsyncState:
  def bufSize: Int

trait AnyAsyncGearsState extends AnyAsyncState:
  def parallelism: Int

extension (inline a: AnyAsyncState) inline def toShift[A[_]](inline fn: Int => AsyncShift[A]) = fn(a.bufSize)
extension (a: AnyAsyncGearsState)
  inline def toShift[A[X] <: StreamOps[X] { type ThisStream[Y] <: A[Y] }]() = gearsShift[A](a.bufSize, a.parallelism)

// === shift definitions
trait AsyncShift[A[_]]:
  extension [T](a: A[T])
    def shift(bufSize: Int): A[T]
    def shift(): A[T]

def noShift[A[_]](): AsyncShift[A] = new AsyncShift:
  extension [T](a: A[T])
    def shift(bufSize: Int) = a
    def shift() = a

def rxShift(bufSize: Int): AsyncShift[Flowable] = new AsyncShift:
  val sched = rxschedulers.Schedulers.computation()
  extension [T](a: Flowable[T])
    def shift(bufSize: Int): Flowable[T] = a.observeOn(sched, false, bufSize)
    def shift(): Flowable[T] = shift(bufSize)

def gearsShift[A[X] <: StreamOps[X] { type ThisStream[Y] <: A[Y] }](bufSize: Int, parallelism: Int): AsyncShift[A] =
  new AsyncShift:
    extension [T](a: A[T])
      def shift(bufSize: Int): A[T] = a.parallel(bufSize, parallelism)
      def shift(): A[T] = shift(bufSize)

def fs2Shift(bufSize: Int): AsyncShift[StreamFS2] = new AsyncShift:
  extension [T](a: StreamFS2[T])
    def shift(bufSize: Int): StreamFS2[T] = a.prefetchN(bufSize)
    def shift(): StreamFS2[T] = shift(bufSize)
