package org.openhorizon.exchangeapi.route.agent

import org.scalatest.Suites

// Run the following test suites sequentially, in order.
class TestAgentConfigMgmt extends Suites (
  new TestDeleteAgentConfigMgmt,
  new TestGetAgentConfigMgmt,
  new TestPutAgentConfigMgmt
)
