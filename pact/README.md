# Pact contracts directory
#
# Consumer-driven contract tests (Pact) are added in Phase 7.
# The Booking Service is the consumer of the Seat Inventory gRPC interface.
# Pact gRPC support: https://docs.pact.io/implementation_guides/grpc
#
# PHASE 7 TODO:
#   - Add Pact broker URL to CI pipeline
#   - Implement provider verification test: BookingService → SeatInventoryService
#   - Publish pacts to Pact Broker on every CI run
