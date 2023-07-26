package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCode

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
    if (serverError.getMessage.contains("An I/O error occurred")) (HttpCode.BAD_GW, ApiResponse(ApiRespType.BAD_GW, response)) else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, response))
  }
}
