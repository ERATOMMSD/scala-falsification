package falsification

import scala.collection.mutable.ArrayBuffer

import hybrid.Duration
import hybrid.Input
import hybrid.Region
import hybrid.Score
import hybrid.Signal
import hybrid.System
import hybrid.Time
import mtl.Formula
import mtl.Robustness
import mtl.Value
import util.Combinatorics
import util.Proportional
import util.Uniform

class Bin(val level: Int, actions: Seq[(Input, Duration)]) {
  val todo = ArrayBuffer[(Input, Duration)](actions: _*)
  val done = ArrayBuffer[(Input, BinnedNode)]()

  val fail = ArrayBuffer[(Input, BinnedNode)]()
  val leaf = ArrayBuffer[(Input, BinnedNode)]()

  def size = todo.size + done.size

  // the probability is proportional to the "good" edges,
  // i.e., if most actions are fruitless,
  // then the probability decreases as size goes tozero
  def probability = Math.pow(2, -level) * size / actions.size

  def sample(e: Double): Either[(Input, Duration), (Input, BinnedNode)] = {
    val k = 3 * e / (1 - e)

    val p1 = if (todo.isEmpty) 0 else k
    val p2 = if (done.isEmpty) 0 else 1
    val p3 = if (done.isEmpty) 0 else 1
    val p4 = if (done.isEmpty) 0 else 1

    val choice = Proportional.sample(p1, p2, p3, p4)

    choice match {
      case 0 => Left(Uniform.pick(todo))
      case 1 => Right(Uniform.from(done))
      case 2 => Right(Uniform.minBy(done)(_._2.local_score))
      case 3 => Right(Uniform.minBy(done)(_._2.global_score))
    }
  }
}

class BinnedNode(val time: Time, levels: Seq[Seq[(Input, Duration)]]) {
  val bins = ArrayBuffer[Bin](init: _*)
  //  var score: Score = Score.MaxValue
  var result: Result = null
  var visited = 0

  var exhausted = false

  var local_score = Score.MaxValue
  var global_score = Score.MaxValue

  def update(result: Result) = {
    if (this.result == null || result.score < this.result.score) {
      this.result = result
      this.global_score = result.score
    }
  }

  def init = levels.zipWithIndex.map {
    case (actions, level) =>
      new Bin(level, actions)
  }

  def isEmpty = {
    bins forall (_.size == 0)
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
    def update(root: BinnedNode)
  }

  object Observer {
    object default extends Observer {
      def reset() {}
      def update(root: BinnedNode) {}
    }
  }

  var verbose: Boolean = false
  var observer: Observer = Observer.default

  def level(n: Int, dt: Duration, in: Region): Seq[(Input, Duration)] = {
    for (ps <- Combinatorics.splits(0, in.dimensions, n)) yield {
      val u = in.split(ps)
      (u, dt)
    }
  }

  case class falsification(controlpoints: Seq[Int], exploration: Double, budget: Int) extends Falsification with WithStatistics {
    override def productPrefix = "Adaptive.falsification"

    def identification = "adaptive"

    var explore = 0
    var exploit = 0
    val c = Math.sqrt(2)

    val params = Seq(
      "control points" -> controlpoints.mkString(" "),
      "exploration ratio" -> exploration,
      "budget" -> budget)

    def search(sys: System, phi: Formula, T: Time, sim: (Signal, Time) => Result): Result = {
      Falsification.observer.reset(phi)
      Adaptive.observer.reset()

      val in = sys.in

      val levels = controlpoints.zipWithIndex map {
        case (cp, i) => level(i, T / cp, in)
      }

      def playout(us: Signal): Result = {
        val res = sim(us, T)
        res
      }

      def sample(node: BinnedNode, us: Signal): Result = {
        val t = node.time
        node.visited += 1

        if (node.isEmpty) {
          node.result
        } else node.sample(exploration) match {
          case (bin, Left((u, dt))) if T <= t + dt =>
            explore += 1

            val result = playout(us ++ Signal.point(t, u))
            // Falsification.observer.update(result)
            val dummy = new BinnedNode(-1, Seq())
            dummy.local_score = result.score
            bin.leaf += ((u, dummy))
            result

          case (bin, Left((u, dt))) =>
            explore += 1
            //            print(bin.level + "@" + t + " ")
            // create new child node and a trace
            val child = new BinnedNode(t + dt, levels)
            val result = sample(child, us ++ Signal.point(t, u))
            val Result(tr, rs) = result

            // check feasibility
            val pr = tr until child.time

            val Value(lower, upper) = Robustness.bounds(phi, pr.us, pr.ys)

            if (lower < 0) {
              Falsification.observer.update(result until child.time)
              bin.done += ((u, child))
              child.local_score = upper
              child.global_score = rs.score
              child.result = result
              if (verbose) println("good: " + lower + " ... " + upper + " at time " + child.time)
            } else {
              //                Falsification.observer.update(child.result)
              child.local_score = result.score
              bin.fail += ((u, child))
              if (verbose) println("bad: " + lower + " ... " + upper + " at time " + child.time)
            }

            node update result
            result

          case (bin, Right((u, child))) =>
            exploit += 1
            val result = sample(child, us ++ Signal.point(t, u))
            node update result
            result
        }
      }

      val root = new BinnedNode(0, levels)
      root.result = Result.empty
      var solved = false

      for (i <- 0 until budget if !solved) {
        sample(root, Signal.empty)
        solved |= root.result.score < 0

        print(".")
        Adaptive.observer.update(root)
      }
      println()

      val result = root.result

      if (solved) {
        if (verbose) println("solved :)")
        Falsification.observer.reset(phi)
        Falsification.observer.update(result)
      }

      result
    }
  }
}