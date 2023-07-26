package org.openhorizon.exchangeapi.auth

// The context info about the request passed into the login() methods
final case class RequestInfo(creds: Creds,
                             isDbMigration: Boolean,
                             hint: String)
