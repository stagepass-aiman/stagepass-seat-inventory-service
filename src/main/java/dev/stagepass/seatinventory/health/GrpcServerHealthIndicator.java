package dev.stagepass.seatinventory.health;

import io.grpc.ConnectivityState;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the gRPC server.
 * Included in /actuator/health/readiness output.
 *
 * <p>The readiness probe must confirm the gRPC Netty server is actually
 * listening before the Kubernetes pod receives traffic from the Booking Service.
 */
@Component("grpcServer")
public class GrpcServerHealthIndicator implements HealthIndicator {

    private final GrpcServerLifecycle grpcServerLifecycle;

    public GrpcServerHealthIndicator(final GrpcServerLifecycle grpcServerLifecycle) {
        this.grpcServerLifecycle = grpcServerLifecycle;
    }

    @Override
    public Health health() {
        if (grpcServerLifecycle.isRunning()) {
            return Health.up()
                    .withDetail("grpcServer", "UP")
                    .withDetail("port", "9090")
                    .build();
        }
        return Health.down()
                .withDetail("grpcServer", "DOWN")
                .build();
    }
}
