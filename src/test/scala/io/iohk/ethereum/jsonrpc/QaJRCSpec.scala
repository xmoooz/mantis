package io.iohk.ethereum.jsonrpc

import akka.util.ByteString
import io.iohk.ethereum.{ByteGenerators, crypto}
import io.iohk.ethereum.NormalPatience
import io.iohk.ethereum.consensus.ethash.MockedMinerProtocol.MineBlocks
import io.iohk.ethereum.consensus.ethash.{MinerResponse, MinerResponses}
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Checkpoint
import io.iohk.ethereum.jsonrpc.JsonRpcController.JsonRpcConfig
import io.iohk.ethereum.jsonrpc.QAService.MineBlocksResponse.MinerResponseType._
import io.iohk.ethereum.jsonrpc.QAService._
import io.iohk.ethereum.nodebuilder.BlockchainConfigBuilder
import io.iohk.ethereum.utils.{ByteStringUtils, Config}
import org.json4s.Extraction
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class QaJRCSpec extends AnyWordSpec with Matchers with ScalaFutures with NormalPatience with JsonMethodsImplicits {

  "QaJRC" should {
    "request block mining and return valid response with correct message" when {
      "mining ordered" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MinerResponses.MiningOrdered)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveObjectResult(responseType(MiningOrdered), nullMessage)
      }

      "miner is working" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MinerResponses.MinerIsWorking)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveObjectResult(responseType(MinerIsWorking), nullMessage)
      }

      "miner doesn't exist" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MinerResponses.MinerNotExist)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveObjectResult(responseType(MinerNotExist), nullMessage)
      }

      "miner not support current msg" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MinerResponses.MinerNotSupport(MineBlocks(1, true)))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveObjectResult(responseType(MinerNotSupport), msg("MineBlocks(1,true,None)"))
      }

      "miner return error" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MinerResponses.MiningError("error"))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveObjectResult(responseType(MiningError), msg("error"))
      }
    }

    "request block mining and return InternalError" when {
      "communication with miner failed" in new TestSetup {
        (qaService.mineBlocks _)
          .expects(mineBlocksReq)
          .returning(Future.failed(new ClassCastException("error")))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).futureValue

        response should haveError(JsonRpcErrors.InternalError)
      }
    }

    "request generating checkpoint and return valid response" when {
      "given block to be checkpointed exists and checkpoint is generated correctly" in new TestSetup {
        (qaService.generateCheckpoint _)
          .expects(generateCheckpointReq)
          .returning(Future.successful(Right(GenerateCheckpointResponse(checkpoint))))

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(generateCheckpointRpcRequest).futureValue

        response should haveResult(Extraction.decompose(checkpoint))
      }
    }

    "request generating block with checkpoint and return valid response" when {
      "requested best block to be checkpointed and block with checkpoint is generated correctly" in new TestSetup {
        val req = generateCheckpointRpcRequest.copy(
          params = Some(
            JArray(
              List(
                JArray(
                  privateKeysAsJson
                )
              )
            )
          )
        )
        val expectedServiceReq = generateCheckpointReq.copy(blockHash = None)
        (qaService.generateCheckpoint _)
          .expects(expectedServiceReq)
          .returning(Future.successful(Right(GenerateCheckpointResponse(checkpoint))))

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(req).futureValue

        response should haveResult(Extraction.decompose(checkpoint))
      }
    }

    "request generating block with checkpoint and return InvalidParams" when {
      "given block to be checkpointed doesn't exist in blockchain" in new TestSetup {
        val errorMsg =
          s"Block to be checkpointed with hash ${ByteStringUtils.hash2string(blockHash)} doesn't exist"
        (qaService.generateCheckpoint _)
          .expects(generateCheckpointReq)
          .returning(
            Future.successful(
              Left(
                JsonRpcErrors.InvalidParams(errorMsg)
              )
            )
          )

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(generateCheckpointRpcRequest).futureValue

        response should haveError(JsonRpcErrors.InvalidParams(errorMsg))
      }

      "block hash is not valid" in new TestSetup {
        val req = generateCheckpointRpcRequest.copy(
          params = Some(
            JArray(
              List(
                JArray(
                  privateKeysAsJson
                ),
                JInt(1)
              )
            )
          )
        )
        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(req).futureValue

        response should haveError(JsonRpcErrors.InvalidParams())
      }

      "private keys are not valid" in new TestSetup {
        val req = generateCheckpointRpcRequest.copy(
          params = Some(
            JArray(
              List(
                JArray(
                  privateKeysAsJson :+ JInt(1)
                ),
                JString(blockHashAsString)
              )
            )
          )
        )
        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(req).futureValue

        response should haveError(
          JsonRpcErrors.InvalidParams("Unable to parse private key, expected byte data but got: JInt(1)")
        )
      }

      "bad params structure" in new TestSetup {
        val req = generateCheckpointRpcRequest.copy(
          params = Some(
            JArray(
              List(
                JString(blockHashAsString),
                JArray(
                  privateKeysAsJson
                )
              )
            )
          )
        )
        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(req).futureValue

        response should haveError(JsonRpcErrors.InvalidParams())
      }
    }

    "request generating block with checkpoint and return InternalError" when {
      "generating failed" in new TestSetup {
        (qaService.generateCheckpoint _)
          .expects(generateCheckpointReq)
          .returning(Future.failed(new RuntimeException("error")))

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(generateCheckpointRpcRequest).futureValue

        response should haveError(JsonRpcErrors.InternalError)
      }
    }

    "request federation members info and return valid response" when {
      "getting federation public keys is successful" in new TestSetup {
        val checkpointPubKeys: Seq[ByteString] = blockchainConfig.checkpointPubKeys.toList
        (qaService.getFederationMembersInfo _)
          .expects(GetFederationMembersInfoRequest())
          .returning(Future.successful(Right(GetFederationMembersInfoResponse(checkpointPubKeys))))

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(getFederationMembersInfoRpcRequest).futureValue

        val result = JObject(
          "membersPublicKeys" -> JArray(
            checkpointPubKeys.map(encodeAsHex).toList
          )
        )

        response should haveResult(result)
      }
    }

    "request federation members info and return InternalError" when {
      "getting federation members info failed" in new TestSetup {
        (qaService.getFederationMembersInfo _)
          .expects(GetFederationMembersInfoRequest())
          .returning(Future.failed(new RuntimeException("error")))

        val response: JsonRpcResponse =
          jsonRpcController.handleRequest(getFederationMembersInfoRpcRequest).futureValue

        response should haveError(JsonRpcErrors.InternalError)
      }
    }
  }

  trait TestSetup extends MockFactory with JRCMatchers with ByteGenerators with BlockchainConfigBuilder {
    def config: JsonRpcConfig = JsonRpcConfig(Config.config)

    val appStateStorage = mock[AppStateStorage]
    val web3Service = mock[Web3Service]
    val netService = mock[NetService]
    val personalService = mock[PersonalService]
    val debugService = mock[DebugService]
    val ethService = mock[EthService]
    val checkpointingService = mock[CheckpointingService]

    val qaService = mock[QAService]
    val jsonRpcController =
      new JsonRpcController(
        web3Service,
        netService,
        ethService,
        personalService,
        None,
        debugService,
        qaService,
        checkpointingService,
        config
      )

    val mineBlocksReq = MineBlocksRequest(1, true, None)

    val mineBlocksRpcRequest = JsonRpcRequest(
      "2.0",
      "qa_mineBlocks",
      Some(
        JArray(
          List(
            JInt(1),
            JBool(true)
          )
        )
      ),
      Some(JInt(1))
    )

    val blockHash = byteStringOfLengthNGen(32).sample.get
    val blockHashAsString = ByteStringUtils.hash2string(blockHash)
    val privateKeys = seqByteStringOfNItemsOfLengthMGen(3, 32).sample.get.toList
    val keyPairs = privateKeys.map { key =>
      crypto.keyPairFromPrvKey(key.toArray)
    }
    val signatures = keyPairs.map(ECDSASignature.sign(blockHash.toArray, _))
    val checkpoint = Checkpoint(signatures)
    val privateKeysAsJson = privateKeys.map { key =>
      JString(ByteStringUtils.hash2string(key))
    }

    val generateCheckpointReq = GenerateCheckpointRequest(privateKeys, Some(blockHash))

    val generateCheckpointRpcRequest = JsonRpcRequest(
      "2.0",
      "qa_generateCheckpoint",
      Some(
        JArray(
          List(
            JArray(
              privateKeysAsJson
            ),
            JString(blockHashAsString)
          )
        )
      ),
      Some(1)
    )

    val getFederationMembersInfoRpcRequest = JsonRpcRequest(
      "2.0",
      "qa_getFederationMembersInfo",
      Some(
        JArray(
          List()
        )
      ),
      Some(1)
    )

    def msg(str: String): JField = "message" -> JString(str)
    val nullMessage: JField = "message" -> JNull

    def responseType(expectedType: MineBlocksResponse.MinerResponseType): JField =
      "responseType" -> JString(expectedType.entryName)

    def mockSuccessfulMineBlocksBehaviour(resp: MinerResponse) = {
      (qaService.mineBlocks _)
        .expects(mineBlocksReq)
        .returning(Future.successful(Right(MineBlocksResponse(resp))))
    }

    val fakeChainId: Byte = 42.toByte
  }
}
