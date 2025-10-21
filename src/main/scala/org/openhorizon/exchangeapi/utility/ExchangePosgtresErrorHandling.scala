package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}

object ExchangePosgtresErrorHandling {
  def isDuplicateKeyError(serverError: org.postgresql.util.PSQLException): Boolean = {
    serverError.getServerErrorMessage.getMessage.contains("duplicate key") || serverError.getServerErrorMessage.getRoutine.contains("_bt_check_unique")
  }
  
  def isAccessDeniedError(serverError: org.postgresql.util.PSQLException): Boolean = {
    serverError.getMessage.startsWith("Access Denied:")
  }
  
  def isKeyNotFoundError(serverError: org.postgresql.util.PSQLException): Boolean = {
    serverError.getServerErrorMessage.getDetail.contains("is not present in table") || serverError.getServerErrorMessage.getRoutine.contains("ri_ReportViolation")
  }
  
  def ioProblemError(serverError: org.postgresql.util.PSQLException, response: String): (StatusCode, ApiResponse) = {
    if (serverError.getMessage.contains("An I/O error occurred")) (StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, response)) else (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, response))
  }
}
