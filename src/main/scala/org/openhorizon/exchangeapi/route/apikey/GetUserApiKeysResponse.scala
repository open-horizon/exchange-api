package org.openhorizon.exchangeapi.route.apikey
import org.openhorizon.exchangeapi.table.apikey.ApiKeyMetadata

final case class GetUserApiKeysResponse(apikeys: Seq[ApiKeyMetadata])