package examples

import modelling.CTMC
import modelling.RaftModel.*
import modelling.RaftModel.raftCTMC
import modelling.RaftCTMCSimulation.*
import modelling.Role
import modelling.ServerState
import modelling.CTMC.Action
import java.util.Random
import java.io.{File, PrintWriter}

case class SimulationStats(
                            scenario: String,
                            runId: Int,
                            termChanges: Int,
                            uniqueLeaders: Int,
                            firstLeaderTime: Double,
                            splitVoteCount: Int,
                            maxSimultaneousCandidates: Int,
                            crashEvents: Int,
                            recoveryEvents: Int,
                            reElections: Int,
                            avgLeaderTenure: Double
                          )

def runScenarioSimulation(
                           scenarioName: String,
                           numServers: Int,
                           simTimeLimit: Double = 40.0,
                           customCTMC: Option[CTMC[ServerState]] = None,
                           runId: Int = 0
                         ): SimulationStats = {
  val rng = new Random()
  val initial = initialState(numServers)
  val trace = customCTMC.getOrElse(raftCTMC).newSimulationTrace(initial, rng)

  var firstLeaderTime: Option[Double] = None
  var lastTerm = initial.currentTerm
  var termChanges = 0
  var leaders = Set.empty[Int]
  var lastLeader: Option[Int] = None
  var lastLeaderStartTime: Option[Double] = None

  var lastCrashTime: Option[Double] = None
  var crashEvents = 0
  var recoveryEvents = 0
  var leaderReElections = Map.empty[Int, Int].withDefaultValue(0)

  var splitVoteCount = 0
  var maxSimultaneousCandidates = 0

  var totalLeaderTenure = 0.0
  var leaderTenureCount = 0

  trace.takeWhile(_.time <= simTimeLimit).foreach { event =>
    val time = event.time
    val state = event.state

    val candidates = state.servers.values.count(_.role == Role.Candidate)
    val leaderCount = state.servers.values.count(_.role == Role.Leader)
    maxSimultaneousCandidates = math.max(maxSimultaneousCandidates, candidates)
    if (candidates >= 2 && leaderCount == 0) {
      splitVoteCount += 1
    }

    val crashed = state.servers.collect {
      case (id, s) if s.role == Role.Crashed => id
    }.toSet

    if (lastLeader.exists(crashed.contains) && lastCrashTime.isEmpty) {
      lastCrashTime = Some(time)
      crashEvents += 1
      lastLeaderStartTime.foreach { start =>
        totalLeaderTenure += time - start
        leaderTenureCount += 1
      }
      lastLeaderStartTime = None
    }

    if (lastCrashTime.isDefined && time - lastCrashTime.get >= 5.0) {
      recoveryEvents += 1
      lastCrashTime = None
    }

    if (state.currentTerm != lastTerm) {
      termChanges += 1
      lastTerm = state.currentTerm
    }

    state.servers.find(_._2.role == Role.Leader).foreach { case (id, _) =>
      if (firstLeaderTime.isEmpty) firstLeaderTime = Some(time)
      if (lastLeader.isEmpty || lastLeader.get != id) {
        leaderReElections = leaderReElections.updated(id, leaderReElections(id) + 1)
        lastLeader = Some(id)
        lastLeaderStartTime = Some(time)
      }
      leaders += id
    }
  }

  lastLeaderStartTime.foreach { start =>
    totalLeaderTenure += simTimeLimit - start
    leaderTenureCount += 1
  }

  SimulationStats(
    scenario = scenarioName,
    runId = runId,
    termChanges = termChanges,
    uniqueLeaders = leaders.size,
    firstLeaderTime = firstLeaderTime.getOrElse(-1.0),
    splitVoteCount = splitVoteCount,
    maxSimultaneousCandidates = maxSimultaneousCandidates,
    crashEvents = crashEvents,
    recoveryEvents = recoveryEvents,
    reElections = leaderReElections.values.sum,
    avgLeaderTenure = if leaderTenureCount > 0 then totalLeaderTenure / leaderTenureCount else 0.0
  )
}


def runAllScenarios(repeats: Int): Seq[SimulationStats] = {
  val boostedCrashRate = 1.0

  def isLeaderCrash(before: ServerState, after: ServerState): Boolean =
    before.servers.exists { case (id, s) =>
      s.role == Role.Leader && after.servers.get(id).exists(_.role == Role.Crashed)
    }

  val highCrashCTMC = CTMC.ofFunction((state: ServerState) =>
    raftCTMC.transitions(state).map {
      case Action(rate, sNext) if isLeaderCrash(state, sNext) =>
        Action(boostedCrashRate, sNext)
      case other => other
    }.toSet
  )

  val results = for (i <- 1 to repeats) yield {
    Seq(
      runScenarioSimulation("Normal Startup", 7, runId = i),
      runScenarioSimulation("Split Vote", 6, runId = i),
      runScenarioSimulation("Frequent Crashes", 7, 60.0, customCTMC = Some(highCrashCTMC), runId = i)
    )
  }
  results.flatten
}

def saveResultsToCSV(stats: Seq[SimulationStats], filePath: String): Unit = {
  val writer = new PrintWriter(new File(filePath))
  val header = "scenario,runId,termChanges,uniqueLeaders,firstLeaderTime,splitVoteCount,maxSimultaneousCandidates,crashEvents,recoveryEvents,reElections,avgLeaderTenure"
  writer.println(header)
  stats.foreach { s =>
    writer.println(s"${s.scenario},${s.runId},${s.termChanges},${s.uniqueLeaders},${s.firstLeaderTime},${s.splitVoteCount},${s.maxSimultaneousCandidates},${s.crashEvents},${s.recoveryEvents},${s.reElections},${s.avgLeaderTenure}")
  }
  writer.close()
}

@main def runAndSave(): Unit = {
  val results = runAllScenarios(30)
  saveResultsToCSV(results, "raft_simulation_results.csv")
}
