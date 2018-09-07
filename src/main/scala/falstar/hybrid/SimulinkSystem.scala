package falstar.hybrid

import scala.io.StdIn

import com.mathworks.engine.MatlabEngine

import falstar.linear.Vector
import falstar.util.Timer
import java.io.OutputStreamWriter
import java.io.BufferedWriter

case class SimulinkSystem(
  path: String, name: String,
  params: Seq[String],
  inputs: Seq[String],
  outputs: Seq[String],
  load: Seq[String] = Nil)
  extends System {

  import Simulink._

  lazy val initialized = {
    object setup extends Timer

    setup.start()
    assert(engine != null)

    print("initializing '" + name + "' ...")

    eval("addpath('" + path + "')")
    eval("load_system('" + name + "')")

    println("WARNING: set_param not implemented")
    // eval("set_param('" + name + "'," + params.map { case (k, v) => k + "," + v }.mkString(", ") + ")")

    for (file <- load)
      eval("load('" + file + "')")

    if (accelerated) {
      println(" compiling ")
      // eval("accelbuild('" + name + "')")
      eval("set_param('" + name + "','SimulationMode','rapid')")
    }

    setup.stop()
    println(" done (" + setup.seconds + "s)")

    true
  }

  def sim(ps: Input, us: Signal, T: Time) = {

    for ((x, a) <- (params, ps.data).zipped)
      eval(x + " = " + a)

    assert(initialized)

    // println("simulate " + name + " from " + 0 + " to " + T)
    // println("simulate " + name + " to " + T + " with inputs " + us /*.collapse*/ .mkString(" "))

    val t__ = us map { case (t, u) => Array(t) }
    val u__ = us map { case (t, u) => u.data }

    // NOTE: need to duplicate last entry in the input signal
    val U = u__.last
    eval("t__ = [" + t__.map(_.mkString(" ")).mkString("; ") + "; " + T + "]")
    eval("u__ = [" + u__.map(_.mkString(" ")).mkString("; ") + "; " + U.mkString(" ") + "]")

    // println("t__ = [" + t__.map(_.mkString(" ")).mkString("; ") + "; " + T + "]")
    // println("u__ = [" + u__.map(_.mkString(" ")).mkString("; ") + "; " + U.mkString(" ") + "]")

    eval("result = sim('" + name + "', 'StopTime', '" + T + "'" + ")")

    eval("tout = result.tout")
    eval("yout = result.yout.signals")
    eval("nout = size(yout, 2)")

    val ts: Array[Time] = get("tout")
    val nout: Double = get("nout")

    val yout = for (i <- 1 to nout.toInt) yield {
      val n = "y" + i
      eval(n + " = yout(" + i + ").values")
      val yi: Array[Double] = get(n)
      yi
    }

    val ys = yout.transpose

    val zs = Array.tabulate(ts.length) {
      i =>
        val t = ts(i)
        val y = Vector(ys(i): _*)
        (t, y)
    }

    assert(Math.abs(ts.last - T) < 0.1, "inconcistent simulink stopping time " + ts.last + " expected " + T)

    Trace(us, zs)
  }
}

object Simulink {
  var accelerated = true
  var verbose = false
  var connected = false

  val stream = if (verbose)
    new BufferedWriter(new OutputStreamWriter(System.out))
  else
    MatlabEngine.NULL_WRITER

  val threshold = 0.0
  val separator = "-" * 20
  val nextResult = falstar.util.numbers(0)

  def main(args: Array[String]) {
    engine

    var run = true
    while (run) {
      try {
        val line = StdIn.readLine("> ")
        if (line.isEmpty) run = false
        else engine.eval(line)
      } catch {
        case _: Throwable =>
          run = false
      }
    }
    disconnect()
  }

  def eval(line: String) {
    val out = stream
    val err = stream
    if (verbose) println("matlab> " + line)
    engine.eval(line + ";", out, err)
  }

  def get[T](name: String): T = {
    engine.getVariable(name)
  }

  lazy val engine = {
    object timer extends Timer

    timer.start()
    print("starting matlab ...")

    val res = try {
      val res = MatlabEngine.connectMatlab()
      println(" connected (" + timer.seconds + "s)")
      res
    } catch {
      case _: Throwable =>
        val res = MatlabEngine.startMatlab()
        println(" done (" + timer.seconds + "s)")
        res
    }

    connected = true
    res
  }

  def disconnect() {
    if (connected)
      try { engine.disconnect() }
      finally {}
  }
}