package io.iohk.ethereum.blockchain.sync.regular

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestActor.AutoPilot
import akka.testkit.TestKit
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.PeersClient
import io.iohk.ethereum.blockchain.sync.regular.RegularSync.MinedBlock
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import io.iohk.ethereum.network.EtcPeerManagerActor.{GetHandshakedPeers, HandshakedPeers}
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import io.iohk.ethereum.network.PeerEventBusActor.{PeerSelector, Subscribe}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63.{GetNodeData, NodeData}
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer, PeerEventBusActor}
import io.iohk.ethereum.ommers.OmmersPool.RemoveOmmers
import io.iohk.ethereum.utils.Config.SyncConfig
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class RegularSyncSpec extends RegularSyncFixtures with WordSpecLike with BeforeAndAfterEach with Matchers with MockFactory {
  type Fixture = RegularSyncFixture

  var testSystem: ActorSystem = _

  override def beforeEach: Unit =
    testSystem = ActorSystem()

  override def afterEach: Unit =
    TestKit.shutdownActorSystem(testSystem)

  "Regular Sync" when {
    "initializing" should {
      "subscribe for new blocks and new hashes" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peerEventBus.expectMsg(
          PeerEventBusActor.Subscribe(
            MessageClassifier(Set(NewBlock.code, NewBlockHashes.code), PeerSelector.AllPeers)))
      }
      "subscribe to handshaked peers list" in new Fixture(testSystem) {
        etcPeerManager.expectMsg(EtcPeerManagerActor.GetHandshakedPeers)
      }
    }

    "fetching blocks" should {
      "fetch headers and bodies concurrently" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(MessageFromPeer(NewBlock(testBlocks.last, testBlocks.last.number), defaultPeer.id))

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers)))
        peersClient.expectMsgAllOfEq(
          blockHeadersRequest(1),
          PeersClient.Request.create(GetBlockBodies(testBlocksChunked.head.hashes), PeersClient.BestPeer)
        )
      }

      "blacklist peer which caused failed request" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgType[PeersClient.Request[GetBlockHeaders]]
        peersClient.reply(PeersClient.RequestFailed(defaultPeer, "a random reason"))
        peersClient.expectMsg(PeersClient.BlacklistPeer(defaultPeer.id, "a random reason"))
      }

      "blacklist peer which returns headers starting from one with higher number than expected" in new Fixture(
        testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked(1).headers)))
        peersClient.expectMsgPF() {
          case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
        }
      }

      "blacklist peer which returns headers not forming a chain" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(
          PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers.filter(_.number % 2 == 0))))
        peersClient.expectMsgPF() {
          case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
        }
      }

      "wait for time defined in config until issuing a retry request due to no suitable peer" in new Fixture(testSystem) {
        regularSync ! RegularSync.Start

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.NoSuitablePeer)
        peersClient.expectNoMessage(syncConfig.syncRetryInterval)
        peersClient.expectMsgEq(blockHeadersRequest(0))
      }

      "not fetch new blocks if fetcher's queue reached size defined in configuration" in new Fixture(testSystem) {
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = testKitSettings.DefaultTimeout.duration,
          maxFetcherQueueSize = 1,
          blockBodiesPerRequest = 2,
          blockHeadersPerRequest = 2,
          blocksBatchSize = 2
        )

        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(
          MessageFromPeer(NewBlock(testBlocks.last, testBlocks.last.header.difficulty), defaultPeer.id))

        peersClient.expectMsgEq(blockHeadersRequest(0))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockHeaders(testBlocksChunked.head.headers)))
        peersClient.expectMsgEq(
          PeersClient.Request.create(GetBlockBodies(testBlocksChunked.head.hashes), PeersClient.BestPeer))
        peersClient.reply(PeersClient.Response(defaultPeer, BlockBodies(testBlocksChunked.head.bodies)))

        peersClient.expectNoMessage()
      }
    }

    "resolving branches" should {
      trait FakeLedger { self: Fixture =>
        class FakeLedgerImpl extends TestLedgerImpl {
          override def importBlock(block: Block)(
              implicit blockExecutionContext: ExecutionContext): Future[BlockImportResult] = {
            val result: BlockImportResult = if (didTryToImportBlock(block)) {
              DuplicateBlock
            } else {
              if (importedBlocks.isEmpty || bestBlock.isParentOf(block) || importedBlocks.exists(_.isParentOf(block))) {
                importedBlocks.add(block)
                BlockImportedToTop(List(BlockData(block, Nil, block.header.difficulty)))
              } else if (block.number > bestBlock.number) {
                importedBlocks.add(block)
                BlockEnqueued
              } else {
                BlockImportFailed("foo")
              }
            }

            Future.successful(result)
          }

          override def resolveBranch(headers: scala.Seq[BlockHeader]): BranchResolutionResult = {
            val importedHashes = importedBlocks.map(_.hash).toSet

            if (importedBlocks.isEmpty || (importedHashes.contains(headers.head.parentHash) && headers.last.number > bestBlock.number)) {
              NewBetterBranch(Nil)
            } else {
              UnknownBranch
            }
          }
        }
      }

      "go back to earlier block in order to find a common parent with new branch" in new Fixture(testSystem)
      with FakeLedger {
        implicit val ec: ExecutionContext = system.dispatcher
        override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
        (blockchain.getBestBlockNumber _).when().onCall(() => ledger.bestBlock.number)
        override lazy val ledger: TestLedgerImpl = new FakeLedgerImpl()
        override lazy val syncConfig = defaultSyncConfig.copy(
          blockHeadersPerRequest = 5,
          blockBodiesPerRequest = 5,
          blocksBatchSize = 5,
          syncRetryInterval = 1.second,
          printStatusInterval = 0.5.seconds,
          branchResolutionRequestSize = 6
        )

        val commonPart: List[Block] = testBlocks.take(syncConfig.blocksBatchSize)
        val alternativeBranch: List[Block] = getBlocks(syncConfig.blocksBatchSize * 2, commonPart.last)
        val alternativeBlocks: List[Block] = commonPart ++ alternativeBranch

        class BranchResolutionAutoPilot(didResponseWithNewBranch: Boolean, blocks: List[Block])
            extends PeersClientAutoPilot(blocks) {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetBlockHeaders(Left(nr), maxHeaders, _, _), _, _)
                if nr >= alternativeBranch.numberAtUnsafe(syncConfig.blocksBatchSize) && !didResponseWithNewBranch =>
              val responseHeaders = alternativeBranch.headers.filter(_.number >= nr).take(maxHeaders.toInt)
              sender ! PeersClient.Response(defaultPeer, BlockHeaders(responseHeaders))
              Some(new BranchResolutionAutoPilot(true, alternativeBlocks))
            case PeersClient.Request(GetBlockBodies(hashes), _, _) if
                !hashes.toSet.subsetOf(blocks.hashes.toSet) &&
                hashes.toSet.subsetOf(testBlocks.hashes.toSet) =>
              val matchingBodies = hashes.flatMap(hash => testBlocks.find(_.hash == hash)).map(_.body)
              sender ! PeersClient.Response(defaultPeer, BlockBodies(matchingBodies))
              None
          }
        }

        peersClient.setAutoPilot(new BranchResolutionAutoPilot(didResponseWithNewBranch = false, testBlocks))

        Await.result(ledger.importBlock(genesis), remainingOrDefault)

        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(MessageFromPeer(NewBlock(alternativeBlocks.last, alternativeBlocks.last.number), defaultPeer.id))

        awaitCond(ledger.bestBlock == alternativeBlocks.last, 5.seconds)
      }
    }

    "fetching state node" should {
      abstract class MissingStateNodeFixture(system: ActorSystem) extends Fixture(system) {
        val failingBlock: Block = testBlocksChunked.head.head
        ledger.setImportResult(failingBlock, () => Future.failed(new MissingNodeException(failingBlock.hash)))
      }

      "blacklist peer which returns empty response" in new MissingStateNodeFixture(testSystem) {
        val failingPeer: Peer = peerByNumber(1)

        peersClient.setAutoPilot(new PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              sender ! PeersClient.Response(failingPeer, NodeData(Nil))
              None
          }
        })

        regularSync ! RegularSync.Start

        fishForBlacklistPeer(failingPeer)
      }
      "blacklist peer which returns invalid node" in new MissingStateNodeFixture(testSystem) {
        val failingPeer: Peer = peerByNumber(1)
        peersClient.setAutoPilot(new PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              sender ! PeersClient.Response(failingPeer, NodeData(List(ByteString("foo"))))
              None
          }
        })

        regularSync ! RegularSync.Start

        fishForBlacklistPeer(failingPeer)
      }
      "retry fetching node if validation failed" in new MissingStateNodeFixture(testSystem) {
        def fishForFailingBlockNodeRequest(): Boolean = peersClient.fishForSpecificMessage() {
          case PeersClient.Request(GetNodeData(hash :: Nil), _, _) if hash == failingBlock.hash => true
        }

        class WrongNodeDataPeersClientAutoPilot(var handledRequests: Int = 0) extends PeersClientAutoPilot {
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case PeersClient.Request(GetNodeData(_), _, _) =>
              val response = handledRequests match {
                case 0 => Some(PeersClient.Response(peerByNumber(1), NodeData(Nil)))
                case 1 => Some(PeersClient.Response(peerByNumber(2), NodeData(List(ByteString("foo")))))
                case _ => None
              }

              response.foreach(sender ! _)
              Some(new WrongNodeDataPeersClientAutoPilot(handledRequests + 1))
          }
        }

        peersClient.setAutoPilot(new WrongNodeDataPeersClientAutoPilot())

        regularSync ! RegularSync.Start

        fishForFailingBlockNodeRequest()
        fishForFailingBlockNodeRequest()
        fishForFailingBlockNodeRequest()
      }
      "save fetched node" in new Fixture(testSystem) {
        implicit val ec: ExecutionContext = system.dispatcher
        override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]
        val failingBlock: Block = testBlocksChunked.head.head
        peersClient.setAutoPilot(new PeersClientAutoPilot)

        (ledger.resolveBranch _).when(*).returns(NewBetterBranch(Nil))
        (ledger
          .importBlock(_: Block)(_: ExecutionContext))
          .when(*, *)
          .returns(Future.failed(new MissingNodeException(failingBlock.hash)))

        var saveNodeWasCalled: Boolean = false
        val nodeData = List(ByteString(failingBlock.header.toBytes: Array[Byte]))
        (blockchain.getBestBlockNumber _).when().returns(0)
        (blockchain.getBlockHeaderByNumber _).when(*).returns(Some(genesis.header))
        (blockchain.saveNode _)
          .when(*, *, *)
          .onCall((hash, encoded, totalDifficulty) => {
            val expectedNode = nodeData.head

            hash should be(kec256(expectedNode))
            encoded should be(expectedNode.toArray)
            totalDifficulty should be(failingBlock.number)

            saveNodeWasCalled = true
          })

        regularSync ! RegularSync.Start

        awaitCond(saveNodeWasCalled)
      }
    }

    "catching the top" should {
      "ignore new blocks if they are too new" in new Fixture(testSystem) {
        override lazy val ledger: TestLedgerImpl = stub[TestLedgerImpl]

        val newBlock: Block = testBlocks.last

        regularSync ! RegularSync.Start
        peerEventBus.expectMsgClass(classOf[Subscribe])

        peerEventBus.reply(MessageFromPeer(NewBlock(newBlock, 1), defaultPeer.id))

        Thread.sleep(remainingOrDefault.toMillis)

        (ledger.importBlock(_: Block)(_: ExecutionContext)).verify(*, *).never()
      }
    }

    "on top" should {
      "import received new block" in new OnTopFixture(testSystem) {
        goToTop()

        sendNewBlock()

        awaitCond(importedNewBlock)
      }
      "broadcast imported block" in new OnTopFixture(testSystem) {
        goToTop()

        etcPeerManager.expectMsg(GetHandshakedPeers)
        etcPeerManager.reply(HandshakedPeers(handshakedPeers))

        sendNewBlock()

        etcPeerManager.fishForSpecificMessageMatching() {
          case EtcPeerManagerActor.SendMessage(message, _) =>
            message.underlyingMsg match {
              case NewBlock(block, _) if block == newBlock => true
              case _ => false
            }
          case _ => false
        }
      }
      "update ommers for imported block" in new OnTopFixture(testSystem) {
        goToTop()

        sendNewBlock()

        ommersPool.expectMsg(RemoveOmmers(newBlock.header :: newBlock.body.uncleNodesList.toList))
      }
      "fetch hashes if received NewHashes message" in new OnTopFixture(testSystem) {
        goToTop()

        blockFetcher !
          MessageFromPeer(NewBlockHashes(List(BlockHash(newBlock.hash, newBlock.number))), defaultPeer.id)

        peersClient.expectMsgPF() {
          case PeersClient.Request(GetBlockHeaders(_, _, _, _), _, _) => true
        }
      }
    }

    "handling mined blocks" should {
      "not import when importing other blocks" in new Fixture(testSystem) {
        val headPromise: Promise[BlockImportResult] = Promise()
        ledger.setImportResult(testBlocks.head, () => headPromise.future)
        val minedBlock: Block = getBlock(genesis)
        peersClient.setAutoPilot(new PeersClientAutoPilot())

        regularSync ! RegularSync.Start

        peerEventBus.expectMsgClass(classOf[Subscribe])
        peerEventBus.reply(MessageFromPeer(NewBlock(testBlocks.last, testBlocks.last.number), defaultPeer.id))

        awaitCond(ledger.didTryToImportBlock(testBlocks.head))
        regularSync ! RegularSync.MinedBlock(minedBlock)
        headPromise.success(BlockImportedToTop(Nil))
        Thread.sleep(remainingOrDefault.toMillis)
        ledger.didTryToImportBlock(minedBlock) shouldBe false
      }

      "import when on top" in new OnTopFixture(testSystem) {
        goToTop()

        regularSync ! RegularSync.MinedBlock(newBlock)

        awaitCond(importedNewBlock)
      }

      "import when not on top and not importing other blocks" in new Fixture(testSystem) {
        val minedBlock: Block = getBlock(genesis)
        ledger.setImportResult(minedBlock, () => Future.successful(BlockImportedToTop(Nil)))

        regularSync ! RegularSync.Start

        regularSync ! MinedBlock(minedBlock)

        awaitCond(ledger.didTryToImportBlock(minedBlock))
      }

      "broadcast after successful import" in new OnTopFixture(testSystem) {
        goToTop()

        etcPeerManager.expectMsg(GetHandshakedPeers)
        etcPeerManager.reply(HandshakedPeers(handshakedPeers))

        regularSync ! RegularSync.MinedBlock(newBlock)

        etcPeerManager.fishForSpecificMessageMatching() {
          case EtcPeerManagerActor.SendMessage(message, _) =>
            message.underlyingMsg match {
              case NewBlock(block, _) if block == newBlock => true
              case _ => false
            }
          case _ => false
        }
      }

      "update ommers after successful import" in new OnTopFixture(testSystem) {
        goToTop()

        regularSync ! RegularSync.MinedBlock(newBlock)

        ommersPool.expectMsg(RemoveOmmers(newBlock.header :: newBlock.body.uncleNodesList.toList))
      }
    }
  }
}