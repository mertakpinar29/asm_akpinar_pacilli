package examples

import examples.RaftState.*
import modelling.CTMC
import modelling.CTMC.*

enum RaftState:
  case FOLLOWER, CANDIDATE, LEADER, CRASHED

object StochasticChannel:

  export RaftState.*
  export modelling.RaftCTMCSimulation.*

  def stocChannel: CTMC[RaftState] = CTMC.ofTransitions(
    // Follower transitions
    Transition(FOLLOWER, 0.01 --> CANDIDATE),   // election timeout
    Transition(FOLLOWER, 0.005 --> CRASHED),    // crash

    // Candidate transitions
    Transition(CANDIDATE, 0.05 --> LEADER),     // wins election
    Transition(CANDIDATE, 0.02 --> FOLLOWER),   // heartbeat received
    Transition(CANDIDATE, 0.005 --> CRASHED),   // crash

    // Leader transitions
    Transition(LEADER, 0.1   --> LEADER),   // stays leader (highest probability)
    Transition(LEADER, 0.01  --> FOLLOWER), // discovers higher term
    Transition(LEADER, 0.005 --> CRASHED),   // crash


      // Crashed transitions
    Transition(CRASHED, 0.01 --> FOLLOWER)      // recovery
  )
@main def mainStochasticChannel() = // example run
  import StochasticChannel.*
  RaftState.values.foreach(s => println(s"$s,${stocChannel.transitions(s)}"))
