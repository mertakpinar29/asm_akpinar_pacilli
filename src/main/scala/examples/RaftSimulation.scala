package examples

import modelling.RaftModel.*
import modelling.RaftCTMCSimulation.*
import java.util.Random

@main def runSimulation(): Unit =
  val initial = initialState(10) // number of servers
  val trace = raftCTMC.newSimulationTrace(initial, new Random)

  trace
    .takeWhile(_.time <= 10.0)
    .foreach { event =>
      val roles = event.state.servers.values.map(s => s"${s.id}:${s.role}").mkString(", ")
      println(f"t=${event.time}%.2f → $roles")
    }
