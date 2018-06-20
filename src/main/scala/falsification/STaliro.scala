package falsification

import hybrid.Signal
import hybrid.System
import hybrid.Trace
import linear.Vector
import mtl.Always
import mtl.And
import mtl.Equal
import mtl.Eventually
import mtl.False
import mtl.Formula
import mtl.Less
import mtl.LessEqual
import mtl.NotEqual
import mtl.Or
import mtl.Robustness
import mtl.Term
import mtl.True
import mtl.Not
import mtl.Implies
import mtl.Proposition

object STaliro {
  case class dummy(prefix: String) extends Falsification {
    def identification = "S-Taliro (print formulas only)"
    def params = Seq()

    var pi = 0
    var fi = 0
    var props = Map[Proposition, Int]()

    def print(prop: Proposition): String = {
      if (!(props contains prop)) {
        props += (prop -> pi)
        println("props(" + pi + ").str = '" + prefix + pi + "'; % " + prop)
        pi += 1
      }

      val i = props(prop)
      val name = prefix + i
      name
    }

    def print(phi: Formula): String = phi match {
      case True => "true"
      case False => "false"
      case prop: Proposition => print(prop)
      case Not(phi) => "(! " + print(phi) + ")"
      case Or(phi, psi) => "(" + print(phi) + " \\/ " + print(psi) + ")"
      case And(phi, psi) => "(" + print(phi) + " /\\ " + print(psi) + ")"
      case Implies(phi, psi) => "(" + print(phi) + " -> " + print(psi) + ")"
      case Always(t0, t1, phi) => "([]_[" + t0 + "," + t1 + "] " + print(phi) + ")"
      case Eventually(t0, t1, phi) => "(<>_[" + t0 + "," + t1 + "] " + print(phi) + ")"
    }

    def search(sys: System, phi: Formula): (Result, Statistics) = {
      println(sys.name + "_phi" + fi + "' = " + print(phi) + "';")
      fi += 1

      val i = Vector.empty
      val us = Signal((0, Vector.zero(sys.inports.length)))
      val ys = Signal((0, Vector.zero(sys.outports.length)))
      val tr = Trace(i, us, Signal.empty)
      val rs = Robustness(Array((0.0, 0.0)))
      val res = Result(tr, rs)
      val stat = Statistics.empty

      (res, stat)
    }
  }
}