package io.iohk.ethereum.blockchain.sync

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.SyncStateDownloaderActor.{CancelDownload, RegisterScheduler}
import io.iohk.ethereum.blockchain.sync.SyncStateScheduler.{ProcessingStatistics, SchedulerState, SyncResponse}
import io.iohk.ethereum.blockchain.sync.SyncStateSchedulerActor.{
  GetMissingNodes,
  MissingNodes,
  PrintInfo,
  PrintInfoKey,
  RestartRequested,
  StartSyncingTo,
  StateSyncFinished,
  StateSyncStats,
  WaitingForNewTargetBlock
}
import io.iohk.ethereum.utils.ByteStringUtils
import io.iohk.ethereum.utils.Config.SyncConfig

import scala.concurrent.duration._

class SyncStateSchedulerActor(downloader: ActorRef, sync: SyncStateScheduler, syncConfig: SyncConfig)
    extends Actor
    with ActorLogging
    with Timers {

  def idle(processingStatistics: ProcessingStatistics): Receive = {
    case StartSyncingTo(root, bn) =>
      timers.startTimerAtFixedRate(PrintInfoKey, PrintInfo, 30.seconds)
      log.info(s"Starting state sync to root ${ByteStringUtils.hash2string(root)} on block ${bn}")
      //TODO handle case when we already have root i.e state is synced up to this point
      val initState = sync.initState(root).get
      val (initialMissing, state1) = initState.getAllMissingHashes
      downloader ! RegisterScheduler
      downloader ! GetMissingNodes(initialMissing)
      context become (syncing(state1, processingStatistics, bn, sender()))
    case PrintInfo =>
      log.info(s"Waiting for target block to start the state sync")
  }

  private def finalizeSync(state: SchedulerState, targetBlock: BigInt, syncInitiator: ActorRef): Unit = {
    if (state.memBatch.nonEmpty) {
      log.debug(s"Persisting ${state.memBatch.size} elements to blockchain and finalizing the state sync")
      sync.persistBatch(state, targetBlock)
      syncInitiator ! StateSyncFinished
      context.become(idle(ProcessingStatistics()))
    } else {
      log.info(s"Finalizing the state sync")
      syncInitiator ! StateSyncFinished
      context.become(idle(ProcessingStatistics()))
    }
  }

  // scalastyle:off method.length
  def syncing(
      currentState: SchedulerState,
      currentStats: ProcessingStatistics,
      targetBlock: BigInt,
      syncInitiator: ActorRef
  ): Receive = {
    case MissingNodes(nodes, downloaderCap) =>
      log.debug(s"Received {} new nodes to process", nodes.size)
      // Current SyncStateDownloaderActor makes sure that there is no not requested or duplicated values in its response.
      // so we can ignore those errors.
      // TODO make processing async as sometimes downloader sits idle
      sync.processResponses(currentState, nodes) match {
        case Left(value) =>
          log.error(s"Critical error while state syncing ${value}, stopping state sync")
          // TODO we should probably start sync again from new target block, as current trie is malformed or declare
          // fast sync as failure and start normal sync from scratch
          context.stop(self)
        case Right((newState, statistics)) =>
          if (newState.numberOfPendingRequests == 0) {
            reportStats(syncInitiator, currentStats, currentState)
            finalizeSync(newState, targetBlock, syncInitiator)
          } else {
            log.debug(
              s" There are {} pending state requests," +
                s"Missing queue size is {} elements",
              newState.numberOfPendingRequests,
              newState.queue.size()
            )

            val (missing, state2) = sync.getMissingNodes(newState, downloaderCap)

            if (missing.nonEmpty) {
              log.debug(s"Asking downloader for {} missing state nodes", missing.size)
              downloader ! GetMissingNodes(missing)
            }

            if (state2.memBatch.size >= syncConfig.stateSyncPersistBatchSize) {
              log.debug("Current membatch size is {}, persisting nodes to database", state2.memBatch.size)
              val state3 = sync.persistBatch(state2, targetBlock)
              val newStats = currentStats.addStats(statistics).addSaved(state2.memBatch.size)
              reportStats(syncInitiator, newStats, state3)
              context.become(syncing(state3, newStats, targetBlock, syncInitiator))
            } else {
              val newStats = currentStats.addStats(statistics)
              reportStats(syncInitiator, newStats, state2)
              context.become(syncing(state2, newStats, targetBlock, syncInitiator))
            }
          }
      }

    case PrintInfo =>
      val syncStats = s""" Status of mpt state sync:
                             | Number of Pending requests: ${currentState.numberOfPendingRequests},
                             | Number of Missing hashes waiting to be retrieved: ${currentState.queue.size()},
                             | Number of Mpt nodes saved to database: ${currentStats.saved},
                             | Number of duplicated hashes: ${currentStats.duplicatedHashes},
                             | Number of not requested hashes: ${currentStats.notRequestedHashes},
                        """.stripMargin

      log.info(syncStats)

    case RestartRequested =>
      downloader ! CancelDownload
      sync.persistBatch(currentState, targetBlock)
      sender() ! WaitingForNewTargetBlock
      context.become(idle(currentStats))
  }

  override def receive: Receive = idle(ProcessingStatistics())

  private def reportStats(
      to: ActorRef,
      currentStats: ProcessingStatistics,
      currentState: SyncStateScheduler.SchedulerState
  ): Unit = {
    to ! StateSyncStats(
      currentStats.saved + currentState.memBatch.size,
      currentState.numberOfPendingRequests
    )
  }

}

object SyncStateSchedulerActor {
  def props(downloader: ActorRef, sync: SyncStateScheduler, syncConfig: SyncConfig): Props = {
    Props(new SyncStateSchedulerActor(downloader, sync, syncConfig))
  }

  final case object PrintInfo
  final case object PrintInfoKey

  case class StartSyncingTo(stateRoot: ByteString, blockNumber: BigInt)

  case class StateSyncStats(saved: Long, missing: Long)

  case class GetMissingNodes(nodesToGet: List[ByteString])

  case class MissingNodes(missingNodes: List[SyncResponse], downloaderCapacity: Int)

  case object StateSyncFinished

  case object RestartRequested
  case object WaitingForNewTargetBlock

}