package examples

import modelling.CTMC
import modelling.RaftModel.*
import modelling.RaftModel.raftCTMC
import modelling.RaftCTMCSimulation.*
import modelling.Role
import modelling.ServerState
import java.util.Random

@main def runScenarios(): Unit =
  runScenario1()
  runScenario2()
  runScenario3()
  runScenario4()

def runScenario1(): Unit =
  runSimulation("Scenario 1: Normal Startup", numServers = 7, detectSplitVotes = true)

def runScenario2(): Unit =
  runSimulation("Scenario 2: Split Vote", numServers = 6, detectSplitVotes = true)

def runScenario3(): Unit =
  runSimulation("Scenario 3: Leader Crash", numServers = 7, trackCrashes = true)

def runScenario4(): Unit =
  val boostedCrashRate = 1.0

  def isLeaderCrash(before: ServerState, after: ServerState): Boolean =
    before.servers.exists {
      case (id, s) =>
        s.role == Role.Leader &&
          after.servers.get(id).exists(_.role == Role.Crashed)
    }

  val highCrashCTMC = CTMC.ofFunction ((state: ServerState) => // highCrachCTMC is a modified version of raftCTMC where leader crash rate is boosted
    val original = raftCTMC.transitions(state)
    original.map {
      case Action(rate, sNext) if isLeaderCrash(state, sNext) =>
        Action(boostedCrashRate, sNext)
      case other => other
    }.toSet
  )

  runSimulation(
    scenarioName = "Scenario 4: Frequent Crashes",
    numServers = 7,
    simTimeLimit = 60.0,
    trackCrashes = true,
    customCTMC = Some(highCrashCTMC)
  )

def runSimulation(
                   scenarioName: String,
                   numServers: Int,
                   simTimeLimit: Double = 40.0,
                   detectSplitVotes: Boolean = false,
                   trackCrashes: Boolean = false,
                   customCTMC: Option[CTMC[ServerState]] = None
                 ): Unit =
  //println(s"\n--- $scenarioName ---\n")

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

  //println("%-6s | %-50s".format("Time", "Server Roles (id:role@Tn)"))

  trace
    .takeWhile(_.time <= simTimeLimit)
    .foreach { event =>
      val time = event.time
      val state = event.state

      if detectSplitVotes then
        val candidates = state.servers.values.count(_.role == Role.Candidate)
        val leaderCount = state.servers.values.count(_.role == Role.Leader)
        maxSimultaneousCandidates = math.max(maxSimultaneousCandidates, candidates)
        if candidates >= 2 && leaderCount == 0 then
          //println(f"[SPLIT VOTE] $time%.2f → $candidates candidates, no leader")
          splitVoteCount += 1

      if trackCrashes then
        val crashed = state.servers.collect {
          case (id, s) if s.role == Role.Crashed => id
        }.toSet

        if lastLeader.exists(crashed.contains) && lastCrashTime.isEmpty then
          //println(f"[CRASH] $time%.2f → Leader ${lastLeader.get} crashed")
          lastCrashTime = Some(time)
          crashEvents += 1

          lastLeaderStartTime.foreach { start =>
            val tenure = time - start
            totalLeaderTenure += tenure
            leaderTenureCount += 1
          }
          lastLeaderStartTime = None

        if lastCrashTime.isDefined && time - lastCrashTime.get >= 5.0 then
          recoveryEvents += 1
          lastCrashTime = None

      if state.currentTerm != lastTerm then
        termChanges += 1
        lastTerm = state.currentTerm

      state.servers.find(_._2.role == Role.Leader).foreach { case (id, _) =>
        if firstLeaderTime.isEmpty then firstLeaderTime = Some(time)

        if lastLeader.isEmpty || lastLeader.get != id then
          leaderReElections = leaderReElections.updated(id, leaderReElections(id) + 1)
          // println(f"[NEW LEADER] $time%.2f → Server $id elected")

          lastLeader = Some(id)
          lastLeaderStartTime = Some(time)
        leaders += id
      }

      val rolesStr = state.servers.toList
        .sortBy(_._1)
        .map { case (id, s) => s"$id:${s.role}@T${s.term}" }
        .mkString(" ")
      //println(f"$time%-6.2f | $rolesStr")
    }

  lastLeaderStartTime.foreach { start =>
    totalLeaderTenure += simTimeLimit - start
    leaderTenureCount += 1
  }

  println(s"\n--- $scenarioName Summary ---")
  println(s"Total terms entered            : $termChanges")
  println(s"Unique leaders elected         : ${leaders.size}")
  println(s"Leader server IDs              : ${leaders.toList.mkString(", ")}")
  val formattedTime = firstLeaderTime.map(t => f"$t%.2f").getOrElse("never")
  println(s"First leader elected at        : $formattedTime")

  if detectSplitVotes then
        println(s"Split vote events detected    : $splitVoteCount")
        println(s"Max simultaneous candidates   : $maxSimultaneousCandidates")

    if trackCrashes then
      println(s"Total leader crashes           : $crashEvents")
      println(s"Total recoveries observed      : $recoveryEvents")
      println(s"Leader re-elections            : ${leaderReElections.values.sum}")
      val avgTenure = if leaderTenureCount > 0 then totalLeaderTenure / leaderTenureCount else 0.0
      println(f"Average leader tenure (seconds): $avgTenure%.2f")
