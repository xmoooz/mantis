package io.iohk.ethereum.faucet.jsonrpc

import akka.actor.ActorSystem
import akka.pattern.RetrySupport
import akka.util.Timeout
import io.iohk.ethereum.faucet.FaucetHandler.{FaucetHandlerMsg, FaucetHandlerResponse}
import io.iohk.ethereum.faucet.jsonrpc.FaucetDomain.{SendFundsRequest, SendFundsResponse, StatusRequest, StatusResponse}
import io.iohk.ethereum.faucet.{FaucetConfig, FaucetConfigBuilder}
import io.iohk.ethereum.jsonrpc.AkkaTaskOps._
import io.iohk.ethereum.jsonrpc.{JsonRpcError, ServiceResponse}
import io.iohk.ethereum.utils.Logger

//TODO: Add unit tests - task: ETCM-395
class FaucetRpcService(config: FaucetConfig)(implicit system: ActorSystem)
    extends FaucetConfigBuilder
    with RetrySupport
    with FaucetHandlerBuilder
    with Logger {

  implicit lazy val actorTimeout: Timeout = Timeout(config.responseTimeout)

  def sendFunds(sendFundsRequest: SendFundsRequest): ServiceResponse[SendFundsResponse] =
    faucetHandler().flatMap(handler =>
      handler
        .askFor[Any](FaucetHandlerMsg.SendFunds(sendFundsRequest.address))
        .map(handleSendFundsResponse orElse handleErrors)
    )

  def status(statusRequest: StatusRequest): ServiceResponse[StatusResponse] =
    faucetHandler()
      .flatMap(handler => handler.askFor[Any](FaucetHandlerMsg.Status))
      .map(handleStatusResponse orElse handleErrors)

  private def handleSendFundsResponse: PartialFunction[Any, Either[JsonRpcError, SendFundsResponse]] = {
    case FaucetHandlerResponse.TransactionSent(txHash) =>
      Right(SendFundsResponse(txHash))
  }

  private def handleStatusResponse: PartialFunction[Any, Either[JsonRpcError, StatusResponse]] = {
    case FaucetHandlerResponse.StatusResponse(status) =>
      Right(StatusResponse(status))
  }

  private def handleErrors[T]: PartialFunction[Any, Either[JsonRpcError, T]] = {
    case FaucetHandlerResponse.FaucetIsUnavailable =>
      Left(JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds"))

    case FaucetHandlerResponse.WalletRpcClientError(error) =>
      Left(JsonRpcError.LogicError(s"Faucet error: $error"))
  }
}