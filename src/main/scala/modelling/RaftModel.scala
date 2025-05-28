package modelling
import modelling.CTMC.*
import scala.util.Random
import java.time.LocalTime

enum Role:
  case Follower, Candidate, Leader, Crashed

case class Server(
                   id: Int,
                   role: Role,
                   term: Int,
                   votedFor: Option[Int],
                   log: List[String],
                   timeoutExpired: Boolean = false,
                   electionTimeout: Double
                 )

case class ServerState(
                        servers: Map[Int, Server],
                        votes: Map[Int, Set[Int]],
                        currentTerm: Int
                      )

val BROADCAST_TIME: Double = 0.02
val ELECTION_TIMEOUT: Double = 0.3
val MTBF: Double = 100.0 // Mean Time Between Failures

val TIMEOUT_RATE = 1.0 / ELECTION_TIMEOUT // ≈ 3.33
val HEARTBEAT_RATE = 1.0 / BROADCAST_TIME // ≈ 50.0
val CRASH_RATE = 50.0 / MTBF // 0.01
val RECOVERY_RATE: Double = 1.0 / 5.0 // 0.2

var startElection: LocalTime = LocalTime.now()

object RaftModel:
  def initialState(numServers: Int): ServerState = // all servers start as followers at term 0
    val servers = (0 until numServers).map { id =>
      val randomizedTimeout = 0.15 + scala.util.Random.nextDouble() * 0.15
      id -> Server(id, Role.Follower, 0, None, List(), false, randomizedTimeout)
    }.toMap
    ServerState(servers, Map(), 0)

  private def transition(state: ServerState, serverId: Int): ServerState =
    val server = state.servers(serverId)
    server.role match
      case Role.Follower =>
        // A follower can become candidate on timeout only if no leader exists
        if !server.timeoutExpired || state.servers.values.exists(_.role == Role.Leader) then
          state
        else
          val newTerm = server.term + 1
          val updatedServer = server.copy(
            role = Role.Candidate,
            term = newTerm,
            votedFor = Some(serverId),
            timeoutExpired = false
          )
          val updatedServers = state.servers.updated(serverId, updatedServer)
          val updatedVotes = Map(serverId -> Set(serverId))
          state.copy(servers = updatedServers, votes = updatedVotes, currentTerm = newTerm)

      case Role.Candidate =>
        val maybeLeader = state.servers.values.find(_.role == Role.Leader)

        maybeLeader match
          case Some(leader) =>
            if leader.term >= server.term then
              // Leader exists with different term: step down
              val updatedServer = server.copy(
                role = Role.Follower,
                term = leader.term,
                timeoutExpired = false,
                votedFor = None
              )
              val updatedServers = state.servers.updated(serverId, updatedServer)
              state.copy(servers = updatedServers, currentTerm = leader.term)
            else
              // Leader with same term, no action needed
              state

          case None =>
            // No leader exists
            if server.timeoutExpired then
              // Candidate timed out without becoming leader → revert to follower
              val reverted = server.copy(
                role = Role.Follower,
                timeoutExpired = false,
                votedFor = None
              )
              state.copy(servers = state.servers.updated(serverId, reverted))
            else
              // Still collecting votes
              val stateWithVotes = collectVotes(state, serverId)
              val updatedVotes = stateWithVotes.votes.getOrElse(serverId, Set())

              if updatedVotes.size > stateWithVotes.servers.size / 2 then
                // Won majority, become leader
                val updatedServer = server.copy(role = Role.Leader)
                val updatedServers = stateWithVotes.servers.updated(serverId, updatedServer)
                val newTerm = math.max(stateWithVotes.currentTerm, server.term)
                stateWithVotes.copy(servers = updatedServers, currentTerm = newTerm)
              else
                stateWithVotes

      case Role.Leader =>
        // Leader can crash
        val updatedServer = server.copy(
          role = Role.Crashed,
          term = server.term,
          votedFor = None,
          timeoutExpired = false
        )
        val updatedServers = state.servers.updated(serverId, updatedServer)
        state.copy(servers = updatedServers, currentTerm = server.term)

      case Role.Crashed =>
        // Crashed server recovers as follower
        val updatedServer = server.copy(
          role = Role.Follower,
          term = server.term,
          votedFor = None,
          timeoutExpired = false
        )
        val updatedServers = state.servers.updated(serverId, updatedServer)
        state.copy(servers = updatedServers, currentTerm = server.term)

  private def requestVote(state: ServerState, candidateId: Int, followerId: Int): ServerState = // a follower can vote for a candidate if it has not voted yet and the candidate's term is greater than or equal to its own
    val candidate = state.servers(candidateId)
    val follower = state.servers(followerId)

    if candidate.term < follower.term then // reject vote if candidate's term is lower
      state
    else // Possibly update follower's term and role if candidate's term is higher
      val steppedDownFollower =
        if candidate.term > follower.term then
          follower.copy(
            term = candidate.term,
            role = Role.Follower,
            votedFor = None,
            timeoutExpired = false
          )
        else follower

      // Grant vote if follower hasn't voted yet in this term
      val shouldGrantVote = steppedDownFollower.votedFor.isEmpty

      val updatedFollower =
        if shouldGrantVote then
          steppedDownFollower.copy(votedFor = Some(candidateId))
        else steppedDownFollower

      val updatedServers = state.servers.updated(followerId, updatedFollower)

      val updatedVotes =
        if shouldGrantVote then
          state.votes.updatedWith(candidateId) {
            case Some(voters) => Some(voters + followerId)
            case None => Some(Set(followerId))
          }
        else state.votes

      state.copy(servers = updatedServers, votes = updatedVotes, currentTerm = math.max(state.currentTerm, candidate.term))

  private def collectVotes(state: ServerState, candidateId: Int): ServerState = // a candidate collects votes from all followers
    val otherIds = state.servers.keySet - candidateId
    otherIds.foldLeft(state)((s, followerId) => requestVote(s, candidateId, followerId))

  private def expireTimeout(state: ServerState, id: Int): ServerState = // when a follower's timeout expires, it can become a candidate
    val server = state.servers(id)
    val updated = server.copy(timeoutExpired = true)
    val updatedMap = state.servers.updated(id, updated)
    state.copy(servers = updatedMap)

  private def sendHeartbeat(state: ServerState, leaderId: Int, receiverId: Int): ServerState = // a leader sends a heartbeat to all followers
    val leader = state.servers(leaderId)
    val receiver = state.servers(receiverId)

    val updatedReceiver = receiver.role match // if the receiver is a follower, it resets its timeout; if it's a candidate, it becomes a follower and resets its timeout; if it's a leader and the term is greater, it becomes a follower and resets its timeout
      case Role.Follower =>
        receiver.copy(timeoutExpired = false, term = leader.term)
      case Role.Candidate if leader.term >= receiver.term =>
        receiver.copy(
          role = Role.Follower,
          timeoutExpired = false,
          term = leader.term,
          votedFor = None
        )

      case Role.Leader if leader.term > receiver.term =>
        receiver.copy(
          role = Role.Follower,
          timeoutExpired = false,
          term = leader.term,
          votedFor = None
        )
      case _ =>
        receiver

    if receiver.role == Role.Candidate then
      println(s"Heartbeat sent to Candidate ${receiverId}, currentTerm = ${receiver.term}, leaderTerm = ${leader.term}, updatedRole = ${updatedReceiver.role}")
    val updatedMap = state.servers.updated(receiverId, updatedReceiver)
    state.copy(servers = updatedMap)

  val raftCTMC: CTMC[ServerState] = CTMC.ofFunction {
    case state =>
      val followers = state.servers.values.filter(_.role == Role.Follower)
      val leaders = state.servers.values.filter(_.role == Role.Leader)
      val followersOrCandidates = state.servers.values
        .filter(s => (s.role == Role.Follower || s.role == Role.Candidate) && !s.timeoutExpired)
      val timeoutTriggers: Set[Action[ServerState]] =
        followersOrCandidates.map { s =>
          val timeoutRate = 1.0 / s.electionTimeout
          timeoutRate --> expireTimeout(state, s.id)
        }.toSet
      val roleTransitions: Set[Action[ServerState]] = state.servers.values.flatMap {
        case s if s.role == Role.Follower && s.timeoutExpired =>
          Set(TIMEOUT_RATE --> transition(state, s.id))
        case s if s.role == Role.Candidate && s.term <= state.currentTerm && leaders.isEmpty =>
          Set(TIMEOUT_RATE --> transition(state, s.id))
        case s if s.role == Role.Leader =>
          Set(CRASH_RATE --> transition(state, s.id))
        case s if s.role == Role.Crashed =>
          Set(RECOVERY_RATE --> transition(state, s.id))
        case _ => Set.empty
      }.toSet
      val heartbeatTransitions: Set[Action[ServerState]] = leaders.flatMap { leader =>
        val followerIds = state.servers.keySet - leader.id
        followerIds.map { fid =>
          val updatedState = sendHeartbeat(state, leader.id, fid)

          // Optional debug logging — remove in final version
          val from = state.servers(fid)
          val to = updatedState.servers(fid)
          if from.role != to.role || from.term != to.term then
            println(s"Heartbeat applied: ${fid} ${from.role} → ${to.role}, term ${from.term} → ${to.term}")

          HEARTBEAT_RATE --> updatedState
        }
      }.toSet

      timeoutTriggers ++ roleTransitions ++ heartbeatTransitions
  }