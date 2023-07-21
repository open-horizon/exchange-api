package org.openhorizon.exchangeapi.route.agentconfigurationmanagement

import org.scalatest.Suites

// Run the following test suites sequentially, in order.
class TestAgentConfigMgmt extends Suites (
  new TestDeleteAgentConfigMgmt,
  new TestGetAgentConfigMgmt,
  new TestPutAgentConfigMgmt
)
