package org.openhorizon.exchangeapi.route.apikey

final case class PostApiKeyResponse(
  id: String,
  description: String,
  owner: String,
  value: String,
  lastUpdated: String
)