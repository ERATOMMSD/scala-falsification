package falstar.falsification

import scala.collection.mutable.ArrayBuffer

import falstar.hybrid.Config
import falstar.hybrid.Duration
import falstar.hybrid.Input
import falstar.hybrid.Region
import falstar.hybrid.Score
import falstar.hybrid.Signal
import falstar.hybrid.System
import falstar.hybrid.Time
import falstar.linear.Vector
import falstar.mtl.Formula
import falstar.mtl.Robustness
import falstar.util.Combinatorics
import falstar.util.Proportional
import falstar.util.Uniform
import falstar.hybrid.InputType
import falstar.hybrid.Constant
import falstar.hybrid.PiecewiseConstant
import falstar.hybrid.Value
import falstar.hybrid.Trace

sealed trait Strategy[+A]

sealed trait Selection
object Selection {
  case object uniform extends Selection
  case object prefix extends Selection
  case object suffix extends Selection
}

case class Explore[A](a: A, forced: Boolean) extends Strategy[A]
case class Exploit(un: (Input, Node), s: Selection, forced: Boolean) extends Strategy[Nothing]

class Bin[A](val level: Int, actions: Seq[A]) {
  val todo = ArrayBuffer[A](actions: _*)
  val done = ArrayBuffer[(Input, Node)]()

  val fail = ArrayBuffer[(Input, Node)]()
  val leaf = ArrayBuffer[(Input, Node)]()

  def size = todo.size + done.size

  // the probability is proportional to the "good" edges,
  // i.e., if most actions are fruitless,
  // then the probability decreases as size goes tozero
  def probability = {
    assert(!actions.isEmpty)
    Math.pow(2, -level) * size / actions.size
  }

  def sample(e: Double): Strategy[A] = {
    val k = 3 * e / (1 - e)

    val p1 = if (todo.isEmpty) 0 else k
    val p2 = if (done.isEmpty) 0 else 1
    val p3 = if (done.isEmpty) 0 else 1
    val p4 = if (done.isEmpty) 0 else 1

    val choice = Proportional.sample(p1, p2, p3, p4)

    choice match {
      case 0 =>
        Explore(Uniform.pick(todo), forced = done.isEmpty)
      case 1 =>
        Exploit(Uniform.from(done), Selection.uniform, forced = todo.isEmpty)
      case 2 =>
        Exploit(Uniform.minBy(done)(_._2.local_score), Selection.prefix, forced = todo.isEmpty)
      case 3 =>
        Exploit(Uniform.minBy(done)(_._2.global_score), Selection.suffix, forced = todo.isEmpty)
    }
  }
}

class Node(val time: Time, val levels: Seq[Seq[(Input, Duration)]]) {
  var visited = 0
  var exhausted = false
  var local_score = Score.MaxValue
  var global_score = Score.MaxValue

  val bins = ArrayBuffer[Bin[(Input, Duration)]](init: _*)

  def init = levels.zipWithIndex.collect {
    case (actions, level) if !actions.isEmpty =>
      new Bin(level, actions)
  }

  def isEmpty = {
    bins forall (_.size == 0)
  }

  def update(result: Result) = {
    if (result.score < global_score) {
      global_score = result.score
    }
  }

  def sample(e: Double) = {
    assert(!isEmpty)
    val bin = Proportional.sample(bins)(_.probability)
    (bin, bin.sample(e))
  }
}

object Adaptive {
  trait Observer {
    def reset()
    def update(root: Node)
  }

  object Observer {
    object default extends Observer {
      def reset() {}
      def update(root: Node) {}
    }
  }

  var verbose: Boolean = false
  var observer: Observer = Observer.default

  // second call: just set some inputs to constant
  def level(l: Int, dt: Duration, inputs: Seq[(String, InputType)]) = {
    val varying = inputs collect {
      case (name, _: Constant) => name
      case (name, _: PiecewiseConstant) => name
    }

    val splits = Combinatorics.splits(varying.toList, l)

    for (split <- splits) yield {
      val u = Vector(inputs map {
        case (name, Value(value)) =>
          value
        case (name, Constant(min, max)) =>
          val p = split(name)
          min + p * (max - min)
        case (name, PiecewiseConstant(min, max)) =>
          val p = split(name)
          min + p * (max - min)
      }: _*)

      (u, dt)
    }
  }

  def levels(cps: Seq[Int], T: Time, inputs: Seq[(String, InputType)]) = {
    cps.zipWithIndex map {
      case (cp, l) => level(l, T / cp, inputs)
    }
  }

  def fix_constants(u: Input, inputs: Seq[(String, InputType)]) = {
    inputs.zipWithIndex map {
      case ((name, _: Constant), i) => (name, Value(u(i)))
      case ((name, typ), i) => (name, typ)
    }
  }

  case class falsification(controlpoints: Seq[Int], exploration: Double, budget: Int) extends Falsification with WithStatistics {
    override def productPrefix = "Adaptive.falsification"

    def identification = "adaptive"

    class Statistics {
      var failed_explore_choice = 0
      var failed_uniform_choice = 0
      var failed_prefix_choice = 0
      var failed_suffix_choice = 0
      var failed_explore_forced = 0
      var failed_uniform_forced = 0
      var failed_prefix_forced = 0
      var failed_suffix_forced = 0

      var success_explore_choice = 0
      var success_uniform_choice = 0
      var success_prefix_choice = 0
      var success_suffix_choice = 0
      var success_explore_forced = 0
      var success_uniform_forced = 0
      var success_prefix_forced = 0
      var success_suffix_forced = 0

      def +=(forced: Boolean, sel: Option[Selection], success: Boolean) = (success, sel, forced) match {
        case (false, None, false) => failed_explore_choice += 1
        case (false, Some(Selection.uniform), false) => failed_uniform_choice += 1
        case (false, Some(Selection.prefix), false) => failed_prefix_choice += 1
        case (false, Some(Selection.suffix), false) => failed_suffix_choice += 1
        case (false, None, true) => failed_explore_forced += 1
        case (false, Some(Selection.uniform), true) => failed_uniform_forced += 1
        case (false, Some(Selection.prefix), true) => failed_prefix_forced += 1
        case (false, Some(Selection.suffix), true) => failed_suffix_forced += 1

        case (true, None, false) => success_explore_choice += 1
        case (true, Some(Selection.uniform), false) => success_uniform_choice += 1
        case (true, Some(Selection.prefix), false) => success_prefix_choice += 1
        case (true, Some(Selection.suffix), false) => success_suffix_choice += 1
        case (true, None, true) => success_explore_forced += 1
        case (true, Some(Selection.uniform), true) => success_uniform_forced += 1
        case (true, Some(Selection.prefix), true) => success_prefix_forced += 1
        case (true, Some(Selection.suffix), true) => success_suffix_forced += 1
      }
    }

    var statistics = new Statistics

    def params = Seq(
      "control points" -> controlpoints.mkString(" "),
      "exploration ratio" -> exploration,
      "budget" -> budget,

      "failed_explore_choice" -> statistics.failed_explore_choice,
      "failed_uniform_choice" -> statistics.failed_uniform_choice,
      "failed_prefix_choice" -> statistics.failed_prefix_choice,
      "failed_suffix_choice" -> statistics.failed_suffix_choice,
      "failed_explore_forced" -> statistics.failed_explore_forced,
      "failed_uniform_forced" -> statistics.failed_uniform_forced,
      "failed_prefix_forced" -> statistics.failed_prefix_forced,
      "failed_suffix_forced" -> statistics.failed_suffix_forced,

      "success_explore_choice" -> statistics.success_explore_choice,
      "success_uniform_choice" -> statistics.success_uniform_choice,
      "success_prefix_choice" -> statistics.success_prefix_choice,
      "success_suffix_choice" -> statistics.success_suffix_choice,
      "success_explore_forced" -> statistics.success_explore_forced,
      "success_uniform_forced" -> statistics.success_uniform_forced,
      "success_prefix_forced" -> statistics.success_prefix_forced,
      "success_suffix_forced" -> statistics.success_suffix_forced)

    def search(sys: System, cfg: Config, phi: Formula, T: Time, sim: (Input, Signal, Time) => Result): Result = {
      Falsification.observer.reset(phi)
      Adaptive.observer.reset()

      statistics = new Statistics

      val pn = cfg.pn(sys.params)
      val in = cfg.in(sys.inputs)
      val cs = cfg.cs(sys.inputs)

      val inputs = sys.inputs map {
        name => (name, cfg.inputs(name))
      }

      val ps = pn.sample // Value or Constant, established by Parser.configureSystem

      val root_levels = levels(controlpoints, T, inputs)
      val root = new Node(0, root_levels)
      var solved = false
      var result = Result.empty

      for (i <- 0 until budget if !solved) {
        result = sample(root, Signal.empty, inputs)
        solved |= result.isFalsified

        print(".")
        // Adaptive.observer.update(root)
      }
      println()

      if (solved) {
        if (verbose) println("solved :)")
        Falsification.observer.reset(phi)
        Falsification.observer.update(result)
      }

      def playout(ps: Input, us: Signal): Result = {
        val res = sim(ps, us, T)
        res
      }

      def sample(node: Node, us: Signal, inputs: Seq[(String, InputType)]): Result = {
        val t = node.time
        node.visited += 1

        if (node.isEmpty) {
          Result.empty
        } else node.sample(exploration) match {
          case (bin, Explore((u, dt), forced)) if T <= t + dt =>
            val result = playout(ps, us ++ Signal.point(t, u))
            // Falsification.observer.update(result)
            val dummy = new Node(-1, Seq())
            dummy.local_score = result.score
            bin.leaf += ((u, dummy))

            statistics += (forced, None, result.isFalsified)

            result

          case (bin, Explore((u, dt), forced)) =>
            //            print(bin.level + "@" + t + " ")
            // create new child node and a trace
            val new_inputs = fix_constants(u, inputs)
            val new_levels = levels(controlpoints, T, new_inputs)

            val child = new Node(t + dt, new_levels)
            val result = sample(child, us ++ Signal.point(t, u), new_inputs)
            val Result(tr, rs) = result

            // check feasibility
            val pr = tr until child.time

            val falstar.mtl.Value(lower, upper) = Robustness.bounds(phi, pr.us, pr.ys)

            if (lower < 0) {
              Falsification.observer.update(result until child.time)
              bin.done += ((u, child))
              child.local_score = upper
              child.global_score = rs.score
              if (verbose) println("good: " + lower + " ... " + upper + " at time " + child.time)
            } else {
              //                Falsification.observer.update(child.result)
              child.local_score = result.score
              bin.fail += ((u, child))
              if (verbose) println("bad: " + lower + " ... " + upper + " at time " + child.time)
            }

            node update result

            statistics += (forced, None, result.isFalsified)

            result

          case (bin, Exploit((u, child), sel, forced)) =>
            val result = sample(child, us ++ Signal.point(t, u), inputs)
            node update result

            statistics += (forced, Some(sel), result.isFalsified)

            result
        }
      }

      result
    }
  }
}
