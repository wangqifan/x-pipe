package com.ctrip.xpipe.redis.core.meta.impl;

import com.ctrip.xpipe.cluster.ClusterType;
import com.ctrip.xpipe.endpoint.HostPort;
import com.ctrip.xpipe.redis.core.entity.*;
import com.ctrip.xpipe.redis.core.exception.RedisRuntimeException;
import com.ctrip.xpipe.redis.core.meta.*;
import com.ctrip.xpipe.redis.core.meta.clone.MetaCloneFacade;
import com.ctrip.xpipe.redis.core.route.RouteChooseStrategy;
import com.ctrip.xpipe.redis.core.transform.DefaultSaxParser;
import com.ctrip.xpipe.redis.core.util.OrgUtil;
import com.ctrip.xpipe.tuple.Pair;
import com.ctrip.xpipe.utils.FileUtils;
import com.ctrip.xpipe.utils.MapUtils;
import com.ctrip.xpipe.utils.StringUtil;
import com.google.common.base.Joiner;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * @author wenchao.meng
 *
 * Jul 7, 2016
 */
public class DefaultXpipeMetaManager extends AbstractMetaManager implements XpipeMetaManager{
	
	private String fileName = null;

	protected final XpipeMeta xpipeMeta;
	private Map<HostPort, MetaDesc> inverseMap;

	private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

	public DefaultXpipeMetaManager(XpipeMeta xpipeMeta){
		this.xpipeMeta = xpipeMeta;
	}

	private DefaultXpipeMetaManager(String fileName) {
		this.fileName = fileName;
		xpipeMeta = load(fileName);
	}

	public static XpipeMetaManager buildFromFile(String fileName){
		return new DefaultXpipeMetaManager(fileName);
	}

	public static XpipeMetaManager buildFromMeta(XpipeMeta xpipeMeta){
		return new DefaultXpipeMetaManager(xpipeMeta);
	}

	public XpipeMeta load(String fileName) {
		
		try {
			InputStream ins = FileUtils.getFileInputStream(fileName);
			return DefaultSaxParser.parse(ins);
		} catch (SAXException | IOException e) {
			logger.error("[load]" + fileName, e);
			throw new IllegalStateException("load " + fileName + " failed!", e);
		}
	}
	
	@Override
	public String doGetActiveDc(String clusterId){
		boolean noOneWayInHetero = false;
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}
			if (ClusterType.lookup(clusterMeta.getType()).supportMultiActiveDC()) {
				throw new IllegalArgumentException("cluster " + clusterId +" support multi active dc");
			}
			String azGroupType = clusterMeta.getAzGroupType();
			if(!StringUtil.isEmpty(azGroupType) && ClusterType.lookup(clusterMeta.getAzGroupType()) != ClusterType.ONE_WAY) {
				// 异构类型需要避免拿到单机房缓存作为主机房信息
				noOneWayInHetero = noOneWayInHetero || true;
				continue;
			}
			String activeDc = clusterMeta.getActiveDc();
			if(activeDc == null){
				logger.info("[getActiveDc][activeDc null]{}", clusterMeta);
				throw new MetaException(String.format("cluster exist but active dc == null %s", clusterMeta));
			}
			return activeDc.trim().toLowerCase();
		}
		if(noOneWayInHetero) {
			throw new MetaException("clusterId " + clusterId + " does not hava one_way, therefore retrieving the active DC is not allowed.");
		}
		throw new MetaException("clusterId " + clusterId + " not found!");
	}
	
	@Override
	public Set<String> doGetBackupDcs(String clusterId, String shardId) {

		boolean found = false;
		
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}
			
			found = true;
			
			if(StringUtil.isEmpty(clusterMeta.getBackupDcs())){
				logger.info("[getBackupDcs][backup dcs empty]{}, {}", dcMeta.getId(), clusterMeta);
				continue;
			}
			
			
			Set<String> backDcs = expandDcs(clusterMeta.getBackupDcs());
			backDcs.remove(clusterMeta.getActiveDc().toLowerCase().trim());
			return backDcs;
		}
		
		if(found){
			return new HashSet<>();
		}
		throw new MetaException("clusterId " + clusterId + " not found!");
	}

	@Override
	public Set<String> doGetDownstreamDcs(String dc, String clusterId, String shardId) {

		DcMeta dcMeta = getDirectDcMeta(dc);
		ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);

		if (clusterMeta == null) {
			throw new MetaException("clusterId " + clusterId + " not found!");
		}

		return expandDcs(clusterMeta.getDownstreamDcs());
	}

	@Override
	public String doGetUpstreamDc(String dc, String clusterId, String shardId) {

		SourceMeta source = getSourceOrThrow(dc, clusterId, shardId);
		return source.getUpstreamDc();
	}

	@Override
	public String doGetSrcDc(String dc, String clusterId, String shardId) {

	    SourceMeta source = getSourceOrThrow(dc, clusterId, shardId);
	    return source.getSrcDc();
	}

	private SourceMeta getSourceOrThrow(String dc, String clusterId, String shardId) {

		DcMeta dcMeta = getDirectDcMeta(dc);
		ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
		if (clusterMeta == null) {
			throw new MetaException("clusterId " + clusterId + " not found!");
		}

		for (SourceMeta sourceMeta : clusterMeta.getSources()) {
			if (sourceMeta.getShards().containsKey(shardId)) {
				return sourceMeta;
			}
		}

		throw new MetaException("clusterId " + clusterId + "shardId" + shardId + " not found!");
	}

	@Override
	public Set<String> doGetRelatedDcs(String clusterId, String shardId) {
		boolean found = false;

		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}

			found = true;

			if(StringUtil.isEmpty(clusterMeta.getDcs())){
				logger.info("[getRelatedDcs][dcs empty]{}, {}", dcMeta.getId(), clusterMeta);
				continue;
			}

			return expandDcs(clusterMeta.getDcs());
		}

		if(found) {
			return Collections.emptySet();
		}
		throw new MetaException("clusterId " + clusterId + " not found!");
	}

	private Set<String> expandDcs(String dcsDesc) {
		
		Set<String> dcs = new HashSet<>();
		if(StringUtil.isEmpty(dcsDesc)){
			return dcs;
		}
		for(String dc : dcsDesc.split("\\s*,\\s*")){
			dc = dc.trim();
			if(!StringUtil.isEmpty(dc)){
				dcs.add(dc.toLowerCase());
			}
		}
		return dcs;
	}

	@Override
	public Set<ClusterMeta> doGetDcClusters(String dc) {
		return new HashSet<>(getDirectDcMeta(dc).getClusters().values());
	}

	@Override
	public ClusterMeta doGetClusterMeta(String dc, String clusterId) {
		return clone(getDirectClusterMeta(dc, clusterId));
	}

	@Override
	public ClusterType doGetClusterType(String clusterId) {
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()) {

			ClusterMeta clusterMeta = dcMeta.findCluster(clusterId);
			if (clusterMeta == null) {
				continue;
			}
			return ClusterType.lookup(clusterMeta.getType());
		}
		throw new MetaException("[getClusterType] unfound cluster for name:" + clusterId);
	}
	
	public ClusterMeta getDirectClusterMeta(String dc, String clusterId) {
		
		DcMeta dcMeta = getDirectDcMeta(dc);
		if(dcMeta == null){
			return null;
		}
		return dcMeta.getClusters().get(clusterId);
	}
	
	public DcMeta getDirectDcMeta(String dc) {

		for(Map.Entry<String, DcMeta> dentry : xpipeMeta.getDcs().entrySet()){
			String dcId = dentry.getKey();
			if(dcId.equalsIgnoreCase(dc)){
				return dentry.getValue();
			}
		}
		return null;
	}

	@Override
	public ShardMeta doGetShardMeta(String dc, String clusterId, String shardId) {
		
		return clone(getDirectShardMeta(dc, clusterId, shardId));
	}

	protected ShardMeta getDirectShardMeta(String dc, String clusterId, String shardId) {
		
		ClusterMeta clusterMeta = getDirectClusterMeta(dc, clusterId);
		if(clusterMeta == null){
			return null;
		}
		return clusterMeta.getAllShards().get(shardId);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<KeeperMeta> doGetKeepers(String dc, String clusterId, String shardId) {
		
		return cloneList(getDirectKeepers(dc, clusterId, shardId));
	}

	protected List<KeeperMeta> getDirectKeepers(String dc, String clusterId, String shardId) {

		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return shardMeta.getKeepers();
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<ApplierMeta> doGetAppliers(String dc, String clusterId, String shardId) {

		return cloneList(getDirectAppliers(dc, clusterId, shardId));
	}

	protected List<ApplierMeta> getDirectAppliers(String dc, String clusterId, String shardId) {

		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return shardMeta.getAppliers();
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<RedisMeta> doGetRedises(String dc, String clusterId, String shardId) {
		
		ShardMeta shardMeta = getShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return cloneList(shardMeta.getRedises());
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<RedisMeta> doGetRedises(String dc, String clusterId) {

		List<RedisMeta> result = new ArrayList<>();

	    ClusterMeta clusterMeta = getClusterMeta(dc, clusterId);
		if (clusterMeta == null) {
			return result;
		}
		for (ShardMeta shardMeta : clusterMeta.getShards().values()) {
			if (shardMeta == null) {
				continue;
			}
		    result.addAll(cloneList(shardMeta.getRedises()));
		}

		return result;
	}

	protected List<RedisMeta> getDirectRedises(String dc, String clusterId, String shardId) {

		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			return null;
		}
		return shardMeta.getRedises();
	}

	@Override
	public KeeperMeta doGetKeeperActive(String dc, String clusterId, String shardId) {
		
		List<KeeperMeta> keepers = getDirectKeepers(dc, clusterId, shardId);
		if(keepers == null){
			return null;
		}
		
		return clone(getDirectKeeperActive(keepers));
	}

	private KeeperMeta getDirectKeeperActive(List<KeeperMeta> keepers) {
		
		for(KeeperMeta keeperMeta : keepers){
			if(keeperMeta.isActive()){
				return keeperMeta;
			}
		}
		return null;
	}

	@Override
	public List<KeeperMeta> doGetKeeperBackup(String dc, String clusterId, String shardId) {
		
		List<KeeperMeta> keepers = getKeepers(dc, clusterId, shardId);
		if(keepers == null){
			return null;
		}
		
		LinkedList<KeeperMeta> result = new LinkedList<>();
		for(KeeperMeta keeperMeta : keepers){
			if(!keeperMeta.isActive()){
				result.add(keeperMeta);
			}
		}
		return cloneList(result);
	}

	@Override
	public MetaDesc doFindMetaDesc(HostPort hostPort) {

		if(inverseMap != null){
			return inverseMap.get(hostPort);
		}

		synchronized (this){
			if(inverseMap == null){
				inverseMap = InverseHostPortMapBuilder.build(xpipeMeta);
			}
		}

		return inverseMap.get(hostPort);
	}

	@Override
	public Pair<String, RedisMeta> doGetRedisMaster(String clusterId, String shardId) {
		
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){

			ClusterMeta clusterMeta = dcMeta.findCluster(clusterId);
			if( clusterMeta == null ){
				continue;
			}

			ShardMeta shardMeta = clusterMeta.findShard(shardId);
			if(shardMeta == null){
				continue;
			}

			for(RedisMeta redisMeta : shardMeta.getRedises()){
				if(redisMeta.isMaster()){
					return new Pair<>(dcMeta.getId(), clone(redisMeta));
				}
			}
		}
		return null;
	}

	
	
	@Override
	public boolean doNoneKeeperActive(String dc, String clusterId, String shardId) {
		
		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		
		if(shardMeta == null){
			throw new RedisRuntimeException(String.format("[shard not found]dc:%s, cluster:%s, shard:%s", dc, clusterId, shardId));
		}
		boolean changed = false;
		for(KeeperMeta keeperMeta : shardMeta.getKeepers()){
			if(keeperMeta.isActive()){
				keeperMeta.setActive(false);
				changed = true;
			}
		}
		return changed;
	}
	
	@Override
	public void doSetSurviveKeepers(String dcId, String clusterId, String shardId, List<KeeperMeta> surviveKeepers) {
		
		List<KeeperMeta> keepers = getDirectKeepers(dcId, clusterId, shardId);
		
		List<KeeperMeta> unfoundKeepers = new LinkedList<>();
		
		for(KeeperMeta active : surviveKeepers){
			boolean found = false;
			for(KeeperMeta current :keepers){
				if(MetaUtils.same(active, current)){
					found = true;
					current.setSurvive(true);
					break;
				}
			}
			if(!found){
				unfoundKeepers.add(active);
			}
		}
		
		if(unfoundKeepers.size() > 0){
			throw new IllegalArgumentException("unfound keeper set active:" + unfoundKeepers);
		}
		
	}

	@Override
	public void doSetRedisGtidAndSids(String dc, String clusterId, String shardId, RedisMeta redisMeta, String gtid, String sids) {
		List<RedisMeta> directRedises = getDirectRedises(dc, clusterId, shardId);
		for (RedisMeta real : directRedises) {
			if (MetaUtils.same(redisMeta, real)) {
				real.setGtid(gtid);
				real.setSid(sids);
				return;
			}
		}

		logger.warn("[doSetRedisGtidAndSids] not found, dc={} cluster={}, shard={}, redis ip={}, port={}",
				dc, clusterId, shardId, redisMeta.getIp(), redisMeta.getPort());
	}

	@Override
	public boolean doUpdateKeeperActive(String dc, String clusterId, String shardId, KeeperMeta activeKeeper) {
		
		if(!valid(activeKeeper)){
			logger.info("[updateKeeperActive][keeper information unvalid]{}", activeKeeper);
		}
		
		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			throw new MetaException(String.format("unfound keepers: %s %s %s", dc, clusterId, shardId));
		}
		List<KeeperMeta> keepers = shardMeta.getKeepers();
		boolean found = false;
		boolean changed = false;
		for(KeeperMeta keeperMeta : keepers){
			if(keeperMeta.getIp().equals(activeKeeper.getIp()) && keeperMeta.getPort().equals(activeKeeper.getPort())){
				found = true;
				if(!keeperMeta.isActive()){
					logger.info("[updateKeeperActive][set keeper active]{}", keeperMeta);
					keeperMeta.setActive(true);
					changed = true;
				}
			}else{
				if(keeperMeta.isActive()){
					logger.info("[updateKeeperActive][set keeper unactive]{}", keeperMeta);
					keeperMeta.setActive(false);
					changed = true;
				}
			}
		}
		if(!found && valid(activeKeeper)){
			changed = true;
			activeKeeper.setActive(true);
			activeKeeper.setParent(shardMeta);
			keepers.add(activeKeeper);
		}
		return changed;
	}
	
	private boolean valid(KeeperMeta activeKeeper) {
		
		if(activeKeeper == null || activeKeeper.getIp() == null || activeKeeper.getPort() == null){
			return false;
		}
		return true;
	}

	public String getFileName() {
		return fileName;
	}

	@Override
	public List<MetaServerMeta> doGetMetaServers(String dc) {
		
		DcMeta dcMeta = getDirectDcMeta(dc);
		if( dcMeta == null ){
			return null;
		}
		return cloneList(dcMeta.getMetaServers());
	}

	@Override
	public ZkServerMeta doGetZkServerMeta(String dc) {
		
		DcMeta dcMeta = getDirectDcMeta(dc);
		if( dcMeta == null ){
			return null;
		}
		return clone(dcMeta.getZkServer());
	}

	@Override
	public Set<String> doGetDcs() {
		return xpipeMeta.getDcs().keySet();
	}

	@Override
	public boolean doUpdateRedisMaster(String dc, String clusterId, String shardId, RedisMeta redisMaster) throws MetaException {
		
		String activeDc = getActiveDc(clusterId);
		if(!activeDc.equals(dc)){
			throw new MetaException("active dc:" + activeDc + ", but given:" + dc + ", clusterID:" + clusterId);
		}
		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			throw new MetaException(String.format("unfound shard %s,%s,%s", dc, clusterId, shardId));
		}
		
		boolean found = false, changed = false;
		String newMaster = String.format("%s:%d", redisMaster.getIp(), redisMaster.getPort());
		String oldRedisMaster = null;
		for(RedisMeta redisMeta : shardMeta.getRedises()){
			if(redisMeta.getIp().equals(redisMaster.getIp()) && redisMeta.getPort().equals(redisMaster.getPort())){
				found = true;
				if(!redisMeta.isMaster()){
					logger.info("[updateRedisMaster][change redis to master]{}", redisMeta);
					redisMeta.setMaster(null);
					changed = true;
				}else{
					logger.info("[updateRedisMaster][redis already master]{}", redisMeta);
				}
			}else{
				if(redisMeta.isMaster()){
					logger.info("[updateRedisMaster][change redis to slave]{}", redisMeta);
					//unknown
					oldRedisMaster = String.format("%s:%d", redisMeta.getIp(), redisMeta.getPort());
					
					redisMeta.setMaster(getNewMasterofOldMaster(redisMeta, newMaster));
					changed = true;
				}
			}
		}
		
		
		if(oldRedisMaster != null){
			for(RedisMeta redisMeta : shardMeta.getRedises()){
				if(oldRedisMaster.equalsIgnoreCase(redisMeta.getMaster())){
					redisMeta.setMaster(newMaster);
					changed = true;
				}
			}
			for(KeeperMeta keeperMeta : shardMeta.getKeepers()){
				if(oldRedisMaster.equalsIgnoreCase(keeperMeta.getMaster())){
					keeperMeta.setMaster(newMaster);
					changed = true;
				}
			}
		}
		
		if(!found){
			redisMaster.setParent(shardMeta);
			shardMeta.getRedises().add(redisMaster);
			changed = true;
		}
		return changed;
	}

	private String getNewMasterofOldMaster(RedisMeta oldRedisMaster, String newRedisMaster) {
		
		Integer phase = oldRedisMaster.parent().getPhase(); 
		if(phase == null){
			phase = ((ClusterMeta) oldRedisMaster.parent().parent()).getPhase();
		}
		if(phase == null){
			phase = 1;
		}
		
		if(phase == 1){
			return newRedisMaster;
		}
		KeeperMeta keeperActive = getDirectKeeperActive(oldRedisMaster.parent().getKeepers());
		if(keeperActive == null){
			throw new RedisRuntimeException(String.format("can not find active keeper:", oldRedisMaster.parent().getKeepers()));
		}
		return String.format("%s:%d", keeperActive.getIp(), keeperActive.getPort());
	}

	@Override
	public boolean doDcExists(String dc) {
		return getDirectDcMeta(dc)!= null;
	}

	@Override
	public KeeperContainerMeta doGetKeeperContainer(String dc, KeeperMeta keeperMeta) {
		
		DcMeta dcMeta = getDirectDcMeta(dc);
		for(KeeperContainerMeta keeperContainerMeta : dcMeta.getKeeperContainers()){
			if(keeperContainerMeta.getId().equals(keeperMeta.getKeeperContainerId())){
				return clone(keeperContainerMeta);
			}
		}
		throw new IllegalArgumentException(String.format("[getKeeperContainer][unfound keepercontainer]%s, %s", dc, keeperMeta));
	}

	@Override
	public ApplierContainerMeta doGetApplierContainer(String dc, ApplierMeta applierMeta) {
		DcMeta dcMeta = getDirectDcMeta(dc);
		for (ApplierContainerMeta applierContainerMeta : dcMeta.getApplierContainers()) {
			if (applierContainerMeta.getId().equals(applierMeta.getApplierContainerId())) {
				return clone(applierContainerMeta);
			}
		}
		throw new IllegalArgumentException(String.format("[getApplierContainer][unfound appliercontainer]%s, %s", dc, applierMeta));
    }

	@Override
	public void doUpdate(DcMeta dcMeta) {
		
		xpipeMeta.addDc(clone(dcMeta));
	}

	@Override
	public void doUpdate(String dcId, ClusterMeta clusterMeta) {
		
		DcMeta dcMeta = xpipeMeta.getDcs().get(dcId);
		dcMeta.addCluster(clone(clusterMeta));
	}

	@Override
	public ClusterMeta doRemoveCluster(String dcId, String clusterId) {
		
		DcMeta dcMeta = xpipeMeta.getDcs().get(dcId);
		return dcMeta.removeCluster(clusterId);
	}


	@Override
	public DcMeta doGetDcMeta(String dcId) {
		
		return clone(getDirectDcMeta(dcId));
	}

	@Override
	public String doGetDcZone(String dcId) {
		return getDirectDcMeta(dcId).getZone();
	}

	@Override
	public List<KeeperMeta> doGetAllSurviveKeepers(String dcId, String clusterId, String shardId) {

		List<KeeperMeta> keepers = getDirectKeepers(dcId, clusterId, shardId);
		List<KeeperMeta> result = new LinkedList<>();
		
		for(KeeperMeta keeper : keepers){
			if(keeper.isSurvive()){
				result.add(MetaCloneFacade.INSTANCE.clone(keeper));
			}
		}
		return result;
	}

	@Override
	public boolean doHasCluster(String dcId, String clusterId) {
		DcMeta dcMeta = getDirectDcMeta(dcId);
		if(dcMeta == null){
			return false;
		}
		return dcMeta.getClusters().get(clusterId) != null;
	}

	@Override
	public boolean doHasShard(String dcId, String clusterId, String shardId) {
		ShardMeta shardMeta = getDirectShardMeta(dcId, clusterId, shardId);
		if(shardMeta == null){
			return false;
		}
		return true;
	}

	@Override
	public SentinelMeta doGetSentinel(String dc, String clusterId, String shardId) {
		
		DcMeta dcMeta = getDirectDcMeta(dc);
		if((dcMeta == null)){
			throw new RedisRuntimeException("dcmeta not found:" + dc); 
		}
		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(shardMeta == null){
			throw new RedisRuntimeException(String.format("shardMeta not found:%s %s %s", dc, clusterId, shardId));
		}
		Long sentinelId = shardMeta.getSentinelId();
		SentinelMeta sentinelMeta = dcMeta.getSentinels().get(sentinelId);
		if(sentinelMeta == null){
			return new SentinelMeta().setAddress("");
		}
		return MetaCloneFacade.INSTANCE.clone(sentinelMeta);
	}

	@Override
	public String doGetSentinelMonitorName(String dc, String clusterId, String shardId) {
		ShardMeta shardMeta = getDirectShardMeta(dc, clusterId, shardId);
		if(null == shardMeta) {
			throw new RedisRuntimeException(String.format("shardMeta not found:%s %s %s", dc, clusterId, shardId));
		}

		return shardMeta.getSentinelMonitorName();
	}

	@Override
	public void doPrimaryDcChanged(String dc, String clusterId, String shardId, String newPrimaryDc) {
		
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			changePrimaryDc(dcMeta, clusterId, shardId, newPrimaryDc);
		}
	}

	@Override
	public List<RouteMeta> doGetRoutes(String currentDc, String tag) {

		DcMeta dcMeta = getDirectDcMeta(currentDc);
		List<RouteMeta> routes = dcMeta.getRoutes();
		List<RouteMeta> result = new LinkedList<>();

		if(routes != null){
			routes.forEach(routeMeta -> {
				if(routeMeta.tagEquals(tag) && currentDc.equalsIgnoreCase(routeMeta.getSrcDc())) {
					result.add(MetaCloneFacade.INSTANCE.clone(routeMeta));
				}
			});
		}

		return result;
	}

	public Map<String, List<RouteMeta>> getClusterDesignatedRoutes(String clusterId, String srcDcId) {
		DcMeta dcMeta = getDirectDcMeta(srcDcId);
		if (null == dcMeta) throw new MetaException("dc " + srcDcId + " not found");

		ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
		if (null == clusterMeta) throw new MetaException("cluster " + clusterId + " not found");

		Map<String, List<RouteMeta>> designatedRoutes = new HashMap<>();
		Set<Long> designatedRouteIds = expandIds(clusterMeta.getClusterDesignatedRouteIds());
		List<RouteMeta> allDcRoutes = dcMeta.getRoutes();
		if (designatedRouteIds.isEmpty() || allDcRoutes.isEmpty()) return designatedRoutes;

		for (RouteMeta routeMeta : allDcRoutes) {
			if (designatedRouteIds.contains(routeMeta.getId())) {
				MapUtils.getOrCreate(designatedRoutes, routeMeta.getDstDc().toLowerCase(), ArrayList::new).add(clone(routeMeta));
			}
		}
		return designatedRoutes;
	}

	private Set<Long> expandIds(String idDesc) {
		Set<Long> ids = new HashSet<>();
		if (StringUtil.isEmpty(idDesc)) {
			return ids;
		}

		for (String idStr: idDesc.split("\\s*,\\s*")) {
			try {
				Long id = Long.parseLong(idStr);
				ids.add(id);
			} catch (NumberFormatException e) {
				logger.warn("[expandIds] unexpected id {}", idStr);
			}
		}

		return ids;
	}

	@Override
	public RouteMeta doChooseMetaRoute(ClusterMeta cluster, String srcDc, String dstDc, RouteChooseStrategy strategy) {
		return doChooseRoute(cluster, routes(srcDc, Route.TAG_META), dstDc, strategy, true);
	}

	@Override
	public Map<String, RouteMeta> doChooseMetaRoutes(String clusterId, String srcDc, List<String> dstDcs,
													 RouteChooseStrategy strategy, boolean useClusterPrioritizedRoutes) {
		ClusterMeta clusterMeta = getDirectClusterMeta(srcDc, clusterId);
		if (null == clusterMeta) throw new IllegalArgumentException("unknown cluster " + clusterId);

		List<RouteMeta> routes = routes(srcDc, Route.TAG_META);
		Map<String, RouteMeta> choosedRoutes = new HashMap<>();
		for (String dc: dstDcs) {
			try {
				RouteMeta route = doChooseRoute(clusterMeta, routes, dc, strategy, useClusterPrioritizedRoutes);
				if (null != route) choosedRoutes.put(dc, route);
			} catch (Throwable th) {
				logger.info("[doChooseMetaRoutes][{}][{}->{}] fail", clusterId, srcDc, dc, th);
			}
		}

		return choosedRoutes;
	}

	private RouteMeta doChooseRoute(ClusterMeta cluster, List<RouteMeta> routes, String dstDc,
								   RouteChooseStrategy strategy, boolean useClusterPrioritizedRoutes) {
		if (StringUtil.isEmpty(dstDc)) throw new IllegalArgumentException("empty dstDc");

		List<RouteMeta> dstDcRoutes = new LinkedList<>();
		routes.forEach(routeMeta -> {
			if (dstDc.equalsIgnoreCase(routeMeta.getDstDc())) dstDcRoutes.add(routeMeta);
		});
		if (dstDcRoutes.isEmpty()) return null;

		if (useClusterPrioritizedRoutes) {
			Set<Long> clusterDesignatedRouteIds = expandIds(cluster.getClusterDesignatedRouteIds());
			List<RouteMeta> clusterDesignatedRoutes = dstDcRoutes.stream()
					.filter(route -> clusterDesignatedRouteIds.contains(route.getId()))
					.collect(Collectors.toList());
			if (!clusterDesignatedRoutes.isEmpty()) return strategy.choose(clusterDesignatedRoutes, cluster.getId());
		}

		String clusterType = cluster.getType();
		List<RouteMeta> routesByClusterType = dstDcRoutes.stream()
				.filter(route -> route.isIsPublic() && clusterType.equalsIgnoreCase(route.getClusterType()))
				.collect(Collectors.toList());
		if (routesByClusterType.isEmpty()) {
			routesByClusterType = dstDcRoutes.stream()
					.filter(route -> route.isIsPublic() && route.getClusterType().equals(""))
					.collect(Collectors.toList());
		}
		if (routesByClusterType.isEmpty()) return null;

		Integer orgId = cluster.getOrgId();
		List<RouteMeta> routesByOrg = routesByClusterType.stream()
				.filter(route -> route.isIsPublic() && orgId.equals(route.getOrgId()))
				.collect(Collectors.toList());
		if (routesByOrg.isEmpty()) {
			routesByOrg = routesByClusterType.stream()
					.filter(route -> route.isIsPublic() && OrgUtil.isDefaultOrg(route.getOrgId()))
					.collect(Collectors.toList());
		}
		// don't degrade to default clusterType, it's too complex
		if (routesByOrg.isEmpty()) return null;

		return strategy.choose(routesByOrg, cluster.getId());
	}

	@Override
	public List<ClusterMeta> doGetSpecificActiveDcClusters(String currentDc, String clusterActiveDc) {

		DcMeta directDcMeta = getDirectDcMeta(currentDc);
		if(directDcMeta == null){
			throw new IllegalArgumentException(String.format("can not find currentDc %s, %s", currentDc, clusterActiveDc));
		}

		List<ClusterMeta> result = new LinkedList<>();
		directDcMeta.getClusters().forEach((clusterId, clusterMeta) -> {
			if(clusterActiveDc.equalsIgnoreCase(clusterMeta.getActiveDc())){
				result.add(clone(clusterMeta));
			}
		});

		return result;
	}

	protected <T> T random(List<T> resultsCandidates) {

		if(resultsCandidates.isEmpty()){
			return null;
		}
		int random = new Random().nextInt(resultsCandidates.size());
		logger.debug("[randomRoute]random: {}, size: {}", random, resultsCandidates.size());
		return resultsCandidates.get(random);

	}

	private void changePrimaryDc(DcMeta dcMeta, String clusterId, String shardId, String newPrimaryDc) {
		
		ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
		if(clusterMeta == null){
			throw new RedisRuntimeException(String.format("clusterMeta not found:%s %s %s", dcMeta.getId(), clusterId, shardId));
		}
		
		String currentPrimaryDc = clusterMeta.getActiveDc();
		
		if(StringUtil.trimEquals(newPrimaryDc, currentPrimaryDc, true)){
			logger.info("[changePrimaryDc][equal]{}, {}, {}", clusterId, shardId, newPrimaryDc);
			return;
		}
		
		newPrimaryDc = newPrimaryDc.trim().toLowerCase();
		
		Set<String> allDcs = new HashSet<>();
		if(currentPrimaryDc != null){
			allDcs.add(currentPrimaryDc.trim().toLowerCase());
		}
		allDcs.addAll(expandDcs(clusterMeta.getBackupDcs()));
		
		clusterMeta.setActiveDc(newPrimaryDc);
		
		allDcs.remove(newPrimaryDc);
		clusterMeta.setBackupDcs(Joiner.on(",").join(allDcs));
	}
	
	@Override
	public String toString() {
		return xpipeMeta.toString();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return readWriteLock;
	}
}
