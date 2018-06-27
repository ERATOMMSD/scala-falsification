package falsification

import hybrid.Config
import hybrid.Constant
import hybrid.PiecewiseConstant
import hybrid.Signal
import hybrid.Signal.SignalOps
import hybrid.SimulinkSystem
import hybrid.System
import hybrid.Trace
import hybrid.Value
import linear.Vector
import mtl.Always
import mtl.And
import mtl.Const
import mtl.DividedBy
import mtl.Equal
import mtl.Eventually
import mtl.False
import mtl.Formula
import mtl.Implies
import mtl.InPort
import mtl.Less
import mtl.LessEqual
import mtl.Minus
import mtl.Not
import mtl.NotEqual
import mtl.Or
import mtl.Plus
import mtl.Port
import mtl.Robustness
import mtl.Term
import mtl.Times
import mtl.Transform
import mtl.True
import util.Probability

object Breach {
  def print(tm: Term): String = tm match {
    case c: Const => c.toString
    case p: Port => p.name + "[t]"
    case Plus(left, right) => "(" + print(left) + " + " + print(right) + ")"
    case Minus(left, right) => "(" + print(left) + " - " + print(right) + ")"
    case Times(left, right) => "(" + print(left) + " * " + print(right) + ")"
    case DividedBy(left, right) => "(" + print(left) + " / " + print(right) + ")"
    case Transform(tm, _, f) if !f.isEmpty => f + "(" + print(tm) + ")"
  }

  def print(phi: Formula): String = phi match {
    case True => "true"
    case False => "false"

    case Less(left, right) => "(" + print(left) + " < " + print(right) + ")"
    case LessEqual(left, right) => "(" + print(left) + " <= " + print(right) + ")"
    case Equal(left, right) => "(" + print(left) + " == " + print(right) + ")"
    case NotEqual(left, right) => "(not (" + print(left) + " == " + print(right) + "))"

    case Not(phi) => "(not " + print(phi) + ")"
    case Or(phi, psi) => "(" + print(phi) + " or " + print(psi) + ")"
    case And(phi, psi) => "(" + print(phi) + " and " + print(psi) + ")"
    case Implies(phi, psi) => "(" + print(phi) + " => " + print(psi) + ")"

    case Always(t0, t1, phi) => "(alw_[" + t0 + "," + t1 + "] " + print(phi) + ")"
    case Eventually(t0, t1, phi) => "(ev_[" + t0 + "," + t1 + "] " + print(phi) + ")"
  }

  case class dummy(cp: Int) extends Falsification {
    def identification = "Breach (print)"
    def params = Seq()

    def search(sys: System, cfg: Config, _phi: Formula): (Result, Statistics) = {
      println(print(_phi))

      val T = _phi.T
      val in = cfg.in(sys.inputs)
      val phi = print(_phi)

      println("sys = BreachSimulinkSystem('" + sys.name + "')")
      println("phi = STL_Formula('" + phi + "', '" + phi + "')")

      println("gen.type = 'UniStep';")

      println("gen.cp = " + cp + ";")
      println("sys.SetInputGen(gen)")
      println("sys.Sys.tspan = 0:" + T + ";")

      for (k <- 0 until cp) {
        for (InPort(name, i) <- sys.inports) yield {
          println("sys.SetParamRanges({'" + name + "_u" + k + "'}, [" + in.left(i) + " " + in.right(i) + "])")
        }
      }

      println()

      println("problem = FalsificationProblem(sys, phi)")
      println("problem.max_obj_eval = 100;")
      println("problem.max_time = 600; % ten minutes should be enough")
      println("problem.setup_solver('cmaes')")
      println("problem.solver_options.Seed = randi(1000)")
      println("problem.solve()")
      println()

      val us = Signal((0, Vector.zero(sys.inports.length)))
      val ys = Signal((0, Vector.zero(sys.outports.length)))
      val tr = Trace(us, Signal.empty)
      val rs = Robustness(Array((0.0, 0.0)))
      val res = Result(tr, rs)
      val stat = Statistics.empty
      (res, stat)
    }
  }

  case class falsification(controlpoints: Int, solver: String, budget: Int) extends Falsification {
    def identification = "Breach"

    def params = Seq(
      "control points" -> controlpoints,
      "solver" -> solver,
      "budget" -> budget)

    def search(sys: System, cfg: Config, _phi: Formula): (Result, Statistics) = sys match {
      case sys @ SimulinkSystem(path, name, params, inputs, outputs, load) =>
        val dt = 0.01
        val T = _phi.T

        val in = cfg.in(sys.inputs)
        val params = sys.params
        val inports = sys.inports

        val phi = print(_phi)

        import hybrid.Simulink.eval
        import hybrid.Simulink.get

        // set params and variables
        assert(sys.initialized)

        eval("InitBreach")

        for (name <- params) {
          cfg.params(name) match {
            case Value(x) =>
              eval(name + " = " + x)
          }
        }

        eval("sys = BreachSimulinkSystem('" + sys.name + "')")
        eval("sys.Sys.tspan = 0:" + T)
        eval("phi = STL_Formula('" + phi + "', '" + phi + "')")

        for (InPort(name, i) <- inports) {
          cfg.inputs(name) match {
            case Value(x) =>
            case Constant(min, max) =>
              eval("gen_" + name + " = constant_signal_gen({'" + name + "'})")
            case PiecewiseConstant(min, max) =>
              eval("gen_" + name + " = fixed_cp_signal_gen({'" + name + "'}, " + controlpoints + ")")
          }
        }

        val gens = inports map ("gen_" + _.name)
        eval("gen = BreachSignalGen(" + gens.mkString("{", ", ", "}") + ")")
        eval("sys.SetInputGen(gen)")

        for (InPort(name, i) <- inports) {
          cfg.inputs(name) match {
            case Value(x) =>
            case Constant(min, max) =>
              eval("sys.SetParamRanges({'" + name + "_u0'}, [" + in.left(i) + " " + in.right(i) + "])")

            case PiecewiseConstant(min, max) =>
              for (k <- 0 until controlpoints) {
                eval("sys.SetParamRanges({'" + name + "_u" + k + "'}, [" + in.left(i) + " " + in.right(i) + "])")
              }
          }
        }

        eval("problem = FalsificationProblem(sys, phi)")
        eval("problem.max_obj_eval = 100")
        eval("problem.max_time = 0")
        eval("problem.setup_solver('cmaes')")
        eval("problem.solver_options.Seed = " + Probability.seed)
        eval("problem.solve()")
        Probability.setNextDeterministicSeed()

        eval("time = problem.time_spent")
        eval("sims = problem.nb_obj_eval")
        eval("score = problem.obj_best")

        eval("best = problem.BrSet_Best")

        val score: Double = get("score")
        val sims: Double = get("sims")
        val time: Double = get("time")

        val ts: Array[Double] = get("t__")
        val um: Array[Array[Double]] = get("u__")
        val uv = um map (Vector(_: _*))
        val us = ts zip uv

        // fake the result
        val tr = Trace(us.collapse, Signal.empty)
        val rs = Robustness(Array((0.0, score)))

        val res = Result(tr, rs)
        val stats = Statistics(sims.toInt, time.toLong, 0)

        (res, stats)

      case _ =>
        throw new Exception("not a simulink model")
    }
  }
}
