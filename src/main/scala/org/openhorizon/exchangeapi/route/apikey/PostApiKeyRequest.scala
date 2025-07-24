package org.openhorizon.exchangeapi.route.apikey

final case class PostApiKeyRequest(description: Option[String] = None,
                                   label: Option[String] = None)

