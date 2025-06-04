package lab.demo

import it.unibo.scafi.incarnations.Incarnation
import lab.demo.Incarnation._
import scala.util.Random

class RaftLeaderElection extends AggregateProgram with StandardSensors {
  private sealed trait Role
  private case object Follower extends Role
  private case object Candidate extends Role
  private case object Leader extends Role

  def randomTimeout(): Int = 300 + Random.nextInt(200)

  def main(): String = {
    val id = mid()
    val total = foldhood(0)(_ + _)(1) + 1
    val timeout = randomTimeout()

    def sense1 = sense[Boolean]("sens1")
    val forceToCandidate = sense1

    val state: (Role, Int, Option[Int], Int) =
      rep[(Role, Int, Option[Int], Int)]((Follower, 0, None, 0)) {
        case (currentRole, currentTerm, currentVotedFor, timePassed) =>
          val roleCode = currentRole match {
            case Follower => 0
            case Candidate => 1
            case Leader => 2
          }

          // Detect visible leader and its term
          val leaderSeenTerm = foldhood(-1)(Math.max) {
            val neighborRole = nbr { roleCode }
            val neighborTerm = nbr { currentTerm }
            if (neighborRole == 2) neighborTerm else -1
          }

          val seesLeader = leaderSeenTerm >= 0



          // Reset timeout if leader is seen
          val nextTime = if (seesLeader) 0 else timePassed + 1

          // Sync term to leader or neighbor term
          val maxNeighborTerm = foldhood(currentTerm)(Math.max)(nbr { currentTerm })
          val nextTerm =
            if (leaderSeenTerm > currentTerm) leaderSeenTerm
            else if (maxNeighborTerm > currentTerm) maxNeighborTerm
            else if (!seesLeader && nextTime > timeout && currentRole != Leader) currentTerm + 1
            else currentTerm

          // Detect candidate neighbors
          val candidateNeighbors = foldhood(Set.empty[(Int, Int)])(_ ++ _) {
            val neighborRole = nbr { roleCode }
            val neighborTerm = nbr { currentTerm }
            val neighborId = nbr { mid() }
            if (neighborRole == 1 && neighborTerm == nextTerm) Set((neighborId, neighborTerm)) else Set.empty
          }

          // Grant vote to one candidate if not already voted
          val newVotedFor = currentRole match {
            case Follower if currentVotedFor.isEmpty && candidateNeighbors.nonEmpty =>
              Some(candidateNeighbors.head._1)
            case _ => None
          }

          // Count votes if candidate
          val votesReceived =
            if (currentRole == Candidate)
              foldhood(0)(_ + _) {
                val neighborVotedFor = nbr { currentVotedFor.getOrElse(-1) }
                if (neighborVotedFor == id) 1 else 0
              } + 1
            else 0

          val newRole = currentRole match {
            case Follower =>
              if ((!seesLeader && nextTime > timeout) || forceToCandidate) Candidate else Follower

            case Candidate =>
              if (votesReceived > total / 2) Leader
              else if (nextTime > timeout * 4 || seesLeader) Follower
              else Candidate

            case Leader =>
              val higherTermSeen = foldhood(false)(_ || _) {
                nbr {
                  currentTerm
                } > currentTerm

              }
              if (maxNeighborTerm > currentTerm) Follower else Leader
          }

          println(s"Node $id | Term: $nextTerm | Role: $newRole | VotedFor: $newVotedFor | Votes: $votesReceived | Time: $nextTime")

          (newRole, nextTerm, newVotedFor, nextTime)
      }

    val (role, term, _, _) = state
    s"$id: $role (term $term)"
  }
}
