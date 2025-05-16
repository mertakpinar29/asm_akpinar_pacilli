import scala.util.Random
import scala.collection.mutable
import scala.math.log

object RaftCTMCSimulation extends App {

  sealed trait State
  case object Follower extends State
  case object Candidate extends State
  case object Leader extends State
  case object Crashed extends State

  val random = new Random()

  // Define transition rates
  val rElection = 0.01
  val rWin = 0.02
  val rHeartbeat = 0.05
  val rDiscover = 0.01
  val rCrash = 0.005
  val rRecover = 0.01

  // Current simulation state
  var currentState: State = Follower
  var currentTime: Double = 0.0
  val maxTime = 500.0

  // State history for analysis
  val history = mutable.ListBuffer[(Double, State)]()

  def sampleExponential(rate: Double): Double = {
    -log(1.0 - random.nextDouble()) / rate
  }

  def nextTransition(state: State): (State, Double) = {
    val transitions: List[(State, Double)] = state match {
      case Follower =>
        List(
          Candidate -> rElection,
          Crashed -> rCrash
        )
      case Candidate =>
        List(
          Leader -> rWin,
          Follower -> rHeartbeat,
          Crashed -> rCrash
        )
      case Leader =>
        List(
          Follower -> rDiscover,
          Crashed -> rCrash
        )
      case Crashed =>
        List(
          Follower -> rRecover
        )
    }

    // Sample time for each possible transition
    val samples = transitions.map { case (target, rate) =>
      val t = sampleExponential(rate)
      (target, t)
    }

    // Choose the transition with the smallest sampled time
    samples.minBy(_._2)
  }

  println("Simulating RAFT Node CTMC...")
  while (currentTime < maxTime) {
    history += currentTime -> currentState
    val (nextState, deltaTime) = nextTransition(currentState)
    currentTime += deltaTime
    currentState = nextState
  }

  // Summary
  println("\nSimulation finished.")
  val grouped = history.groupBy(_._2).mapValues(_.size.toDouble / history.size)
  println("State proportions:")
  grouped.foreach { case (state, proportion) =>
    println(f"  $state%-10s -> ${proportion * 100}%.2f%% of the time")
  }
}
