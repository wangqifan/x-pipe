package com.ctrip.xpipe.redis.checker.healthcheck.actions.redismaster;

import com.ctrip.xpipe.cluster.ClusterType;
import com.ctrip.xpipe.redis.checker.alert.ALERT_TYPE;
import com.ctrip.xpipe.redis.checker.healthcheck.*;
import com.ctrip.xpipe.redis.checker.healthcheck.leader.AbstractRedisLeaderAwareHealthCheckActionFactory;
import com.ctrip.xpipe.redis.checker.healthcheck.leader.SiteLeaderAwareHealthCheckAction;
import com.ctrip.xpipe.redis.checker.healthcheck.util.ClusterTypeSupporterSeparator;
import com.ctrip.xpipe.utils.StringUtil;
import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @author chen.zhu
 * <p>
 * Oct 09, 2018
 */
@Component
public class RedisMasterCheckActionFactory extends AbstractRedisLeaderAwareHealthCheckActionFactory implements OneWaySupport, BiDirectionSupport, SingleDcSupport, LocalDcSupport, CrossDcSupport {

    private Map<ClusterType, List<RedisMasterController>> controllersByClusterType;

    private Map<ClusterType, List<RedisMasterActionListener>> listenersByClusterType;

    @Autowired
    public RedisMasterCheckActionFactory(List<RedisMasterController> controllers, List<RedisMasterActionListener> listeners) {
        this.controllersByClusterType = ClusterTypeSupporterSeparator.divideByClusterType(controllers);
        this.listenersByClusterType = ClusterTypeSupporterSeparator.divideByClusterType(listeners);
    }

    @Override
    public SiteLeaderAwareHealthCheckAction create(RedisHealthCheckInstance instance) {
        RedisMasterCheckAction action = new RedisMasterCheckAction(scheduled, instance, executors);

        RedisInstanceInfo info = instance.getCheckInfo();
        ClusterType clusterType = info.getClusterType();
        String azGroupType = info.getAzGroupType();
        ClusterType azGroupClusterType = StringUtil.isEmpty(azGroupType) ? null : ClusterType.lookup(azGroupType);
        List<RedisMasterController> redisMasterControllers;
        if (clusterType == ClusterType.ONE_WAY && azGroupClusterType == ClusterType.SINGLE_DC) {
            redisMasterControllers = controllersByClusterType.get(azGroupClusterType);
        } else {
            redisMasterControllers = controllersByClusterType.get(clusterType);
        }
        action.addControllers(redisMasterControllers);
        action.addListeners(listenersByClusterType.get(clusterType));

        return action;
    }

    @Override
    public Class<? extends SiteLeaderAwareHealthCheckAction> support() {
        return RedisMasterCheckAction.class;
    }

    @Override
    protected List<ALERT_TYPE> alertTypes() {
        return Lists.newArrayList(ALERT_TYPE.REPL_WRONG_SLAVE, ALERT_TYPE.MASTER_OVER_ONE);
    }
}
