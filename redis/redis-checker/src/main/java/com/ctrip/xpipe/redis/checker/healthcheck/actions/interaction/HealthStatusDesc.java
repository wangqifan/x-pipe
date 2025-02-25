package com.ctrip.xpipe.redis.checker.healthcheck.actions.interaction;

import com.ctrip.xpipe.endpoint.HostPort;

/**
 * @author lishanglin
 * date 2022/7/18
 */
public class HealthStatusDesc {

    private HostPort hostPort;

    private HEALTH_STATE state;

    private Boolean lastMarkHandled = null;

    private long lastPongTime = -1;

    private long lastHealthDelayTime = -1;

    public HealthStatusDesc() {

    }

    public HealthStatusDesc(HostPort hostPort, HEALTH_STATE state) {
        this.hostPort = hostPort;
        this.state = state;
    }

    public HealthStatusDesc(HostPort hostPort, HEALTH_STATE state, Boolean lastMarkHandled) {
        this.hostPort = hostPort;
        this.state = state;
        this.lastMarkHandled = lastMarkHandled;
    }

    public HealthStatusDesc(HostPort hostPort, HealthStatus status) {
        this(hostPort, status, null);
    }

    public HealthStatusDesc(HostPort hostPort, HealthStatus status, Boolean lastMarkHandled) {
        this.hostPort = hostPort;
        this.state = status.getState();
        this.lastPongTime = status.getLastPongTime();
        this.lastHealthDelayTime = status.getLastHealthyDelayTime();
        this.lastMarkHandled = lastMarkHandled;
    }

    public HostPort getHostPort() {
        return hostPort;
    }

    public HEALTH_STATE getState() {
        return state;
    }

    public long getLastPongTime() {
        return lastPongTime;
    }

    public long getLastHealthDelayTime() {
        return lastHealthDelayTime;
    }

    public Boolean getLastMarkHandled() {
        return lastMarkHandled;
    }
}
