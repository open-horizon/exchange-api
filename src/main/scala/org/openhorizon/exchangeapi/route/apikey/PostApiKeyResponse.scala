package org.openhorizon.exchangeapi.route.apikey

final case class PostApiKeyResponse(
  description: String,
  id: String,
  lastUpdated: String,
  owner: String,
  value: String
)