package com.ctrip.xpipe.redis.core.redis.operation;

/**
 * @author lishanglin
 * date 2022/2/17
 */
public interface RedisSingleKeyOp extends RedisOp {

    RedisKey getKey();

    byte[] getValue();

}
