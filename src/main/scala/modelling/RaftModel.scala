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

var startElection: LocalTime = LocalTime.now()

object RaftModel:
  def initialState(numServers: Int): ServerState = // all servers start as followers at term 0
    val servers = (0 until numServers).map { id =>
      id -> Server(id, Role.Follower, 0, None, List(), false, Random.between(0, 5))
    }.toMap
    ServerState(servers, Map(), 0)

  private def transition(state: ServerState, serverId: Int): ServerState =
    val server = state.servers(serverId)
    server.role match
      case Role.Follower => // a follower can become a candidate on timeout
        if !server.timeoutExpired || state.servers.values.exists(_.role == Role.Leader) then
          state
        else // when switching to candidate, the term is incremented and votedFor is set to self
          // when starting election, start election timer
          val startElection = LocalTime.now()
          val newTerm = server.term + 1
          val updatedServer = server.copy(
            role = Role.Candidate,
            term = newTerm,
            votedFor = Some(serverId),
            timeoutExpired = false
          )
          val updatedServers = state.servers.updated(serverId, updatedServer)
          val updatedVotes = state.votes + (serverId -> Set(serverId))
          state.copy(servers = updatedServers, votes = updatedVotes, currentTerm = newTerm)
      case Role.Candidate =>
        val maybeLeader = state.servers.values.find(_.role == Role.Leader)

        maybeLeader match
          case Some(leader) =>
          // If candidate's term is lower, update term and become Follower
            if server.term != leader.term then
              val updatedServer = server.copy(
                role = Role.Follower,
                term = leader.term,
                timeoutExpired = false,
                votedFor = None
              )
              val updatedServers = state.servers.updated(serverId, updatedServer)
              state.copy(servers = updatedServers, currentTerm = leader.term)
            else
              state // no action if candidate is already up-to-date
          case None =>
            // no leader, proceed to collect votes
            val now = LocalTime.now()
            if  now.isAfter(startElection.plusSeconds(server.electionTimeout.toLong)) then
              val reverted = server.copy(
                role = Role.Follower,
                timeoutExpired = false,
                votedFor = None
              )
              state.copy(servers = state.servers.updated(serverId, reverted))
            else
              // collect votes and see if we have a majority
              val stateWithVotes = collectVotes(state, serverId)
              val updatedVotes = stateWithVotes.votes.getOrElse(serverId, Set())
            
              if updatedVotes.size > stateWithVotes.servers.size / 2 then
                val updatedServer = server.copy(role = Role.Leader)
                val updatedServers = stateWithVotes.servers.updated(serverId, updatedServer)
                val newTerm = math.max(stateWithVotes.currentTerm, server.term)
                stateWithVotes.copy(servers = updatedServers, currentTerm = newTerm)
              else
                stateWithVotes
      case Role.Leader => // leader can crash
        val updatedServer = server.copy(
          role = Role.Crashed,
          term = server.term,
          votedFor = None,
          timeoutExpired = false
        )
        val updatedServers = state.servers.updated(serverId, updatedServer)
        state.copy(servers = updatedServers, currentTerm = server.term)
      case Role.Crashed => // a crashed server can recover and become a follower
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

    if follower.role == Role.Follower && follower.votedFor.isEmpty && candidate.term >= follower.term then
      val updatedFollower = follower.copy(votedFor = Some(candidateId), term = candidate.term)
      val updatedServers = state.servers.updated(followerId, updatedFollower)
      val updatedVotes = state.votes.updatedWith(candidateId) {
        case Some(voters) => Some(voters + followerId)
        case None => Some(Set(followerId))
      }
      state.copy(servers = updatedServers, votes = updatedVotes)
    else
      state

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
      val followersOrCandidates = state.servers.values.filter(s =>
        (s.role == Role.Follower || s.role == Role.Candidate) && !s.timeoutExpired
      )
      val timeoutTriggers: Set[Action[ServerState]] =
        if (leaders.nonEmpty)
          Set.empty // Don't allow timeouts if a leader is alive
        else
        followersOrCandidates.map { s =>
          1.5 --> expireTimeout(state, s.id)
        }.toSet
      val roleTransitions: Set[Action[ServerState]] = state.servers.values.flatMap {
        case s if s.role == Role.Follower && s.timeoutExpired =>
          Set(2.0 --> transition(state, s.id))
        case s if s.role == Role.Candidate && s.term <= state.currentTerm && leaders.isEmpty =>
          Set(2.0 --> transition(state, s.id))
        case s if s.role == Role.Leader =>
          Set(0.5 --> transition(state, s.id))
        case s if s.role == Role.Crashed =>
          Set(0.1 --> transition(state, s.id))
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

          4.0 --> updatedState
        }
      }.toSet

      timeoutTriggers ++ roleTransitions ++ heartbeatTransitions
  }