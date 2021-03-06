/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.UniqueAddress
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import java.net.URLEncoder
import scala.util.control.NoStackTrace
import akka.util.ByteString
import akka.serialization.SerializationExtension
import java.security.MessageDigest
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.actor.Address
import akka.actor.Terminated
import scala.collection.immutable.Queue

object Replicator {

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Replicator]] actor.
   *
   * Use `Option.apply` to create the `Option` from Java.
   */
  def props(
    role: Option[String],
    gossipInterval: FiniteDuration = 2.second,
    maxDeltaElements: Int = 1000,
    pruningInterval: FiniteDuration = 30.seconds,
    maxPruningDissemination: FiniteDuration = 60.seconds): Props =
    Props(new Replicator(role, gossipInterval, maxDeltaElements, pruningInterval, maxPruningDissemination))

  /**
   * Java API: Factory method for the [[akka.actor.Props]] of the [[Replicator]] actor
   * with default values.
   *
   * Use `Option.apply` to create the `Option`.
   */
  def defaultProps(role: Option[String]): Props = props(role)

  sealed trait ReadConsistency
  object ReadOne extends ReadFrom(1)
  object ReadTwo extends ReadFrom(2)
  object ReadThree extends ReadFrom(3)
  case class ReadFrom(n: Int) extends ReadConsistency
  case object ReadQuorum extends ReadConsistency
  case object ReadAll extends ReadConsistency

  sealed trait WriteConsistency
  object WriteOne extends WriteTo(1)
  object WriteTwo extends WriteTo(2)
  object WriteThree extends WriteTo(3)
  case class WriteTo(n: Int) extends WriteConsistency
  case object WriteQuorum extends WriteConsistency
  case object WriteAll extends WriteConsistency

  /**
   * Java API: The [[ReadOne]] instance
   */
  def ReadOneInstance = ReadOne

  /**
   * Java API: The [[ReadTwo]] instance
   */
  def ReadTwoInstance = ReadTwo

  /**
   * Java API: The [[ReadThree]] instance
   */
  def ReadThreeInstance = ReadThree

  /**
   * Java API: The [[ReadQuorum]] instance
   */
  def ReadQuorumInstance = ReadQuorum

  /**
   * Java API: The [[ReadAll]] instance
   */
  def ReadAllInstance = ReadAll

  /**
   * Java API: The [[WriteOne]] instance
   */
  def WriteOneInstance = WriteOne

  /**
   * Java API: The [[WriteTwo]] instance
   */
  def WriteTwoInstance = WriteTwo

  /**
   * Java API: The [[WriteThree]] instance
   */
  def WriteThreeInstance = WriteThree

  /**
   * Java API: The [[WriteQuorum]] instance
   */
  def WriteQuorumInstance = WriteQuorum

  /**
   * Java API: The [[WriteAll]] instance
   */
  def WriteAllInstance = WriteAll

  case object GetKeys
  /**
   * Java API: The [[GetKeys]] instance
   */
  def GetKeysInstance = GetKeys
  case class GetKeysResult(keys: Set[String]) {
    /**
     * Java API
     */
    def getKeys: java.util.Set[String] = {
      import scala.collection.JavaConverters._
      keys.asJava
    }
  }

  sealed trait Command {
    def key: String
  }

  object Get {
    /**
     * `Get` value from local `Replicator`, i.e. `ReadOne`
     * consistency.
     */
    def apply(key: String): Get = Get(key, ReadOne, Duration.Zero, None)
  }
  /**
   * Send this message to the local `Replicator` to retrieve a data value for the
   * given `key`. The `Replicator` will reply with one of the [[GetResponse]] messages.
   */
  case class Get(key: String, consistency: ReadConsistency, timeout: FiniteDuration, request: Option[Any] = None)
    extends Command with ReplicatorMessage {
    /**
     * Java API: `Get` value from local `Replicator`, i.e. `ReadOne` consistency.
     */
    def this(key: String) = this(key, ReadOne, Duration.Zero, None)
  }
  sealed trait GetResponse {
    def key: String
  }
  case class GetSuccess(key: String, data: ReplicatedData, request: Option[Any])
    extends ReplicatorMessage with GetResponse
  case class NotFound(key: String, request: Option[Any])
    extends ReplicatorMessage with GetResponse
  case class GetFailure(key: String, request: Option[Any])
    extends ReplicatorMessage with GetResponse

  case class Subscribe(key: String, subscriber: ActorRef)
    extends ReplicatorMessage
  case class Unsubscribe(key: String, subscriber: ActorRef)
    extends ReplicatorMessage
  case class Changed(key: String, data: ReplicatedData)
    extends ReplicatorMessage

  object Update {

    /**
     * Modify value of local `Replicator`, i.e. `ReadOne` / `WriteOne` consistency.
     *
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     */
    def apply[A <: ReplicatedData](key: String, initial: A)(modify: A => A): Update[A] =
      Update(key, ReadOne, WriteOne, Duration.Zero, None)(modifyWithInitial(initial, modify))

    /**
     * Modify value of local `Replicator`, i.e. `ReadOne` / `WriteOne` consistency.
     */
    def apply[A <: ReplicatedData](key: String, initial: A, request: Option[Any])(
      modify: A => A): Update[A] =
      Update(key, ReadOne, WriteOne, Duration.Zero, request)(modifyWithInitial(initial, modify))

    /**
     * Modify value of local `Replicator` and replicate with given `writeConsistency`.
     */
    def apply[A <: ReplicatedData](
      key: String, initial: A, writeConsistency: WriteConsistency,
      timeout: FiniteDuration)(modify: A => A): Update[A] =
      Update(key, ReadOne, writeConsistency, timeout, None)(modifyWithInitial(initial, modify))

    /**
     * Modify value of local `Replicator` and replicate with given `writeConsistency`.
     */
    def apply[A <: ReplicatedData](
      key: String, initial: A, writeConsistency: WriteConsistency,
      timeout: FiniteDuration, request: Option[Any])(modify: A => A): Update[A] =
      Update(key, ReadOne, writeConsistency, timeout, request)(modifyWithInitial(initial, modify))

    /**
     * Retrieve value from other replicas with given `readConsistency` and then modify value and
     * replicate with given `writeConsistency`.
     */
    def apply[A <: ReplicatedData](
      key: String, initial: A, readConsistency: ReadConsistency, writeConsistency: WriteConsistency,
      timeout: FiniteDuration, request: Option[Any])(modify: A => A): Update[A] =
      Update(key, readConsistency, writeConsistency, timeout, request)(modifyWithInitial(initial, modify))

    private def modifyWithInitial[A <: ReplicatedData](initial: A, modify: A => A): Option[A] => A = {
      case Some(data) => modify(data)
      case None       => modify(initial)
    }
  }
  /**
   * Send this message to the local `Replicator` to update a data value for the
   * given `key`. The `Replicator` will reply with one of the [[UpdateResponse]] messages.
   *
   * Note that the [[Replicator.Update$ companion]] object provides `apply` functions for convenient
   * construction of this message.
   *
   * The current data value for the `key` is passed as parameter to the `modify` function.
   * It is `None` if there is no value for the `key`, and otherwise `Some(data)`. The function
   * is supposed to return the new value of the data, which will then be replicated according to
   * the given `writeConsistency`.
   *
   * The `modify` function is called by the `Replicator` actor and must therefore be a pure
   * function that only uses the data parameter and stable fields from enclosing scope. It must
   * for example not access `sender()` reference of an enclosing actor.
   *
   * If `readConsistency != ReadOne` it will first retrieve the data from other nodes
   * and then apply the `modify` function with the latest data. If the read fails the update
   * will continue anyway, using the local value of the data.
   * To support "read your own writes" all incoming commands for this key will be
   * buffered until the read is completed and the function has been applied.
   */
  case class Update[A <: ReplicatedData](key: String, readConsistency: ReadConsistency, writeConsistency: WriteConsistency,
                                         timeout: FiniteDuration, request: Option[Any])(val modify: Option[A] => A)
    extends Command {

    /**
     * Java API
     */
    def this(key: String, readConsistency: ReadConsistency, writeConsistency: WriteConsistency,
             timeout: FiniteDuration, request: Option[Any],
             modify: akka.japi.Function[Option[A], A]) =
      this(key, readConsistency, writeConsistency, timeout, request)(data => modify.apply(data))

    /**
     * Java API: Modify value of local `Replicator`, i.e. `ReadOne` / `WriteOne` consistency.
     *
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     */
    def this(key: String, initial: A, modify: akka.japi.Function[A, A]) =
      this(key, ReadOne, WriteOne, Duration.Zero, None)(Update.modifyWithInitial(initial, data => modify.apply(data)))

    /**
     * Java API: Modify value of local `Replicator`, i.e. `ReadOne` / `WriteOne` consistency.
     */
    def this(key: String, initial: A, request: Option[Any], modify: akka.japi.Function[A, A]) =
      this(key, ReadOne, WriteOne, Duration.Zero, request)(Update.modifyWithInitial(initial, data => modify.apply(data)))

    /**
     * Java API: Modify value of local `Replicator` and replicate with given `writeConsistency`.
     */
    def this(
      key: String, initial: A, writeConsistency: WriteConsistency,
      timeout: FiniteDuration, modify: akka.japi.Function[A, A]) =
      this(key, ReadOne, writeConsistency, timeout, None)(Update.modifyWithInitial(initial, data => modify.apply(data)))

    /**
     * Java API: Modify value of local `Replicator` and replicate with given `writeConsistency`.
     */
    def this(
      key: String, initial: A, writeConsistency: WriteConsistency,
      timeout: FiniteDuration, request: Option[Any], modify: akka.japi.Function[A, A]) =
      this(key, ReadOne, writeConsistency, timeout, request)(Update.modifyWithInitial(initial, data => modify.apply(data)))

    /**
     * Java API: Retrieve value from other replicas with given `readConsistency` and then modify value and
     * replicate with given `writeConsistency`.
     */
    def this(
      key: String, initial: A, readConsistency: ReadConsistency, writeConsistency: WriteConsistency,
      timeout: FiniteDuration, request: Option[Any], modify: akka.japi.Function[A, A]) =
      this(key, readConsistency, writeConsistency, timeout, request)(Update.modifyWithInitial(initial, data => modify.apply(data)))

  }

  sealed trait UpdateResponse {
    def key: String
  }
  case class UpdateSuccess(key: String, request: Option[Any]) extends UpdateResponse
  sealed trait UpdateFailure extends UpdateResponse {
    def key: String
    def request: Option[Any]
  }
  case class ReplicationUpdateFailure(key: String, request: Option[Any]) extends UpdateFailure
  case class ConflictingType(key: String, errorMessage: String, request: Option[Any])
    extends RuntimeException with NoStackTrace with UpdateFailure {
    override def toString: String = s"ConflictingType [$key]: $errorMessage"
  }
  case class InvalidUsage(key: String, errorMessage: String, request: Option[Any])
    extends RuntimeException with NoStackTrace with UpdateFailure {
    override def toString: String = s"InvalidUsage [$key]: $errorMessage"
  }
  case class ModifyFailure(key: String, errorMessage: String, request: Option[Any])
    extends RuntimeException with NoStackTrace with UpdateFailure {
    override def toString: String = s"ModifyFailure [$key]: $errorMessage"
  }

  object Delete {
    /**
     * `Delete` value of local `Replicator`, i.e. `WriteOne`
     * consistency.
     */
    def apply(key: String): Delete = Delete(key, WriteOne, Duration.Zero)
  }
  /**
   * Send this message to the local `Replicator` to delete a data value for the
   * given `key`. The `Replicator` will reply with one of the [[DeleteResponse]] messages.
   */
  case class Delete(key: String, consistency: WriteConsistency, timeout: FiniteDuration) extends Command {
    /**
     * Java API: `Delete` value of local `Replicator`, i.e. `WriteOne`
     * consistency.
     */
    def this(key: String) = this(key, WriteOne, Duration.Zero)
  }
  sealed trait DeleteResponse {
    def key: String
  }
  case class DeleteSuccess(key: String) extends DeleteResponse
  case class ReplicationDeleteFailure(key: String) extends DeleteResponse
  case class DataDeleted(key: String)
    extends RuntimeException with NoStackTrace with DeleteResponse {
    override def toString: String = s"DataDeleted [$key]"
  }

  /**
   * Marker trait for remote messages serialized by
   * [[akka.contrib.datareplication.protobuf.ReplicatorMessageSerializer]].
   */
  trait ReplicatorMessage extends Serializable

  /**
   * INTERNAL API
   */
  private[akka] object Internal {

    case object GossipTick
    case object RemovedNodePruningTick
    case object ClockTick
    case class Write(key: String, envelope: DataEnvelope) extends ReplicatorMessage
    case object WriteAck extends ReplicatorMessage
    case class Read(key: String) extends ReplicatorMessage
    case class ReadResult(envelope: Option[DataEnvelope]) extends ReplicatorMessage
    case class ReadRepair(key: String, envelope: DataEnvelope)
    case object ReadRepairAck
    case class BufferedCommand(cmd: Command, replyTo: ActorRef)
    case class UpdateInProgress(cmd: Update[ReplicatedData], replyTo: ActorRef)

    // Gossip Status message contains SHA-1 digests of the data to determine when
    // to send the full data
    type Digest = ByteString
    val deletedDigest: Digest = ByteString.empty

    case class DataEnvelope(
      data: ReplicatedData,
      pruning: Map[UniqueAddress, PruningState] = Map.empty)
      extends ReplicatorMessage {

      import PruningState._

      def initRemovedNodePruning(removed: UniqueAddress, owner: UniqueAddress): DataEnvelope = {
        copy(pruning = pruning.updated(removed, PruningState(owner, PruningInitialized(Set.empty))))
      }

      def prune(from: UniqueAddress): DataEnvelope = {
        data match {
          case dataWithRemovedNodePruning: RemovedNodePruning ⇒
            require(pruning.contains(from))
            val to = pruning(from).owner
            val prunedData = dataWithRemovedNodePruning.prune(from, to)
            copy(data = prunedData, pruning = pruning.updated(from, PruningState(to, PruningPerformed)))
          case _ ⇒ this
        }

      }

      def merge(other: DataEnvelope): DataEnvelope =
        if (other.data == DeletedData) DeletedEnvelope
        else {
          var mergedRemovedNodePruning = other.pruning
          for ((key, thisValue) ← pruning) {
            mergedRemovedNodePruning.get(key) match {
              case None ⇒ mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue)
              case Some(thatValue) ⇒
                mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue merge thatValue)
            }
          }

          copy(data = cleaned(data, mergedRemovedNodePruning), pruning = mergedRemovedNodePruning).merge(other.data)
        }

      def merge(otherData: ReplicatedData): DataEnvelope =
        if (otherData == DeletedData) DeletedEnvelope
        else copy(data = data merge cleaned(otherData, pruning).asInstanceOf[data.T])

      private def cleaned(c: ReplicatedData, p: Map[UniqueAddress, PruningState]): ReplicatedData = p.foldLeft(c) {
        case (c: RemovedNodePruning, (removed, PruningState(_, PruningPerformed))) ⇒
          if (c.needPruningFrom(removed)) c.pruningCleanup(removed) else c
        case (c, _) ⇒ c
      }

      def addSeen(node: Address): DataEnvelope = {
        var changed = false
        val newRemovedNodePruning = pruning.map {
          case (removed, pruningNode) ⇒
            val newPruningState = pruningNode.addSeen(node)
            changed = (newPruningState ne pruningNode) || changed
            (removed, newPruningState)
        }
        if (changed) copy(pruning = newRemovedNodePruning)
        else this
      }
    }

    val DeletedEnvelope = DataEnvelope(DeletedData)

    case object DeletedData extends ReplicatedData with ReplicatedDataSerialization {
      type T = ReplicatedData
      override def merge(that: ReplicatedData): ReplicatedData = DeletedData
    }

    case class Status(digests: Map[String, Digest]) extends ReplicatorMessage
    case class Gossip(updatedData: Map[String, DataEnvelope]) extends ReplicatorMessage

    // Testing purpose
    case object GetNodeCount
    case class NodeCount(n: Int)
  }
}

/**
 * A replicated in-memory data store supporting low latency and high availability
 * requirements.
 *
 * The `Replicator` actor takes care of direct replication and gossip based
 * dissemination of Conflict Free Replicated Data Types (CRDT) to replicas in the
 * the cluster.
 * The data types must be convergent CRDTs and implement [[ReplicatedData]], i.e.
 * they provide a monotonic merge function and the state changes always converge.
 *
 * You can use your own custom [[ReplicatedData]] types, and several types are provided
 * by this package, such as:
 *
 * <ul>
 * <li>Counters: [[GCounter]], [[PNCounter]]</li>
 * <li>Registers: [[LWWRegister]], [[Flag]]</li>
 * <li>Sets: [[GSet]], [[ORSet]]</li>
 * <li>Maps: [[ORMap]], [[LWWMap]], [[PNCounterMap]]</li>
 * </ul>
 *
 * For good introduction to the CRDT subject watch the
 * <a href="http://vimeo.com/43903960">Eventually Consistent Data Structures</a>
 * talk by Sean Cribbs and and the
 * <a href="http://research.microsoft.com/apps/video/dl.aspx?id=153540">talk by Mark Shapiro</a>
 * and read the excellent paper <a href="http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf">
 * A comprehensive study of Convergent and Commutative Replicated Data Types</a>
 * by Mark Shapiro et. al.
 *
 * The `Replicator` actor must be started on each node in the cluster, or group of
 * nodes tagged with a specific role. It communicates with other `Replicator` instances
 * with the same path (without address) that are running on other nodes . For convenience it
 * can be used with the [[DataReplication]] extension.
 *
 * == Update ==
 *
 * To modify and replicate a [[ReplicatedData]] value you send a [[Replicator.Update]] message
 * to the the local `Replicator`.
 * The current data value for the `key` of the `Update` is passed as parameter to the `modify`
 * function of the `Update`. The function is supposed to return the new value of the data, which
 * will then be replicated according to the given consistency level.
 *
 * The `modify` function is called by the `Replicator` actor and must therefore be a pure
 * function that only uses the data parameter and stable fields from enclosing scope. It must
 * for example not access `sender()` reference of an enclosing actor.
 *
 * You supply a write consistency level which has the following meaning:
 * <ul>
 * <li>`WriteOne` the value will immediately only be written to the local replica,
 *     and later disseminated with gossip</li>
 * <li>`WriteTwo` the value will immediately be written to at least two replicas,
 *     including the local replica</li>
 * <li>`WriteThree` the value will immediately be written to at least three replicas,
 *     including the local replica</li>
 * <li>`WriteTo(n)` the value will immediately be written to at least `n` replicas,
 *     including the local replica</li>
 * <li>`WriteQuorum` the value will immediately be written to a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`WriteAll` the value will immediately be written to all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Update` a [[Replicator.UpdateSuccess]] is sent to the sender of the
 * `Update` if the value was successfully replicated according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.UpdateFailure]] is sent.
 * Note that `UpdateFailure` does not mean that the update completely failed or was rolled back.
 * It may still have been replicated to some nodes, and will eventually be replicated to all
 * nodes with the gossip protocol.
 *
 * You will always see your own writes. For example if you send two `Update` messages
 * changing the value of the same `key`, the `modify` function of the second message will
 * see the change that was performed by the first `Update` message.
 *
 * The `Update` message also supports a read consistency level with same meaning as described for
 * `Get` below. If the given read consitency level is not `ReadOne` it will first retrieve the
 * data from other nodes and then apply the `modify` function with the latest data. If the read
 * fails the update will continue anyway, using the local value of the data.
 * To support "read your own writes" all incoming commands for this key will be
 * buffered until the read is completed and the function has been applied.
 *
 * In the `Update` message you can pass an optional request context, which the `Replicator`
 * does not care about, but is included in the reply messages. This is a convenient
 * way to pass contextual information (e.g. original sender) without having to use `ask`
 * or local correlation data structures.
 *
 * == Get ==
 *
 * To retrieve the current value of a data you send [[Replicator.Get]] message to the
 * `Replicator`. You supply a consistency level which has the following meaning:
 * <ul>
 * <li>`ReadOne` the value will only be read from the local replica</li>
 * <li>`ReadTwo` the value will be read and merged from two replicas,
 *     including the local replica</li>
 * <li>`ReadThree` the value will be read and merged from three replicas,
 *     including the local replica</li>
 * <li>`ReadFrom(n)` the value will be read and merged from `n` replicas,
 *     including the local replica</li>
 * <li>`ReadQuorum` the value will read and merged from a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`ReadAll` the value will be read and merged from all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Get` a [[Replicator.GetSuccess]] is sent to the sender of the
 * `Get` if the value was successfully retrieved according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.GetFailure]] is sent.
 * If the key does not exist the reply will be [[Replicator.GetFailure]].
 *
 * You will always read your own writes. For example if you send a `Update` message
 * followed by a `Get` of the same `key` the `Get` will retrieve the change that was
 * performed by the preceding `Update` message. However, the order of the reply messages are
 * not defined, i.e. in the previous example you may receive the `GetSuccess` before
 * the `UpdateSuccess`.
 *
 * In the `Get` message you can pass an optional request context in the same way as for the
 * `Update` message, described above. For example the original sender can be passed and replied
 * to after receiving and transforming `GetSuccess`.
 *
 * You can retrieve all keys of a local replica by sending [[Replicator.GetKeys]] message to the
 * `Replicator`. The reply of `GetKeys` is a [[Replicator.GetKeysResult]] message.
 *
 * == Subscribe ==
 *
 * You may also register interest in change notifications by sending [[Replicator.Subscribe]]
 * message to the `Replicator`. It will send [[Replicator.Changed]] messages to the registered
 * subscriber when the data for the subscribed key is updated. The subscriber is automatically
 * removed if the subscriber is terminated. A subscriber can also be deregistered with the
 * [[Replicator.Unsubscribe]] message.
 *
 * == Delete ==
 *
 * A data entry can be deleted by sending a [[Replicator.Delete]] message to the local
 * local `Replicator`. As reply of the `Delete` a [[Replicator.DeleteSuccess]] is sent to
 * the sender of the `Delete` if the value was successfully deleted according to the supplied
 * consistency level within the supplied timeout. Otherwise a [[Replicator.ReplicationDeleteFailure]]
 * is sent. Note that `ReplicationDeleteFailure` does not mean that the delete completely failed or
 * was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
 * to all nodes. A deleted key cannot be reused again, but it is still recommended to delete unused
 * data entries because that reduces the replication overhead when new nodes join the cluster.
 * Subsequent `Delete`, `Update` and `Get` requests will be replied with [[Replicator.DataDeleted]].
 * Subscribers will receive [[Replicator.DataDeleted]].
 *
 * == CRDT Garbage ==
 *
 * One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
 * For example a `GCounter` keeps track of one counter per node. If a `GCounter` has been updated
 * from one node it will associate the identifier of that node forever. That can become a problem
 * for long running systems with many cluster nodes being added and removed. To solve this problem
 * the `Replicator` performs pruning of data associated with nodes that have been removed from the
 * cluster. Data types that need pruning have to implement [[RemovedNodePruning]]. The pruning consists
 * of several steps:
 * <ol>
 * <li>When a node is removed from the cluster it is first important that all updates that were
 * done by that node are disseminated to all other nodes. The pruning will not start before the
 * `maxPruningDissemination` duration has elapsed. The time measurement is stopped when any
 * replica is unreachable, so it should be configured to worst case in a healthy cluster.</li>
 * <li>The nodes are ordered by their address and the node ordered first is called leader.
 * The leader initiates the pruning by adding a `PruningInitialized` marker in the data envelope.
 * This is gossiped to all other nodes and they mark it as seen when they receive it.</li>
 * <li>When the leader sees that all other nodes have seen the `PruningInitialized` marker
 * the leader performs the pruning and changes the marker to `PruningPerformed` so that nobody
 * else will redo the pruning. The data envelope with this pruning state is a CRDT itself.
 * The pruning is typically performed by "moving" the part of the data associated with
 * the removed node to the leader node. For example, a `GCounter` is a `Map` with the node as key
 * and the counts done by that node as value. When pruning the value of the removed node is
 * moved to the entry owned by the leader node. See [[RemovedNodePruning#prune]].</li>
 * <li>Thereafter the data is always cleared from parts associated with the removed node so that
 * it does not come back when merging. See [[RemovedNodePruning#pruningCleanup]]</li>
 * <li>After another `maxPruningDissemination` duration after pruning the last entry from the
 * removed node the `PruningPerformed` markers in the data envelope are collapsed into a
 * single tombstone entry, for efficiency. Clients may continue to use old data and therefore
 * all data are always cleared from parts associated with tombstoned nodes. </li>
 * </ol>
 */
class Replicator(
  role: Option[String],
  gossipInterval: FiniteDuration,
  maxDeltaElements: Int,
  pruningInterval: FiniteDuration,
  maxPruningDissemination: FiniteDuration) extends Actor with ActorLogging {

  import Replicator._
  import Replicator.Internal._
  import PruningState._

  val cluster = Cluster(context.system)
  val selfAddress = cluster.selfAddress
  val selfUniqueAddress = cluster.selfUniqueAddress

  require(!cluster.isTerminated, "Cluster node must not be terminated")
  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${selfAddress}] doesn't have the role [$role]")

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val pruningTask = context.system.scheduler.schedule(pruningInterval, pruningInterval, self, RemovedNodePruningTick)
  val clockTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, ClockTick)

  val serializer = SerializationExtension(context.system).serializerFor(classOf[DataEnvelope])
  val maxPruningDisseminationNanos = maxPruningDissemination.toNanos

  // cluster nodes, doesn't contain selfAddress
  var nodes: Set[Address] = Set.empty

  // nodes removed from cluster, to be pruned, and tombstoned
  var removedNodes: Map[UniqueAddress, Long] = Map.empty
  var pruningPerformed: Map[UniqueAddress, Long] = Map.empty
  var tombstoneNodes: Set[UniqueAddress] = Set.empty

  var leader: Option[Address] = None
  def isLeader: Boolean = leader.exists(_ == selfAddress)

  // for pruning timeouts are based on clock that is only increased when all
  // nodes are reachable
  var previousClockTime = System.nanoTime()
  var allReachableClockTime = 0L
  var unreachable = Set.empty[Address]

  var dataEntries = Map.empty[String, (DataEnvelope, Digest)]

  var subscribers = Map.empty[String, Set[ActorRef]]

  var updateInProgressBuffer = Map.empty[String, Queue[BufferedCommand]]

  override def preStart(): Unit = {
    val leaderChangedClass = if (role.isDefined) classOf[RoleLeaderChanged] else classOf[LeaderChanged]
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[ReachabilityEvent], leaderChangedClass)
  }

  override def postStop(): Unit = {
    cluster unsubscribe self
    gossipTask.cancel()
    pruningTask.cancel()
    clockTask.cancel()
  }

  def matchingRole(m: Member): Boolean = role.forall(m.hasRole)

  def receive = normalReceive

  val normalReceive: Receive = {
    case Get(key, consistency, timeout, req)           ⇒ receiveGet(key, consistency, timeout, req, sender())
    case Read(key)                                     ⇒ receiveRead(key)
    case Update(key, _, _, _, req) if !isLocalSender() ⇒ receiveInvalidUpdate(key, req)
    case u @ Update(key, readC, writeC, timeout, req)  ⇒ receiveUpdate(key, u.modify, readC, writeC, timeout, req, sender())
    case Write(key, envelope)                          ⇒ receiveWrite(key, envelope)
    case ReadRepair(key, envelope)                     ⇒ receiveReadRepair(key, envelope)
    case GetKeys                                       ⇒ receiveGetKeys()
    case Delete(key, consistency, timeout)             ⇒ receiveDelete(key, consistency, timeout, sender())
    case GossipTick                                    ⇒ receiveGossipTick()
    case Status(otherDigests)                          ⇒ receiveStatus(otherDigests)
    case Gossip(updatedData)                           ⇒ receiveGossip(updatedData)
    case Subscribe(key, subscriber)                    ⇒ receiveSubscribe(key, subscriber)
    case Unsubscribe(key, subscriber)                  ⇒ receiveUnsubscribe(key, subscriber)
    case Terminated(ref)                               ⇒ receiveTerminated(ref)
    case MemberUp(m)                                   ⇒ receiveMemberUp(m)
    case MemberRemoved(m, _)                           ⇒ receiveMemberRemoved(m)
    case _: MemberEvent                                ⇒ // not of interest
    case UnreachableMember(m)                          ⇒ receiveUnreachable(m)
    case ReachableMember(m)                            ⇒ receiveReachable(m)
    case LeaderChanged(leader)                         ⇒ receiveLeaderChanged(leader, None)
    case RoleLeaderChanged(role, leader)               ⇒ receiveLeaderChanged(leader, Some(role))
    case ClockTick                                     ⇒ receiveClockTick()
    case RemovedNodePruningTick                        ⇒ receiveRemovedNodePruningTick()
    case GetNodeCount                                  ⇒ receiveGetNodeCount()
  }

  def receiveGet(key: String, consistency: ReadConsistency, timeout: FiniteDuration,
                 req: Option[Any], replyTo: ActorRef): Unit = {
    // don't use sender() in this method, since it is used from drainUpdateInProgressBuffer
    val localValue = getData(key)
    log.debug("Received Get for key [{}], local data [{}]", key, localValue)
    if (consistency == ReadOne) {
      val reply = localValue match {
        case Some(DataEnvelope(DeletedData, _)) ⇒ DataDeleted(key)
        case Some(DataEnvelope(data, _))        ⇒ GetSuccess(key, data, req)
        case None                               ⇒ NotFound(key, req)
      }
      replyTo ! reply
    } else
      context.actorOf(Props(classOf[ReadAggregator], key, consistency, timeout, req, nodes, localValue, replyTo))
  }

  def receiveRead(key: String): Unit = {
    sender() ! ReadResult(getData(key))
  }

  def isLocalSender(): Boolean = !sender().path.address.hasGlobalScope

  def receiveInvalidUpdate(key: String, req: Option[Any]): Unit = {
    sender() ! InvalidUsage(key,
      "Replicator Update should only be used from an actor running in same local ActorSystem", req)
  }

  def receiveUpdate(key: String, modify: Option[ReplicatedData] => ReplicatedData,
                    readConsistency: ReadConsistency, writeConsistency: WriteConsistency,
                    timeout: FiniteDuration, req: Option[Any], replyTo: ActorRef): Unit = {
    // don't use sender() in this method, since it is used from drainUpdateInProgressBuffer
    val localValue = getData(key)
    if (readConsistency == ReadOne) {
      Try {
        localValue match {
          case Some(DataEnvelope(DeletedData, _))         ⇒ throw new DataDeleted(key)
          case Some(envelope @ DataEnvelope(existing, _)) ⇒ modify(Some(existing))
          case None                                       => modify(None)
        }
      } match {
        case Success(newData) =>
          log.debug("Received Update for key [{}], old data [{}], new data [{}]", key, localValue, newData)
          update(key, newData, req) match {
            case Success(merged) ⇒
              if (writeConsistency == WriteOne)
                replyTo ! UpdateSuccess(key, req)
              else
                context.actorOf(Props(classOf[WriteAggregator], key, merged, writeConsistency, timeout, req,
                  nodes, replyTo))
            case Failure(e) ⇒
              replyTo ! e
          }
        case Failure(e: DataDeleted) =>
          log.debug("Received Update for deleted key [{}]", key)
          replyTo ! e
        case Failure(e) =>
          log.debug("Received Update for key [{}], failed: {}", key, e.getMessage)
          replyTo ! ModifyFailure(key, "Update failed: " + e.getMessage, req)
      }
    } else {
      // Update with readConsistency != ReadOne means that we will first retrieve the data with
      // ReadAggregator (same as is used for Get). To support "read your own writes" all incoming
      // commands for this key will be buffered while the read is in progress. When the read is 
      // completed the update will continue, operating on the retrieved data, and replicate the
      // new value with the writeConsistency. Buffered commands are also processed when the read
      // is completed.
      log.debug("Received Update for key [{}], reading from [{}]", key, readConsistency)
      val req2 = Some(UpdateInProgress(Update(key, readConsistency, writeConsistency, timeout, req)(modify), replyTo))
      // FIXME props factory methods
      context.actorOf(Props(classOf[ReadAggregator], key, readConsistency, timeout, req2, nodes, localValue, self))
      if (updateInProgressBuffer.isEmpty)
        context.become(updateInProgressReceive)
      if (!updateInProgressBuffer.contains(key))
        updateInProgressBuffer = updateInProgressBuffer.updated(key, Queue.empty)
    }
  }

  def update(key: String, data: ReplicatedData, req: Option[Any]): Try[DataEnvelope] = {
    getData(key) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒
        Failure(DataDeleted(key))
      case Some(envelope @ DataEnvelope(existing, pruning)) ⇒
        if (existing.getClass == data.getClass || data == DeletedData) {
          val merged = envelope.merge(pruningCleanupTombstoned(data))
          setData(key, merged)
          Success(merged)
        } else {
          val errMsg = s"Wrong type for updating [$key], existing type [${existing.getClass.getName}], got [${data.getClass.getName}]"
          log.warning(errMsg)
          Failure(ConflictingType(key, errMsg, req))
        }
      case None ⇒
        val envelope = DataEnvelope(pruningCleanupTombstoned(data))
        setData(key, envelope)
        Success(envelope)
    }
  }

  val updateInProgressReceive: Receive = ({
    case Update(key, _, _, _, req) if !isLocalSender() ⇒ receiveInvalidUpdate(key, req)
    case cmd: Command if updateInProgressBuffer.contains(cmd.key) =>
      log.debug("Update in progress for [{}], buffering [{}]", cmd.key, cmd)
      updateInProgressBuffer = updateInProgressBuffer.updated(cmd.key,
        updateInProgressBuffer(cmd.key).enqueue(BufferedCommand(cmd, sender())))
    case getResponse: GetResponse => getResponse match {
      case GetSuccess(key, _, Some(UpdateInProgress(u @ Update(_, _, writeC, timeout, req), replyTo: ActorRef))) =>
        // local value has been updated by the read-repair
        continueUpdateAfterRead(key, u.modify, writeC, timeout, req, replyTo)
      case NotFound(key, Some(UpdateInProgress(u @ Update(_, _, writeC, timeout, req), replyTo: ActorRef))) =>
        continueUpdateAfterRead(key, u.modify, writeC, timeout, req, replyTo)
      case GetFailure(key, Some(UpdateInProgress(u @ Update(_, readC, writeC, timeout, req), replyTo: ActorRef))) =>
        // use local value
        log.debug("Update [{}] reading from [{}] failed, using local value", key, readC)
        continueUpdateAfterRead(key, u.modify, writeC, timeout, req, replyTo)
      case other =>
        // FIXME perhaps throw IllegalStateException
        log.error("Unexpected reply [{}]", other)
        unhandled(other)
        drainUpdateInProgressBuffer(getResponse.key)
    }
  }: Receive).orElse(normalReceive)

  def continueUpdateAfterRead(key: String, modify: Option[ReplicatedData] => ReplicatedData,
                              writeConsistency: WriteConsistency, timeout: FiniteDuration,
                              req: Option[Any], replyTo: ActorRef): Unit = {
    log.debug("Continue update of [{}] after read", key)
    receiveUpdate(key, modify, ReadOne, writeConsistency, timeout, req, replyTo)
    drainUpdateInProgressBuffer(key)
  }

  def drainUpdateInProgressBuffer(key: String): Unit = {
    @tailrec def drain(): Unit = {
      val (cmd, remaining) = updateInProgressBuffer(key).dequeue
      if (remaining.isEmpty)
        updateInProgressBuffer -= key
      else
        updateInProgressBuffer = updateInProgressBuffer.updated(key, remaining)
      val cont = cmd match {
        case BufferedCommand(Get(_, consistency, timeout, req), replyTo) ⇒
          receiveGet(key, consistency, timeout, req, replyTo)
          true
        case BufferedCommand(u @ Update(_, readC, writeC, timeout, req), replyTo) ⇒
          receiveUpdate(key, u.modify, readC, writeC, timeout, req, replyTo)
          (readC == ReadOne)
        case BufferedCommand(Delete(_, consistency, timeout), replyTo) ⇒
          receiveDelete(key, consistency, timeout, replyTo)
          true
      }
      if (cont && remaining.nonEmpty) drain()
    }

    if (updateInProgressBuffer(key).nonEmpty) drain()
    else updateInProgressBuffer -= key

    if (updateInProgressBuffer.isEmpty) context.become(normalReceive)
  }

  def receiveWrite(key: String, envelope: DataEnvelope): Unit = {
    write(key, envelope)
    sender() ! WriteAck
  }

  def write(key: String, writeEnvelope: DataEnvelope): Unit =
    getData(key) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒ // already deleted
      case Some(envelope @ DataEnvelope(existing, _)) ⇒
        if (existing.getClass == writeEnvelope.data.getClass || writeEnvelope.data == DeletedData) {
          val merged = envelope.merge(pruningCleanupTombstoned(writeEnvelope)).addSeen(selfAddress)
          setData(key, merged)
        } else {
          log.warning("Wrong type for writing [{}], existing type [{}], got [{}]",
            key, existing.getClass.getName, writeEnvelope.data.getClass.getName)
        }
      case None ⇒
        setData(key, pruningCleanupTombstoned(writeEnvelope).addSeen(selfAddress))
    }

  def receiveReadRepair(key: String, writeEnvelope: DataEnvelope): Unit = {
    write(key, writeEnvelope)
    sender() ! ReadRepairAck
  }

  def receiveGetKeys(): Unit =
    sender() ! GetKeysResult(dataEntries.collect {
      case (key, (DataEnvelope(data, _), _)) if data != DeletedData ⇒ key
    }(collection.breakOut))

  def receiveDelete(key: String, consistency: WriteConsistency,
                    timeout: FiniteDuration, replyTo: ActorRef): Unit = {
    // don't use sender() in this method, since it is used from drainUpdateInProgressBuffer
    update(key, DeletedData, None) match {
      case Success(merged) ⇒
        if (consistency == WriteOne)
          replyTo ! DeleteSuccess(key)
        else
          context.actorOf(Props(classOf[WriteAggregator], key, merged, consistency, timeout, None,
            nodes, replyTo))
      case Failure(e) ⇒
        replyTo ! e
    }
  }

  def setData(key: String, envelope: DataEnvelope): Unit = {
    val digest =
      if (envelope.data == DeletedData) deletedDigest
      else {
        val bytes = serializer.toBinary(envelope)
        ByteString.fromArray(MessageDigest.getInstance("SHA-1").digest(bytes))
      }

    // notify subscribers, when changed
    subscribers.get(key) foreach { s ⇒
      val oldDigest = getDigest(key)
      if (oldDigest.isEmpty || digest != oldDigest.get) {
        val msg = if (envelope.data == DeletedData) DataDeleted(key) else Changed(key, envelope.data)
        s foreach { _ ! msg }
      }
    }

    dataEntries = dataEntries.updated(key, (envelope, digest))
  }

  def getData(key: String): Option[DataEnvelope] = dataEntries.get(key).map { case (envelope, _) ⇒ envelope }

  def getDigest(key: String): Option[Digest] = dataEntries.get(key).map { case (_, digest) ⇒ digest }

  def receiveGossipTick(): Unit = selectRandomNode(nodes.toVector) foreach gossipTo

  def gossipTo(address: Address): Unit =
    replica(address) ! Status(dataEntries.map { case (key, (_, digest)) ⇒ (key, digest) })

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def replica(address: Address): ActorSelection =
    context.actorSelection(self.path.toStringWithAddress(address))

  def receiveStatus(otherDigests: Map[String, Digest]): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip status from [{}], containing [{}]", sender().path.address,
        otherDigests.keys.mkString(", "))

    def isOtherOutdated(key: String, otherDigest: Digest): Boolean =
      getDigest(key) match {
        case Some(digest) if digest != otherDigest ⇒ true
        case _                                     ⇒ false
      }
    val otherOutdatedKeys = otherDigests.collect {
      case (key, otherV) if isOtherOutdated(key, otherV) ⇒ key
    }
    val otherMissingKeys = dataEntries.keySet -- otherDigests.keySet
    val keys = (otherMissingKeys ++ otherOutdatedKeys).take(maxDeltaElements)
    if (keys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip to [{}], containing [{}]", sender().path.address,
          keys.mkString(", "))
      val g = Gossip(keys.map(k ⇒ k -> getData(k).get)(collection.breakOut))
      sender() ! g
    }
  }

  def receiveGossip(updatedData: Map[String, DataEnvelope]): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip from [{}], containing [{}]", sender().path.address, updatedData.keys.mkString(", "))
    updatedData.foreach {
      case (key, envelope) ⇒ write(key, envelope)
    }
  }

  def receiveSubscribe(key: String, subscriber: ActorRef): Unit = {
    subscribers = subscribers.updated(key, subscribers.getOrElse(key, Set.empty) + subscriber)
    context.watch(subscriber)
    getData(key) foreach {
      case DataEnvelope(DeletedData, _) ⇒ subscriber ! DataDeleted(key)
      case DataEnvelope(data, _)        ⇒ subscriber ! Changed(key, data)
    }
  }

  def receiveUnsubscribe(key: String, subscriber: ActorRef): Unit = {
    subscribers.get(key) match {
      case Some(existing) ⇒
        val s = existing - subscriber
        if (s.isEmpty)
          subscribers -= key
        else
          subscribers = subscribers.updated(key, s)
      case None ⇒
    }
    if (!hasSubscriber(subscriber))
      context.unwatch(subscriber)
  }

  def hasSubscriber(subscriber: ActorRef): Boolean = {
    @tailrec def find(keys: List[String]): Boolean =
      if (keys.isEmpty) false
      else if (subscribers(keys.head)(subscriber)) true
      else find(keys.tail)

    find(subscribers.keys.toList)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    for ((k, s) ← subscribers) {
      if (s(ref)) {
        if (s.isEmpty)
          subscribers -= k
        else
          subscribers = subscribers.updated(k, s)
      }
    }
  }

  def receiveMemberUp(m: Member): Unit =
    if (matchingRole(m) && m.address != selfAddress)
      nodes += m.address

  def receiveMemberRemoved(m: Member): Unit = {
    if (m.address == selfAddress)
      context stop self
    else if (matchingRole(m)) {
      nodes -= m.address
      removedNodes = removedNodes.updated(m.uniqueAddress, allReachableClockTime)
      unreachable -= m.address
    }
  }

  def receiveUnreachable(m: Member): Unit =
    if (matchingRole(m)) unreachable += m.address

  def receiveReachable(m: Member): Unit =
    if (matchingRole(m)) unreachable -= m.address

  def receiveLeaderChanged(leaderOption: Option[Address], roleOption: Option[String]): Unit =
    if (roleOption == role) leader = leaderOption

  def receiveClockTick(): Unit = {
    val now = System.nanoTime()
    if (unreachable.isEmpty)
      allReachableClockTime += (now - previousClockTime)
    previousClockTime = now
  }

  def receiveRemovedNodePruningTick(): Unit = {
    if (isLeader && removedNodes.nonEmpty) {
      initRemovedNodePruning()
    }
    performRemovedNodePruning()
    tombstoneRemovedNodePruning()
  }

  def initRemovedNodePruning(): Unit = {
    // initiate pruning for removed nodes
    val removedSet: Set[UniqueAddress] = removedNodes.collect {
      case (r, t) if ((allReachableClockTime - t) > maxPruningDisseminationNanos) ⇒ r
    }(collection.breakOut)

    for ((key, (envelope, _)) ← dataEntries; removed ← removedSet) {

      def init(): Unit = {
        val newEnvelope = envelope.initRemovedNodePruning(removed, selfUniqueAddress)
        log.debug("Initiated pruning of [{}] for data key [{}]", removed, key)
        setData(key, newEnvelope)
      }

      envelope.data match {
        case dataWithRemovedNodePruning: RemovedNodePruning ⇒
          envelope.pruning.get(removed) match {
            case None ⇒ init()
            case Some(PruningState(owner, PruningInitialized(_))) if owner != selfUniqueAddress ⇒ init()
            case _ ⇒ // already in progress
          }
        case _ ⇒
      }
    }
  }

  def performRemovedNodePruning(): Unit = {
    // perform pruning when all seen Init
    dataEntries.foreach {
      case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
        pruning.foreach {
          case (removed, PruningState(owner, PruningInitialized(seen))) if owner == selfUniqueAddress && seen == nodes ⇒
            val newEnvelope = envelope.prune(removed)
            pruningPerformed = pruningPerformed.updated(removed, allReachableClockTime)
            log.debug("Perform pruning of [{}] from [{}] to [{}]", key, removed, selfUniqueAddress)
            setData(key, newEnvelope)
          case _ ⇒
        }
      case _ ⇒ // deleted, or pruning not needed
    }
  }

  def tombstoneRemovedNodePruning(): Unit = {

    def allPruningPerformed(removed: UniqueAddress): Boolean = {
      dataEntries.forall {
        case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
          pruning.get(removed) match {
            case Some(PruningState(_, PruningInitialized(_))) ⇒ false
            case _ ⇒ true
          }
        case _ ⇒ true // deleted, or pruning not needed
      }
    }

    pruningPerformed.foreach {
      case (removed, timestamp) if ((allReachableClockTime - timestamp) > maxPruningDisseminationNanos) &&
        allPruningPerformed(removed) ⇒
        log.debug("All pruning performed for [{}], tombstoned", removed)
        pruningPerformed -= removed
        removedNodes -= removed
        tombstoneNodes += removed
        dataEntries.foreach {
          case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, _), _)) ⇒
            setData(key, pruningCleanupTombstoned(removed, envelope))
          case _ ⇒ // deleted, or pruning not needed
        }
      case (removed, timestamp) ⇒ // not ready
    }
  }

  def pruningCleanupTombstoned(envelope: DataEnvelope): DataEnvelope =
    tombstoneNodes.foldLeft(envelope)((c, removed) ⇒ pruningCleanupTombstoned(removed, c))

  def pruningCleanupTombstoned(removed: UniqueAddress, envelope: DataEnvelope): DataEnvelope = {
    val pruningCleanuped = pruningCleanupTombstoned(removed, envelope.data)
    if ((pruningCleanuped ne envelope.data) || envelope.pruning.contains(removed))
      envelope.copy(data = pruningCleanuped, pruning = envelope.pruning - removed)
    else
      envelope
  }

  def pruningCleanupTombstoned(data: ReplicatedData): ReplicatedData =
    tombstoneNodes.foldLeft(data)((c, removed) ⇒ pruningCleanupTombstoned(removed, c))

  def pruningCleanupTombstoned(removed: UniqueAddress, data: ReplicatedData): ReplicatedData =
    data match {
      case dataWithRemovedNodePruning: RemovedNodePruning ⇒
        if (dataWithRemovedNodePruning.needPruningFrom(removed)) dataWithRemovedNodePruning.pruningCleanup(removed) else data
      case _ ⇒ data
    }

  def receiveGetNodeCount(): Unit = {
    // selfAddress is not included in the set
    sender() ! NodeCount(nodes.size + 1)
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class ReadWriteAggregator extends Actor {
  import Replicator.Internal._

  def timeout: FiniteDuration
  def nodes: Set[Address]

  import context.dispatcher
  var timeoutSchedule = context.system.scheduler.scheduleOnce(timeout, self, ReceiveTimeout)

  var remaining = nodes

  override def postStop(): Unit = {
    timeoutSchedule.cancel()
  }

  def replica(address: Address): ActorSelection =
    context.actorSelection(context.parent.path.toStringWithAddress(address))

  def becomeDone(): Unit = {
    if (remaining.isEmpty)
      context.stop(self)
    else {
      // stay around a bit more to collect acks, avoiding deadletters
      context.become(done)
      timeoutSchedule.cancel()
      timeoutSchedule = context.system.scheduler.scheduleOnce(2.seconds, self, ReceiveTimeout)
    }
  }

  def done: Receive = {
    case WriteAck | _: ReadResult ⇒
      remaining -= sender().path.address
      if (remaining.isEmpty) context.stop(self)
    case ReceiveTimeout ⇒ context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[akka] class WriteAggregator(
  key: String,
  envelope: Replicator.Internal.DataEnvelope,
  consistency: Replicator.WriteConsistency,
  override val timeout: FiniteDuration,
  req: Option[Any],
  override val nodes: Set[Address],
  replyTo: ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._

  val doneWhenRemainingSize = consistency match {
    case WriteTo(n) ⇒ nodes.size - (n - 1)
    case WriteAll   ⇒ 0
    case WriteQuorum ⇒
      val N = nodes.size + 1
      if (N < 3) -1
      else {
        val w = N / 2 + 1 // write to at least (N/2+1) nodes
        N - w
      }
  }

  override def preStart(): Unit = {
    // FIXME perhaps not send to all, e.g. for WriteTwo we could start with less
    val writeMsg = Write(key, envelope)
    nodes.foreach { replica(_) ! writeMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case WriteAck ⇒
      remaining -= sender().path.address
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def reply(ok: Boolean): Unit = {
    if (ok && envelope.data == DeletedData)
      replyTo.tell(DeleteSuccess(key), context.parent)
    else if (ok)
      replyTo.tell(UpdateSuccess(key, req), context.parent)
    else if (envelope.data == DeletedData)
      replyTo.tell(ReplicationDeleteFailure(key), context.parent)
    else
      replyTo.tell(ReplicationUpdateFailure(key, req), context.parent)
    becomeDone()
  }
}

/**
 * INTERNAL API
 */
private[akka] class ReadAggregator(
  key: String,
  consistency: Replicator.ReadConsistency,
  override val timeout: FiniteDuration,
  req: Option[Any],
  override val nodes: Set[Address],
  localValue: Option[Replicator.Internal.DataEnvelope],
  replyTo: ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._

  var result = localValue
  val doneWhenRemainingSize = consistency match {
    case ReadFrom(n) ⇒ nodes.size - (n - 1)
    case ReadAll     ⇒ 0
    case ReadQuorum ⇒
      val N = nodes.size + 1
      if (N < 3) -1
      else {
        val r = N / 2 + 1 // read from at least (N/2+1) nodes
        N - r
      }
  }

  override def preStart(): Unit = {
    // FIXME perhaps not send to all, e.g. for ReadTwo we could start with less
    val readMsg = Read(key)
    nodes.foreach { replica(_) ! readMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case ReadResult(envelope) ⇒
      result = (result, envelope) match {
        case (Some(a), Some(b))  ⇒ Some(a.merge(b))
        case (r @ Some(_), None) ⇒ r
        case (None, r @ Some(_)) ⇒ r
        case (None, None)        ⇒ None
      }
      remaining -= sender().path.address
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def reply(ok: Boolean): Unit =
    (ok, result) match {
      case (true, Some(envelope)) ⇒
        context.parent ! ReadRepair(key, envelope)
        // read-repair happens before GetSuccess
        context.become(waitReadRepairAck(envelope))
      case (true, None) ⇒
        replyTo.tell(NotFound(key, req), context.parent)
        becomeDone()
      case (false, _) ⇒
        replyTo.tell(GetFailure(key, req), context.parent)
        becomeDone()
    }

  def waitReadRepairAck(envelope: Replicator.Internal.DataEnvelope): Receive = {
    case ReadRepairAck =>
      val replyMsg =
        if (envelope.data == DeletedData) DataDeleted(key)
        else GetSuccess(key, envelope.data, req)
      replyTo.tell(replyMsg, context.parent)
      becomeDone()
    case _: ReadResult ⇒
      // collect late replies
      remaining -= sender().path.address
  }
}

