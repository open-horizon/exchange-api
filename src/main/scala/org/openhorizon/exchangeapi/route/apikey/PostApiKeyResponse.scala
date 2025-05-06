package org.openhorizon.exchangeapi.route.apikey

final case class PostApiKeyResponse (
  id: String,
  description: String,
  user: String,
  value: String,
  created_at: String,
  created_by: String,
  modified_at: String,
  modified_by: String)