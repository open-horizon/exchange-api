package com.horizon.exchangeapi.auth

import javax.security.auth.login.LoginException

class UserFacingError(msg: String) extends LoginException(msg)

object AuthErrors {
  def message: Throwable => String = {
    case t: UserFacingError => t.getMessage
    case _ => "invalid credentials"
  }
}
