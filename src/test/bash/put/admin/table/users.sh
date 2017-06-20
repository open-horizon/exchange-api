# Gets a hash of the specified pw
source `dirname $0`/../../../functions.sh PUT $*

# Pipe the info into this
curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d @- $EXCHANGE_URL_ROOT/v1/admin/tables/users | $parse

# curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '[
#   {
#     "username": "2",
#     "password": "$2a$10$0wya.WH8ayVYJEoEBcM3g.SsjCmrVpR6tU.RFurYC9W3vK481nybC",
#     "email": "2@gmail.com",
#     "lastUpdated": "2016-12-02T13:12:25.030Z[UTC]"
#   },
#   {
#     "username": "bp",
#     "password": "$2a$10$xNMVEfKCivXDr/d6pwuUCedRTt.puudl8PCSLjGuv32Ar4drafYW.",
#     "email": "bruceandml@gmail.com",
#     "lastUpdated": "2016-12-02T13:14:30.031Z[UTC]"
#   }
# ]' $EXCHANGE_URL_ROOT/v1/admin/tables/users | $parse
