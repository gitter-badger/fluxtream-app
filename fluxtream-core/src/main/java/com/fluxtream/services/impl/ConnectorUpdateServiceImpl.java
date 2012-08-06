package com.fluxtream.services.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.updaters.AbstractUpdater;
import com.fluxtream.connectors.updaters.ScheduleResult;
import com.fluxtream.connectors.updaters.UpdateInfo.UpdateType;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.ApiNotification;
import com.fluxtream.domain.ApiUpdate;
import com.fluxtream.domain.ConnectorInfo;
import com.fluxtream.domain.UpdateWorkerTask;
import com.fluxtream.domain.UpdateWorkerTask.Status;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.ConnectorUpdateService;
import com.fluxtream.services.GuestService;
import com.fluxtream.services.SystemService;
import com.fluxtream.utils.JPAUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Component
public class ConnectorUpdateServiceImpl implements ConnectorUpdateService {

	static Logger logger = Logger.getLogger(ConnectorUpdateServiceImpl.class);

	private Map<Connector, AbstractUpdater> updaters = new HashMap<Connector, AbstractUpdater>();

	@Autowired
	BeanFactory beanFactory;

    @Qualifier("apiDataServiceImpl")
    @Autowired
	ApiDataService apiDataService;

	@Autowired
	ThreadPoolTaskExecutor executor;

    @Autowired
    GuestService guestService;

    @Autowired
    SystemService systemService;

	@PersistenceContext
	EntityManager em;

	@Override
	@Transactional(readOnly = false)
	public void cleanupRunningUpdateTasks() {
		JPAUtils.execute(em, "updateWorkerTasks.delete.byStatus",
				Status.IN_PROGRESS);
	}

    @Override
    public List<ScheduleResult> updateConnector(final long guestId, Connector connector){
        int[] objectTypeValues = connector.objectTypeValues();
        List<ScheduleResult> scheduleResults = new ArrayList<ScheduleResult>();
        for (int objectTypes : objectTypeValues) {
            scheduleObjectTypeUpdate(guestId, connector, objectTypes, scheduleResults);
        }
        return scheduleResults;
    }

    @Override
    public List<ScheduleResult> updateConnectorObjectType(final long guestId, final Connector connector, int objectTypes) {
        List<ScheduleResult> scheduleResults = new ArrayList<ScheduleResult>();
        getScheduledUpdateTask(guestId, connector.getName(), objectTypes);
        scheduleObjectTypeUpdate(guestId, connector, objectTypes, scheduleResults);
        return scheduleResults;
    }

    /**
     * Schedules a new update if there is no update for the user for this ObjectType
     * @param guestId The user's id
     * @param connector The Connector that is to be updated
     * @param objectTypes the integer bitmask of object types to be updated
     * @param scheduleResults The result of adding the update will be added to the list. \result.type will be of type
     *                        ScheduleResult.ResultType. If there was a previously existing \result.type will be
     *                        ALREADY_SCHEDULED
     */
    private void scheduleObjectTypeUpdate(long guestId, Connector connector, int objectTypes, List<ScheduleResult> scheduleResults) {
        UpdateWorkerTask updateWorkerTask = getScheduledUpdateTask(guestId, connector.getName(), objectTypes);
        if (updateWorkerTask != null) {
            scheduleResults.add(new ScheduleResult(connector.getName(), objectTypes, ScheduleResult.ResultType.ALREADY_SCHEDULED, updateWorkerTask.timeScheduled));
        }
        else {
            UpdateType updateType = isHistoryUpdateCompleted(guestId, connector.getName(), objectTypes)
                                               ? UpdateType.INCREMENTAL_UPDATE
                                               : UpdateType.INITIAL_HISTORY_UPDATE;
            final ScheduleResult scheduleResult = scheduleUpdate(guestId, connector.getName(), objectTypes, updateType, System.currentTimeMillis());
            scheduleResults.add(scheduleResult);
        }
    }

    @Override
    public List<ScheduleResult> updateAllConnectors(final long guestId) {
        List<ConnectorInfo> connectors =  systemService.getConnectors();
        List<ScheduleResult> scheduleResults = new ArrayList<ScheduleResult>();
        for (int i = 0; i < connectors.size(); i++){
            if (!guestService.hasApiKey(guestId, connectors.get(i).getApi())) {
                connectors.remove(i--);
            }
            else {
                Connector connector = connectors.get(i).getApi();
                List<ScheduleResult> updateRes = updateConnector(guestId, connector);
                scheduleResults.addAll(updateRes);
            }
        }
        return scheduleResults;
    }

    @Transactional(readOnly = false)
	@Override
	public ScheduleResult reScheduleUpdateTask(UpdateWorkerTask updt, long time, boolean incrementRetries,
                                               UpdateWorkerTask.AuditTrailEntry auditTrailEntry) {
		if (!incrementRetries) {
			UpdateWorkerTask failed = new UpdateWorkerTask(updt);
			failed.retries = updt.retries;
			failed.connectorName = updt.connectorName;
			failed.status = Status.FAILED;
			failed.guestId = updt.guestId;
			failed.timeScheduled = updt.timeScheduled;
			em.persist(failed);
			updt.retries = 0;
		} else
			updt.retries += 1;
        updt.addAuditTrailEntry(auditTrailEntry);
		updt.status = Status.SCHEDULED;
		updt.timeScheduled = time;
		em.merge(updt);
		return new ScheduleResult(
                updt.connectorName,
                updt.getObjectTypes(),
				ScheduleResult.ResultType.SCHEDULED_UPDATE_DEFERRED,
                time);
	}

	@Override
	@Transactional(readOnly = false)
	public void setUpdateWorkerTaskStatus(long updateWorkerTaskId, Status status)
			throws RuntimeException {
		UpdateWorkerTask updt = em
				.find(UpdateWorkerTask.class, updateWorkerTaskId);
		if (updt == null) {
			RuntimeException exception = new RuntimeException(
					"null UpdateWorkerTask trying to set its status: "
							+ updateWorkerTaskId);
			logger.error("action=bg_update stage=unknown error");
			throw exception;
		}
		updt.status = status;
	}

	@Override
	@Transactional(readOnly = false)
	public void pollScheduledUpdates() {
		logger.debug("looking for a job...");
		List<UpdateWorkerTask> updateWorkerTasks = JPAUtils.find(em,
				UpdateWorkerTask.class, "updateWorkerTasks.byStatus",
				Status.SCHEDULED, System.currentTimeMillis());
		if (updateWorkerTasks.size() == 0) {
			logger.debug("nothing to do");
			return;
		}
		for (UpdateWorkerTask updateWorkerTask : updateWorkerTasks) {
			logger.debug("executing update: " + updateWorkerTask);
			setUpdateWorkerTaskStatus(updateWorkerTask.getId(), Status.IN_PROGRESS);
			logger.info("guestId=" + updateWorkerTask.getGuestId() +
					"action=bg_update stage=launch");
			UpdateTask updateTask = beanFactory.getBean(UpdateTask.class);
			updateTask.su = updateWorkerTask;
			try {
				executor.execute(updateTask);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
	}

	@Override
	public void addUpdater(Connector connector, AbstractUpdater updater) {
		logger.info("adding updater for connector: " + connector + " : "
				+ updater.getClass());
		updaters.put(connector, updater);
	}

	@Override
	public AbstractUpdater getUpdater(Connector connector) {
		return updaters.get(connector);
	}

	@Override
	public ScheduleResult scheduleUpdate(long guestId, String connectorName,
			int objectTypes, UpdateType updateType, long timeScheduled,
			String... jsonParams) {
		UpdateWorkerTask updateScheduled = getScheduledUpdateTask(guestId, connectorName, objectTypes);
		if (updateScheduled==null) {
			UpdateWorkerTask updateWorkerTask = new UpdateWorkerTask();
			updateWorkerTask.guestId = guestId;
			updateWorkerTask.connectorName = connectorName;
			updateWorkerTask.objectTypes = objectTypes;
			updateWorkerTask.updateType = updateType;
			updateWorkerTask.status = Status.SCHEDULED;
			updateWorkerTask.timeScheduled = timeScheduled;
			if (jsonParams!=null&&jsonParams.length>0)
				updateWorkerTask.jsonParams = jsonParams[0];
			em.persist(updateWorkerTask);
			long now = System.currentTimeMillis();
			return new ScheduleResult(connectorName, objectTypes,
					timeScheduled <= now ? ScheduleResult.ResultType.SCHEDULED_UPDATE_IMMEDIATE
							: ScheduleResult.ResultType.SCHEDULED_UPDATE_DEFERRED,
                    timeScheduled);
		} else {
			StringBuilder sb = new StringBuilder();
			sb.append("guestId=");
			sb.append(guestId);
			sb.append(" action=bg_update stage=reject_scheduling ");
			sb.append("connectorName=");
			sb.append(connectorName);
			sb.append(" objectTypes=");
			sb.append(objectTypes);
			logger.info(sb.toString());
			return new ScheduleResult(connectorName, objectTypes,
					ScheduleResult.ResultType.ALREADY_SCHEDULED,
                    updateScheduled.timeScheduled);
		}
	}

	@Override
	public boolean isHistoryUpdateCompleted(long guestId, String connectorName,
			int objectTypes) {
		List<UpdateWorkerTask> updateWorkerTasks = JPAUtils.find(em,
				UpdateWorkerTask.class, "updateWorkerTasks.completed",
				Status.DONE, guestId,
				UpdateType.INITIAL_HISTORY_UPDATE, objectTypes,
				connectorName);
		return updateWorkerTasks.size() > 0;
	}

	@Override
	@Transactional(readOnly = false)
	public void addApiNotification(Connector connector, long guestId, String content) {
		ApiNotification notification = new ApiNotification();
		notification.api = connector.value();
		notification.guestId = guestId;
		notification.ts = System.currentTimeMillis();
		notification.content = content;
		em.persist(notification);
	}

	@Override
	@Transactional(readOnly = false)
	public void addApiUpdate(long guestId, Connector api, int objectTypes,
			long ts, long elapsed, String query, boolean success) {
		ApiUpdate updt = new ApiUpdate();
		updt.guestId = guestId;
		updt.api = api.value();
		updt.ts = System.currentTimeMillis();
		updt.query = query;
		updt.objectTypes = objectTypes;
		updt.elapsed = elapsed;
		updt.success = success;
		em.persist(updt);
	}

	@Override
	public ApiUpdate getLastUpdate(long guestId, Connector api) {
        return JPAUtils.findUnique(em, ApiUpdate.class,
                "apiUpdates.last", guestId, api.value());
	}

	@Override
	public ApiUpdate getLastSuccessfulUpdate(long guestId, Connector api) {
        return JPAUtils.findUnique(em, ApiUpdate.class, "apiUpdates.last.successful.byApi", guestId, api.value());
	}

    @Override
    public List<ApiUpdate> getUpdates(long guestId, final Connector connector, final int pageSize, final int page) {
        return JPAUtils.findPaged(em, ApiUpdate.class, "apiUpdates.last.paged", pageSize, page,
                                                     guestId, connector.value());
    }

    @Override
	public ApiUpdate getLastSuccessfulUpdate(long guestId, Connector api,
			int objectTypes) {
		if (objectTypes == -1)
			return getLastSuccessfulUpdate(guestId, api);
        return JPAUtils.findUnique(em, ApiUpdate.class,
                "apiUpdates.last.successful.byApiAndObjectTypes", guestId,
                api.value(), objectTypes);
	}

    @Override
    @Transactional(readOnly = false)
    public UpdateWorkerTask getScheduledUpdateTask(long guestId, String connectorName, int objectTypes) {
        UpdateWorkerTask updateWorkerTask = JPAUtils.findUnique(em,
                                                                UpdateWorkerTask.class, "updateWorkerTasks.withObjectTypes.isScheduled",
                                                                Status.SCHEDULED, Status.IN_PROGRESS, guestId,
                                                                objectTypes, connectorName);
        if (updateWorkerTask!=null&&hasStalled(updateWorkerTask)) {
            updateWorkerTask.status = Status.STALLED;
            em.merge(updateWorkerTask);
            return null;
        }
        return updateWorkerTask;
    }

    @Override
    @Transactional(readOnly = false)
    public List<UpdateWorkerTask> getScheduledOrInProgressUpdateTasks(long guestId, Connector connector) {
		List<UpdateWorkerTask> updateWorkerTask = JPAUtils.find(em,
				UpdateWorkerTask.class, "updateWorkerTasks.isScheduledOrInProgress",
				Status.SCHEDULED, Status.IN_PROGRESS, guestId,
				connector.getName());
        for (UpdateWorkerTask workerTask : updateWorkerTask) {
            if (hasStalled(workerTask)) {
                workerTask.status = Status.STALLED;
                em.merge(workerTask);
            }
        }
		return updateWorkerTask;
	}

    @Override
    public List<UpdateWorkerTask> getUpdatingUpdateTasks(final long guestId, final Connector connector) {
        List<UpdateWorkerTask> updateWorkerTask = JPAUtils.find(em,
                UpdateWorkerTask.class, "updateWorkerTasks.isInProgressOrScheduledBefore",
                System.currentTimeMillis(), guestId,
                connector.getName());
        Iterator<UpdateWorkerTask> i = updateWorkerTask.iterator();
        while(i.hasNext()) {
            UpdateWorkerTask workerTask = i.next();
            if (hasStalled(workerTask)) {
                workerTask.status = Status.STALLED;
                em.merge(workerTask);
                i.remove();
            }
        }
        return updateWorkerTask;
    }

    private boolean hasStalled(UpdateWorkerTask updateWorkerTask) {
        return System.currentTimeMillis()-updateWorkerTask.timeScheduled>3600000;
    }

	@Transactional(readOnly = false)
	@Override
	public void deleteScheduledUpdateTasks(long guestId, Connector connector) {
		JPAUtils.execute(em, "updateWorkerTasks.delete.byApi", guestId,
				connector.getName());
	}

	@Override
	public long getTotalNumberOfGuestsUsingConnector(Connector connector) {
        return JPAUtils.count(em, "apiKey.count.byApi",
                connector.value());
	}

	@Override
	public long getTotalNumberOfUpdates(Connector connector) {
        return JPAUtils.count(em, "apiUpdates.count.all",
                connector.value());
	}

	@Override
	public long getNumberOfUpdates(long guestId, Connector connector) {
        return JPAUtils.count(em,
                "apiUpdates.count.byGuest", guestId, connector.value());
	}

	@Override
	public long getTotalNumberOfUpdatesSince(Connector connector, long then) {
        return JPAUtils.count(em,
                "apiUpdates.count.all.since", connector.value(), then);
	}

	@Override
	public long getNumberOfUpdatesSince(long guestId, Connector connector,
			long then) {
        return JPAUtils.count(em,
                "apiUpdates.count.byGuest.since", guestId, connector.value(),
                then);
	}

    @Override
    public Collection<UpdateWorkerTask> getLastFinishedUpdateTasks(final long guestId, final Connector connector) {
        List<UpdateWorkerTask> tasks = JPAUtils.find(em, UpdateWorkerTask.class,
                                                     "updateWorkerTasks.getLastFinishedTask",
                                                     System.currentTimeMillis(),
                                                     guestId,
                                                     connector.getName());
        HashMap<Integer, UpdateWorkerTask> seen = new HashMap<Integer, UpdateWorkerTask>();
        for(UpdateWorkerTask task : tasks)
        {
            if(seen.containsKey(task.objectTypes))
            {
                if(seen.get(task.objectTypes).timeScheduled < task.timeScheduled)
                    seen.put(task.objectTypes, task);
            }
            else
            {
                seen.put(task.objectTypes, task);
            }
        }
        return seen.values();
    }

    @Override
	public Set<Long> getConnectorGuests(Connector connector) {
		List<ApiKey> keys = JPAUtils.find(em, ApiKey.class, "apiKeys.byConnector", connector.value());
		Set<Long> guestIds = new HashSet<Long>();
		for (ApiKey key : keys) {
			guestIds.add(key.getGuestId());
		}
		return guestIds;
	}

}
