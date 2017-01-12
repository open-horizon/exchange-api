This directory and subdirs contain simple bash scripts running curl for ad hoc tests of the Exchagne API.
For automated tests, see src/test/scala/exchangeapi

## Preconditions

- Install jq (command-line JSON processor)
- Set these environment variables:
    - EXCHANGE_USER
    - EXCHANGE_PW
    - EXCHANGE_ROOTPW
    - EXCHANGE_URL_ROOT: e.g. http://localhost:8080