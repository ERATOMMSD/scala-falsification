package falstar.hybrid

import falstar.linear.Matrix
import falstar.linear.Vector
import scala.collection.mutable.ArrayBuffer
import falstar.linear.Integrator

case class ContinuousSystem(
  name: String,
  x0: State,
  params: Seq[String],
  inputs: Seq[String],
  outputs: Seq[String],
  flow: Flow,
  dt: Duration)
  extends System {

  def sim(ps: Input, us: Signal, T: Time): Trace = {
    sim(0, x0, us, T)
  }

  def sim(t0: Time, x0: State, us: Signal, T: Time): Trace = {
    val hmin = 0.001
    val hmax = dt

    var t = t0
    var x = x0
    var i = 0
    val n = us.length
    val xs = new ArrayBuffer[(Time, State)]()

    xs += ((t0, x0))

    while (i < n && t < T) {
      val (_, ui) = us(i)
      val (ti, _) = if (i + 1 < n) us(i + 1) else (T, us(0))
      // val dt = ti - t
      // val tx = Integrator.rk4(flow, t, dt, x, ui)
      val tx = Integrator.dp45(flow, t, dt, x, ui, hmin, hmax,
        (t: Time, x: State) => xs += ((t, x)))
      t = tx._1
      x = tx._2
      if (t >= ti) i += 1
      //        xs += tx
    }

    val ys = xs.toArray[(Time, State)]
    Trace(us, ys)
  }
}

object Flow {
  def linear(A: Matrix): Flow = {
    (t: Time, x: State, u: Input) => A * x
  }

  def linear(A: Matrix, B: Matrix): Flow = {
    (t: Time, x: State, u: Input) => A * x + B * u
  }
}

object ContinuousSystem {
  def linear(name: String, x0: State, A: Matrix, dt: Duration) = {
    ContinuousSystem(name, x0, Seq(), Seq(), Seq(), Flow.linear(A), dt)
  }

  def linear(name: String, x0: State, params: Seq[String], inputs: Seq[String], outputs: Seq[String], A: Matrix, B: Matrix, dt: Duration) = {
    ContinuousSystem(name, x0, params, inputs, outputs, Flow.linear(A, B), dt)
  }
}
